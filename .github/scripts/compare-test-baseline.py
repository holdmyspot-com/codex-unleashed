#!/usr/bin/env python3
"""Compare a patched test run with the failures of one unpatched run, or record that unpatched run.

A baseline is a committed JSON file at <baseline-dir>/<upstream-commit>/<platform>.json. It lists the test IDs that
failed or timed out in a single run of the unpatched upstream commit on that platform. The comparison reads the same
kind of report from a patched run and prints:

  new       failing or timed out now, absent from the baseline (the run fails on these)
  fixed     in the baseline and executed without failing now
  unchanged in the baseline and failing now
  not run   in the baseline but absent from the patched report, so nothing is known about them

Usage:
  compare-test-baseline.py --platform P [--related-platform R]... --upstream-commit C [--runner nextest|bazel]
                           [--baseline-dir D] PATH...
  compare-test-baseline.py --platform P --upstream-commit C --write-baseline [--output FILE] PATH...

Each PATH is a nextest JUnit XML file, a Bazel test.xml or test.log file, or a directory searched for junit.xml,
test.xml and test.log files. The platform is any label that names a file; the script does not interpret it.

Test IDs. A nextest ID is "<binary-id> <test name>", as nextest prints it. A Bazel ID is the target label
("//package:target") for a target-level failure, and "<label> <test name>" for each "test <name> ... FAILED" line in
the target's test.log. Timeouts keep their ID and are listed under timed_out.
The comparison matches IDs without test-case's case numbers (`test_case_<n>_`), which test-case 3.4 added, so a
baseline that names a case `::direct` still matches `::test_case_1_direct`.

Each --related-platform names another baseline of the same run, such as another shard of a sharded Bazel job. Its
failures are not new here, because adding targets can move a test into a different shard than the one that recorded
it; a related failure that runs here is fixed or unchanged. Only the --platform baseline is checked for tests not run.

Exit status: 1 if any test is new, or if no report was found; 2 for a missing baseline; 0 otherwise.
"""

import argparse
import json
import os
import re
import sys
import xml.etree.ElementTree as ElementTree
from pathlib import Path

ANSI_ESCAPE = re.compile(r"[\x1b�?]\[[0-9;]*[A-Za-z]")
LIBTEST_FAILED = re.compile(r"^test (\S+) \.\.\. FAILED\s*$")
FAILURE_TAGS = ("failure", "error")
TIMEOUT = re.compile(r"time(?:d)?[ -]?out", re.IGNORECASE)
TEST_CASE_PREFIX = re.compile(r"(?<=::)(?:test_case_[0-9]+_)?_*")


class Report:
    """The tests one run executed and the ones that failed or timed out."""

    def __init__(self):
        self.executed = set()
        self.failed = set()
        self.timed_out = set()

    def add(self, test, failed=False, timed_out=False):
        self.executed.add(test)
        if timed_out:
            self.timed_out.add(test)
        elif failed:
            self.failed.add(test)

    @property
    def failing(self):
        return self.failed | self.timed_out


def bazel_label(path, root_names=("testlogs", "bazel-testlogs")):
    """Derives "//package:target" from <...>/testlogs/<package>/<target>[/shard_N_of_M]/test.*; None if absent."""
    parts = list(Path(path).parent.parts)
    for index in range(len(parts) - 1, -1, -1):
        if parts[index] in root_names:
            parts = parts[index + 1:]
            break
    else:
        return None
    while parts and re.fullmatch(r"shard_\d+_of_\d+|run_\d+_of_\d+|attempt_\d+", parts[-1]):
        parts = parts[:-1]
    if len(parts) < 2:
        return None
    return "//" + "/".join(parts[:-1]) + ":" + parts[-1]


def junit_case_failure(case):
    """Returns (failed, timed_out) for one JUnit testcase; flaky and rerun records do not count as failures."""
    for child in case:
        if child.tag in FAILURE_TAGS:
            # Only the type names a timeout; failure messages quote arbitrary test output.
            return True, bool(TIMEOUT.search(child.get("type") or ""))
    return False, False


def scan_junit(path, label=None):
    report = Report()
    try:
        root = ElementTree.parse(path).getroot()
    except ElementTree.ParseError as error:
        raise SystemExit(f"Cannot parse {path}: {error}")
    for case in root.iter("testcase"):
        name = case.get("name", "")
        classname = case.get("classname", "")
        if label is not None:
            # Bazel writes one testcase per target, named by its label.
            test = label
        else:
            test = f"{classname} {name}".strip()
        # A case a harness skipped did not run.
        if any(child.tag == "skipped" for child in case):
            continue
        failed, timed_out = junit_case_failure(case)
        report.add(test, failed, timed_out)
    return report


def scan_bazel_log(path, label):
    report = Report()
    text = ANSI_ESCAPE.sub("", Path(path).read_text(errors="replace"))
    for raw in text.splitlines():
        match = LIBTEST_FAILED.match(raw.strip())
        if match:
            report.add(f"{label} {match.group(1)}", failed=True)
    return report


def report_files(paths):
    for entry in paths:
        entry = Path(entry)
        if entry.is_dir():
            for name in ("junit.xml", "test.xml", "test.log"):
                yield from sorted(entry.rglob(name))
        elif entry.is_file():
            yield entry
        else:
            raise SystemExit(f"No such file or directory: {entry}")


def collect(paths, runner):
    files = []
    report = Report()
    for path in report_files(paths):
        files.append(path)
        if runner == "bazel":
            label = bazel_label(path) or str(path.parent)
            if path.suffix == ".xml":
                part = scan_junit(path, label)
            else:
                part = scan_bazel_log(path, label)
        else:
            if path.suffix != ".xml":
                continue
            part = scan_junit(path)
        report.executed |= part.executed
        report.failed |= part.failed
        report.timed_out |= part.timed_out
    return files, report


def baseline_path(directory, commit, platform):
    return Path(directory) / commit / f"{platform}.json"


def write_baseline(path, commit, platform, runner, report):
    data = {
        "upstream_commit": commit,
        "platform": platform,
        "runner": runner,
        "tests_executed": len(report.executed),
        "failed": sorted(report.failed),
        "timed_out": sorted(report.timed_out),
    }
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(data, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    return data


def read_baseline(path):
    data = json.loads(Path(path).read_text(encoding="utf-8"))
    return set(data.get("failed", [])) | set(data.get("timed_out", []))


def matching_key(test):
    """Return the form of a test ID that the comparison matches on.

    test-case 3.4 names each generated case `test_case_<n>_<name>`, where 3.3 named it `<name>`, keeping a leading
    underscore when the name began with one. The patch queue upgrades test-case, so a baseline recorded on an upstream
    commit that uses 3.3 names the same cases differently. The key drops that prefix and any leading underscores of
    each `::` segment. It cannot join two different cases: 3.3 rejects two cases of one test with the same name.
    """
    return TEST_CASE_PREFIX.sub("", test)


def compare(baseline, report, related=frozenset()):
    """Sort the tests into new, fixed, unchanged and not run, listing failing tests by their names in the report.

    `related` holds the failures of related baselines: they count as known failures, but only `baseline` is checked
    for tests that did not run.
    """
    failing = {matching_key(test): test for test in report.failing}
    executed = {matching_key(test) for test in report.executed}
    own = {matching_key(test): test for test in baseline}
    recorded = {matching_key(test): test for test in related} | own
    new = sorted(test for key, test in failing.items() if key not in recorded)
    fixed = sorted(test for key, test in recorded.items() if key in executed and key not in failing)
    unchanged = sorted(failing[key] for key in recorded if key in failing)
    not_run = sorted(test for key, test in own.items() if key not in executed)
    return new, fixed, unchanged, not_run


def render(sections, files, report):
    new, fixed, unchanged, not_run = sections
    lines = [f"Scanned {len(files)} report file(s): {len(report.executed)} test(s) executed, "
             f"{len(report.failing)} failing or timed out."]
    for title, tests in (("new (fail the run)", new), ("fixed relative to the baseline", fixed),
                         ("unchanged baseline failures", unchanged), ("in the baseline but not run", not_run)):
        lines.append(f"[{title}] {len(tests)}")
        for test in tests:
            lines.append(f"    {test}")
    return lines


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--platform", required=True)
    parser.add_argument("--related-platform", action="append", default=[],
                        help="another baseline of the same run whose failures are not new here; repeatable")
    parser.add_argument("--upstream-commit", required=True)
    parser.add_argument("--runner", choices=("nextest", "bazel"), default="nextest")
    parser.add_argument("--baseline-dir", default="docs/test-baselines")
    parser.add_argument("--write-baseline", action="store_true",
                        help="record the failures in these reports as the baseline instead of comparing")
    parser.add_argument("--output", help="with --write-baseline, the file to write instead of the committed path")
    parser.add_argument("paths", nargs="+")
    arguments = parser.parse_args(argv)

    files, report = collect(arguments.paths, arguments.runner)
    if not files or not report.executed:
        print("No test results were found to compare; the tests must keep their reports.", file=sys.stderr)
        return 1
    if arguments.write_baseline:
        target = Path(arguments.output) if arguments.output else baseline_path(
            arguments.baseline_dir, arguments.upstream_commit, arguments.platform)
        data = write_baseline(target, arguments.upstream_commit, arguments.platform, arguments.runner, report)
        print(f"Wrote {target}: {data['tests_executed']} executed, {len(data['failed'])} failed, "
              f"{len(data['timed_out'])} timed out.")
        return 0

    paths = [baseline_path(arguments.baseline_dir, arguments.upstream_commit, platform)
             for platform in [arguments.platform, *arguments.related_platform]]
    for path in paths:
        if not path.is_file():
            print(f"No baseline at {path}; record one from an unpatched run with --write-baseline.", file=sys.stderr)
            return 2
    related = set().union(*(read_baseline(path) for path in paths[1:]))
    sections = compare(read_baseline(paths[0]), report, related)
    lines = render(sections, files, report)
    print("\n".join(lines))
    summary = os.environ.get("GITHUB_STEP_SUMMARY")
    if summary:
        with open(summary, "a", encoding="utf-8") as handle:
            handle.write("### Test baseline comparison\n\n```\n" + "\n".join(lines) + "\n```\n")
    return 1 if sections[0] else 0


if __name__ == "__main__":
    sys.exit(main())
