"""Probe macOS address-space-limited mmap failure and retry; not a scheduler.

Run without arguments on native macOS. Events are JSON on stdout; a rejected
limit, unexpected allocation result or missing worker event fails the probe.
Only an owned subprocess's soft RLIMIT_AS changes. The worker restores its
original limits and closes its mapping; the controller kills/reaps an unfinished
worker on failure, removing that process and its limits.
Waiting/retry belongs to this fixture, not an injected compiler allocator.
"""

import argparse
import concurrent.futures
import errno
import json
import mmap
import os
import platform
import resource
import subprocess
import sys


def emit(event, **details):
    """Flush a JSON event with this process's identity to the controller/log."""
    print(json.dumps({"event": event, "pid": os.getpid(), **details}), flush=True)


def run_worker():
    """Deny a 256 MiB mapping with RLIMIT_AS, await permission, restore and retry.

    The soft allowance is measured virtual size plus 32 MiB. Allocation failure
    must raise OSError with ENOMEM; another result fails the diagnostic.
    Both attempts use the same size, protection and anonymous/private flags.
    """
    requested_bytes = 256 * 1024 * 1024
    virtual_size_kib = int(subprocess.check_output(
        ["/bin/ps", "-o", "vsz=", "-p", str(os.getpid())], text=True
    ).strip())
    virtual_size_bytes = virtual_size_kib * 1024
    original_limits = resource.getrlimit(resource.RLIMIT_AS)
    limited_soft_bytes = virtual_size_bytes + (32 * 1024 * 1024)
    allocation_flags = mmap.MAP_PRIVATE | mmap.MAP_ANON
    protection = mmap.PROT_READ | mmap.PROT_WRITE
    resource.setrlimit(resource.RLIMIT_AS, (limited_soft_bytes, original_limits[1]))
    try:
        emit("worker_address_limit_set", virtual_size_bytes=virtual_size_bytes,
             original_soft_bytes=original_limits[0], original_hard_bytes=original_limits[1],
             limit_bytes=limited_soft_bytes, requested_bytes=requested_bytes)
        try:
            mapping = mmap.mmap(-1, requested_bytes, flags=allocation_flags, prot=protection)
        except OSError as error:
            if error.errno != errno.ENOMEM:
                raise
            emit("allocation_denied", bytes=requested_bytes, errno=error.errno)
        else:
            mapping.close()
            raise AssertionError("Mapping succeeded while RLIMIT_AS should deny it")

        assert sys.stdin.readline().strip() == "retry", "Missing allocation retry permission"
        resource.setrlimit(resource.RLIMIT_AS, original_limits)
        emit("worker_address_limit_restored", soft_bytes=resource.getrlimit(resource.RLIMIT_AS)[0])
        with mmap.mmap(-1, requested_bytes, flags=allocation_flags, prot=protection) as mapping:
            mapping[0] = 42
            mapping[-1] = 43
            assert (mapping[0], mapping[-1]) == (42, 43)
            emit("allocation_resumed", bytes=requested_bytes, first_byte=mapping[0], last_byte=mapping[-1])
        emit("allocation_freed")
    finally:
        resource.setrlimit(resource.RLIMIT_AS, original_limits)


def run_probe():
    """Require denial, an independent peer's completion and the same worker's retry.

    Missing events fail after 30 seconds. An unfinished worker is killed and
    reaped before the output reader is closed, including after an event timeout.
    """
    with concurrent.futures.ThreadPoolExecutor(max_workers=1) as reader:
        with subprocess.Popen([sys.executable, __file__, "--worker"], stdin=subprocess.PIPE,
                              stdout=subprocess.PIPE, text=True) as worker:
            def expect_event(expected):
                """Read/print the expected event from the owned worker within 30 seconds."""
                line = reader.submit(worker.stdout.readline).result(timeout=30)
                if line == "":
                    raise AssertionError(f"Worker output ended before {expected}; exit={worker.poll()}")
                event = json.loads(line)
                print(json.dumps(event), flush=True)
                assert event["event"] == expected, event
                assert event["pid"] == worker.pid, event
                return event

            try:
                limits = expect_event("worker_address_limit_set")
                denied = expect_event("allocation_denied")
                assert denied["bytes"] == limits["requested_bytes"]
                assert worker.poll() is None, "Worker exited instead of waiting for retry permission"
                subprocess.run(["/usr/bin/true"], check=True, timeout=30)
                emit("independent_peer_completed", worker_pid=worker.pid)
                worker.stdin.write("retry\n")
                worker.stdin.flush()
                restored = expect_event("worker_address_limit_restored")
                assert restored["soft_bytes"] == limits["original_soft_bytes"]
                resumed = expect_event("allocation_resumed")
                assert resumed["bytes"] == denied["bytes"]
                expect_event("allocation_freed")
                assert worker.wait(timeout=30) == 0
                emit("probe_passed", worker_pid=worker.pid)
            finally:
                if worker.poll() is None:
                    worker.kill()
                worker.wait()


def main():
    """Run the native controller or its internal worker; reject other platforms."""
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--worker", action="store_true", help=argparse.SUPPRESS)
    arguments = parser.parse_args()
    assert sys.platform == "darwin", "This probe requires native macOS"
    expected_machine = {"X64": "x86_64", "ARM64": "arm64"}.get(os.environ.get("RUNNER_ARCH"))
    if expected_machine:
        assert platform.machine() == expected_machine, "Python architecture differs from the native runner"
    if arguments.worker:
        run_worker()
    else:
        emit("native_identity", machine=platform.machine(), python=sys.version,
             runner_arch=os.environ.get("RUNNER_ARCH"), kernel=platform.release())
        run_probe()


if __name__ == "__main__":
    main()
