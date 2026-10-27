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


def run_worker(api, kernel):
    """Make one 256 MiB VirtualAlloc, read/write it, await finish, free and exit.

    An allocation failure raises the native Windows error. No caller-side loop
    or retry is available; a passing hook must return a successful pointer.
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
    if pointer is None:
        raise ctypes.WinError(ctypes.get_last_error())
    try:
        ctypes.c_ubyte.from_address(pointer).value = 42
        ctypes.c_ubyte.from_address(pointer + requested_bytes - 1).value = 43
        first_byte = ctypes.c_ubyte.from_address(pointer).value
        last_byte = ctypes.c_ubyte.from_address(pointer + requested_bytes - 1).value
        assert (first_byte, last_byte) == (42, 43)
        api.emit("native_call_returned", pointer=hex(pointer), first_byte=first_byte, last_byte=last_byte)
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
    controls allow the 256 MiB request before starting it. ``retry`` retains
    the denied-allocation experiment. Every mode requires normal exit within
    30 seconds and kills/reaps an unfinished worker on failure.
    """
    import frida

    is_shutdown_control = mode in ("attach", "forward", "native-forward", "native-fast")
    job_extended_limits = 9
    kill_on_job_close = 0x2000
    process_commit_limit = 0x100
    job = api.require_api_success(kernel.CreateJobObjectW(None, None))
    try:
        limits = api.ExtendedLimitInformation()
        limits.BasicLimitInformation.LimitFlags = kill_on_job_close
        api.require_api_success(kernel.SetInformationJobObject(job, job_extended_limits, ctypes.byref(limits), ctypes.sizeof(limits)))
        with concurrent.futures.ThreadPoolExecutor(max_workers=1) as reader:
            with subprocess.Popen([sys.executable, __file__, "--worker"], stdin=subprocess.PIPE,
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
                    elif mode == "native-fast":
                        script_source = Path(__file__).with_name("windows-native-allocation-fast-forwarder.js").read_text()
                        installed_event = "hook_installed"
                    else:
                        script_source = Path(__file__).with_name("windows-allocation-interceptor.js").read_text()
                        installed_event = "hook_installed"
                    script = session.create_script(script_source)
                    script.on("message", receive_message)
                    script.load()
                    expected_allocation_address = ready["allocation_address"]
                    if mode == "native-fast":
                        target = expect_hook_event("hook_target")
                        if target["architecture"] == "arm64":
                            expected_allocation_address = ready["implementation_address"]
                        assert int(target["replacement_address"], 16) == int(expected_allocation_address, 16)
                    installed = expect_hook_event(installed_event)
                    if installed_event == "hook_installed":
                        assert int(installed["address"], 16) == int(expected_allocation_address, 16)

                    process = api.require_api_success(kernel.OpenProcess(0x0101, False, worker.pid))
                    try:
                        api.require_api_success(kernel.AssignProcessToJobObject(job, process))
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
                    returned = api.read_event(worker, reader, "native_call_returned")
                    if mode == "retry":
                        assert int(returned["pointer"], 16) == int(retried["pointer"], 16)
                    if mode == "native-fast":
                        observed = reader.submit(script.exports_sync.probe_allocation_count).result(timeout=30)
                        assert observed == 1, observed
                        api.emit("native_forwarding_observed", worker_pid=worker.pid, matching_calls=observed)
                    worker.stdin.write("finish\n")
                    worker.stdin.flush()
                    api.read_event(worker, reader, "allocation_freed")
                    try:
                        worker_exit = worker.wait(timeout=30)
                    except subprocess.TimeoutExpired:
                        if is_shutdown_control:
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
        api.require_api_success(kernel.CloseHandle(job))


def main():
    """Run the controller or its internal worker on matching native Windows Python."""
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--worker", action="store_true", help=argparse.SUPPRESS)
    parser.add_argument("--mode", choices=("attach", "forward", "native-forward", "native-fast", "retry"), default="retry",
                        help="Instrumentation under test: attachment, JavaScript/native forwarding, direct native forwarding, or allocation retry (default)")
    arguments = parser.parse_args()
    assert sys.platform == "win32", "This probe requires native Windows"
    assert ctypes.sizeof(ctypes.c_void_p) == 8, "This probe requires a 64-bit process"
    expected_machine = {"X64": "AMD64", "ARM64": "ARM64"}.get(os.environ.get("RUNNER_ARCH"))
    if expected_machine:
        assert platform.machine().upper() == expected_machine
    api = load_windows_api()
    kernel = api.bind_kernel_api()
    if arguments.worker:
        run_worker(api, kernel)
    else:
        api.emit("native_identity", machine=platform.machine(), python=sys.version,
                 runner_arch=os.environ.get("RUNNER_ARCH"), mode=arguments.mode)
        run_probe(api, kernel, arguments.mode)


if __name__ == "__main__":
    main()
