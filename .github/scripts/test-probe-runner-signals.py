import json
from pathlib import Path
import platform
import subprocess
import sys
import tempfile
import unittest


class SignalObservationTest(unittest.TestCase):
    """Check native runner diagnostics and command exit-status propagation."""
    def test_observes_command_signals_and_preserves_exit_status(self):
        """Require signal, journal and memory evidence for successful, failed and SIGKILL commands.

        Each invocation owns its temporary directory. Numeric command exits are
        retained; SIGKILL is returned as shell status 137 while the receipt retains -9."""
        probe = Path(__file__).with_name("probe-runner-signals.py").resolve()
        for exit_code in (0, 17, 137):
            with self.subTest(exit_code=exit_code):
                with tempfile.TemporaryDirectory(dir=Path.cwd()) as directory:
                    command = (
                        "import os, signal, time; "
                        "print('observed-command-pid=' + str(os.getpid()), flush=True); "
                        "os.kill(os.getpid(), signal.SIGCONT); "
                        f"time.sleep({6 if exit_code == 0 else 0}); "
                        f"raise SystemExit({exit_code})"
                    )
                    if exit_code == 137:
                        command = command.replace(
                            "raise SystemExit(137)", "os.kill(os.getpid(), signal.SIGKILL)"
                        )
                    result = subprocess.run(
                        [sys.executable, str(probe), sys.executable, "-c", command],
                        cwd=directory,
                        capture_output=True,
                        text=True,
                        timeout=60
                    )
                    self.assertEqual(result.returncode, exit_code, result.stdout + result.stderr)
                    self.assertRegex(result.stdout, r"(?:KiB|MiB|GiB) Mem")
                    self.assertRegex(
                        result.stdout, r"codex-runner-signal-probe(?:\[\d+\])?: journal-ready-\d+"
                    )
                    if exit_code == 0:
                        self.assertGreaterEqual(result.stdout.count("MiB Mem"), 2)
                    command_pid = next(
                        line.removeprefix("observed-command-pid=")
                        for line in result.stdout.splitlines()
                        if line.startswith("observed-command-pid=")
                    )
                    self.assertIn(f"receiver_pid={command_pid} signal=18 ", result.stdout)
                    if exit_code == 137:
                        self.assertIn(f"receiver_pid={command_pid} signal=9 ", result.stdout)
                        kill_syscall = {
                            "aarch64": "__arm64_sys_kill+",
                            "x86_64": "__x64_sys_kill+"
                        }[platform.machine()]
                        self.assertIn(kill_syscall, result.stdout)
                    receipt = json.loads((Path(directory) / "signal-probe.json").read_text())
                    self.assertEqual(receipt["status"], "passed")
                    expected_command_exit = -9 if exit_code == 137 else exit_code
                    self.assertEqual(receipt["command_exit"], expected_command_exit)


if __name__ == "__main__":
    unittest.main()
