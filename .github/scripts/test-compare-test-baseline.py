#!/usr/bin/env python3
"""Unit tests for compare-test-baseline.py."""

import importlib.util
import io
import json
import tempfile
import unittest
from contextlib import redirect_stderr, redirect_stdout
from pathlib import Path

SPEC = importlib.util.spec_from_file_location("compare_test_baseline",
                                              Path(__file__).with_name("compare-test-baseline.py"))
comparer = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(comparer)

COMMIT = "a" * 40


def junit(*cases):
    body = ""
    for name, outcome in cases:
        child = {
            "pass": "",
            "fail": '<failure message="the request timed out" type="test failure with exit code 101"/>',
            "timeout": '<failure message="x" type="test timeout"/>',
            "error": '<error message="exec failed" type="test exec fail"/>',
            "flaky": '<flakyFailure message="first try"/>',
            "skip": "<skipped/>",
        }[outcome]
        body += f'<testcase name="{name}" classname="codex-core::all">{child}</testcase>'
    return f'<?xml version="1.0"?><testsuites><testsuite name="codex-core::all">{body}</testsuite></testsuites>'


class Workspace:
    def __init__(self):
        self.directory = tempfile.TemporaryDirectory()
        self.root = Path(self.directory.name)

    def write(self, name, content):
        path = self.root / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(content)
        return str(path)

    def close(self):
        self.directory.cleanup()


def run(*arguments):
    out, err = io.StringIO(), io.StringIO()
    with redirect_stdout(out), redirect_stderr(err):
        try:
            code = comparer.main(list(arguments))
        except SystemExit as exit_request:
            code = exit_request.code
    return code, out.getvalue(), err.getvalue()


class CompareTests(unittest.TestCase):
    def setUp(self):
        self.workspace = Workspace()
        self.addCleanup(self.workspace.close)
        self.baselines = str(self.workspace.root / "baselines")

    def record(self, platform, report_xml, runner="nextest", name="unpatched.xml"):
        path = self.workspace.write(name, report_xml)
        return run("--platform", platform, "--upstream-commit", COMMIT, "--baseline-dir", self.baselines,
                   "--runner", runner, "--write-baseline", path)

    def compare(self, platform, report_xml, runner="nextest", name="patched.xml"):
        path = self.workspace.write(name, report_xml)
        return run("--platform", platform, "--upstream-commit", COMMIT, "--baseline-dir", self.baselines,
                   "--runner", runner, path)

    def test_write_then_compare_finds_new_fixed_and_unchanged(self):
        code, _, _ = self.record("linux", junit(("a", "fail"), ("b", "timeout"), ("c", "pass"), ("d", "fail")))
        self.assertEqual(code, 0)
        data = json.loads((Path(self.baselines) / COMMIT / "linux.json").read_text())
        self.assertEqual(data["failed"], ["codex-core::all a", "codex-core::all d"])
        self.assertEqual(data["timed_out"], ["codex-core::all b"])
        code, out, _ = self.compare("linux", junit(("a", "fail"), ("b", "pass"), ("c", "fail"), ("d", "skip"),
                                                    ("e", "pass")))
        self.assertEqual(code, 1)
        self.assertIn("[new (fail the run)] 1\n    codex-core::all c", out)
        self.assertIn("[fixed relative to the baseline] 1\n    codex-core::all b", out)
        self.assertIn("[unchanged baseline failures] 1\n    codex-core::all a", out)
        self.assertIn("[in the baseline but not run] 1\n    codex-core::all d", out)

    def test_test_case_numbers_do_not_change_a_test_id(self):
        # test-case 3.4 prefixes each generated case with its number; 3.3 kept a leading underscore instead.
        self.record("linux", junit(("f::direct", "fail"), ("g::_mcp_expects", "fail"), ("h::plain", "fail"),
                                   ("k::code_mode", "pass")))
        code, out, _ = self.compare("linux", junit(("f::test_case_1_direct", "fail"),
                                                    ("g::test_case_2_mcp_expects", "fail"),
                                                    ("h::test_case_1_plain", "pass"),
                                                    ("k::test_case_2_code_mode", "fail")))
        self.assertEqual(code, 1)
        self.assertIn("[new (fail the run)] 1\n    codex-core::all k::test_case_2_code_mode", out)
        self.assertIn("[fixed relative to the baseline] 1\n    codex-core::all h::plain", out)
        self.assertIn("[unchanged baseline failures] 2\n    codex-core::all f::test_case_1_direct\n"
                      "    codex-core::all g::test_case_2_mcp_expects", out)
        self.assertIn("[in the baseline but not run] 0", out)

    def test_related_platform_failures_are_not_new(self):
        # A patch that adds Bazel targets can move a test into another shard than the one that recorded its failure.
        self.record("shard-1", junit(("a", "fail"), ("c", "fail")), name="one.xml")
        self.record("shard-2", junit(("b", "fail"), ("d", "fail")), name="two.xml")
        path = self.workspace.write("patched.xml", junit(("a", "fail"), ("b", "fail"), ("d", "pass")))
        code, out, _ = run("--platform", "shard-1", "--related-platform", "shard-2", "--upstream-commit", COMMIT,
                           "--baseline-dir", self.baselines, path)
        self.assertEqual(code, 0)
        self.assertIn("[new (fail the run)] 0", out)
        self.assertIn("[fixed relative to the baseline] 1\n    codex-core::all d", out)
        self.assertIn("[unchanged baseline failures] 2\n    codex-core::all a\n    codex-core::all b", out)
        self.assertIn("[in the baseline but not run] 1\n    codex-core::all c\n", out)

    def test_a_missing_related_baseline_is_exit_two(self):
        self.record("shard-1", junit(("a", "fail")))
        code, _, err = self.compare_related("shard-1", "shard-9")
        self.assertEqual(code, 2)
        self.assertIn("shard-9.json", err)

    def compare_related(self, platform, related):
        path = self.workspace.write("patched.xml", junit(("a", "fail")))
        return run("--platform", platform, "--related-platform", related, "--upstream-commit", COMMIT,
                   "--baseline-dir", self.baselines, path)

    def test_baseline_failures_alone_pass(self):
        self.record("linux", junit(("a", "fail"), ("b", "pass")))
        code, out, _ = self.compare("linux", junit(("a", "fail"), ("b", "pass")))
        self.assertEqual(code, 0)
        self.assertIn("[new (fail the run)] 0", out)

    def test_a_timeout_that_was_a_failure_is_unchanged(self):
        self.record("linux", junit(("a", "fail")))
        code, _, _ = self.compare("linux", junit(("a", "timeout")))
        self.assertEqual(code, 0)

    def test_exec_errors_count_as_failures(self):
        self.record("linux", junit(("a", "pass")))
        code, out, _ = self.compare("linux", junit(("a", "error")))
        self.assertEqual(code, 1)
        self.assertIn("codex-core::all a", out)

    def test_flaky_retries_that_passed_do_not_fail(self):
        self.record("linux", junit(("a", "pass")))
        code, _, _ = self.compare("linux", junit(("a", "flaky")))
        self.assertEqual(code, 0)

    def test_missing_baseline_is_exit_two(self):
        code, _, err = self.compare("windows", junit(("a", "pass")))
        self.assertEqual(code, 2)
        self.assertIn("--write-baseline", err)

    def test_no_reports_fails(self):
        empty = str(self.workspace.root / "empty")
        Path(empty).mkdir()
        code, _, err = run("--platform", "linux", "--upstream-commit", COMMIT, empty)
        self.assertEqual(code, 1)
        self.assertIn("No test results", err)

    def test_platforms_keep_separate_baselines(self):
        self.record("linux", junit(("a", "fail")))
        self.record("macos", junit(("b", "fail")), name="mac.xml")
        code, _, _ = self.compare("macos", junit(("b", "fail"), ("a", "fail")))
        self.assertEqual(code, 1)
        code, _, _ = self.compare("linux", junit(("a", "fail")))
        self.assertEqual(code, 0)

    def test_output_overrides_the_baseline_path(self):
        path = self.workspace.write("one.xml", junit(("a", "fail")))
        target = str(self.workspace.root / "out" / "baseline.json")
        code, _, _ = run("--platform", "linux", "--upstream-commit", COMMIT, "--write-baseline", "--output", target,
                         path)
        self.assertEqual(code, 0)
        self.assertEqual(json.loads(Path(target).read_text())["failed"], ["codex-core::all a"])

    def test_directories_are_searched_for_reports(self):
        self.record("linux", junit(("a", "fail")))
        self.workspace.write("run/nextest/default/junit.xml", junit(("a", "fail")))
        code, _, _ = run("--platform", "linux", "--upstream-commit", COMMIT, "--baseline-dir", self.baselines,
                         str(self.workspace.root / "run"))
        self.assertEqual(code, 0)

    def test_bazel_target_and_case_failures(self):
        xml = ('<testsuites><testsuite name="x"><testcase name="//a:b" classname="x"><failure message="m"/>'
               '</testcase></testsuite></testsuites>')
        log = "running 2 tests\ntest suite::one ... ok\ntest suite::two ... FAILED\n"
        root = "bazel-testlogs"
        self.workspace.write(f"{root}/a/b/test.xml", xml)
        self.workspace.write(f"{root}/a/b/test.log", log)
        self.workspace.write(f"{root}/c/d/shard_1_of_2/test.xml",
                             '<testsuites><testsuite name="x"><testcase name="//c:d" classname="x"/></testsuite>'
                             "</testsuites>")
        directory = str(self.workspace.root / root)
        code, _, _ = run("--platform", "linux", "--runner", "bazel", "--upstream-commit", COMMIT,
                         "--baseline-dir", self.baselines, "--write-baseline", directory)
        self.assertEqual(code, 0)
        data = json.loads((Path(self.baselines) / COMMIT / "linux.json").read_text())
        self.assertEqual(data["failed"], ["//a:b", "//a:b suite::two"])
        self.assertEqual(data["tests_executed"], 3)

    def test_bazel_label_removes_the_shard_directory(self):
        self.assertEqual(comparer.bazel_label("x/testlogs/codex-rs/cli/cli-test/shard_3_of_16/test.xml"),
                         "//codex-rs/cli:cli-test")
        self.assertEqual(comparer.bazel_label("x/testlogs/codex-rs/cli/cli-test/test.log"),
                         "//codex-rs/cli:cli-test")

    def test_ansi_escapes_do_not_hide_bazel_failures(self):
        path = self.workspace.write("testlogs/a/b/test.log", "test x ... \x1b[31mFAILED\x1b[0m\n")
        report = comparer.scan_bazel_log(path, "//a:b")
        self.assertEqual(report.failed, {"//a:b x"})


if __name__ == "__main__":
    unittest.main()
