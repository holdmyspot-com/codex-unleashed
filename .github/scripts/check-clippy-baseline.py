#!/usr/bin/env python3
"""Accept a failed Bazel Clippy run whose only errors are classified upstream diagnostics.

Usage: check-clippy-baseline.py <bazel-clippy.log> <exit-code> <baseline.json|missing> <report-path>

The baseline lists diagnostics as "path:line: message", where path is relative to codex-rs/ and uses forward
slashes. Exits 0 when Bazel succeeded, or when Bazel reported only failed Clippy actions (exit code 1 under
--keep_going) and every error diagnostic is in the baseline. Otherwise exits with Bazel's code, or 1.
"""
import json
import re
import sys

ANSI = re.compile(r"\x1b\[[0-9;]*m")
ERROR = re.compile(r"^error(?:\[\w+\])?: (.+)$")
LOCATION = re.compile(r"^\s*--> (.+?):(\d+):\d+$")
BAZEL_ERROR = re.compile(r"^ERROR: (.*)$")
CLIPPY_ACTION = re.compile(r"Clippy (//\S+) failed")
SUMMARY = re.compile(r"^error: aborting due to|^error: could not compile")


def normalize(path):
    path = path.replace("\\", "/")
    return path[len("codex-rs/"):] if path.startswith("codex-rs/") else path


def main():
    log_path, code_text, baseline_path, report_path = sys.argv[1:5]
    code = int(code_text)
    if code == 0:
        open(report_path, "w").write("result=pass\n")
        return 0
    lines = [ANSI.sub("", line.rstrip("\n")) for line in open(log_path, errors="replace")]
    found, other, actions, pending = set(), [], set(), None
    for line in lines:
        bazel = BAZEL_ERROR.match(line)
        if bazel:
            action = CLIPPY_ACTION.search(bazel.group(1))
            if action:
                actions.add(action.group(1))
            elif not bazel.group(1).startswith("Build did NOT complete successfully"):
                other.append(line)
            continue
        error = ERROR.match(line)
        if error and not SUMMARY.match(line):
            pending = error.group(1)
            continue
        location = LOCATION.match(line)
        if location and pending is not None:
            found.add(f"{normalize(location.group(1))}:{location.group(2)}: {pending}")
            pending = None
    accepted = set()
    if baseline_path != "missing":
        accepted = set(json.load(open(baseline_path))["accepted"])
    unexpected = sorted(found - accepted)
    report = [f"bazel_exit={code}", f"failed_clippy_actions={len(actions)}"]
    report += [f"accepted={d}" for d in sorted(found & accepted)]
    report += [f"unexpected={d}" for d in unexpected]
    report += [f"bazel_error={line}" for line in other]
    ok = code == 1 and actions and found and not unexpected and not other
    report.append(f"result={'accepted-upstream' if ok else 'fail'}")
    open(report_path, "w").write("\n".join(report) + "\n")
    print("\n".join(report))
    return 0 if ok else (code or 1)


if __name__ == "__main__":
    sys.exit(main())
