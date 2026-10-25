"""Compare the pinned Windows status command with Bazel's actual status input."""

import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess


def main():
    evidence = Path(os.environ["BASELINE_STATUS_EVIDENCE"])
    evidence.mkdir(parents=True, exist_ok=True)
    expected = (evidence / "upstream-commit").read_text().strip()
    before = subprocess.check_output(["git", "diff", "--binary"])
    assert (
        subprocess.check_output(["git", "rev-parse", "HEAD"]).decode().strip()
        == expected
    )
    assert not subprocess.check_output(["git", "status", "--porcelain"])
    (evidence / "source-before.diff").write_bytes(before)
    package = Path("baseline_workspace_status_probe")
    assert not package.exists()
    records = []

    def run(name, arguments):
        result = subprocess.run(
            arguments, stdout=subprocess.PIPE, stderr=subprocess.STDOUT
        )
        (evidence / f"{name}.log").write_bytes(result.stdout)
        records.append(
            {"phase": name, "arguments": arguments, "exit_code": result.returncode}
        )
        return result

    try:
        package.mkdir()
        (package / "status.bzl").write_text(
            "def _status_impl(ctx):\n"
            "    output = ctx.actions.declare_file(ctx.label.name + '.txt')\n"
            "    ctx.actions.symlink(output = output, target_file = ctx.info_file)\n"
            "    return [DefaultInfo(files = depset([output]))]\n"
            "status = rule(implementation = _status_impl)\n"
        )
        (package / "BUILD.bazel").write_text(
            'load(":status.bzl", "status")\nstatus(name = "status")\n'
        )
        direct = run(
            "direct-cmd",
            [
                "cmd.exe",
                "/S",
                "/E:ON",
                "/V:ON",
                "/D",
                "/C",
                r".\scripts\workspace-status.cmd",
            ],
        )
        checks = {
            "direct_cmd": direct.returncode == 0
            and f"STABLE_GIT_COMMIT {expected}".encode() in direct.stdout
        }
        status = (
            Path(os.environ["BAZEL_OUTPUT_BASE"])
            / "execroot/_main/bazel-out/stable-status.txt"
        )
        for name, extra in [
            ("default", []),
            (
                "explicit-cmd",
                ["--workspace_status_command=./scripts/workspace-status.cmd"],
            ),
            ("default-again", []),
            ("explicit-windows-config", ["--config=windows"]),
            (
                "explicit-unix-command",
                ["--workspace_status_command=./scripts/workspace-status.sh"],
            ),
        ]:
            result = run(
                name,
                [
                    os.environ["BASELINE_BASH"],
                    ".github/scripts/run-bazel-ci.sh",
                    "--",
                    "build",
                    "--announce_rc",
                    "--disk_cache=",
                    *extra,
                    "--",
                    "//baseline_workspace_status_probe:status",
                ],
            )
            contents = status.read_bytes() if status.exists() else b""
            (evidence / f"{name}-stable-status.txt").write_bytes(contents)
            checks[name] = (
                result.returncode == 0
                and f"STABLE_GIT_COMMIT {expected}\n".encode() in contents
            )
            records[-1]["status_sha256"] = hashlib.sha256(contents).hexdigest()
    finally:
        shutil.rmtree(package)
        after = subprocess.check_output(["git", "diff", "--binary"])
        (evidence / "source-after.diff").write_bytes(after)
        (evidence / "commands.json").write_text(json.dumps(records, indent=2) + "\n")
        assert before == after
        assert not subprocess.check_output(["git", "status", "--porcelain"])
    (evidence / "verification.json").write_text(json.dumps(checks, indent=2) + "\n")
    print(json.dumps(checks, indent=2))
    if not all(checks.values()):
        raise SystemExit(1)


if __name__ == "__main__":
    main()
