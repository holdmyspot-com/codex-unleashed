#!/usr/bin/env python3
"""Test package-smoke-baseline.sh with in-memory uv and Java launcher collaborators."""

import json
import os
import subprocess
import tempfile
import unittest
from pathlib import Path

SCRIPTS = Path(__file__).resolve().parent
COMMIT = "b" * 40
TARGET = "aarch64-pc-windows-msvc"
KNOWN = "test_packaged_executor_initializes_and_enforces_sandbox[cli_root]"

# Bash imports these exported functions into the test child without a PATH executable. The launcher collaborator
# captures its argument vector and supplies controlled stdout, stderr, status, and output-file bytes.
UV_FUNCTION = r'''() {
  local report=""
  while [[ $# -gt 0 ]]; do
    if [[ "$1" == --junitxml ]]; then report="$2"; fi
    shift
  done
  [[ -n "${FAKE_NO_REPORT:-}" ]] && return 4
  {
    echo '<?xml version="1.0"?><testsuites><testsuite name="pytest">'
    echo '<testcase classname="test_codex_package" name="test_other"/>'
    IFS=, read -r -a failing <<< "${FAKE_FAILURES:-}"
    for name in ${failing[@]+"${failing[@]}"}; do
      echo "<testcase classname=\"test_codex_package\" name=\"$name\"><failure message=\"timeout\"/></testcase>"
    done
    echo '</testsuite></testsuites>'
  } > "$report"
  [[ -n "${FAKE_FAILURES:-}" ]] && return 1
  return 0
}'''

TOOLING_FUNCTION = r'''() {
  local launcher="$1"; shift
  [[ "$launcher" == "$CODEX_TOOLING" && "$1" == compare-test-baseline ]] || return 90
  printf '%s\0' "$@" > "$TOOLING_ARGS"
  local argument
  while [[ $# -gt 0 ]]; do
    if [[ "$1" == --output ]]; then
      printf '%s' "$TOOLING_JSON" > "$2"
      break
    fi
    shift
  done
  printf '%s' "${TOOLING_STDOUT:-}"
  printf '%s' "${TOOLING_STDERR:-}" >&2
  return "${TOOLING_EXIT:-0}"
}'''


class PackageSmokeBaselineTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        root = Path(self.directory.name)
        self.workspace = root / "workspace"
        self.temp = root / "temp"
        self.tools = root / "tools"
        for path in (self.temp, self.tools):
            path.mkdir()
        (self.workspace / "scripts" / "codex_package" / "smoke_tests").mkdir(parents=True)
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

    def run_script(self, mode, failures="", no_report=False, tooling_exit=0, tooling_stdout=""):
        env = dict(os.environ, TARGET=TARGET, UPSTREAM_COMMIT=COMMIT, RUNNER_TEMP=str(self.temp),
                   GITHUB_WORKSPACE=str(self.workspace), PACKAGE_SMOKE_TOOLS=str(self.tools),
                   PACKAGE_SMOKE_EVIDENCE=str(self.temp / "evidence"), FAKE_FAILURES=failures,
                   CODEX_TOOLING="in-memory-launcher", CODEX_TOOLING_BASH="bash",
                   TOOLING_ARGS=str(self.temp / "tooling-args"), TOOLING_EXIT=str(tooling_exit),
                   TOOLING_STDOUT=tooling_stdout,
                   TOOLING_JSON=json.dumps({"failed": [f"test_codex_package {name}" for name in failures.split(",") if name],
                                            "timed_out": []}))
        env["BASH_FUNC_uv%%"] = UV_FUNCTION
        env["BASH_FUNC_bash%%"] = TOOLING_FUNCTION
        if no_report:
            env["FAKE_NO_REPORT"] = "1"
        return subprocess.run(["bash", str(SCRIPTS / "package-smoke-baseline.sh"), mode], env=env,
                              capture_output=True, text=True)

    def test_known_failure_passes(self):
        self.write_baseline([f"test_codex_package {KNOWN}"])
        result = self.run_script("compare", KNOWN)
        self.assertEqual(0, result.returncode, result.stderr or result.stdout)
        arguments = (self.temp / "tooling-args").read_bytes().split(b"\0")[:-1]
        self.assertEqual(
            [b"compare-test-baseline", b"--platform", f"package-smoke-{TARGET}".encode(),
             b"--upstream-commit", COMMIT.encode(), b"--baseline-dir",
             str(self.tools / "test-baselines").encode(),
             str(self.temp / "package-smoke-reports" / "gzip.xml").encode(),
             str(self.temp / "package-smoke-reports" / "zstd.xml").encode()], arguments)

    def test_new_failure_fails(self):
        self.write_baseline([f"test_codex_package {KNOWN}"])
        result = self.run_script("compare", f"{KNOWN},test_new", tooling_exit=1,
                                 tooling_stdout="test_codex_package test_new\n")
        self.assertEqual(1, result.returncode)
        self.assertIn("test_codex_package test_new", result.stdout)

    def test_failure_without_a_baseline_fails(self):
        self.assertEqual(1, self.run_script("compare", KNOWN, tooling_exit=1).returncode)

    def test_clean_rerun_without_a_baseline_passes(self):
        result = self.run_script("compare")
        self.assertEqual(0, result.returncode)
        self.assertIn("No smoke test failed", result.stdout)
        arguments = (self.temp / "tooling-args").read_bytes().split(b"\0")[:-1]
        self.assertEqual(
            [b"compare-test-baseline", b"--platform", f"package-smoke-{TARGET}".encode(),
             b"--upstream-commit", COMMIT.encode(), b"--write-baseline", b"--output",
             str(self.temp / "package-smoke-reports" / "observed.json").encode(),
             str(self.temp / "package-smoke-reports" / "gzip.xml").encode(),
             str(self.temp / "package-smoke-reports" / "zstd.xml").encode()], arguments)

    def test_missing_archive_is_a_build_failure(self):
        self.write_baseline([f"test_codex_package {KNOWN}"])
        (self.workspace / "dist" / TARGET / f"codex-package-{TARGET}.tar.zst").unlink()
        result = self.run_script("compare", KNOWN)
        self.assertEqual(1, result.returncode)
        self.assertIn("build failure", result.stderr)

    def test_missing_report_fails(self):
        self.assertEqual(1, self.run_script("compare", no_report=True).returncode)

    def test_record_writes_the_baseline(self):
        self.assertEqual(0, self.run_script("record", KNOWN).returncode)
        data = json.loads((self.temp / "evidence" / f"package-smoke-{TARGET}.json").read_text(encoding="utf-8"))
        self.assertEqual([f"test_codex_package {KNOWN}"], data["failed"])

    def test_missing_tooling_handoff_fails_clearly(self):
        env = dict(os.environ, TARGET=TARGET, UPSTREAM_COMMIT=COMMIT, RUNNER_TEMP=str(self.temp),
                   GITHUB_WORKSPACE=str(self.workspace), PACKAGE_SMOKE_TOOLS=str(self.tools))
        result = subprocess.run(["bash", str(SCRIPTS / "package-smoke-baseline.sh"), "compare"], env=env,
                                capture_output=True, text=True)
        self.assertEqual(1, result.returncode)
        self.assertIn("CODEX_TOOLING is missing", result.stderr)


if __name__ == "__main__":
    unittest.main()
