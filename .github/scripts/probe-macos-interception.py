"""Verify native macOS mmap forwarding and ordinary owned-worker shutdown.

Run with pinned Frida on native macOS. Each worker makes one 256 MiB native
mapping request, accesses both endpoints, unmaps it and exits normally. Controls
attach without a hook; native-fast adds a direct CModule replacement. Missing
events and process exit each have a 30-second deadline. Failure cleanup kills
and reaps only the owned worker before detaching its instrumentation. This
probe changes no resource limits and does not accept allocation blocking.
"""

import argparse
import concurrent.futures
import ctypes
import json
import os
from pathlib import Path
import platform
import queue
import subprocess
import sys


def emit(event, **details):
    """Flush a structured event carrying this process's identity."""
    print(json.dumps({"event": event, "pid": os.getpid(), **details}), flush=True)


def run_worker():
    """Make a single native mmap call, verify it, unmap it and await normal exit."""
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
    emit("worker_ready", mapping_address=hex(ctypes.cast(native.mmap, ctypes.c_void_p).value),
         requested_bytes=requested_bytes, thread_id=str(thread_id.value))
    assert sys.stdin.readline().strip() == 'allocate', 'Missing allocation permission'
    pointer = native.mmap(None, requested_bytes, 3, 0x1002, -1, 0)
    saved_errno = ctypes.get_errno()
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
    """Require the selected instrumentation, successful mapping and normal exit."""
    import frida

    with concurrent.futures.ThreadPoolExecutor(max_workers=1) as reader:
        with subprocess.Popen([sys.executable, __file__, '--worker'], stdin=subprocess.PIPE,
                              stdout=subprocess.PIPE, text=True) as worker:
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
                session = frida.attach(worker.pid)
                if mode == 'attach':
                    source = "send({event: 'candidate_ready', pid: Process.id});"
                else:
                    source = Path(__file__).with_name('macos-native-allocation-forwarder.js').read_text()
                script = session.create_script(source)
                script.on('message', lambda message, data: messages.put(message))
                script.load()
                message = messages.get(timeout=30)
                assert message['type'] == 'send', message
                assert message['payload'] == {'event': 'candidate_ready', 'pid': worker.pid}, message
                if mode == 'native-fast':
                    installed = reader.submit(script.exports_sync.install, ready['mapping_address'],
                                              ready['thread_id']).result(timeout=30)
                    assert int(installed['address'], 16) == int(ready['mapping_address'], 16), installed
                    expected_architecture = {'arm64': 'arm64', 'x86_64': 'x64'}[platform.machine()]
                    assert installed['architecture'] == expected_architecture, installed
                    emit('hook_installed', worker_pid=worker.pid, **installed)
                worker.stdin.write('allocate\n')
                worker.stdin.flush()
                expect_event('native_call_returned')
                if mode == 'native-fast':
                    count = reader.submit(script.exports_sync.matching_calls).result(timeout=30)
                    assert count == 1, {"matching_calls": count}
                    emit('native_forwarding_observed', worker_pid=worker.pid, matching_calls=count)
                worker.stdin.write('finish\n')
                worker.stdin.flush()
                expect_event('allocation_freed')
                assert worker.wait(timeout=30) == 0
                emit('interception_probe_passed', mode=mode, worker_pid=worker.pid,
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


def main():
    """Select the controller or its internal worker and verify native identity."""
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--worker', action='store_true', help=argparse.SUPPRESS)
    parser.add_argument('--mode', choices=('attach', 'native-fast'), default='native-fast')
    arguments = parser.parse_args()
    assert sys.platform == 'darwin', 'Native macOS is required'
    assert ctypes.sizeof(ctypes.c_void_p) == 8, 'A 64-bit process is required'
    expected_machine = {'X64': 'x86_64', 'ARM64': 'arm64'}.get(os.environ.get('RUNNER_ARCH'))
    if expected_machine:
        assert platform.machine() == expected_machine, 'Python architecture differs from the runner'
    if arguments.worker:
        run_worker()
    else:
        emit('native_identity', machine=platform.machine(), python=sys.version,
             kernel=platform.release(), runner_arch=os.environ.get('RUNNER_ARCH'), mode=arguments.mode)
        run_probe(arguments.mode)


if __name__ == '__main__':
    main()
