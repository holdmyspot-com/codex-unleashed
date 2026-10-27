"""Deny a real owned Rust compiler's heap allocation or reallocation and resume it.

Requires native 64-bit Windows, pinned Frida on PYTHONPATH, an absolute compiler
path, expected native host and owned storage. A 20,000-function stdin library is
compiled normally, then under a worker-only job commit limit with a native heap
hook on RtlAllocateHeap and RtlReAllocateHeap. Acceptance requires a real null result, independent progress while that
call waits, successful internal retry, compiler exit zero and identical metadata.
The controller reads hook state through ReadProcessMemory while the allowance is
restricted. It restores capacity before permitting retry. Only the owned compiler
and temporary files are affected. This does not prove physical-pressure recovery,
all allocator paths, thread classification for a production scheduler or FIFO.
"""
import argparse
import concurrent.futures
import contextlib
import ctypes
import hashlib
import importlib.util
import json
from pathlib import Path
import queue
import struct
import subprocess
import tempfile


def emit(event, **details):
    """Flush a diagnostic event for this owned compilation."""
    print(json.dumps({'event': event, **details}), flush=True)


def load_api():
    """Reuse the existing Windows job-limit structures and pointer-safe calls."""
    path = Path(__file__).with_name('probe-windows-allocation.py')
    spec = importlib.util.spec_from_file_location('windows_api', path)
    api = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(api)
    return api


def bind_events(kernel):
    """Bind native event, handle duplication, state-read and thread snapshot APIs."""
    signatures = {
        'CreateEventW': ([ctypes.c_void_p, ctypes.c_int, ctypes.c_int, ctypes.c_wchar_p], ctypes.c_void_p),
        'SetEvent': ([ctypes.c_void_p], ctypes.c_int),
        'GetCurrentProcess': ([], ctypes.c_void_p),
        'DuplicateHandle': ([ctypes.c_void_p, ctypes.c_void_p, ctypes.c_void_p,
                            ctypes.POINTER(ctypes.c_void_p), ctypes.c_uint32, ctypes.c_int,
                            ctypes.c_uint32], ctypes.c_int),
        'WaitForSingleObject': ([ctypes.c_void_p, ctypes.c_uint32], ctypes.c_uint32),
        'ReadProcessMemory': ([ctypes.c_void_p, ctypes.c_void_p, ctypes.c_void_p, ctypes.c_size_t,
                               ctypes.POINTER(ctypes.c_size_t)], ctypes.c_int),
        'CreateToolhelp32Snapshot': ([ctypes.c_uint32, ctypes.c_uint32], ctypes.c_void_p),
        'Thread32First': ([ctypes.c_void_p, ctypes.c_void_p], ctypes.c_int),
        'Thread32Next': ([ctypes.c_void_p, ctypes.c_void_p], ctypes.c_int)
    }
    for name, (arguments, result) in signatures.items():
        operation = getattr(kernel, name)
        operation.argtypes = arguments
        operation.restype = result


def find_compiler_threads(api, kernel, compiler_pid):
    """Capture existing compiler thread IDs before instrumentation is attached."""
    class ThreadEntry(ctypes.Structure):
        """Native THREADENTRY32 fields, which contain only 32-bit integers."""
        _fields_ = [('size', ctypes.c_uint32), ('usage', ctypes.c_uint32),
                    ('thread_id', ctypes.c_uint32), ('owner_pid', ctypes.c_uint32),
                    ('base_priority', ctypes.c_int32), ('delta_priority', ctypes.c_int32),
                    ('flags', ctypes.c_uint32)]
    snapshot = kernel.CreateToolhelp32Snapshot(4, 0)
    assert snapshot != ctypes.c_void_p(-1).value, ctypes.get_last_error()
    try:
        entry = ThreadEntry()
        entry.size = ctypes.sizeof(entry)
        api.require_api_success(kernel.Thread32First(snapshot, ctypes.byref(entry)))
        threads = []
        while True:
            if entry.owner_pid == compiler_pid:
                threads.append(entry.thread_id)
            if kernel.Thread32Next(snapshot, ctypes.byref(entry)) == 0:
                assert ctypes.get_last_error() == 18, ctypes.get_last_error()
                break
        assert threads, {'compiler_pid': compiler_pid, 'threads': threads}
        return threads
    finally:
        api.require_api_success(kernel.CloseHandle(snapshot))


def read_state(api, kernel, process, address):
    """Read published native state after its event signals completed writes."""
    buffer = ctypes.create_string_buffer(64)
    copied = ctypes.c_size_t()
    api.require_api_success(kernel.ReadProcessMemory(process, address, buffer, 64, ctypes.byref(copied)))
    assert copied.value == 64, copied.value
    values = struct.unpack('<8I2QI', buffer.raw[:52])
    names = ('thread_id', 'flags', 'attempts', 'first_error', 'final_error',
             'permission_wait', 'release_wait', 'signal_ok', 'requested_bytes', 'pointer', 'operation_id')
    return dict(zip(names, values))


def run_probe(compiler, expected_host, storage):
    """Compare ordinary metadata with a compilation containing a denied call."""
    import frida
    api = load_api()
    kernel = api.bind_kernel_api()
    bind_events(kernel)
    identity = subprocess.run([str(compiler), '-vV'], capture_output=True, text=True,
                              check=True, timeout=30).stdout
    assert f'host: {expected_host}\n' in identity, identity
    emit('compiler_identity', identity=identity, frida_version=frida.__version__)
    source = '\n'.join(f'pub fn function_{index}() -> u64 {{ {index} }}' for index in range(20000))
    emit('compiler_source', functions=20000, sha256=hashlib.sha256(source.encode()).hexdigest())
    with tempfile.TemporaryDirectory(prefix='compiler-heap-', dir=storage) as scratch:
        output = Path(scratch) / 'result.rmeta'
        command = [str(compiler), '-', '--crate-name', 'scheduler_probe', '--crate-type=lib',
                   '--emit=metadata', '-o', str(output)]
        reference = subprocess.run(command, input=source, cwd=scratch, capture_output=True,
                                   text=True, timeout=90)
        assert reference.returncode == 0, {'exit': reference.returncode, 'stderr': reference.stderr}
        assert (reference.stdout, reference.stderr) == ('', ''), reference.stderr
        expected = output.read_bytes()
        assert expected, 'Reference metadata is empty'
        output.unlink()
        with contextlib.ExitStack() as handles:
            job = api.require_api_success(kernel.CreateJobObjectW(None, None))
            handles.callback(kernel.CloseHandle, job)
            limits = api.ExtendedLimitInformation()
            limits.BasicLimitInformation.LimitFlags = 0x2000
            api.require_api_success(kernel.SetInformationJobObject(job, 9, ctypes.byref(limits), ctypes.sizeof(limits)))
            with concurrent.futures.ThreadPoolExecutor(max_workers=1) as calls:
                with subprocess.Popen(command, cwd=scratch, stdin=subprocess.PIPE,
                                      stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True) as child:
                    session = None
                    completion = None
                    try:
                        initial = find_compiler_threads(api, kernel, child.pid)
                        process = api.require_api_success(kernel.OpenProcess(0x100551, False, child.pid))
                        handles.callback(kernel.CloseHandle, process)
                        api.require_api_success(kernel.AssignProcessToJobObject(job, process))
                        controller_events = []
                        remote_events = []
                        for index in range(5):
                            event = api.require_api_success(kernel.CreateEventW(None, index != 4, index == 4, None))
                            handles.callback(kernel.CloseHandle, event)
                            duplicate = ctypes.c_void_p()
                            api.require_api_success(kernel.DuplicateHandle(kernel.GetCurrentProcess(), event, process,
                                ctypes.byref(duplicate), 0, False, 2))
                            controller_events.append(event)
                            remote_events.append(hex(duplicate.value))
                        messages = queue.Queue()
                        session = frida.attach(child.pid)
                        script = session.create_script(Path(__file__).with_name('windows-compiler-heap-retry.js').read_text())
                        script.on('message', lambda message, data: messages.put(message))
                        script.load()
                        ready = messages.get(timeout=30)
                        assert ready == {'type': 'send', 'payload': {'event': 'candidate_ready', 'pid': child.pid}}, ready
                        installed = calls.submit(script.exports_sync.install, initial, remote_events).result(timeout=30)
                        emit('compiler_hook_installed', compiler_pid=child.pid, initial_threads=initial, **installed)
                        address = int(installed['state_address'], 16)
                        api.require_api_success(kernel.QueryInformationJobObject(job, 9, ctypes.byref(limits), ctypes.sizeof(limits), None))
                        limits.ProcessMemoryLimit = limits.PeakProcessMemoryUsed + 4096
                        limits.BasicLimitInformation.LimitFlags = 0x2100
                        api.require_api_success(kernel.SetInformationJobObject(job, 9, ctypes.byref(limits), ctypes.sizeof(limits)))
                        emit('compiler_commit_limit_set', compiler_pid=child.pid, peak_bytes=limits.PeakProcessMemoryUsed,
                             limit_bytes=limits.ProcessMemoryLimit)
                        completion = calls.submit(child.communicate, source, timeout=90)
                        blocked = kernel.WaitForSingleObject(controller_events[0], 30000)
                        assert blocked == 0, {'blocked_wait': blocked, 'compiler_exit': child.poll()}
                        state = read_state(api, kernel, process, address)
                        assert state['attempts'] == 1, state
                        assert state['requested_bytes'] > 0, state
                        assert state['operation_id'] in (1, 2), state
                        assert state['thread_id'] not in installed['excluded_threads'], state
                        assert child.poll() is None
                        emit('compiler_allocation_blocked', compiler_pid=child.pid, state=state)
                        subprocess.run([str(compiler), '-V'], capture_output=True, check=True, timeout=30)
                        pending = read_state(api, kernel, process, address)
                        assert pending['attempts'] == 1, pending
                        assert child.poll() is None
                        emit('independent_peer_completed_while_blocked', compiler_pid=child.pid, state=pending)
                        limits.ProcessMemoryLimit += 256 * 1024 * 1024
                        api.require_api_success(kernel.SetInformationJobObject(job, 9, ctypes.byref(limits), ctypes.sizeof(limits)))
                        emit('compiler_commit_limit_raised', limit_bytes=limits.ProcessMemoryLimit)
                        api.require_api_success(kernel.SetEvent(controller_events[1]))
                        returned = kernel.WaitForSingleObject(controller_events[2], 30000)
                        assert returned == 0, {'returned_wait': returned, 'compiler_exit': child.poll()}
                        retried = read_state(api, kernel, process, address)
                        assert retried['attempts'] == 2, retried
                        assert retried['permission_wait'] == 0, retried
                        assert retried['pointer'] > 0, retried
                        emit('compiler_allocation_retried', compiler_pid=child.pid, state=retried)
                        api.require_api_success(kernel.SetEvent(controller_events[3]))
                        stdout, stderr = completion.result(timeout=90)
                        assert child.returncode == 0, {'exit': child.returncode, 'stdout': stdout, 'stderr': stderr}
                        assert (stdout, stderr) == ('', ''), {'stdout': stdout, 'stderr': stderr}
                        actual = output.read_bytes()
                        assert actual == expected, {'reference_sha256': hashlib.sha256(expected).hexdigest(),
                                                    'instrumented_sha256': hashlib.sha256(actual).hexdigest()}
                        emit('compiler_heap_retry_passed', compiler_pid=child.pid, exit=child.returncode,
                             metadata_bytes=len(actual), metadata_sha256=hashlib.sha256(actual).hexdigest())
                    finally:
                        if child.poll() is None:
                            child.kill()
                        if completion:
                            try:
                                terminal_stdout, terminal_stderr = completion.result(timeout=10)
                            except subprocess.TimeoutExpired as error:
                                emit('compiler_io_deadline', stdout=repr(error.stdout), stderr=repr(error.stderr))
                                child.communicate()
                            else:
                                if child.returncode != 0:
                                    emit('compiler_terminal_failure', exit=child.returncode,
                                         stdout=terminal_stdout, stderr=terminal_stderr)
                        else:
                            child.communicate()
                        if session:
                            try:
                                session.detach()
                            except frida.InvalidOperationError:
                                pass


def main():
    """Resolve the explicit native compiler identity and owned storage."""
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--compiler', type=Path, required=True)
    parser.add_argument('--expected-host', required=True)
    parser.add_argument('--storage', type=Path, required=True)
    arguments = parser.parse_args()
    assert arguments.compiler.is_absolute(), 'Compiler must be an absolute path'
    assert arguments.compiler.is_file(), 'Compiler must exist'
    assert arguments.storage.is_dir(), 'Owned storage must exist'
    run_probe(arguments.compiler, arguments.expected_host, arguments.storage)


if __name__ == '__main__':
    main()
