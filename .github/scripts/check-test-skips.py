#!/usr/bin/env python3
"""Fail a CI run when a test skipped itself because the runner lacks required setup.

The checked tests print a skip message and return successfully, so a missing remote environment, shell, sandbox
prerequisite or helper binary never fails the test run. This script scans the output that passing tests produced
(nextest JUnit reports, or Bazel test.xml and test.log files) and classifies every skip message.

A skip passes only if an explicit allowlist rule matches it; each rule names the reason the skip is by design. A
skip that matches a rule for a known missing setup, or that matches no rule, fails the check. Run it in a step
that follows the tests so the test results are still reported.

Usage: check-test-skips.py --platform {linux,macos,windows} PATH...
Each PATH is a JUnit XML file, a Bazel test.xml or test.log file, or a directory searched for junit.xml,
test.xml and test.log files.
"""

import argparse
import collections
import os
import re
import sys
import xml.etree.ElementTree as ElementTree
from dataclasses import dataclass
from pathlib import Path

ALLOW = "allowed"
MISSING = "missing setup"
UNKNOWN = "unclassified"

ANSI_ESCAPE = re.compile(r"\x1b\[[0-9;]*[A-Za-z]")
# A skip message starts the line, follows "; " or ", " ("python not found; skipping ...") or is one of the
# macro-produced messages. Log lines from product code (tracing output) also say "skipping" and are not test skips.
SKIP_LINE = re.compile(r"^\s*skipp(?:ing|ed)\b|[;,]\s+skipping\b", re.IGNORECASE)
TRACING_LINE = re.compile(r"^\s*(?:\S+\s+)?(?:ERROR|WARN|INFO|DEBUG|TRACE)\b")
LIBTEST_LINE = re.compile(r"^test \S+ \.\.\. ")


@dataclass(frozen=True)
class Rule:
    """Maps skip messages to a verdict; `platforms` limits the rule to those hosts (None means all)."""

    pattern: re.Pattern
    verdict: str
    reason: str
    platforms: tuple = None

    def applies(self, platform):
        return self.platforms is None or platform in self.platforms


def rule(pattern, verdict, reason, platforms=None):
    return Rule(re.compile(pattern, re.IGNORECASE), verdict, reason, platforms)


# Order matters: the first matching applicable rule decides. Missing-setup rules precede allowlist rules that
# could overlap them.
RULES = [
    # WSL tests mention bwrap, so this rule must precede the missing-setup rules.
    rule(r"WSL|WSLg", ALLOW, "WSL-only test; the runner is not WSL"),
    # Missing setup: these must never fire on a correctly prepared runner.
    rule(r"requires a remote test environment", MISSING,
         "remote test environment (Docker exec-server) was not set up"),
    rule(r"zsh", MISSING, "zsh is not installed"),
    rule(r"Bash or Zsh executor", MISSING, "zsh is not installed"),
    rule(r"python3? (?:not found|is unavailable)|python not found", MISSING, "python is not installed",
         ("linux", "macos")),
    rule(r"git or python3 is unavailable", MISSING, "git or python3 is not installed"),
    rule(r"bwrap|bubblewrap|user/mount namespaces|unprivileged user namespaces|/dev/shm|unshare is unavailable"
         r"|namespaced proc mount|sandbox proc is unavailable|skipping sandbox test|Landlock", MISSING,
         "bubblewrap, user namespaces or another Linux sandbox prerequisite is missing", ("linux",)),
    rule(r"managed proxy", MISSING, "managed-proxy prerequisite (bubblewrap, python3 or a socket) is missing",
         ("linux",)),
    rule(r"(?:binary|server|helper)[^,;]* (?:not available|not found|unavailable)|could not locate binary", MISSING,
         "a helper binary or test server was not built or exported"),
    rule(r"native spawning is unavailable", MISSING, "native process spawning is unavailable"),
    rule(r"setsid not available", MISSING, "setsid is not installed"),
    # By design on every platform.
    rule(r"Skipping test in a remote test environment", ALLOW,
         "skip_if_remote: the test cannot run against the remote environment and is deliberately skipped there"),
    rule(r"Skipping test in the Wine-exec test environment", ALLOW,
         "skip_if_wine_exec: Wine-exec exists only under Bazel and the test is excluded there by design"),
    rule(r"Skipping test in a Windows target environment", ALLOW,
         "skip_if_target_windows: the test does not apply to a Windows target"),
    rule(r"network is disabled", ALLOW,
         "skip_if_no_network: set only when Codex sandboxes itself, which CI does not do"),
    rule(r"is set to 'seatbelt'", ALLOW,
         "skip_if_sandbox: set only when Codex sandboxes itself, which CI does not do"),
    rule(r"OPENAI_API_KEY not set", ALLOW, "live-API tests need a key that CI never has"),
    rule(r"tmux is unavailable", ALLOW, "the resize smoke test is #[ignore]d upstream"),
    rule(r"Skipping test because it cannot execute on Windows", ALLOW, "test does not apply to Windows"),
    rule(r"Linux symlink grant regression on another host OS", ALLOW, "test applies only to a Linux host"),
    rule(r"bundled binaries require a release digest", ALLOW,
         "the test covers the system-bwrap fallback, which a build with a bundled bwrap does not take"),
    rule(r"already in use|failed to bind unix socket|no alternate supplementary group", ALLOW,
         "host-dependent resource (fixed port, socket path or group) is unavailable"),
    rule(r"procfs is unavailable|pidfd_open is unavailable", ALLOW,
         "host kernel feature is unavailable; the skip is logged for review"),
    # By design on one platform.
    rule(r"nested Seatbelt (?:is )?unavailable", ALLOW,
         "the test runs inside an outer Seatbelt sandbox and cannot nest another", ("macos",)),
    rule(r"python not found|python3? not found", ALLOW,
         "the Windows runner image has no python under the expected name", ("windows",)),
    rule(r"Windows symlink privilege unavailable|Windows symlink apply_patch sandbox test"
         r"|cannot create restricted tokens|PowerShell 7 is not installed|native MXC is unavailable"
         r"|ConPTY", ALLOW,
         "the hosted Windows runner lacks the privilege or component the test needs", ("windows",)),
]


@dataclass(frozen=True)
class Skip:
    source: str
    test: str
    line: str


def classify(line, platform):
    for candidate in RULES:
        if candidate.applies(platform) and candidate.pattern.search(line):
            return candidate.verdict, candidate.reason
    return UNKNOWN, "no allowlist or missing-setup rule matches this skip"


def skip_lines(text):
    for raw in text.splitlines():
        line = ANSI_ESCAPE.sub("", raw).strip()
        if not line or LIBTEST_LINE.match(line) or TRACING_LINE.match(line):
            continue
        if SKIP_LINE.search(line):
            yield line


def scan_xml(path):
    try:
        root = ElementTree.parse(path).getroot()
    except ElementTree.ParseError as error:
        raise SystemExit(f"Cannot parse {path}: {error}")
    for case in root.iter("testcase"):
        name = f"{case.get('classname', '')}::{case.get('name', '')}".strip(":")
        for stream in case:
            if stream.tag in ("system-out", "system-err") and stream.text:
                for line in skip_lines(stream.text):
                    yield Skip(str(path), name, line)
    # Bazel puts the whole log in a suite-level stream.
    for suite in root.iter("testsuite"):
        for stream in suite:
            if stream.tag in ("system-out", "system-err") and stream.text:
                for line in skip_lines(stream.text):
                    yield Skip(str(path), suite.get("name", ""), line)


def scan_text(path):
    text = Path(path).read_text(errors="replace")
    for line in skip_lines(text):
        yield Skip(str(path), "", line)


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


def collect(paths):
    """Returns the files scanned and the distinct skips; a Bazel target's test.log and test.xml repeat each other."""
    files = []
    found = {}
    for path in report_files(paths):
        files.append(path)
        scanner = scan_xml if path.suffix == ".xml" else scan_text
        for skip in scanner(path):
            # The same target's test.log and test.xml carry the same line; key by the target directory.
            if path.name in ("test.log", "test.xml"):
                key = (str(path.parent), skip.line)
            else:
                key = (str(path), skip.test, skip.line)
            found.setdefault(key, skip)
    return files, list(found.values())


def summarize(skips, platform):
    groups = collections.OrderedDict()
    for skip in skips:
        verdict, reason = classify(skip.line, platform)
        key = (verdict, re.sub(r"\d+", "N", skip.line), reason)
        groups.setdefault(key, []).append(skip)
    return groups


def render(groups, files, skips):
    lines = [f"Scanned {len(files)} report file(s); found {len(skips)} skip message(s)."]
    for verdict in (MISSING, UNKNOWN, ALLOW):
        for (found_verdict, message, reason), members in groups.items():
            if found_verdict != verdict:
                continue
            lines.append(f"[{verdict}] x{len(members)} {message}")
            lines.append(f"    reason: {reason}")
            for member in members[:3]:
                where = member.test or os.path.relpath(member.source)
                lines.append(f"    e.g. {where}")
    return lines


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--platform", required=True, choices=("linux", "macos", "windows"))
    parser.add_argument("paths", nargs="+")
    arguments = parser.parse_args(argv)

    files, skips = collect(arguments.paths)
    if not files:
        print("No test output was found to scan; the tests must keep their output.", file=sys.stderr)
        return 1
    groups = summarize(skips, arguments.platform)
    lines = render(groups, files, skips)
    failing = sum(len(m) for (verdict, _, _), m in groups.items() if verdict != ALLOW)
    lines.append(f"{failing} skip message(s) point to missing setup or are unclassified.")
    print("\n".join(lines))
    summary = os.environ.get("GITHUB_STEP_SUMMARY")
    if summary:
        with open(summary, "a", encoding="utf-8") as handle:
            handle.write("### Test skip check\n\n```\n" + "\n".join(lines) + "\n```\n")
    return 1 if failing else 0


if __name__ == "__main__":
    sys.exit(main())
