"""Check that the quiet-build watchdog reports and stops a silent command but leaves an active one alone."""
from pathlib import Path
import importlib.util
import io
import os
import re
import signal
import subprocess
import sys
import tempfile
import unittest

WATCHER = Path(__file__).with_name("watch-quiet-build.py").resolve()


def run_watcher(directory, command, *limits):
    """Runs the watcher over one test-owned directory with the given timing limits in seconds.

    The report lives inside the watched directory, as it does in the release build, so writing it must not count as
    build output."""
    watched = Path(directory) / "logs"
    watched.mkdir()
    report = watched / "quiet-report.log"
    result = subprocess.run(
        [sys.executable, "-I", str(WATCHER), "--watch", str(watched), "--report", str(report), *limits,
         "--", sys.executable, "-c", command],
        cwd=directory, capture_output=True, text=True, timeout=60)
    return result, report


class QuietBuildWatchTest(unittest.TestCase):
    """Each test owns its temporary directory, command and report."""

    def test_reports_and_stops_a_silent_command(self):
        """A command that stops writing gets a process report naming its blocked child, then is terminated."""
        command = (
            "import subprocess, sys, time; "
            "child = subprocess.Popen([sys.executable, '-c', 'import time; time.sleep(600)']); "
            "zombie = subprocess.Popen([sys.executable, '-c', 'pass']); "
            "print('quiet-child-pid=' + str(child.pid), flush=True); "
            "print('quiet-zombie-pid=' + str(zombie.pid), flush=True); "
            "time.sleep(600)"
        )
        with tempfile.TemporaryDirectory(dir=Path.cwd()) as directory:
            result, report = run_watcher(directory, command, "--first-report", "1", "--interval", "1",
                "--stop-after", "2.5")
            self.assertEqual(result.returncode, 124, result.stdout + result.stderr)
            child = next(line.removeprefix("quiet-child-pid=") for line in result.stdout.splitlines()
                if line.startswith("quiet-child-pid="))
            text = report.read_text()
            self.assertIn("quiet_seconds=", text)
            self.assertRegex(text, rf"(?m)^pid={child} ")
            self.assertRegex(text, rf"(?m)^pid={child} .*state=S")
            self.assertRegex(text, rf"(?m)^pid={child} .*wchan=")
            self.assertRegex(text, rf"(?m)^pid={child} .*tracer=0 ", "the child's tracer was not reported")
            self.assertRegex(text, rf"(?m)^pid={child} .*fds=\d+ nofile=\d+ ",
                "the child's open descriptors and descriptor limit were not reported")
            zombie = next(line.removeprefix("quiet-zombie-pid=") for line in result.stdout.splitlines()
                if line.startswith("quiet-zombie-pid="))
            self.assertRegex(text, rf"(?m)^pid={zombie} .*state=Z", "an exited, unreaped child was not reported")
            quiet = [int(value) for value in re.findall(r"quiet_seconds=(\d+)", text)]
            self.assertGreaterEqual(len(quiet), 2)
            self.assertEqual(quiet, sorted(set(quiet)), "writing a report restarted the quiet period")
            self.assertIn("stopping quiet build", result.stderr)
            alive = subprocess.run(["kill", "-0", child], capture_output=True)
            self.assertNotEqual(alive.returncode, 0, "the silent command's child survived the stop")

    def test_preserves_an_active_command_and_its_status(self):
        """A command that keeps writing to the watched directory is never reported and keeps its exit status."""
        command = (
            "import pathlib, sys, time; "
            "log = pathlib.Path('logs/build.log'); "
            "[(log.write_text(str(i)), time.sleep(0.2)) for i in range(15)]; "
            "sys.exit(7)"
        )
        with tempfile.TemporaryDirectory(dir=Path.cwd()) as directory:
            result, report = run_watcher(directory, command, "--first-report", "1", "--interval", "1",
                "--stop-after", "2")
            self.assertEqual(result.returncode, 7, result.stdout + result.stderr)
            self.assertFalse(report.exists(), "an active command was reported as quiet")


class ProcessListTest(unittest.TestCase):
    """Each test owns its child process and report buffer."""

    def test_lists_processes_without_procfs(self):
        """Without a process file system, as on macOS, the report still names each process with its state."""
        spec = importlib.util.spec_from_file_location("watch_quiet_build", WATCHER)
        watcher = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(watcher)
        child = subprocess.Popen([sys.executable, "-c", "import time; time.sleep(600)"])
        try:
            with tempfile.TemporaryDirectory(dir=Path.cwd()) as directory:
                report = io.StringIO()
                watcher.dump(None, report, procfs=Path(directory) / "absent")
            self.assertRegex(report.getvalue(), rf"(?m)^ *{child.pid} +{os.getpid()} +\d+ +\S+ .*time\.sleep\(600\)")
        finally:
            child.kill()
            child.wait()


class StopTest(unittest.TestCase):
    """Each test owns its recorded signal list."""

    def test_returns_when_remaining_members_cannot_be_signaled(self):
        """A group whose remaining members belong to another user ends the stop instead of raising."""
        spec = importlib.util.spec_from_file_location("watch_quiet_build", WATCHER)
        watcher = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(watcher)
        sent = []

        def signal_group(pgid, sig):
            sent.append(sig)
            if sig == 0:
                raise PermissionError(1, "Operation not permitted")

        watcher.stop(4242, lambda: None, signal_group)
        self.assertEqual(sent, [signal.SIGTERM, 0])


if __name__ == "__main__":
    unittest.main()
