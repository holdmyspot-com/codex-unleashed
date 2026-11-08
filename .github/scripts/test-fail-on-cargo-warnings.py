#!/usr/bin/env python3
"""Exercise the release warning gate on fixture Cargo logs."""

from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

SCRIPT = Path(__file__).with_name("fail-on-cargo-warnings.sh")


class FailOnCargoWarningsTest(unittest.TestCase):
    """Check which Cargo log lines fail the release and what the failure reports."""

    def run_gate(self, logs, extra=()):
        """
        Write logs, a mapping from file name to text, into a fresh directory and return the gate's result for those
        files followed by the extra names, which are resolved in the same directory and need not exist.
        """
        with tempfile.TemporaryDirectory() as directory:
            for name, text in logs.items():
                (Path(directory) / name).write_text(text)
            paths = [(Path(directory) / name).as_posix() for name in [*logs, *extra]]
            return subprocess.run([shutil.which("bash"), SCRIPT.as_posix(), *paths], capture_output=True, text=True)

    def test_build_script_warning_fails_and_is_quoted(self):
        """A build script's cargo:warning fails with status 1, quoting the original line with its log and line number."""
        warning = ("warning: codex-bwrap@0.0.0: vendor/bubblewrap/network.c:53:3: "
                   "warning: missing initializer for field 'nl_pid'")
        result = self.run_gate({"cargo-build-codex.log": "   Compiling codex v0.0.0\n",
                                "bwrap-build.log": "   Compiling codex-bwrap v0.0.0\n" + warning + "\n"})
        self.assertEqual(result.returncode, 1, result.stderr)
        self.assertIn("bwrap-build.log:2:" + warning, result.stderr)

    def test_nextest_configuration_warning_fails(self):
        """A nextest configuration warning in a test archive log fails too."""
        result = self.run_gate({"archive.log": "warning: ignoring unknown configuration keys in config file "
                                               ".config/nextest.toml: profile.local.inherits\n"})
        self.assertEqual(result.returncode, 1, result.stderr)

    def test_cargo_lint_warning_fails(self):
        """A Cargo warning with a lint code in brackets fails too."""
        result = self.run_gate({"cargo-build-codex.log": "warning[unused_manifest_key]: unused manifest key: x\n"})
        self.assertEqual(result.returncode, 1, result.stderr)

    def test_clean_logs_pass(self):
        """Logs whose only mentions of warnings are inside other lines pass with status 0."""
        result = self.run_gate({"cargo-build-codex.log": "   Compiling codex-warnings v0.0.0\n    Finished `release`\n",
                                "cargo-build-codex-app-server.log": "note: 0 warnings\n"})
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(result.stderr, "")

    def test_missing_logs_fail_with_status_2(self):
        """No log, or a missing one, fails with status 2 instead of passing vacuously."""
        self.assertEqual(self.run_gate({}).returncode, 2)
        result = self.run_gate({"cargo-build-codex.log": ""}, extra=["cargo-build-codex-app-server.log"])
        self.assertEqual(result.returncode, 2, result.stderr)


if __name__ == "__main__":
    unittest.main()
