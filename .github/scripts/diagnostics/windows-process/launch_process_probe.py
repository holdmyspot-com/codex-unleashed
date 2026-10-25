import argparse
import ctypes as C
from ctypes import wintypes as W
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import time


def main():
    if sys.platform != "win32":
        raise RuntimeError("This diagnostic launcher requires native Windows Python")
    parser = argparse.ArgumentParser()
    parser.add_argument("--java", required=True, type=Path)
    parser.add_argument("--jcmd", required=True, type=Path)
    parser.add_argument("--cmd", required=True, type=Path)
    parser.add_argument("--git", required=True, type=Path)
    parser.add_argument("--source", required=True, type=Path)
    parser.add_argument("--owned-parent", required=True, type=Path)
    args = parser.parse_args()
    for name in ("java", "jcmd", "cmd", "git", "source"):
        path = getattr(args, name)
        if not path.is_absolute() or not path.is_file():
            parser.error(name + " must name an absolute existing file")
    if args.java.parent.resolve() != args.jcmd.parent.resolve():
        parser.error("java and jcmd must come from the same JDK bin directory")
    if not args.owned_parent.is_absolute() or not args.owned_parent.is_dir():
        parser.error("owned-parent must name an absolute existing directory")

    import msvcrt
    kernel = C.WinDLL("kernel32", use_last_error=True)
    size_type = C.c_size_t

    class StartupInfo(C.Structure):
        _fields_ = [("cb", W.DWORD), ("reserved", W.LPWSTR), ("desktop", W.LPWSTR),
                    ("title", W.LPWSTR), ("x", W.DWORD), ("y", W.DWORD),
                    ("xsize", W.DWORD), ("ysize", W.DWORD), ("xchars", W.DWORD),
                    ("ychars", W.DWORD), ("fill", W.DWORD), ("flags", W.DWORD),
                    ("show", W.WORD), ("reserved_size", W.WORD), ("reserved_bytes", C.c_void_p),
                    ("stdin", W.HANDLE), ("stdout", W.HANDLE), ("stderr", W.HANDLE)]

    class ProcessInfo(C.Structure):
        _fields_ = [("process", W.HANDLE), ("thread", W.HANDLE),
                    ("pid", W.DWORD), ("thread_id", W.DWORD)]

    class BasicLimits(C.Structure):
        _fields_ = [("process_time", C.c_longlong), ("job_time", C.c_longlong),
                    ("flags", W.DWORD), ("min_working_set", size_type),
                    ("max_working_set", size_type), ("active_limit", W.DWORD),
                    ("affinity", size_type), ("priority", W.DWORD), ("scheduling", W.DWORD)]

    class IoCounters(C.Structure):
        _fields_ = [(name, C.c_ulonglong) for name in
                    ("read_ops", "write_ops", "other_ops", "read_bytes", "write_bytes", "other_bytes")]

    class ExtendedLimits(C.Structure):
        _fields_ = [("basic", BasicLimits), ("io", IoCounters),
                    ("process_memory", size_type), ("job_memory", size_type),
                    ("peak_process_memory", size_type), ("peak_job_memory", size_type)]

    class Accounting(C.Structure):
        _fields_ = [(name, C.c_longlong) for name in
                    ("user", "kernel", "period_user", "period_kernel")] + [
                    (name, W.DWORD) for name in
                    ("faults", "total", "active", "terminated")]

    signatures = {
        "CreateJobObjectW": ([C.c_void_p, W.LPCWSTR], W.HANDLE),
        "SetInformationJobObject": ([W.HANDLE, C.c_int, C.c_void_p, W.DWORD], W.BOOL),
        "QueryInformationJobObject": ([W.HANDLE, C.c_int, C.c_void_p, W.DWORD, C.c_void_p], W.BOOL),
        "AssignProcessToJobObject": ([W.HANDLE, W.HANDLE], W.BOOL),
        "TerminateJobObject": ([W.HANDLE, W.UINT], W.BOOL),
        "CreateProcessW": ([W.LPCWSTR, W.LPWSTR, C.c_void_p, C.c_void_p, W.BOOL,
                            W.DWORD, C.c_void_p, W.LPCWSTR, C.POINTER(StartupInfo),
                            C.POINTER(ProcessInfo)], W.BOOL),
        "ResumeThread": ([W.HANDLE], W.DWORD),
        "WaitForSingleObject": ([W.HANDLE, W.DWORD], W.DWORD),
        "GetExitCodeProcess": ([W.HANDLE, C.POINTER(W.DWORD)], W.BOOL),
        "TerminateProcess": ([W.HANDLE, W.UINT], W.BOOL),
        "CloseHandle": ([W.HANDLE], W.BOOL),
        "GetCurrentProcess": ([], W.HANDLE),
        "DuplicateHandle": ([W.HANDLE, W.HANDLE, W.HANDLE, C.POINTER(W.HANDLE),
                              W.DWORD, W.BOOL, W.DWORD], W.BOOL)
    }
    for name, (parameters, result) in signatures.items():
        function = getattr(kernel, name)
        function.argtypes = parameters
        function.restype = result

    def check(result):
        if not result:
            raise C.WinError(C.get_last_error())
        return result

    def active_processes(job):
        accounting = Accounting()
        check(kernel.QueryInformationJobObject(job, 1, C.byref(accounting), C.sizeof(accounting), None))
        return accounting.active

    def duplicate(file):
        handle = W.HANDLE()
        current = kernel.GetCurrentProcess()
        original = W.HANDLE(msvcrt.get_osfhandle(file.fileno()))
        check(kernel.DuplicateHandle(current, original, current, C.byref(handle), 0, True, 2))
        return handle

    retained = Path(tempfile.mkdtemp(prefix="java-process-probe-", dir=args.owned_parent))
    print("retained_directory=" + str(retained), flush=True)
    work = retained / "work"
    work.mkdir()
    environment = dict(os.environ)
    environment["TEMP"] = str(work)
    environment["TMP"] = str(work)
    environment_block = C.create_unicode_buffer("\0".join(
        key + "=" + value for key, value in sorted(environment.items(), key=lambda pair: pair[0].upper())) + "\0\0")
    processes = []
    job = None
    receipt = {"status": "setup", "cleanup_owner": "workflow owner retains/uploads then removes retained_directory"}
    began = time.monotonic_ns()
    deadline = began + 180_000_000_000
    terminate_at = deadline - 5_000_000_000
    dump_at = began + 10_000_000_000
    exit_code = 1

    try:
        job = check(kernel.CreateJobObjectW(None, None))
        limits = ExtendedLimits()
        limits.basic.flags = 0x2000
        check(kernel.SetInformationJobObject(job, 9, C.byref(limits), C.sizeof(limits)))

        def spawn(command, output):
            startup = StartupInfo()
            startup.cb = C.sizeof(startup)
            startup.flags = 0x100
            handles = []
            process = ProcessInfo()
            try:
                for file in (sys.stdin, output, output):
                    handles.append(duplicate(file))
                startup.stdin, startup.stdout, startup.stderr = handles
                command_line = C.create_unicode_buffer(subprocess.list2cmdline([str(part) for part in command]))
                check(kernel.CreateProcessW(str(command[0]), command_line, None, None, True,
                      0x00000004 | 0x00000400, C.cast(environment_block, C.c_void_p), str(work),
                      C.byref(startup), C.byref(process)))
                processes.append(process)
                try:
                    check(kernel.AssignProcessToJobObject(job, process.process))
                    resumed = kernel.ResumeThread(process.thread)
                    if resumed == 0xFFFFFFFF:
                        raise C.WinError(C.get_last_error())
                except BaseException:
                    kernel.TerminateProcess(process.process, 125)
                    raise
                finally:
                    kernel.CloseHandle(process.thread)
                return process
            finally:
                for handle in handles:
                    kernel.CloseHandle(handle)

        with (retained / "probe.log").open("wb", buffering=0) as probe_output, \
             (retained / "jcmd.log").open("wb", buffering=0) as dump_output:
            command = [args.java, "-Djava.io.tmpdir=" + str(work), args.source,
                       args.cmd, args.git, work]
            print("java_executable=" + str(args.java), flush=True)
            print("jcmd_executable=" + str(args.jcmd), flush=True)
            java = spawn(command, probe_output)
            receipt["java_pid"] = java.pid
            print("java_pid=" + str(java.pid), flush=True)
            receipt["status"] = "running"
            dump_started = False
            while True:
                now = time.monotonic_ns()
                wait = kernel.WaitForSingleObject(java.process, 0)
                if wait == 0:
                    status = W.DWORD()
                    check(kernel.GetExitCodeProcess(java.process, C.byref(status)))
                    receipt["java_exit_code"] = status.value
                    receipt["status"] = "java_exited"
                    exit_code = status.value
                    break
                if wait == 0xFFFFFFFF:
                    raise C.WinError(C.get_last_error())
                if now >= terminate_at:
                    receipt["status"] = "deadline"
                    exit_code = 124
                    break
                if now >= dump_at and dump_started == False:
                    dump_started = True
                    dump = spawn([args.jcmd, str(java.pid), "Thread.print", "-l"], dump_output)
                    receipt["jcmd_pid"] = dump.pid
                    print("jcmd_pid=" + str(dump.pid), flush=True)
                time.sleep(0.05)
    except BaseException as failure:
        receipt["status"] = "launcher_failure"
        receipt["failure_type"] = type(failure).__name__
        receipt["failure"] = str(failure)
        exit_code = 125
    finally:
        if job:
            try:
                check(kernel.TerminateJobObject(job, 124))
                while active_processes(job) > 0 and time.monotonic_ns() < deadline:
                    time.sleep(0.02)
                receipt["remaining_job_processes"] = active_processes(job)
                for process in processes:
                    remaining_ms = max(0, (deadline - time.monotonic_ns()) // 1_000_000)
                    wait = kernel.WaitForSingleObject(process.process, min(remaining_ms, 5000))
                    if wait != 0:
                        receipt["process_reap_failed_pid"] = process.pid
                        exit_code = 125
                    else:
                        status = W.DWORD()
                        check(kernel.GetExitCodeProcess(process.process, C.byref(status)))
                        receipt.setdefault("reaped_processes", []).append(
                            {"pid": process.pid, "exit_code": status.value})
                if receipt["remaining_job_processes"] > 0:
                    exit_code = 125
            except OSError as failure:
                receipt["cleanup_failure"] = str(failure)
                exit_code = 125
            finally:
                kernel.CloseHandle(job)
        for process in processes:
            kernel.CloseHandle(process.process)
        receipt["elapsed_ns"] = time.monotonic_ns() - began
        receipt["launcher_exit_code"] = exit_code
        (retained / "receipt.json").write_text(json.dumps(receipt, indent=2) + "\n", encoding="utf-8")
        print(json.dumps(receipt), flush=True)
    return exit_code


if __name__ == "__main__":
    sys.exit(main())
