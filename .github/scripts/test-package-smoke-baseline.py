#!/usr/bin/env python3
"""Tests package-smoke-baseline.sh with a stand-in for `uv run pytest` that writes the JUnit reports it is told to."""

import json
import os
import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path

SCRIPTS = Path(__file__).resolve().parent
COMMIT = "b" * 40
TARGET = "aarch64-pc-windows-msvc"
KNOWN = "test_packaged_executor_initializes_and_enforces_sandbox[cli_root]"

# The stand-in reads the failing test names from FAKE_FAILURES (comma separated) and fails them in every report.
FAKE_UV = """#!/usr/bin/env bash
set -euo pipefail
report=""
while [[ $# -gt 0 ]]; do
  if [[ "$1" == --junitxml ]]; then report="$2"; fi
  shift
done
[[ -n "${FAKE_NO_REPORT:-}" ]] && exit 4
{
  echo '<?xml version="1.0"?><testsuites><testsuite name="pytest">'
  echo '<testcase classname="test_codex_package" name="test_other"/>'
  IFS=, read -r -a failing <<< "${FAKE_FAILURES:-}"
  for name in ${failing[@]+"${failing[@]}"}; do
    echo "<testcase classname=\\"test_codex_package\\" name=\\"$name\\"><failure message=\\"timeout\\"/></testcase>"
  done
  echo '</testsuite></testsuites>'
} > "$report"
[[ -n "${FAKE_FAILURES:-}" ]] && exit 1
exit 0
"""


class PackageSmokeBaselineTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        root = Path(self.directory.name)
        self.workspace = root / "workspace"
        self.temp = root / "temp"
        self.tools = root / "tools"
        self.bin = root / "bin"
        for path in (self.temp, self.tools, self.bin):
            path.mkdir()
        (self.workspace / "scripts" / "codex_package" / "smoke_tests").mkdir(parents=True)
        shutil.copy(SCRIPTS / "compare-test-baseline.py", self.tools / "compare-test-baseline.py")
        uv = self.bin / "uv"
        uv.write_text(FAKE_UV, encoding="utf-8")
        uv.chmod(0o755)
        self.make_archives()

    def tearDown(self):
        self.directory.cleanup()

    def make_archives(self, omit=None):
        archives = self.workspace / "dist" / TARGET
        archives.mkdir(parents=True, exist_ok=True)
        names = [f"codex-package-{TARGET}.tar.gz", f"codex-package-{TARGET}.tar.zst",
                 f"codex-app-server-package-{TARGET}.tar.gz", f"codex-app-server-package-{TARGET}.tar.zst"]
        symbols = self.workspace / "symbols-dist" / TARGET
        symbols.mkdir(parents=True, exist_ok=True)
        (symbols / f"codex-symbols-{TARGET}.tar.gz").write_text("x", encoding="utf-8")
        for name in names:
            if name != omit:
                (archives / name).write_text("x", encoding="utf-8")

    def write_baseline(self, failed):
        path = self.tools / "test-baselines" / COMMIT / f"package-smoke-{TARGET}.json"
        path.parent.mkdir(parents=True)
        path.write_text(json.dumps({"failed": failed, "timed_out": []}), encoding="utf-8")

    def run_script(self, mode, failures="", no_report=False):
        env = dict(os.environ, TARGET=TARGET, UPSTREAM_COMMIT=COMMIT, RUNNER_TEMP=str(self.temp),
                   GITHUB_WORKSPACE=str(self.workspace), PACKAGE_SMOKE_TOOLS=str(self.tools),
                   PACKAGE_SMOKE_EVIDENCE=str(self.temp / "evidence"), FAKE_FAILURES=failures,
                   PATH=f"{self.bin}{os.pathsep}{os.environ['PATH']}")
        if no_report:
            env["FAKE_NO_REPORT"] = "1"
        return subprocess.run(["bash", str(SCRIPTS / "package-smoke-baseline.sh"), mode], env=env,
                              capture_output=True, text=True)

    def test_known_failure_passes(self):
        self.write_baseline([f"test_codex_package {KNOWN}"])
        self.assertEqual(0, self.run_script("compare", KNOWN).returncode)

    def test_new_failure_fails(self):
        self.write_baseline([f"test_codex_package {KNOWN}"])
        result = self.run_script("compare", f"{KNOWN},test_new")
        self.assertEqual(1, result.returncode)
        self.assertIn("test_codex_package test_new", result.stdout)

    def test_failure_without_a_baseline_fails(self):
        self.assertEqual(1, self.run_script("compare", KNOWN).returncode)

    def test_clean_rerun_without_a_baseline_passes(self):
        self.assertEqual(0, self.run_script("compare").returncode)

    def test_missing_archive_is_a_build_failure(self):
        self.write_baseline([f"test_codex_package {KNOWN}"])
        self.make_archives()
        (self.workspace / "dist" / TARGET / f"codex-package-{TARGET}.tar.zst").unlink()
        result = self.run_script("compare", KNOWN)
        self.assertEqual(1, result.returncode)
        self.assertIn("build failure", result.stderr)

    def test_missing_report_fails(self):
        self.write_baseline([])
        self.assertEqual(1, self.run_script("compare", no_report=True).returncode)

    def test_record_writes_the_baseline(self):
        self.assertEqual(0, self.run_script("record", KNOWN).returncode)
        data = json.loads((self.temp / "evidence" / f"package-smoke-{TARGET}.json").read_text(encoding="utf-8"))
        self.assertEqual([f"test_codex_package {KNOWN}"], data["failed"])


if __name__ == "__main__":
    unittest.main()
