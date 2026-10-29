"""Observe allocations during a real stdin Rust compile and compare its output.

Requires pinned Frida, an absolute native rustc executable, its expected host
triple and an owned scratch directory. A 256-function library is compiled to
metadata after listeners are armed. Acceptance requires an observed allocator
call, ordinary compiler exit and byte-identical output from the same command,
source and working directory without instrumentation. Instrumented compiler I/O
has a 90-second timeout after attachment and setup; the reference compilation
has its own 90-second timeout. Only owned temporary files/processes are created or removed.
This check establishes observed export coverage, not allocation blocking.
"""

import argparse
import concurrent.futures
import hashlib
import json
from pathlib import Path
import queue
import subprocess
import tempfile


def emit(event, **details):
    """Flush a structured diagnostic event to the retained workflow log."""
    print(json.dumps({'event': event, **details}), flush=True)


def run_probe(compiler, expected_host, storage):
    """Observe the native compiler, then compare an uninstrumented compilation.

    The explicit compiler path must report the expected native host. Missing
    script events or RPC results fail after 30 seconds. A failed or unfinished
    owned compiler is killed and reaped before instrumentation is detached.
    """
    import frida

    identity = subprocess.run([str(compiler), '-vV'], capture_output=True, text=True,
                              check=True, timeout=30).stdout
    assert f'host: {expected_host}\n' in identity, {'compiler_identity': identity}
    emit('compiler_identity', identity=identity, frida_version=frida.__version__)
    source = '\n'.join(f'pub fn function_{index}() -> u64 {{ {index} }}' for index in range(256))
    emit('compiler_source', functions=256, sha256=hashlib.sha256(source.encode()).hexdigest())
    script_source = Path(__file__).with_name('compiler-allocation-observer.js').read_text()

    with tempfile.TemporaryDirectory(prefix='compiler-allocation-', dir=storage) as scratch:
        output = Path(scratch) / 'result.rmeta'
        command = [str(compiler), '-', '--crate-name', 'scheduler_probe', '--crate-type=lib',
                   '--emit=metadata', '-o', str(output)]
        with concurrent.futures.ThreadPoolExecutor(max_workers=1) as calls:
            with subprocess.Popen(command, cwd=scratch, stdin=subprocess.PIPE,
                                  stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True) as compiler_process:
                session = None
                messages = queue.Queue()
                try:
                    session = frida.attach(compiler_process.pid)
                    script = session.create_script(script_source)
                    script.on('message', lambda message, data: messages.put(message))
                    script.load()
                    ready = messages.get(timeout=30)
                    assert ready['type'] == 'send', ready
                    assert ready['payload'] == {'event': 'candidate_ready', 'pid': compiler_process.pid}, ready
                    coverage = calls.submit(script.exports_sync.arm).result(timeout=30)
                    assert coverage['installed'], coverage
                    emit('compiler_observers_armed', compiler_pid=compiler_process.pid, **coverage)
                    stdout, stderr = compiler_process.communicate(source, timeout=90)
                    assert compiler_process.returncode == 0, {'exit': compiler_process.returncode,
                                                             'stdout': stdout, 'stderr': stderr}
                    assert (stdout, stderr) == ('', ''), {'stdout': stdout, 'stderr': stderr}
                    observations = []
                    first_observation = messages.get(timeout=30)
                    pending_messages = [first_observation]
                    while messages.empty() is False:
                        pending_messages.append(messages.get_nowait())
                    for message in pending_messages:
                        assert message['type'] == 'send', message
                        payload = message['payload']
                        assert payload['event'] == 'compiler_allocation_observed', payload
                        assert payload['pid'] == compiler_process.pid, payload
                        observations.append(payload)
                        emit(**payload)
                    assert observations, 'No compiler allocator call was observed after source admission'
                    instrumented_output = output.read_bytes()
                    assert instrumented_output, 'The instrumented compiler produced empty metadata'
                finally:
                    if compiler_process.poll() is None:
                        compiler_process.kill()
                    compiler_process.communicate()
                    if session:
                        try:
                            session.detach()
                        except frida.InvalidOperationError:
                            pass

        output.unlink()
        reference = subprocess.run(command, input=source, cwd=scratch, capture_output=True,
                                   text=True, timeout=90)
        assert reference.returncode == 0, {'exit': reference.returncode, 'stderr': reference.stderr}
        assert (reference.stdout, reference.stderr) == ('', ''), {
            'stdout': reference.stdout, 'stderr': reference.stderr}
        reference_output = output.read_bytes()
        assert reference_output == instrumented_output, {
            'instrumented_sha256': hashlib.sha256(instrumented_output).hexdigest(),
            'reference_sha256': hashlib.sha256(reference_output).hexdigest()}
        emit('compiler_allocation_probe_passed', host=expected_host, observed_entries=len(observations),
             metadata_bytes=len(reference_output), metadata_sha256=hashlib.sha256(reference_output).hexdigest())


def main():
    """Validate --compiler and --storage, then run the expected-host allocation probe."""
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--compiler', type=Path, required=True)
    parser.add_argument('--expected-host', required=True)
    parser.add_argument('--storage', type=Path, required=True)
    arguments = parser.parse_args()
    assert arguments.compiler.is_absolute(), 'Compiler path must be absolute'
    assert arguments.compiler.is_file(), 'Compiler executable must exist'
    assert arguments.storage.is_dir(), 'Owned scratch storage must exist'
    run_probe(arguments.compiler, arguments.expected_host, arguments.storage)


if __name__ == '__main__':
    main()
