"""Probe Windows job-limited allocation failure and retry; this is not a scheduler.

Run without arguments on native 64-bit Windows. JSON events go to stdout; an
API error, unexpected allocation result or missing worker event fails the probe.
Only the owned worker receives a commit limit. A worker event must arrive within
30 seconds. All child processes and job/process handles are closed on exit.
The internal --worker invocation waits inside a fixture allocation function;
it does not inject that function into a compiler or block a kernel allocation.
"""

import argparse
import concurrent.futures
import ctypes
from ctypes import wintypes
import json
import os
import platform
import subprocess
import sys


class BasicLimitInformation(ctypes.Structure):
    """Windows JOBOBJECT_BASIC_LIMIT_INFORMATION, using the SDK field layout."""

    _fields_ = [
        ("PerProcessUserTimeLimit", ctypes.c_int64),
        ("PerJobUserTimeLimit", ctypes.c_int64),
        ("LimitFlags", wintypes.DWORD),
        ("MinimumWorkingSetSize", ctypes.c_size_t),
        ("MaximumWorkingSetSize", ctypes.c_size_t),
        ("ActiveProcessLimit", wintypes.DWORD),
        ("Affinity", ctypes.c_size_t),
        ("PriorityClass", wintypes.DWORD),
        ("SchedulingClass", wintypes.DWORD)
    ]


class IoCounters(ctypes.Structure):
    """Windows IO_COUNTERS, embedded unchanged in the extended job limits."""

    _fields_ = [
        ("ReadOperationCount", ctypes.c_uint64),
        ("WriteOperationCount", ctypes.c_uint64),
        ("OtherOperationCount", ctypes.c_uint64),
        ("ReadTransferCount", ctypes.c_uint64),
        ("WriteTransferCount", ctypes.c_uint64),
        ("OtherTransferCount", ctypes.c_uint64)
    ]


class ExtendedLimitInformation(ctypes.Structure):
    """Windows JOBOBJECT_EXTENDED_LIMIT_INFORMATION; memory quantities are bytes."""

    _fields_ = [
        ("BasicLimitInformation", BasicLimitInformation),
        ("IoInfo", IoCounters),
        ("ProcessMemoryLimit", ctypes.c_size_t),
        ("JobMemoryLimit", ctypes.c_size_t),
        ("PeakProcessMemoryUsed", ctypes.c_size_t),
        ("PeakJobMemoryUsed", ctypes.c_size_t)
    ]


def bind_kernel_api():
    """Return kernel32 with pointer-safe signatures for the native probe APIs."""
    kernel = ctypes.WinDLL("kernel32", use_last_error=True)
    signatures = {
        "CreateJobObjectW": ([ctypes.c_void_p, wintypes.LPCWSTR], wintypes.HANDLE),
        "OpenProcess": ([wintypes.DWORD, wintypes.BOOL, wintypes.DWORD], wintypes.HANDLE),
        "AssignProcessToJobObject": ([wintypes.HANDLE, wintypes.HANDLE], wintypes.BOOL),
        "SetInformationJobObject": ([wintypes.HANDLE, ctypes.c_int, ctypes.c_void_p, wintypes.DWORD], wintypes.BOOL),
        "QueryInformationJobObject": ([wintypes.HANDLE, ctypes.c_int, ctypes.c_void_p, wintypes.DWORD, ctypes.c_void_p], wintypes.BOOL),
        "CloseHandle": ([wintypes.HANDLE], wintypes.BOOL),
        "VirtualAlloc": ([ctypes.c_void_p, ctypes.c_size_t, wintypes.DWORD, wintypes.DWORD], ctypes.c_void_p),
        "VirtualFree": ([ctypes.c_void_p, ctypes.c_size_t, wintypes.DWORD], wintypes.BOOL)
    }
    for name, (arguments, result) in signatures.items():
        operation = getattr(kernel, name)
        operation.argtypes = arguments
        operation.restype = result
    return kernel


def require_api_success(result):
    """Raise the captured Windows error when a BOOL/handle API reports failure."""
    if result == 0 or result is None:
        raise ctypes.WinError(ctypes.get_last_error())
    return result


def emit(event, **details):
    """Flush one JSON event with this process's identity to the controller/log."""
    print(json.dumps({"event": event, "pid": os.getpid(), **details}), flush=True)


def allocate_after_permission(kernel, requested_bytes):
    """Retry one denied VirtualAlloc after stdin permission, returning its pointer.

    The same size, address hint, protection and allocation flags are used twice.
    An initial success or a failed retry raises AssertionError. The caller owns
    VirtualFree after success. Permission other than 'retry' fails the probe.
    """
    allocation_flags = 0x3000  # MEM_COMMIT | MEM_RESERVE, from the Windows SDK.
    read_write = 0x04  # PAGE_READWRITE, from the Windows SDK.
    pointer = kernel.VirtualAlloc(None, requested_bytes, allocation_flags, read_write)
    if pointer:
        require_api_success(kernel.VirtualFree(pointer, 0, 0x8000))
        raise AssertionError("Allocation succeeded while the process commit limit should deny it")
    emit("allocation_denied", bytes=requested_bytes, windows_error=ctypes.get_last_error())
    assert sys.stdin.readline().strip() == "retry", "Missing allocation retry permission"
    pointer = kernel.VirtualAlloc(None, requested_bytes, allocation_flags, read_write)
    if pointer is None:
        raise ctypes.WinError(ctypes.get_last_error())
    return pointer


def run_worker(kernel):
    """Wait for the job limit, attempt 256 MiB, retry on permission, touch and free."""
    requested_bytes = 256 * 1024 * 1024
    emit("worker_ready", requested_bytes=requested_bytes)
    assert sys.stdin.readline().strip() == "allocate", "Missing allocation start permission"
    pointer = allocate_after_permission(kernel, requested_bytes)
    try:
        ctypes.c_ubyte.from_address(pointer).value = 42
        ctypes.c_ubyte.from_address(pointer + requested_bytes - 1).value = 43
        first_byte = ctypes.c_ubyte.from_address(pointer).value
        last_byte = ctypes.c_ubyte.from_address(pointer + requested_bytes - 1).value
        assert (first_byte, last_byte) == (42, 43)
        emit("allocation_resumed", bytes=requested_bytes, first_byte=first_byte, last_byte=last_byte)
    finally:
        require_api_success(kernel.VirtualFree(pointer, 0, 0x8000))
    emit("allocation_freed")


def read_event(worker, reader, expected):
    """Read/print the expected worker event within 30 seconds or fail the probe."""
    line = reader.submit(worker.stdout.readline).result(timeout=30)
    if line == "":
        raise AssertionError(f"Worker output ended before {expected}; exit={worker.poll()}")
    event = json.loads(line)
    print(json.dumps(event), flush=True)
    assert event["event"] == expected, event
    assert event["pid"] == worker.pid, event
    return event


def run_probe(kernel):
    """Control one worker's job commit limit and verify denied-then-successful retry."""
    job_extended_limits = 9
    kill_on_job_close = 0x2000
    process_commit_limit = 0x100
    assert ctypes.sizeof(ExtendedLimitInformation) == 144
    job = require_api_success(kernel.CreateJobObjectW(None, None))
    try:
        limits = ExtendedLimitInformation()
        limits.BasicLimitInformation.LimitFlags = kill_on_job_close
        require_api_success(kernel.SetInformationJobObject(job, job_extended_limits, ctypes.byref(limits), ctypes.sizeof(limits)))
        with concurrent.futures.ThreadPoolExecutor(max_workers=1) as reader:
            with subprocess.Popen([sys.executable, __file__, "--worker"], stdin=subprocess.PIPE, stdout=subprocess.PIPE, text=True) as worker:
                try:
                    ready = read_event(worker, reader, "worker_ready")
                    process_access = 0x0101  # PROCESS_SET_QUOTA | PROCESS_TERMINATE.
                    process = require_api_success(kernel.OpenProcess(process_access, False, worker.pid))
                    try:
                        require_api_success(kernel.AssignProcessToJobObject(job, process))
                    finally:
                        require_api_success(kernel.CloseHandle(process))

                    require_api_success(kernel.QueryInformationJobObject(job, job_extended_limits, ctypes.byref(limits), ctypes.sizeof(limits), None))
                    limits.ProcessMemoryLimit = limits.PeakProcessMemoryUsed + (32 * 1024 * 1024)
                    limits.BasicLimitInformation.LimitFlags = kill_on_job_close | process_commit_limit
                    require_api_success(kernel.SetInformationJobObject(job, job_extended_limits, ctypes.byref(limits), ctypes.sizeof(limits)))
                    emit("worker_commit_limit_set", worker_pid=worker.pid, limit_bytes=limits.ProcessMemoryLimit, peak_commit_bytes=limits.PeakProcessMemoryUsed)
                    worker.stdin.write("allocate\n")
                    worker.stdin.flush()
                    denied = read_event(worker, reader, "allocation_denied")
                    assert denied["bytes"] == ready["requested_bytes"]
                    assert worker.poll() is None, "Worker exited instead of waiting for retry permission"

                    peer = subprocess.run([sys.executable, "-c", "print('peer_complete')"], check=True, text=True, capture_output=True, timeout=30)
                    assert peer.stdout.strip() == "peer_complete"
                    emit("independent_peer_completed", worker_pid=worker.pid)
                    limits.ProcessMemoryLimit += ready["requested_bytes"]
                    require_api_success(kernel.SetInformationJobObject(job, job_extended_limits, ctypes.byref(limits), ctypes.sizeof(limits)))
                    emit("worker_commit_limit_raised", limit_bytes=limits.ProcessMemoryLimit)
                    worker.stdin.write("retry\n")
                    worker.stdin.flush()
                    resumed = read_event(worker, reader, "allocation_resumed")
                    assert resumed["bytes"] == denied["bytes"]
                    read_event(worker, reader, "allocation_freed")
                    assert worker.wait(timeout=30) == 0
                    emit("probe_passed", worker_pid=worker.pid)
                finally:
                    if worker.poll() is None:
                        worker.kill()
                    worker.wait()
    finally:
        require_api_success(kernel.CloseHandle(job))


def main():
    """Run the native controller, or its internal worker; reject non-Windows hosts."""
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--worker", action="store_true", help=argparse.SUPPRESS)
    arguments = parser.parse_args()
    assert sys.platform == "win32", "This probe requires native Windows"
    assert ctypes.sizeof(ctypes.c_void_p) == 8, "This probe requires a 64-bit process"
    expected_machine = {"X64": "AMD64", "ARM64": "ARM64"}.get(os.environ.get("RUNNER_ARCH"))
    if expected_machine:
        assert platform.machine().upper() == expected_machine, "Python architecture differs from the native runner"
    kernel = bind_kernel_api()
    if arguments.worker:
        run_worker(kernel)
    else:
        emit("native_identity", machine=platform.machine(), python=sys.version, runner_arch=os.environ.get("RUNNER_ARCH"))
        run_probe(kernel)


if __name__ == "__main__":
    main()
