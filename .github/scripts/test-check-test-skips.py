#!/usr/bin/env python3
"""Unit tests for check-test-skips.py."""

import importlib.util
import io
import tempfile
import unittest
from contextlib import redirect_stdout, redirect_stderr
from pathlib import Path

SPEC = importlib.util.spec_from_file_location("check_test_skips", Path(__file__).with_name("check-test-skips.py"))
checker = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(checker)

JUNIT = """<?xml version="1.0"?>
<testsuites><testsuite name="codex-core::all">
<testcase name="a" classname="codex-core::all"><system-out><![CDATA[{out}]]></system-out><system-err><![CDATA[{err}]]></system-err></testcase>
</testsuite></testsuites>
"""


def run(platform, *files):
    out, err = io.StringIO(), io.StringIO()
    with tempfile.TemporaryDirectory() as directory, redirect_stdout(out), redirect_stderr(err):
        paths = []
        for name, content in files:
            path = Path(directory) / name
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(content)
            paths.append(str(path))
        status = checker.main(["--platform", platform, *paths])
    return status, out.getvalue() + err.getvalue()


class ClassifyTest(unittest.TestCase):
    def verdict(self, line, platform="linux"):
        return checker.classify(line, platform)[0]

    def test_missing_remote_environment(self):
        self.assertEqual(checker.MISSING,
                         self.verdict("Skipping test because it requires a remote test environment."))

    def test_missing_zsh(self):
        self.assertEqual(checker.MISSING, self.verdict("skipping zsh test: /bin/zsh is unavailable"))

    def test_missing_bwrap_and_namespaces(self):
        for line in ("skipping bwrap test: bubblewrap is unavailable",
                     "skipping bwrap test: bwrap sandbox prerequisites are unavailable",
                     "skipping private tmp test: user/mount namespaces are unavailable",
                     "skipping sandbox test: bwrap: No permissions to create new namespace"):
            self.assertEqual(checker.MISSING, self.verdict(line), line)

    def test_missing_python_helper_and_mxc_differ_by_platform(self):
        self.assertEqual(checker.MISSING, self.verdict("python3 not found in PATH, skipping test."))
        self.assertEqual(checker.MISSING,
                         self.verdict("test_stdio_server binary not available, skipping test: x"))
        self.assertEqual(checker.ALLOW, self.verdict("python not found; skipping ConPTY input test", "windows"))
        self.assertEqual(checker.MISSING, self.verdict("python not found; skipping PTY output backpressure test"))

    def test_missing_helper_is_not_a_skip(self):
        for line in ("test_streamable_http_server binary not available, skipping test: x",
                     "codex-linux-sandbox binary not available, skipping test: x",
                     "skipping test: could not locate binary \"test_stdio_server\""):
            self.assertEqual(checker.MISSING, self.verdict(line), line)

    def test_allowed_by_design(self):
        for line in ("Skipping test in a remote test environment: uses the local filesystem",
                     "Skipping test in the Wine-exec test environment: no Linux",
                     "Skipping test because network is disabled in this sandbox",
                     "skipping live_create_file_hello_txt - OPENAI_API_KEY not set",
                     "skipping WSL root test: bwrap sandbox prerequisites are unavailable",
                     "Skipping Linux symlink grant regression on another host OS"):
            self.assertEqual(checker.ALLOW, self.verdict(line), line)

    def test_remote_environment_skip_is_allowed_only_under_bazel(self):
        line = "Skipping test because it requires a remote test environment."
        self.assertEqual(checker.ALLOW, checker.classify(line, "linux", "bazel")[0])
        self.assertEqual(checker.MISSING, checker.classify(line, "linux", "nextest")[0])

    def test_missing_ripgrep_and_kernel_landlock(self):
        self.assertEqual(checker.MISSING, self.verdict("skipping ripgrep config test: no protected rg is available"))
        self.assertEqual(checker.ALLOW, self.verdict("Skipping test: Landlock is not enforceable on this host."))

    def test_seatbelt_only_allowed_on_macos(self):
        line = "skipping DNS regression: nested Seatbelt unavailable"
        self.assertEqual(checker.ALLOW, self.verdict(line, "macos"))
        self.assertEqual(checker.UNKNOWN, self.verdict(line, "linux"))

    def test_unknown_skip_is_not_allowed(self):
        self.assertEqual(checker.UNKNOWN, self.verdict("skipping something new: frobnicator missing"))


class DetectionTest(unittest.TestCase):
    def test_ignores_tracing_and_test_names(self):
        text = "\n".join([
            "2026-01-01T00:00:00Z  WARN codex: skipping duplicated tool foo",
            "WARN skipping onboarding login step",
            "test suite::skipping_is_fine ... ok",
            "running 3 tests",
            "skipping bwrap test: bubblewrap is unavailable",
            "python not found; skipping PTY test",
        ])
        self.assertEqual(["skipping bwrap test: bubblewrap is unavailable",
                          "python not found; skipping PTY test"], list(checker.skip_lines(text)))

    def test_ignores_tracing_lines_with_prefixes_and_replaced_escapes(self):
        text = "[mcp stderr] 2026-01-01T00:00:00Z  WARN codex_app_server: failed to reload, skipping x\n" \
               "[mcp stderr] ?[2m2026-01-01?[0m ?[33m WARN?[0m codex: doctor report did not produce JSON; skipping a"
        self.assertEqual([], list(checker.skip_lines(text)))

    def test_strips_color_codes(self):
        self.assertEqual(["Skipping test: x"], list(checker.skip_lines("\x1b[31mSkipping test: x\x1b[0m")))


class MainTest(unittest.TestCase):
    def test_junit_with_only_allowed_skips_passes_and_lists_them(self):
        xml = JUNIT.format(out="", err="Skipping test in a remote test environment: local only")
        status, output = run("linux", ("junit.xml", xml))
        self.assertEqual(0, status)
        self.assertIn("[allowed] x1", output)

    def test_junit_with_missing_setup_fails(self):
        xml = JUNIT.format(out="Skipping test because it requires a remote test environment.", err="")
        status, output = run("linux", ("junit.xml", xml))
        self.assertEqual(1, status)
        self.assertIn("[missing setup] x1", output)
        self.assertIn("codex-core::all::a", output)

    def test_unknown_skip_fails(self):
        status, output = run("linux", ("junit.xml", JUNIT.format(out="skipping new thing: nope", err="")))
        self.assertEqual(1, status)
        self.assertIn("[unclassified]", output)

    def test_bazel_log_and_xml_of_one_target_count_once(self):
        line = "skipping zsh test: /bin/zsh is unavailable"
        xml = JUNIT.format(out=line, err="")
        status, output = run("linux", ("pkg/t/test.log", line + "\n"), ("pkg/t/test.xml", xml))
        self.assertEqual(1, status)
        self.assertIn("found 1 skip message", output)

    def test_directory_scan_finds_reports(self):
        with tempfile.TemporaryDirectory() as directory:
            nested = Path(directory, "a", "b")
            nested.mkdir(parents=True)
            (nested / "test.log").write_text("skipping zsh test: /bin/zsh is unavailable\n")
            out = io.StringIO()
            with redirect_stdout(out):
                self.assertEqual(1, checker.main(["--platform", "linux", directory]))

    def test_no_reports_fails(self):
        with tempfile.TemporaryDirectory() as directory:
            with redirect_stderr(io.StringIO()):
                self.assertEqual(1, checker.main(["--platform", "linux", directory]))

    def test_clean_output_passes(self):
        status, output = run("linux", ("junit.xml", JUNIT.format(out="all good", err="")))
        self.assertEqual(0, status)
        self.assertIn("found 0 skip", output)


if __name__ == "__main__":
    unittest.main()
