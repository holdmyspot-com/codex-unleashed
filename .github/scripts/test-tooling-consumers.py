"""Exercise the assembled Java tooling launcher through its command boundary."""
import os
import subprocess
import tempfile
import unittest
from contextlib import contextmanager
from pathlib import Path


@contextmanager
def temporary_workspace():
    """Own one probe fixture beneath the tooling action's temporary state."""
    root = Path(os.environ["CODEX_TOOLING_ROOT"]) / "tmp"
    with tempfile.TemporaryDirectory(prefix="consumer-probe-", dir=root) as temporary:
        yield Path(temporary)


def run_tooling(*arguments):
    """Run one command through the assembled launcher using the selected Bash executable."""
    command = [os.environ["CODEX_TOOLING_BASH"], os.environ["CODEX_TOOLING"], *map(str, arguments)]
    return subprocess.run(command, check=False, capture_output=True, text=True, encoding="utf-8")


class ToolingConsumerTest(unittest.TestCase):
    """Check actual commands at the packaged launcher boundary."""

    def test_launcher_reads_xml_report_and_writes_baseline(self):
        with temporary_workspace() as root:
            report = root / "junit.xml"
            report.write_text(
                '<testsuites><testsuite name="launcher"><testcase classname="tooling" '
                'name="report-parsed"/></testsuite></testsuites>\n',
                encoding="utf-8",
            )
            baselines = root / "baselines"
            result = run_tooling(
                "compare-test-baseline",
                "--platform",
                "linux",
                "--upstream-commit",
                "launcher-probe",
                "--baseline-dir",
                baselines,
                "--write-baseline",
                report,
            )

            self.assertEqual(result.returncode, 0, result.stderr or result.stdout)
            self.assertIn("1 executed", result.stdout)
            baseline_files = list(baselines.rglob("*.json"))
            self.assertEqual(len(baseline_files), 1, result.stdout)
            self.assertIn('"tests_executed": 1', baseline_files[0].read_text(encoding="utf-8"))

    def test_launcher_preserves_patch_queue_order(self):
        with temporary_workspace() as root:
            readme = root / "README.md"
            readme.write_text(
                "# Queue\n\n1. [one.patch](vendor/one.patch)\n2. [two.patch](vendor/two.patch)\n",
                encoding="utf-8",
            )
            result = run_tooling("upstream-auto-release", "patches", "--readme", readme)

            self.assertEqual(result.returncode, 0, result.stderr)
            self.assertEqual(result.stdout, "patches/vendor/one.patch patches/vendor/two.patch\n")

    def test_launcher_prints_voice_runtime_help(self):
        result = run_tooling("voice-runtime", "--help")

        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn("Usage: voice-runtime", result.stdout)

    def test_launcher_rejects_incomplete_voice_runtime_stage(self):
        result = run_tooling("voice-runtime", "stage", "--package-dir", "missing")

        self.assertEqual(result.returncode, 2, result.stdout or result.stderr)
        self.assertIn("Usage: voice-runtime", result.stderr)

    def test_launcher_prints_package_license_help(self):
        result = run_tooling("package-license", "--help")

        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn("Usage: package-license", result.stdout)

    def test_launcher_rejects_conflicting_license_options(self):
        with temporary_workspace() as root:
            metadata = root / "metadata"
            upstream = root / "upstream"
            metadata.mkdir()
            upstream.mkdir()
            result = run_tooling(
                "package-license",
                "stage",
                "--metadata-root",
                metadata,
                "--upstream-root",
                upstream,
                "--package-dir",
                root / "package",
                "--archive",
                "unused",
            )

            self.assertEqual(result.returncode, 2, result.stdout or result.stderr)
            self.assertIn("Stage requires --package-dir", result.stderr)


if __name__ == "__main__":
    unittest.main()
