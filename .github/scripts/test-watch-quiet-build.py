"""Check that the quiet-build watchdog reports and stops a silent command but leaves an active one alone."""
from pathlib import Path
import importlib.util
import io
import json
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


    def test_preserves_a_silent_command_that_keeps_computing(self):
        """A command that writes nothing but keeps using CPU, as a long final link does, is never stopped and keeps
        its exit status."""
        command = (
            "import sys, time; end = time.time() + 4; "
            "[None for _ in iter(lambda: time.time() < end, False)]; "
            "sys.exit(5)"
        )
        with tempfile.TemporaryDirectory(dir=Path.cwd()) as directory:
            result, _ = run_watcher(directory, command, "--first-report", "1", "--interval", "1",
                "--stop-after", "2", "--cpu-interval", "0.5", "--min-cpu", "0.1")
            self.assertEqual(result.returncode, 5, result.stdout + result.stderr)
            self.assertNotIn("stopping quiet build", result.stderr)


class CommandCpuTest(unittest.TestCase):
    """Each test owns its process listing text or records."""

    def load(self):
        spec = importlib.util.spec_from_file_location("watch_quiet_build", WATCHER)
        watcher = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(watcher)
        return watcher

    def test_reads_the_cpu_time_of_one_process_group_from_ps(self):
        """Only the group's members are counted, in both macOS's M:SS.ss and procps's [DD-]HH:MM:SS forms."""
        listing = ("  10    10      1:02.50\n  11    10   01:00:03\n  12    10 2-00:00:01\n  13    99      9:00.00\n")
        self.assertEqual(self.load().group_cpu(listing, 10), {10: 62.5, 11: 3603.0, 12: 172801.0})

    def test_reads_the_cpu_time_of_one_windows_process_tree(self):
        """The root and its descendants are counted from 100-nanosecond kernel and user times; other processes are
        not."""
        records = [
            {"ProcessId": 5, "ParentProcessId": 1, "KernelModeTime": 10_000_000, "UserModeTime": 20_000_000},
            {"ProcessId": 6, "ParentProcessId": 5, "KernelModeTime": 0, "UserModeTime": 5_000_000},
            {"ProcessId": 7, "ParentProcessId": 6, "KernelModeTime": 1, "UserModeTime": None},
            {"ProcessId": 8, "ParentProcessId": 1, "KernelModeTime": 99, "UserModeTime": 99},
        ]
        self.assertEqual(self.load().tree_cpu(records, 5), {5: 3.0, 6: 0.5, 7: 1e-07})

    def test_counts_cpu_used_since_the_previous_check(self):
        """A check is due once per interval and reports whether the command's processes, including ones that started
        since the previous check, used at least the minimum CPU time."""
        readings = iter([{1: 1.0}, {1: 1.5, 2: 0.2}, {1: 4.0}, {1: 4.1}])
        cpu = self.load().CommandCpu(lambda: next(readings), interval=10, minimum=0.5)
        self.assertTrue(cpu.advanced(0))
        self.assertFalse(cpu.advanced(5), "a check ran before its interval passed")
        self.assertTrue(cpu.advanced(10))
        self.assertTrue(cpu.advanced(20))
        self.assertFalse(cpu.advanced(30))


class ResourceSampleTest(unittest.TestCase):
    """Each test owns its temporary directory, procfs tree and command."""

    def test_reads_linux_cpu_memory_swap_and_pressure(self):
        """One Linux sample carries the cumulative counters that measure CPU idleness, swapping and memory stalls."""
        spec = importlib.util.spec_from_file_location("watch_quiet_build", WATCHER)
        watcher = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(watcher)
        with tempfile.TemporaryDirectory(dir=Path.cwd()) as directory:
            procfs = Path(directory)
            (procfs / "pressure").mkdir()
            (procfs / "stat").write_text("cpu  10 1 20 300 4 5 6 7 0 0\ncpu0 1 1 1 1 1 1 1 1 0 0\n")
            (procfs / "meminfo").write_text("MemTotal:       16000 kB\nMemFree:  10 kB\nMemAvailable:    8000 kB\n"
                "SwapTotal:       4000 kB\nSwapFree:        3000 kB\n")
            (procfs / "vmstat").write_text("pgmajfault 9\npswpin 11\npswpout 12\n")
            (procfs / "pressure" / "memory").write_text("some avg10=0.00 avg60=0.00 avg300=0.00 total=500\n"
                "full avg10=0.00 avg60=0.00 avg300=0.00 total=200\n")
            (procfs / "pressure" / "cpu").write_text("some avg10=0.00 avg60=0.00 avg300=0.00 total=700\n")
            sample = watcher.linux_resources(procfs)
        self.assertEqual(sample["cpu_ticks"], {"user": 10, "nice": 1, "system": 20, "idle": 300, "iowait": 4,
            "irq": 5, "softirq": 6, "steal": 7})
        self.assertEqual(sample["processors"], 1)
        self.assertEqual(sample["memory_total_bytes"], 16000 * 1024)
        self.assertEqual(sample["memory_available_bytes"], 8000 * 1024)
        self.assertEqual(sample["swap_used_bytes"], 1000 * 1024)
        self.assertEqual((sample["swap_in_pages"], sample["swap_out_pages"], sample["major_faults"]), (11, 12, 9))
        self.assertEqual(sample["memory_stall_us"], {"some": 500, "full": 200})
        self.assertEqual(sample["cpu_stall_us"], {"some": 700})

    def test_samples_resources_without_counting_them_as_output(self):
        """Resource samples are written beside the report while the command runs, and do not keep a silent command
        alive."""
        with tempfile.TemporaryDirectory(dir=Path.cwd()) as directory:
            result, report = run_watcher(directory, "import time; time.sleep(600)", "--first-report", "1",
                "--interval", "1", "--stop-after", "2", "--sample-interval", "0.2")
            self.assertEqual(result.returncode, 124, result.stdout + result.stderr)
            lines = (report.parent / "build-resources.jsonl").read_text().splitlines()
            self.assertGreaterEqual(len(lines), 3)
            samples = [json.loads(line) for line in lines]
            times = [sample["time"] for sample in samples]
            self.assertEqual(times, sorted(times))
            if sys.platform.startswith("linux"):
                self.assertIn("memory_available_bytes", samples[-1])
                self.assertIn("swap_in_pages", samples[-1])


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

    def test_samples_stopped_processes(self):
        """A stopped process is sampled like a JVM."""
        spec = importlib.util.spec_from_file_location("watch_quiet_build", WATCHER)
        watcher = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(watcher)
        self.assertTrue(watcher.sampled("S<", "/opt/runtime/bin/java -m tool"))
        self.assertTrue(watcher.sampled("T", "/toolchain/bin/cargo build"))
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


class StopFactsTest(unittest.TestCase):
    """Each test owns its loaded watcher module."""

    def test_separates_signal_stops_from_mach_suspension(self):
        """A stopped process's line names its BSD status, its task suspend count and each thread's run state and
        suspend count, so a job-control stop reads differently from a task or thread suspended over its Mach port."""
        spec = importlib.util.spec_from_file_location("watch_quiet_build", WATCHER)
        watcher = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(watcher)
        self.assertEqual("stop: bsd_status=SSTOP task_suspend_count=0 threads=waiting/0,stopped/1",
                         watcher.describe_stop(4, 0, [(3, 0), (2, 1)]))
        self.assertEqual("stop: bsd_status=SRUN task_suspend_count=1 threads=running/0",
                         watcher.describe_stop(2, 1, [(1, 0)]))
        self.assertEqual("stop: bsd_status=9 task_suspend_count=? threads=?",
                         watcher.describe_stop(9, None, None))

    def test_names_the_stop_signal_and_tracing(self):
        """A stopped process's signal line tells whether it is traced, which Darwin signal stopped it and which signals
        are pending, so a debugger or tracer stop reads differently from a job-control or group stop signal."""
        spec = importlib.util.spec_from_file_location("watch_quiet_build", WATCHER)
        watcher = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(watcher)
        self.assertEqual("signals: traced=yes stop_signal=SIGSTOP pending=SIGCHLD,SIGTERM",
                         watcher.describe_signals(True, 17, (1 << 19) | (1 << 14)))
        self.assertEqual("signals: traced=no stop_signal=SIGTSTP pending=none",
                         watcher.describe_signals(False, 18, 0))
        self.assertEqual("signals: traced=? stop_signal=? pending=?",
                         watcher.describe_signals(None, None, None))


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
