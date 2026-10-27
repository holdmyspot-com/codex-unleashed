"""Own one native Windows action and descendants through a kill-on-close job.

Requires native 64-bit Windows 10+ Python. Uses atomic JOB_LIST creation, with
only duplicated standard-stream handles inherited. No suspended/uncontained
fallback is offered. Use as a context manager; output belongs to the caller.
"""
import contextlib
import ctypes
from ctypes import wintypes
import os
from pathlib import Path
import subprocess
import sys
import time
from . import windows_api as api


class _ResourceScope(contextlib.ExitStack):
    """Release all resources while retaining an existing failure as primary."""
    def __exit__(self, exception_type, exception, traceback):
        """Attach a release failure as the cause of an existing operation error."""
        try:
            return super().__exit__(exception_type, exception, traceback)
        except BaseException as cleanup_error:
            if exception is None:
                raise
            raise exception.with_traceback(traceback) from cleanup_error


class ProcessTree:
    """Start an owned action and report completion only after its whole job exits.

    command is an argv sequence beginning with an absolute executable path.
    directory is an existing absolute working directory. environment is an
    explicit string mapping, copied before launch; output is a borrowed binary
    file with a Windows handle. Standard input is the null device. Paths,
    arguments and output bytes retain their normal Windows meanings.

    This single-controller object is not thread-safe. It owns the job/process
    handles and children; creation failure releases resources created here,
    without closing output. poll returns the original root exit status only
    after all descendants exit. terminate requests whole-job termination with
    status 125; the controller must record that it aborted the attempt rather
    than infer a successful attempt from an already-exited root's status.
    close terminates unfinished work, waits up to ten seconds and closes native
    handles. A failed wait remains an error; closing handles is not a success
    receipt. Owned job handles are never inherited by the child.
    """
    def __init__(self, command, directory, environment, output):
        if sys.platform != "win32" or ctypes.sizeof(ctypes.c_void_p) != 8:
            raise OSError("ProcessTree requires native 64-bit Windows")
        if isinstance(command, (tuple, list)) is False or len(command) == 0:
            raise ValueError("command must be a nonempty argv sequence")
        command = tuple(command)
        for argument in command:
            if isinstance(argument, str) is False or "\0" in argument:
                raise ValueError("command arguments must be strings without NUL")
        executable = Path(command[0])
        directory = Path(directory)
        if executable.is_absolute() is False or executable.is_file() is False:
            raise ValueError("command must begin with an absolute executable file")
        if directory.is_absolute() is False or directory.is_dir() is False:
            raise ValueError("directory must be an existing absolute directory")
        environment = dict(environment)
        for name, value in environment.items():
            if isinstance(name, str) is False or isinstance(value, str) is False:
                raise TypeError("environment names and values must be strings")
            if name == "" or "\0" in name or "=" in name[1:] or "\0" in value:
                raise ValueError("environment contains an invalid name or NUL")

        self._kernel = api.bind_kernel_api()
        self._closed = False
        self._process = None
        with _ResourceScope() as owned:
            self._job = api.require_api_success(self._kernel.CreateJobObjectW(None, None))
            owned.callback(self._close_handle, self._job)
            limits = api.ExtendedLimitInformation()
            limits.BasicLimitInformation.LimitFlags = 0x2000
            api.require_api_success(self._kernel.SetInformationJobObject(self._job, 9, ctypes.byref(limits), ctypes.sizeof(limits)))
            self._launch(command, directory, environment, output, owned)
            self._cleanup = owned.pop_all()

    def _launch(self, command, directory, environment, output, owned):
        """Create the process in its job with only the two owned stream handles."""
        import msvcrt
        with _ResourceScope() as launch, open(os.devnull, "rb") as null_input:
            streams = []
            for file in (null_input, output):
                duplicate = wintypes.HANDLE()
                current = self._kernel.GetCurrentProcess()
                original = wintypes.HANDLE(msvcrt.get_osfhandle(file.fileno()))
                api.require_api_success(self._kernel.DuplicateHandle(current, original, current, ctypes.byref(duplicate), 0, True, 2))
                streams.append(duplicate.value)
                launch.callback(self._close_handle, duplicate.value)
            size = ctypes.c_size_t()
            result = self._kernel.InitializeProcThreadAttributeList(None, 2, 0, ctypes.byref(size))
            error = ctypes.get_last_error()
            if result != 0 or error != 122:
                raise OSError(error, "InitializeProcThreadAttributeList size query did not report insufficient buffer")
            attributes = ctypes.create_string_buffer(size.value)
            api.require_api_success(self._kernel.InitializeProcThreadAttributeList(attributes, 2, 0, ctypes.byref(size)))
            launch.callback(self._kernel.DeleteProcThreadAttributeList, attributes)
            jobs = (wintypes.HANDLE * 1)(self._job)
            inherited = (wintypes.HANDLE * len(streams))(*streams)
            for key, values in ((0x2000D, jobs), (0x20002, inherited)):
                api.require_api_success(self._kernel.UpdateProcThreadAttribute(attributes, 0, key, values, ctypes.sizeof(values), None, None))
            startup = api.StartupEx()
            startup.startup.cb = ctypes.sizeof(startup)
            startup.startup.flags = 0x100
            startup.startup.stdin, startup.startup.stdout = streams
            startup.startup.stderr = streams[1]
            startup.attributes = ctypes.cast(attributes, ctypes.c_void_p)
            command_line = ctypes.create_unicode_buffer(subprocess.list2cmdline(command))
            environment_text = "\0".join(name + "=" + value for name, value in sorted(environment.items(), key=lambda item: item[0].upper())) + "\0\0"
            environment_block = ctypes.create_unicode_buffer(environment_text)
            info = api.ProcessInfo()
            api.require_api_success(self._kernel.CreateProcessW(command[0], command_line, None, None, True,
                0x80000 | 0x400, ctypes.cast(environment_block, ctypes.c_void_p), str(directory), ctypes.byref(startup), ctypes.byref(info)))
            self._process = info.process
            self.pid = info.pid
            owned.callback(self._close_handle, self._process)
            owned.callback(self._close_handle, info.thread)
            owned.callback(self._terminate_and_wait)

    def poll(self):
        """Return root exit status only when no process remains active; else None."""
        self._require_open()
        accounting = api.Accounting()
        api.require_api_success(self._kernel.QueryInformationJobObject(self._job, 1, ctypes.byref(accounting), ctypes.sizeof(accounting), None))
        if accounting.active > 0:
            return None
        status = self._kernel.WaitForSingleObject(self._process, 0)
        if status == 0x102:
            return None
        if status == 0xFFFFFFFF:
            raise ctypes.WinError(ctypes.get_last_error())
        if status != 0:
            raise OSError(status, "Unexpected process wait status")
        code = wintypes.DWORD()
        api.require_api_success(self._kernel.GetExitCodeProcess(self._process, ctypes.byref(code)))
        return code.value

    def terminate(self):
        """Request status-125 termination of all associated processes and child jobs."""
        self._require_open()
        api.require_api_success(self._kernel.TerminateJobObject(self._job, 125))

    def wait(self, timeout_ms):
        """Wait at most integer timeout_ms for whole-job exit; raise on timeout."""
        self._require_open()
        if type(timeout_ms) is not int or timeout_ms < 0:
            raise ValueError("timeout_ms must be a nonnegative integer")
        deadline = time.monotonic_ns() + (timeout_ms * 1_000_000)
        while True:
            status = self.poll()
            if status is not None:
                return status
            if time.monotonic_ns() >= deadline:
                raise TimeoutError(f"Process tree {self.pid} is still active after {timeout_ms} ms")
            time.sleep(.01)

    def close(self):
        """Terminate/wait for unfinished work, then close owned handles; idempotent."""
        self.__exit__(None, None, None)

    def _terminate_and_wait(self):
        """Preserve a failed native termination/wait as a cleanup error."""
        if self.poll() is None:
            self.terminate()
            self.wait(10_000)

    def _close_handle(self, handle):
        """Close an owned native handle, retaining the native failure if any."""
        api.require_api_success(self._kernel.CloseHandle(handle))

    def _require_open(self):
        """Refuse use after handles have been closed."""
        if self._closed:
            raise ValueError("ProcessTree is closed")

    def __enter__(self):
        self._require_open()
        return self

    def __exit__(self, exception_type, exception, traceback):
        """Release the tree, attaching cleanup errors to an existing body failure."""
        if self._closed:
            return
        try:
            self._cleanup.__exit__(exception_type, exception, traceback)
        finally:
            self._closed = True
