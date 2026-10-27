"""Test a Windows native allocation hook with one caller-side VirtualAlloc.

Run on native 64-bit Windows with pinned Frida installed on PYTHONPATH. The
controller injects only into an owned worker, limits only that worker's committed
memory, and logs JSON events. The caller performs no allocation retry itself.
A missing event fails after 30 seconds. Cleanup kills/reaps unfinished work,
closes job/process handles and detaches the owned instrumentation session.
This tests one fixture/API path; it does not accept compiler-wide scheduling.
"""

import argparse
import concurrent.futures
import ctypes
import importlib.util
import os
from pathlib import Path
import platform
import queue
import subprocess
import sys


def load_windows_api():
    """Load the existing native probe's pointer-safe APIs and job-limit layout."""
    path = Path(__file__).with_name("probe-windows-allocation.py")
    specification = importlib.util.spec_from_file_location("windows_allocation_api", path)
    module = importlib.util.module_from_spec(specification)
    specification.loader.exec_module(module)
    return module


def run_worker(api, kernel, expect_denial):
    """Make one 256 MiB VirtualAlloc, read/write it, await finish, free and exit.

    An unexpected allocation failure raises the native Windows error. The
    expected-denial case reports that error and exits after finish permission.
    No caller-side loop or retry is available.
    """
    requested_bytes = 256 * 1024 * 1024
    kernel.GetCurrentThreadId.argtypes = []
    kernel.GetCurrentThreadId.restype = ctypes.c_uint32
    implementation = ctypes.WinDLL("kernelbase.dll", use_last_error=True)
    api.emit("worker_ready", requested_bytes=requested_bytes,
             thread_id=kernel.GetCurrentThreadId(),
             allocation_address=hex(ctypes.cast(kernel.VirtualAlloc, ctypes.c_void_p).value),
             implementation_address=hex(ctypes.cast(implementation.VirtualAlloc, ctypes.c_void_p).value))
    assert sys.stdin.readline().strip() == "allocate", "Missing allocation start permission"
    pointer = kernel.VirtualAlloc(None, requested_bytes, 0x3000, 0x04)
    last_error = ctypes.get_last_error()
    if pointer is None:
        if expect_denial:
            api.emit("native_call_failed", last_error=last_error)
            assert sys.stdin.readline().strip() == "finish", "Missing failure completion permission"
            return
        raise ctypes.WinError(last_error)
    assert expect_denial is False, "Expected denied allocation unexpectedly succeeded"
    try:
        ctypes.c_ubyte.from_address(pointer).value = 42
        ctypes.c_ubyte.from_address(pointer + requested_bytes - 1).value = 43
        first_byte = ctypes.c_ubyte.from_address(pointer).value
        last_byte = ctypes.c_ubyte.from_address(pointer + requested_bytes - 1).value
        assert (first_byte, last_byte) == (42, 43)
        api.emit("native_call_returned", pointer=hex(pointer), first_byte=first_byte, last_byte=last_byte,
                 last_error=last_error)
        assert sys.stdin.readline().strip() == "finish", "Missing allocation cleanup permission"
    finally:
        api.require_api_success(kernel.VirtualFree(pointer, 0, 0x8000))
    api.emit("allocation_freed")


def run_probe(api, kernel, mode):
    """Exercise attachment, forwarding, or denied-allocation retry and clean exit.

    ``attach`` installs no allocation hook. ``forward`` leaves the JavaScript
    hook unarmed. ``native-forward`` forwards through a native callback;
    ``native-fast`` uses a direct native replacement and original trampoline,
    targeting KernelBase on ARM64 and counting the worker's matching call. These
    controls allow the 256 MiB request before starting it. ``native-retry``
    waits on native events inside the replacement; ``native-timeout`` withholds
    permission and verifies the original denial error. ``retry`` retains the
    JavaScript denied-allocation experiment. Every mode requires normal exit within
    30 seconds and kills/reaps an unfinished worker on failure.
    """
    import frida

    is_shutdown_control = mode in ("attach", "forward", "native-forward", "native-fast")
    is_native_retry = mode in ("native-retry", "native-timeout")
    is_native_hook = mode in ("native-fast", "native-retry", "native-timeout")
    controller_events = []
    kernel.CreateEventW.argtypes = [ctypes.c_void_p, ctypes.c_int, ctypes.c_int, ctypes.c_wchar_p]
    kernel.CreateEventW.restype = ctypes.c_void_p
    kernel.GetCurrentProcess.argtypes = []
    kernel.GetCurrentProcess.restype = ctypes.c_void_p
    kernel.DuplicateHandle.argtypes = [ctypes.c_void_p, ctypes.c_void_p, ctypes.c_void_p,
                                      ctypes.POINTER(ctypes.c_void_p), ctypes.c_uint32,
                                      ctypes.c_int, ctypes.c_uint32]
    kernel.DuplicateHandle.restype = ctypes.c_int
    kernel.SetEvent.argtypes = [ctypes.c_void_p]
    kernel.SetEvent.restype = ctypes.c_int
    kernel.WaitForSingleObject.argtypes = [ctypes.c_void_p, ctypes.c_uint32]
    kernel.WaitForSingleObject.restype = ctypes.c_uint32
    job_extended_limits = 9
    kill_on_job_close = 0x2000
    process_commit_limit = 0x100
    job = api.require_api_success(kernel.CreateJobObjectW(None, None))
    try:
        limits = api.ExtendedLimitInformation()
        limits.BasicLimitInformation.LimitFlags = kill_on_job_close
        api.require_api_success(kernel.SetInformationJobObject(job, job_extended_limits, ctypes.byref(limits), ctypes.sizeof(limits)))
        with concurrent.futures.ThreadPoolExecutor(max_workers=1) as reader:
            worker_arguments = [sys.executable, __file__, "--worker"]
            if mode == "native-timeout":
                worker_arguments.append("--expect-denial")
            with subprocess.Popen(worker_arguments, stdin=subprocess.PIPE,
                                  stdout=subprocess.PIPE, text=True) as worker:
                session = None
                try:
                    ready = api.read_event(worker, reader, "worker_ready")
                    messages = queue.Queue()

                    def receive_message(message, data):
                        """Enqueue the Frida message unchanged for the controller's assertions."""
                        messages.put(message)

                    def expect_hook_event(expected):
                        """Require the named hook event for the owned PID within 30 seconds."""
                        message = messages.get(timeout=30)
                        assert message["type"] == "send", message
                        event = message["payload"]
                        api.emit("interceptor_event", payload=event)
                        assert event["event"] == expected, event
                        assert event["pid"] == worker.pid, event
                        return event

                    session = frida.attach(worker.pid)
                    if mode == "attach":
                        script_source = "send({event: 'attachment_ready', pid: Process.id});"
                        installed_event = "attachment_ready"
                    elif mode == "native-forward":
                        script_source = Path(__file__).with_name("windows-native-allocation-forwarder.js").read_text()
                        installed_event = "hook_installed"
                    elif is_native_hook:
                        script_source = Path(__file__).with_name("windows-native-allocation-fast-forwarder.js").read_text()
                        installed_event = "hook_installed"
                    else:
                        script_source = Path(__file__).with_name("windows-allocation-interceptor.js").read_text()
                        installed_event = "hook_installed"
                    script = session.create_script(script_source)
                    script.on("message", receive_message)
                    script.load()
                    expected_allocation_address = ready["allocation_address"]
                    if is_native_hook:
                        target = expect_hook_event("hook_target")
                        if target["architecture"] == "arm64":
                            expected_allocation_address = ready["implementation_address"]
                        assert int(target["replacement_address"], 16) == int(expected_allocation_address, 16)
                    installed = expect_hook_event(installed_event)
                    if installed_event == "hook_installed":
                        assert int(installed["address"], 16) == int(expected_allocation_address, 16)

                    process = api.require_api_success(kernel.OpenProcess(0x0141, False, worker.pid))
                    try:
                        api.require_api_success(kernel.AssignProcessToJobObject(job, process))
                        if is_native_retry:
                            worker_events = []
                            for _ in range(2):
                                event = api.require_api_success(kernel.CreateEventW(None, True, False, None))
                                controller_events.append(event)
                                duplicate = ctypes.c_void_p()
                                api.require_api_success(kernel.DuplicateHandle(
                                    kernel.GetCurrentProcess(), event, process, ctypes.byref(duplicate),
                                    0, False, 2))
                                worker_events.append(hex(duplicate.value))
                            wait_milliseconds = 1000 if mode == "native-timeout" else 30000
                            armed = reader.submit(script.exports_sync.arm_native, ready["thread_id"],
                                                  *worker_events, wait_milliseconds).result(timeout=30)
                            assert armed is True
                    finally:
                        api.require_api_success(kernel.CloseHandle(process))
                    api.require_api_success(kernel.QueryInformationJobObject(job, job_extended_limits, ctypes.byref(limits), ctypes.sizeof(limits), None))
                    limits.ProcessMemoryLimit = limits.PeakProcessMemoryUsed + (32 * 1024 * 1024)
                    if is_shutdown_control:
                        limits.ProcessMemoryLimit += ready["requested_bytes"]
                    limits.BasicLimitInformation.LimitFlags = kill_on_job_close | process_commit_limit
                    api.require_api_success(kernel.SetInformationJobObject(job, job_extended_limits, ctypes.byref(limits), ctypes.sizeof(limits)))
                    api.emit("worker_commit_limit_set", worker_pid=worker.pid,
                             peak_commit_bytes=limits.PeakProcessMemoryUsed, limit_bytes=limits.ProcessMemoryLimit)
                    if mode == "retry":
                        armed = reader.submit(script.exports_sync.arm, ready["thread_id"],
                                              ready["requested_bytes"]).result(timeout=30)
                        assert armed == {"threadId": ready["thread_id"], "bytes": ready["requested_bytes"]}
                        api.emit("allocation_hook_armed", worker_pid=worker.pid, configuration=armed)
                    worker.stdin.write("allocate\n")
                    worker.stdin.flush()
                    if is_native_retry:
                        blocked_wait = kernel.WaitForSingleObject(controller_events[0], 30000)
                        assert blocked_wait == 0, {"blocked_event_wait": blocked_wait,
                                                   "last_error": ctypes.get_last_error()}
                        blocked_state = reader.submit(script.exports_sync.native_retry_state).result(timeout=30)
                        assert blocked_state["matching_calls"] == 1, blocked_state
                        assert blocked_state["attempts"] == 1, blocked_state
                        assert blocked_state["first_error"] != 0, blocked_state
                        assert worker.poll() is None
                        api.emit("native_allocation_blocked", worker_pid=worker.pid, state=blocked_state)
                        if mode == "native-retry":
                            subprocess.run([sys.executable, "-c", "pass"], check=True, timeout=30)
                            api.emit("independent_peer_completed", worker_pid=worker.pid)
                            pending = reader.submit(script.exports_sync.native_retry_state).result(timeout=30)
                            assert pending["attempts"] == 1, pending
                            assert pending["wait_result"] == 0xffffffff, pending
                            assert int(pending["pointer"], 16) == 0, pending
                            assert worker.poll() is None
                            api.emit("native_allocation_still_blocked", worker_pid=worker.pid, state=pending)
                            limits.ProcessMemoryLimit += ready["requested_bytes"]
                            api.require_api_success(kernel.SetInformationJobObject(
                                job, job_extended_limits, ctypes.byref(limits), ctypes.sizeof(limits)))
                            api.emit("worker_commit_limit_raised", limit_bytes=limits.ProcessMemoryLimit)
                            api.require_api_success(kernel.SetEvent(controller_events[1]))
                    if mode == "retry":
                        blocked = expect_hook_event("native_allocation_blocked")
                        assert blocked["thread_id"] == ready["thread_id"]
                        assert blocked["bytes"] == ready["requested_bytes"]
                        assert (blocked["allocation_type"], blocked["protection"]) == (0x3000, 0x04)
                        assert worker.poll() is None

                        subprocess.run([sys.executable, "-c", "pass"], check=True, timeout=30)
                        api.emit("independent_peer_completed", worker_pid=worker.pid)
                        limits.ProcessMemoryLimit += ready["requested_bytes"]
                        api.require_api_success(kernel.SetInformationJobObject(job, job_extended_limits, ctypes.byref(limits), ctypes.sizeof(limits)))
                        api.emit("worker_commit_limit_raised", limit_bytes=limits.ProcessMemoryLimit)
                        script.post({"type": "retry"})
                        retried = expect_hook_event("native_allocation_retried")
                        assert retried["succeeded"] is True, retried
                        assert retried["thread_id"] == blocked["thread_id"]
                        assert retried["bytes"] == blocked["bytes"]
                    expected_return = "native_call_failed" if mode == "native-timeout" else "native_call_returned"
                    returned = api.read_event(worker, reader, expected_return)
                    if is_native_retry:
                        state = reader.submit(script.exports_sync.native_retry_state).result(timeout=30)
                        assert state["matching_calls"] == 1, state
                        assert state["signal_ok"] == 1, state
                        assert state["final_error"] == returned["last_error"], state
                        if mode == "native-timeout":
                            assert state["attempts"] == 1, state
                            assert state["wait_result"] == 258, state
                            assert int(state["pointer"], 16) == 0, state
                            assert state["error_sentinel"] != state["first_error"], state
                            assert returned["last_error"] == state["first_error"], state
                        else:
                            assert state["attempts"] == 2, state
                            assert state["wait_result"] == 0, state
                            assert int(state["pointer"], 16) == int(returned["pointer"], 16), state
                        api.emit("native_retry_observed", worker_pid=worker.pid, state=state)
                    if mode == "retry":
                        assert int(returned["pointer"], 16) == int(retried["pointer"], 16)
                    if mode == "native-fast":
                        observed = reader.submit(script.exports_sync.probe_allocation_count).result(timeout=30)
                        assert observed == 1, observed
                        api.emit("native_forwarding_observed", worker_pid=worker.pid, matching_calls=observed)
                    worker.stdin.write("finish\n")
                    worker.stdin.flush()
                    if mode != "native-timeout":
                        api.read_event(worker, reader, "allocation_freed")
                    try:
                        worker_exit = worker.wait(timeout=30)
                    except subprocess.TimeoutExpired:
                        if is_shutdown_control or is_native_retry:
                            api.emit("shutdown_control_timed_out", mode=mode, worker_pid=worker.pid)
                            raise
                        snapshot = reader.submit(script.exports_sync.inspect_threads).result(timeout=10)
                        api.emit("shutdown_thread_snapshot", worker_pid=worker.pid, threads=snapshot)
                        removed = reader.submit(script.exports_sync.remove_hook).result(timeout=10)
                        assert removed is True
                        api.emit("hook_removed_after_shutdown_timeout", worker_pid=worker.pid)
                        recovered_exit = worker.wait(timeout=30)
                        api.emit("shutdown_after_hook_removal", worker_pid=worker.pid, exit_code=recovered_exit)
                        raise AssertionError("Worker shutdown required diagnostic hook removal after its normal exit timed out")
                    assert worker_exit == 0
                    api.emit("interception_probe_passed", mode=mode, worker_pid=worker.pid, frida_version=frida.__version__)
                finally:
                    if worker.poll() is None:
                        worker.kill()
                    worker.wait()
                    if session:
                        try:
                            session.detach()
                        except frida.InvalidOperationError:
                            # The confirmed terminal worker has already detached its session.
                            pass
    finally:
        for event in controller_events:
            api.require_api_success(kernel.CloseHandle(event))
        api.require_api_success(kernel.CloseHandle(job))


def main():
    """Run the controller or its internal worker on matching native Windows Python."""
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--worker", action="store_true", help=argparse.SUPPRESS)
    parser.add_argument("--expect-denial", action="store_true", help=argparse.SUPPRESS)
    parser.add_argument("--mode", choices=("attach", "forward", "native-forward", "native-fast", "retry",
                                         "native-retry", "native-timeout"), default="retry",
                        help="Instrumentation under test: attachment, forwarding, JavaScript retry (default), native retry, or native timeout")
    arguments = parser.parse_args()
    assert sys.platform == "win32", "This probe requires native Windows"
    assert ctypes.sizeof(ctypes.c_void_p) == 8, "This probe requires a 64-bit process"
    expected_machine = {"X64": "AMD64", "ARM64": "ARM64"}.get(os.environ.get("RUNNER_ARCH"))
    if expected_machine:
        assert platform.machine().upper() == expected_machine
    api = load_windows_api()
    kernel = api.bind_kernel_api()
    if arguments.worker:
        run_worker(api, kernel, arguments.expect_denial)
    else:
        api.emit("native_identity", machine=platform.machine(), python=sys.version,
                 runner_arch=os.environ.get("RUNNER_ARCH"), mode=arguments.mode)
        run_probe(api, kernel, arguments.mode)


if __name__ == "__main__":
    main()
