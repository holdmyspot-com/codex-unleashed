"""Exercise native action launch, whole-tree completion and borrowed output.

The descendant fixture writes readiness then sleeps; only ProcessTree owns its
termination. --root-only substitutes a deliberately incomplete abort operation
for the same tree assertion, and always reaps the job through the real cleanup.
"""
import ctypes
from ctypes import wintypes
import json
import os
from pathlib import Path
import sys
import subprocess
import tempfile
import time

sys.path.insert(0, sys.argv[1])
from build_scheduler.windows_process import ProcessTree


def wait_for_file(path):
    """Wait up to ten seconds for a fixture child's readiness marker."""
    deadline = time.monotonic_ns() + 10_000_000_000
    while path.exists() is False:
        if time.monotonic_ns() >= deadline:
            raise TimeoutError("Fixture descendant did not publish readiness")
        time.sleep(.01)


def terminate_root_only(tree):
    """Mutation: stop the root process while allowing its descendant to survive."""
    operation = tree._kernel.TerminateProcess
    operation.argtypes = [wintypes.HANDLE, wintypes.UINT]
    operation.restype = wintypes.BOOL
    if operation(tree._process, 125) == 0:
        raise ctypes.WinError(ctypes.get_last_error())


def check_tree(directory, root_only):
    """A requested abort must finish only after the grandchild is also stopped."""
    marker = directory / "grandchild-ready"
    child = "import pathlib,sys,time; pathlib.Path(sys.argv[1]).write_text('ready'); time.sleep(60)"
    root = "import subprocess,sys,time; subprocess.Popen([sys.executable,'-c',sys.argv[1],sys.argv[2]]); time.sleep(60)"
    with (directory / "tree.log").open("wb") as output:
        tree = ProcessTree([sys.executable, "-c", root, child, str(marker)], directory, os.environ, output)
        try:
            wait_for_file(marker)
            assert tree.poll() is None, "live tree reported completion"
            try:
                tree.wait(0)
                raise AssertionError("live tree did not report timeout")
            except TimeoutError:
                pass
            if root_only:
                terminate_root_only(tree)
            else:
                tree.terminate()
            assert tree.wait(1500) == 125, "abort lost its root exit status"
        finally:
            ProcessTree.terminate(tree)
            tree.close()
        assert output.closed is False, "tree closed its borrowed output"
        tree.close()
    print(json.dumps({"case": "whole_tree_abort", "root_only": root_only, "result": "passed"}), flush=True)


def check_launch(directory):
    """Launch preserves argv, explicit environment, cwd and both output streams."""
    arguments = ["", "two words", 'a"b', "trailing slash\\", "snowman \u2603"]
    code = "import json,os,sys; print(json.dumps([sys.argv[1:],os.getcwd(),os.environ['TREE_TEST']])); print('stderr-marker',file=sys.stderr); sys.exit(23)"
    environment = dict(os.environ, TREE_TEST="explicit value \u2603")
    path = directory / "launch.log"
    with path.open("wb") as output:
        with ProcessTree([sys.executable, "-c", code, *arguments], directory, environment, output) as tree:
            assert tree.wait(10_000) == 23, "root failure status was lost"
            assert tree.poll() == 23, "terminal result changed"
        assert output.closed is False, "tree closed borrowed output"
    lines = path.read_text().splitlines()
    received = json.loads(next(line for line in lines if line.startswith("[[")))
    assert received == [arguments, str(directory), environment["TREE_TEST"]], received
    assert "stderr-marker" in lines, lines
    try:
        tree.poll()
        raise AssertionError("closed tree permitted polling")
    except ValueError:
        pass
    print(json.dumps({"case": "argv_environment_directory_output_failure", "result": "passed"}), flush=True)


def check_descendant_completion(directory):
    """Root success cannot report completion while its descendant remains alive."""
    marker = directory / "orphan-ready"
    child = "import pathlib,sys,time; pathlib.Path(sys.argv[1]).write_text('ready'); time.sleep(60)"
    root = "import subprocess,sys; subprocess.Popen([sys.executable,'-c',sys.argv[1],sys.argv[2]])"
    with (directory / "orphan.log").open("wb") as output:
        with ProcessTree([sys.executable, "-c", root, child, str(marker)], directory, os.environ, output) as tree:
            wait_for_file(marker)
            try:
                tree.wait(200)
                raise AssertionError("root exit hid a live descendant")
            except TimeoutError:
                pass
            tree.terminate()
            assert tree.wait(10_000) == 0, "exited root status changed"
    print(json.dumps({"case": "root_exit_waits_for_descendant", "result": "passed"}), flush=True)


def check_creation_failure(directory):
    """A native creation failure retains its error and leaves output borrowed."""
    executable = directory / "invalid.exe"
    executable.write_bytes(b"not a Windows executable")
    try:
        subprocess.run([str(executable)], check=True)
        raise AssertionError("invalid control executable launched")
    except OSError as error:
        expected_error = error.winerror
    with (directory / "invalid.log").open("wb") as output:
        try:
            ProcessTree([str(executable)], directory, os.environ, output)
            raise AssertionError("invalid executable launched")
        except OSError as error:
            assert error.winerror == expected_error, error
        assert output.closed is False, "failed construction closed borrowed output"
    print(json.dumps({"case": "native_creation_failure", "result": "passed"}), flush=True)


with tempfile.TemporaryDirectory(prefix="public-tree-", dir=sys.argv[2]) as folder:
    directory = Path(folder).resolve()
    root_only = "--root-only" in sys.argv[3:]
    check_tree(directory, root_only)
    if root_only is False:
        check_launch(directory)
        check_descendant_completion(directory)
        check_creation_failure(directory)
print(json.dumps({"event": "public_process_tree_passed", "machine": os.environ.get("PROCESSOR_ARCHITECTURE")}), flush=True)
