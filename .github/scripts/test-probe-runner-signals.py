import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest


class SignalObservationTest(unittest.TestCase):
    def test_observes_command_signals_and_preserves_exit_status(self):
        probe = Path(__file__).with_name("probe-runner-signals.py").resolve()
        for exit_code in (0, 17):
            with self.subTest(exit_code=exit_code):
                with tempfile.TemporaryDirectory(dir=Path.cwd()) as directory:
                    command = (
                        "import os, signal; "
                        "print('observed-command-pid=' + str(os.getpid()), flush=True); "
                        "os.kill(os.getpid(), signal.SIGCONT); "
                        f"raise SystemExit({exit_code})"
                    )
                    result = subprocess.run(
                        [sys.executable, str(probe), sys.executable, "-c", command],
                        cwd=directory,
                        capture_output=True,
                        text=True,
                        timeout=60
                    )
                    self.assertEqual(result.returncode, exit_code, result.stdout + result.stderr)
                    command_pid = next(
                        line.removeprefix("observed-command-pid=")
                        for line in result.stdout.splitlines()
                        if line.startswith("observed-command-pid=")
                    )
                    self.assertIn(f"receiver_pid={command_pid} signal=18 ", result.stdout)
                    receipt = json.loads((Path(directory) / "signal-probe.json").read_text())
                    self.assertEqual(receipt["status"], "passed")
                    self.assertEqual(receipt["command_exit"], exit_code)


if __name__ == "__main__":
    unittest.main()
