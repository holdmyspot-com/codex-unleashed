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

    def test_stops_a_command_that_writes_only_scheduler_stall_reports(self):
        """A scheduler that reports its own stall keeps appending to the log, but those reports are not build output."""
        command = (
            "import pathlib, time; "
            "log = pathlib.Path('logs/build.log'); "
            "log.write_text('   Compiling curl-sys\\n'); "
            "[(log.open('a').write('{\"format_version\":1,\"event\":\"scheduler_stall\",\"stalled_seconds\":' + "
            "str(i) + '}\\n'), time.sleep(0.2)) for i in range(150)]"
        )
        with tempfile.TemporaryDirectory(dir=Path.cwd()) as directory:
            result, report = run_watcher(directory, command, "--first-report", "1", "--interval", "1",
                "--stop-after", "2.5")
            self.assertEqual(result.returncode, 124, result.stdout + result.stderr)
            self.assertIn("stopping quiet build", result.stderr)

    def test_keeps_a_command_whose_output_follows_a_stall_report(self):
        """Build output appended after a stall report is activity, so the command runs to its own exit."""
        command = (
            "import pathlib, sys, time; "
            "log = pathlib.Path('logs/build.log'); "
            "log.write_text('{\"event\":\"scheduler_stall\"}\\n'); "
            "[(log.open('a').write('   Compiling crate' + str(i) + '\\n'), time.sleep(0.2)) for i in range(15)]; "
            "sys.exit(5)"
        )
        with tempfile.TemporaryDirectory(dir=Path.cwd()) as directory:
            result, report = run_watcher(directory, command, "--first-report", "1", "--interval", "1",
                "--stop-after", "2")
            self.assertEqual(result.returncode, 5, result.stdout + result.stderr)
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


class WindowsReportTest(unittest.TestCase):
    """Each test owns its loaded watcher module, process records and report buffer.

    The records stand in for the rows that PowerShell's ConvertTo-Json prints for the watcher's process query: one
    object when one process matches, otherwise an array."""

    def load(self):
        spec = importlib.util.spec_from_file_location("watch_quiet_build", WATCHER)
        watcher = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(watcher)
        return watcher

    def test_reads_one_process_or_many(self):
        """A single process and a list of processes are both read as a list of records."""
        watcher = self.load()
        row = '{"pid":10,"ppid":4,"name":"cargo.exe","command":"cargo build","threads":2,"suspended":0}'
        self.assertEqual([record["pid"] for record in watcher.windows_processes(row)], [10])
        self.assertEqual([record["pid"] for record in watcher.windows_processes(f"[{row},{row}]")], [10, 10])

    def test_names_suspended_processes_and_dumps_each_jvm(self):
        """Each process is listed with its suspended thread count, a fully suspended one is marked as held, and only
        JVMs get a thread dump."""
        watcher = self.load()
        processes = [
            {"pid": 10, "ppid": 4, "name": "cargo.exe", "command": "cargo build", "threads": 4, "suspended": 0},
            {"pid": 11, "ppid": 10, "name": "rustc.exe", "command": "rustc --crate-name x", "threads": 3,
             "suspended": 3},
            {"pid": 12, "ppid": 4, "name": "java.exe", "command": None, "threads": 30, "suspended": 0},
        ]
        dumped = []
        report = io.StringIO()
        watcher.dump_windows(processes, report, lambda pid: dumped.append(pid) or f"dump of {pid}\n")
        text = report.getvalue()
        self.assertRegex(text, r"(?m)^pid=11 ppid=10 threads=3 suspended=3 held=yes cmd=rustc --crate-name x$")
        self.assertRegex(text, r"(?m)^pid=10 ppid=4 threads=4 suspended=0 held=no cmd=cargo build$")
        self.assertRegex(text, r"(?m)^pid=12 ppid=4 threads=30 suspended=0 held=no cmd=java\.exe$")
        self.assertEqual(dumped, [12])
        self.assertIn("dump of 12", text)


class SampleSelectionTest(unittest.TestCase):
    """Each test owns its loaded watcher module."""

    def test_samples_stopped_processes_and_frida_helpers(self):
        """A stopped process or a Frida helper is sampled like a JVM, because a stalled build can be held by either."""
        spec = importlib.util.spec_from_file_location("watch_quiet_build", WATCHER)
        watcher = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(watcher)
        self.assertTrue(watcher.sampled("S<", "/opt/runtime/bin/java -m tool"))
        self.assertTrue(watcher.sampled("T", "/toolchain/bin/cargo build"))
        self.assertTrue(watcher.sampled("S<s", "/private/var/root/frida-0123/frida-helper socket-fd:29"))
        self.assertFalse(watcher.sampled("S", "/usr/sbin/syslogd"))

    def test_samples_running_children_of_stopped_processes(self):
        """A running child of a stopped process is sampled, because a held child that never answers its instrumentation
        keeps its stopped parent waiting; an unrelated running process is not."""
        spec = importlib.util.spec_from_file_location("watch_quiet_build", WATCHER)
        watcher = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(watcher)
        listing = ["6322     1   501 T     6100      0   ??  -      git fetch origin",
                   "6327  6322   501 S     6100      0   ??  -      git fetch origin",
                   "  90     1     0 S       90      0   ??  -      /usr/sbin/syslogd"]
        self.assertEqual(["6322", "6327"], [fields[0] for fields in watcher.sampled_processes(listing)])


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

    def test_ends_a_windows_command_with_its_whole_tree(self):
        """Windows has no process groups, so the command's process tree is ended by its root process."""
        spec = importlib.util.spec_from_file_location("watch_quiet_build", WATCHER)
        watcher = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(watcher)
        commands = []
        watcher.stop_tree(4242, lambda command: commands.append(command))
        self.assertEqual(commands, [["taskkill", "/T", "/F", "/PID", "4242"]])


if __name__ == "__main__":
    unittest.main()
