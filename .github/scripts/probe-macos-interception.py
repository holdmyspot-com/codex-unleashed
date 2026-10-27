"""Verify native macOS mmap forwarding and ordinary owned-worker shutdown.

Run with pinned Frida on native macOS. Each worker makes one 256 MiB native
mapping request, accesses both endpoints, unmaps it and exits normally. Controls
attach without a hook; native-fast adds a direct CModule replacement. Missing
events and process exit each have a 30-second deadline. Failure cleanup kills
and reaps only the owned worker before detaching its instrumentation.
native-retry/native-timeout lower only the owned worker's soft address limit
after hook installation. They require native waiting after ENOMEM, followed
by retry permission or a one-second timeout with the original errno preserved.
These controlled probes do not accept physical-pressure recovery or scheduling.
"""

import argparse
import concurrent.futures
import contextlib
import ctypes
import errno
import json
import os
from pathlib import Path
import platform
import queue
import resource
import subprocess
import sys


def emit(event, **details):
    """Flush a structured event carrying this process's identity."""
    print(json.dumps({"event": event, "pid": os.getpid(), **details}), flush=True)


def run_worker(mode):
    """Make one caller-side mapping request; denial modes constrain address space.

    The controller installs instrumentation before permission to allocate.
    Denial modes then set soft RLIMIT_AS to current virtual size plus 32 MiB;
    the original limits are restored on every worker exit path. The timeout
    case requires MAP_FAILED/ENOMEM; other modes verify and unmap the result.
    """
    native = ctypes.CDLL('/usr/lib/libSystem.B.dylib', use_errno=True)
    native.mmap.argtypes = [ctypes.c_void_p, ctypes.c_size_t, ctypes.c_int,
                            ctypes.c_int, ctypes.c_int, ctypes.c_int64]
    native.mmap.restype = ctypes.c_void_p
    native.munmap.argtypes = [ctypes.c_void_p, ctypes.c_size_t]
    native.munmap.restype = ctypes.c_int
    native.pthread_threadid_np.argtypes = [ctypes.c_void_p, ctypes.POINTER(ctypes.c_uint64)]
    native.pthread_threadid_np.restype = ctypes.c_int
    thread_id = ctypes.c_uint64()
    assert native.pthread_threadid_np(None, ctypes.byref(thread_id)) == 0
    requested_bytes = 256 * 1024 * 1024
    original_limits = resource.getrlimit(resource.RLIMIT_AS)
    emit("worker_ready", mapping_address=hex(ctypes.cast(native.mmap, ctypes.c_void_p).value),
         requested_bytes=requested_bytes, thread_id=str(thread_id.value),
         address_resource=resource.RLIMIT_AS)
    assert sys.stdin.readline().strip() == 'allocate', 'Missing allocation permission'
    if mode in ('native-retry', 'native-timeout'):
        virtual_size_kib = int(subprocess.check_output(
            ['/bin/ps', '-o', 'vsz=', '-p', str(os.getpid())], text=True).strip())
        limited_soft_bytes = (virtual_size_kib * 1024) + (32 * 1024 * 1024)
        resource.setrlimit(resource.RLIMIT_AS, (limited_soft_bytes, original_limits[1]))
        emit('worker_address_limit_set', limit_bytes=limited_soft_bytes,
             original_soft_bytes=original_limits[0], original_hard_bytes=original_limits[1])
    try:
        run_mapping_request(native, requested_bytes, mode)
        if mode in ('native-retry', 'native-timeout'):
            restored_limits = resource.getrlimit(resource.RLIMIT_AS)
            assert restored_limits == original_limits, {'restored_limits': restored_limits}
            emit('worker_address_limit_restored', soft_bytes=restored_limits[0], hard_bytes=restored_limits[1])
    finally:
        resource.setrlimit(resource.RLIMIT_AS, original_limits)


def run_mapping_request(native, requested_bytes, mode):
    """Execute the sole caller-side mmap and verify success or bounded denial."""
    pointer = native.mmap(None, requested_bytes, 3, 0x1002, -1, 0)
    saved_errno = ctypes.get_errno()
    if mode == 'native-timeout':
        assert pointer == ctypes.c_void_p(-1).value, {'unexpected_pointer': pointer}
        assert saved_errno == errno.ENOMEM, {'mmap_errno': saved_errno}
        emit('native_call_denied', errno=saved_errno)
        assert sys.stdin.readline().strip() == 'finish', 'Missing cleanup permission'
        return
    assert pointer != ctypes.c_void_p(-1).value, {"mmap_errno": saved_errno}
    assert pointer is not None, 'Unexpected null mapping'
    try:
        ctypes.c_ubyte.from_address(pointer).value = 42
        ctypes.c_ubyte.from_address(pointer + requested_bytes - 1).value = 43
        first_byte = ctypes.c_ubyte.from_address(pointer).value
        last_byte = ctypes.c_ubyte.from_address(pointer + requested_bytes - 1).value
        assert (first_byte, last_byte) == (42, 43)
        emit("native_call_returned", pointer=hex(pointer), first_byte=first_byte, last_byte=last_byte)
        assert sys.stdin.readline().strip() == 'finish', 'Missing cleanup permission'
    finally:
        assert native.munmap(pointer, requested_bytes) == 0, {"munmap_errno": ctypes.get_errno()}
    emit("allocation_freed")


def run_probe(mode):
    """Require selected native behavior, successful cleanup and ordinary exit.

    Timeout requires bounded denial; retry requires independent progress while
    the call waits, then a second internal allocation attempt. Controls require
    their original mapping behavior. Any missing event fails after 30 seconds.
    """
    import frida

    with contextlib.ExitStack() as descriptors, concurrent.futures.ThreadPoolExecutor(max_workers=1) as reader:
        blocked_read, blocked_write = os.pipe()
        descriptors.enter_context(os.fdopen(blocked_read, 'rb', buffering=0))
        blocked_writer = descriptors.enter_context(os.fdopen(blocked_write, 'wb', buffering=0))
        retry_read, retry_write = os.pipe()
        retry_reader = descriptors.enter_context(os.fdopen(retry_read, 'rb', buffering=0))
        descriptors.enter_context(os.fdopen(retry_write, 'wb', buffering=0))
        with subprocess.Popen([sys.executable, __file__, '--worker', '--mode', mode], stdin=subprocess.PIPE,
                              stdout=subprocess.PIPE, text=True,
                              pass_fds=(blocked_write, retry_read)) as worker:
            blocked_writer.close()
            retry_reader.close()
            session = None

            def expect_event(expected):
                """Read and validate the owned worker's next event within 30 seconds."""
                line = reader.submit(worker.stdout.readline).result(timeout=30)
                assert line != '', {"expected": expected, "worker_exit": worker.poll()}
                event = json.loads(line)
                print(json.dumps(event), flush=True)
                assert event['pid'] == worker.pid, event
                assert event['event'] == expected, event
                return event

            try:
                ready = expect_event('worker_ready')
                messages = queue.Queue()
                if mode == 'task-port':
                    observe_task_port(worker.pid)
                else:
                    session = frida.attach(worker.pid)
                    if mode == 'attach':
                        source = "send({event: 'candidate_ready', pid: Process.id});"
                    elif mode == 'native-fast':
                        source = Path(__file__).with_name('macos-native-allocation-forwarder.js').read_text()
                    else:
                        source = Path(__file__).with_name('macos-native-allocation-retry.js').read_text()
                    script = session.create_script(source)
                    script.on('message', lambda message, data: messages.put(message))
                    script.load()
                    message = messages.get(timeout=30)
                    assert message['type'] == 'send', message
                    assert message['payload'] == {'event': 'candidate_ready', 'pid': worker.pid}, message
                if mode in ('native-fast', 'native-retry', 'native-timeout'):
                    install_arguments = [ready['mapping_address'], ready['thread_id']]
                    if mode in ('native-retry', 'native-timeout'):
                        wait_milliseconds = 1000 if mode == 'native-timeout' else 30000
                        install_arguments.extend([ready['address_resource'], blocked_write,
                                                  retry_read, wait_milliseconds])
                    installed = reader.submit(script.exports_sync.install, *install_arguments).result(timeout=30)
                    assert int(installed['address'], 16) == int(ready['mapping_address'], 16), installed
                    expected_architecture = {'arm64': 'arm64', 'x86_64': 'x64'}[platform.machine()]
                    assert installed['architecture'] == expected_architecture, installed
                    emit('hook_installed', worker_pid=worker.pid, **installed)
                worker.stdin.write('allocate\n')
                worker.stdin.flush()
                if mode in ('native-retry', 'native-timeout'):
                    expect_event('worker_address_limit_set')
                    marker = reader.submit(os.read, blocked_read, 1).result(timeout=30)
                    assert marker == b'B', {'blocked_notification': repr(marker)}
                    emit('native_denial_notified', worker_pid=worker.pid)
                    if mode == 'native-retry':
                        pending = reader.submit(script.exports_sync.state).result(timeout=30)
                        assert pending['matching_calls'] == 1, pending
                        assert pending['attempts'] == 1, pending
                        assert pending['first_errno'] == errno.ENOMEM, pending
                        assert pending['poll_result'] == -2, pending
                        subprocess.run(['/usr/bin/true'], check=True, timeout=30)
                        pending = reader.submit(script.exports_sync.state).result(timeout=30)
                        assert pending['attempts'] == 1, pending
                        assert pending['poll_result'] == -2, pending
                        assert worker.poll() is None, 'Worker exited while its allocation should wait'
                        emit('independent_peer_completed', worker_pid=worker.pid, native_state=pending)
                        assert os.write(retry_write, b'R') == 1
                if mode == 'native-timeout':
                    expect_event('native_call_denied')
                else:
                    returned = expect_event('native_call_returned')
                if mode == 'native-fast':
                    count = reader.submit(script.exports_sync.matching_calls).result(timeout=30)
                    assert count == 1, {"matching_calls": count}
                    emit('native_forwarding_observed', worker_pid=worker.pid, matching_calls=count)
                if mode in ('native-retry', 'native-timeout'):
                    state = reader.submit(script.exports_sync.state).result(timeout=30)
                    assert state['matching_calls'] == 1, state
                    assert state['first_errno'] == errno.ENOMEM, state
                    assert state['notification_bytes'] == 1, state
                    assert state['restore_result'] == 0, state
                    if mode == 'native-retry':
                        assert state['attempts'] == 2, state
                        assert state['poll_result'] == 1, state
                        assert state['permission_bytes'] == 1, state
                        assert int(state['pointer'], 16) == int(returned['pointer'], 16), state
                    else:
                        assert state['attempts'] == 1, state
                        assert state['poll_result'] == 0, state
                        assert state['permission_bytes'] == 0, state
                        assert state['clobbered_errno'] == 4660, state
                        assert int(state['pointer'], 16) == ctypes.c_void_p(-1).value, state
                    emit('native_retry_state_observed', worker_pid=worker.pid, mode=mode, native_state=state)
                worker.stdin.write('finish\n')
                worker.stdin.flush()
                if mode != 'native-timeout':
                    expect_event('allocation_freed')
                if mode in ('native-retry', 'native-timeout'):
                    expect_event('worker_address_limit_restored')
                assert worker.wait(timeout=30) == 0
                completion = 'task_port_observation_completed' if mode == 'task-port' else 'interception_probe_passed'
                emit(completion, mode=mode, worker_pid=worker.pid,
                     frida_version=frida.__version__)
            finally:
                if worker.poll() is None:
                    worker.kill()
                worker.wait()
                if session:
                    try:
                        session.detach()
                    except frida.InvalidOperationError:
                        # A terminal worker may have detached its session already.
                        pass


def observe_task_port(worker_pid):
    """Record native task-port access; denial is an observation, not acceptance.

    The acquired send right is deallocated on success. The borrowed current-task
    port belongs to libSystem and is never deallocated by this diagnostic.
    """
    native = ctypes.CDLL('/usr/lib/libSystem.B.dylib')
    current_task = ctypes.c_uint32.in_dll(native, 'mach_task_self_').value
    native.task_for_pid.argtypes = [ctypes.c_uint32, ctypes.c_int, ctypes.POINTER(ctypes.c_uint32)]
    native.task_for_pid.restype = ctypes.c_int
    native.mach_port_deallocate.argtypes = [ctypes.c_uint32, ctypes.c_uint32]
    native.mach_port_deallocate.restype = ctypes.c_int
    native.mach_error_string.argtypes = [ctypes.c_int]
    native.mach_error_string.restype = ctypes.c_char_p
    port = ctypes.c_uint32()
    emit('task_port_request_started', worker_pid=worker_pid, uid=os.geteuid(), executable=sys.executable)
    result = native.task_for_pid(current_task, worker_pid, ctypes.byref(port))
    try:
        emit('task_port_observed', worker_pid=worker_pid, uid=os.geteuid(),
             kernel_result=result, description=native.mach_error_string(result).decode(),
             received_port=port.value)
    finally:
        if result == 0:
            assert port.value != 0, 'Successful task-port acquisition returned a null port'
            assert native.mach_port_deallocate(current_task, port.value) == 0


def main():
    """Select the controller or its internal worker and verify native identity."""
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--worker', action='store_true', help=argparse.SUPPRESS)
    parser.add_argument('--mode', choices=('attach', 'native-fast', 'task-port', 'native-retry', 'native-timeout'),
                        default='native-fast')
    arguments = parser.parse_args()
    assert sys.platform == 'darwin', 'Native macOS is required'
    assert ctypes.sizeof(ctypes.c_void_p) == 8, 'A 64-bit process is required'
    expected_machine = {'X64': 'x86_64', 'ARM64': 'arm64'}.get(os.environ.get('RUNNER_ARCH'))
    if expected_machine:
        assert platform.machine() == expected_machine, 'Python architecture differs from the runner'
    if arguments.worker:
        run_worker(arguments.mode)
    else:
        emit('native_identity', machine=platform.machine(), python=sys.version,
             kernel=platform.release(), runner_arch=os.environ.get('RUNNER_ARCH'), mode=arguments.mode)
        run_probe(arguments.mode)


if __name__ == '__main__':
    main()
