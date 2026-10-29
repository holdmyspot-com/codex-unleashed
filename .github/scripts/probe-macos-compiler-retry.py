"""Require a real native macOS compiler mmap denial, internal retry and equal metadata.

Supply an absolute rustc path, its expected host triple and an existing owned
storage directory. Requires pinned Frida and the adjacent mapping hook. The
compiler alone receives a temporary address-space limit. Missing pipe events
fail after 30 seconds; the reference compilation and instrumented compiler I/O
each have a 90-second timeout. Cleanup reaps the
owned compiler before detaching. This diagnostic does not prove physical
pressure recovery, general thread classification or scheduler integration.
"""
import argparse
import concurrent.futures
import contextlib
import hashlib
import json
import os
from pathlib import Path
import queue
import resource
import select
import struct
import subprocess
import tempfile


def emit(event, **details):
    """Flush one named diagnostic event and its observed values as JSON."""
    print(json.dumps({'event': event, **details}), flush=True)


def read_record(descriptor):
    """Read one native 80-byte pipe event within 30 seconds or fail the probe."""
    readable, _, _ = select.select([descriptor], [], [], 30)
    assert readable == [descriptor], 'Compiler hook did not publish a pipe event within 30 seconds'
    data = os.read(descriptor, 80)
    assert len(data) == 80, {'pipe_record_bytes': len(data), 'data_hex': data.hex()}
    names = ('phase', 'thread_id', 'requested_bytes', 'attempts', 'errno', 'pointer',
             'limited_soft', 'original_soft', 'restored', 'permission')
    return dict(zip(names, struct.unpack('<10q', data)))


def run_probe(compiler, expected_host, storage):
    """Compile identical stdin source normally and through a real denied mapping.

    Requires the native compiler identity to match expected_host and storage to
    exist. Emits identity, actual denial, peer progress, retry and metadata hash;
    assertions retain the failed values. Only owned scratch output is removed.
    """
    import frida
    identity = subprocess.run([str(compiler), '-vV'], capture_output=True, text=True,
                              check=True, timeout=30).stdout
    assert f'host: {expected_host}\n' in identity, identity
    emit('compiler_identity', identity=identity, frida_version=frida.__version__)
    source = '\n'.join(f'pub fn function_{index}() -> u64 {{ {index} }}' for index in range(20000))
    emit('compiler_source', functions=20000, sha256=hashlib.sha256(source.encode()).hexdigest())
    with tempfile.TemporaryDirectory(prefix='compiler-mapping-', dir=storage) as scratch:
        output = Path(scratch) / 'result.rmeta'
        command = [str(compiler), '-', '--crate-name', 'scheduler_probe', '--crate-type=lib',
                   '--emit=metadata', '-o', str(output)]
        reference = subprocess.run(command, input=source, cwd=scratch, capture_output=True,
                                   text=True, timeout=90)
        assert reference.returncode == 0, {'exit': reference.returncode, 'stderr': reference.stderr}
        assert (reference.stdout, reference.stderr) == ('', ''), reference.stderr
        expected = output.read_bytes()
        assert len(expected) > 0, 'Reference metadata is empty'
        output.unlink()

        with contextlib.ExitStack() as descriptors:
            blocked_read, blocked_write = os.pipe()
            descriptors.enter_context(os.fdopen(blocked_read, 'rb', buffering=0))
            blocked_writer = descriptors.enter_context(os.fdopen(blocked_write, 'wb', buffering=0))
            retry_read, retry_write = os.pipe()
            retry_reader = descriptors.enter_context(os.fdopen(retry_read, 'rb', buffering=0))
            descriptors.enter_context(os.fdopen(retry_write, 'wb', buffering=0))
            with concurrent.futures.ThreadPoolExecutor(max_workers=1) as calls:
                with subprocess.Popen(command, cwd=scratch, stdin=subprocess.PIPE,
                                      stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True,
                                      pass_fds=(blocked_write, retry_read)) as child:
                    blocked_writer.close()
                    retry_reader.close()
                    session = None
                    completion = None
                    try:
                        messages = queue.Queue()
                        session = frida.attach(child.pid)
                        script = session.create_script(Path(__file__).with_name('macos-compiler-mapping-retry.js').read_text())
                        script.on('message', lambda message, data: messages.put(message))
                        script.load()
                        ready = messages.get(timeout=30)
                        assert ready == {'type': 'send', 'payload': {'event': 'candidate_ready', 'pid': child.pid}}, ready
                        installed = calls.submit(script.exports_sync.install, resource.RLIMIT_AS,
                            blocked_write, retry_read).result(timeout=30)
                        emit('compiler_hook_installed', compiler_pid=child.pid, **installed)
                        completion = calls.submit(child.communicate, source, timeout=90)
                        sample = read_record(blocked_read)
                        assert sample['phase'] == 3, sample
                        assert sample['errno'] == 0, sample
                        emit('compiler_address_size_at_limit_application', state=sample)
                        denied = read_record(blocked_read)
                        assert denied['phase'] == 1, denied
                        assert denied['attempts'] == 1, denied
                        assert denied['errno'] == 12, denied
                        assert denied['pointer'] == -1, denied
                        assert denied['requested_bytes'] > 0, denied
                        assert child.poll() is None, 'Compiler exited before retry permission'
                        emit('compiler_mapping_blocked', compiler_pid=child.pid, state=denied)

                        subprocess.run([str(compiler), '-V'], capture_output=True, check=True, timeout=30)
                        pending, _, _ = select.select([blocked_read], [], [], 0)
                        assert pending == [], 'Hook returned before retry permission'
                        assert child.poll() is None, 'Compiler exited while its mapping should wait'
                        emit('independent_peer_completed_while_blocked', compiler_pid=child.pid)
                        assert os.write(retry_write, b'R') == 1
                        retried = read_record(blocked_read)
                        assert retried['phase'] == 2, retried
                        assert retried['attempts'] == 2, retried
                        assert retried['pointer'] > 0, retried
                        assert retried['restored'] == 0, retried
                        assert retried['permission'] == 1, retried
                        assert retried['thread_id'] == denied['thread_id'], retried
                        assert retried['requested_bytes'] == denied['requested_bytes'], retried
                        emit('compiler_mapping_retried', compiler_pid=child.pid, state=retried)
                        assert os.write(retry_write, b'F') == 1

                        stdout, stderr = completion.result(timeout=90)
                        assert child.returncode == 0, {'exit': child.returncode, 'stdout': stdout, 'stderr': stderr}
                        assert (stdout, stderr) == ('', ''), {'stdout': stdout, 'stderr': stderr}
                        actual = output.read_bytes()
                        assert actual == expected, {'reference_sha256': hashlib.sha256(expected).hexdigest(),
                                                    'instrumented_sha256': hashlib.sha256(actual).hexdigest()}
                        emit('compiler_mapping_retry_passed', compiler_pid=child.pid, exit=child.returncode,
                             metadata_bytes=len(actual), metadata_sha256=hashlib.sha256(actual).hexdigest())
                    finally:
                        if child.poll() is None:
                            child.kill()
                        if completion is None:
                            child.communicate()
                        else:
                            try:
                                terminal_stdout, terminal_stderr = completion.result(timeout=10)
                            except subprocess.TimeoutExpired as error:
                                emit('compiler_io_deadline', stdout=repr(error.stdout), stderr=repr(error.stderr))
                                child.communicate()
                            else:
                                if child.returncode != 0:
                                    emit('compiler_terminal_failure', exit=child.returncode,
                                         stdout=terminal_stdout, stderr=terminal_stderr)
                        if session is not None:
                            try:
                                session.detach()
                            except frida.InvalidOperationError:
                                pass


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--compiler', type=Path, required=True)
    parser.add_argument('--expected-host', required=True)
    parser.add_argument('--storage', type=Path, required=True)
    arguments = parser.parse_args()
    assert arguments.compiler.is_absolute(), 'Compiler must be an absolute path'
    assert arguments.compiler.is_file(), 'Compiler must exist'
    assert arguments.storage.is_dir(), 'Owned storage must exist'
    run_probe(arguments.compiler, arguments.expected_host, arguments.storage)
