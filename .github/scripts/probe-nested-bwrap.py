"""Compare helper-only and bundled-bubblewrap fixtures on a native Linux host."""

import argparse
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--helper", type=Path, required=True)
    parser.add_argument("--outer-bwrap", type=Path, required=True)
    parser.add_argument("--bundled-bwrap", type=Path, required=True)
    parser.add_argument("--temporary-parent", type=Path, required=True)
    parser.add_argument("--evidence", type=Path, required=True)
    args = parser.parse_args()
    args.evidence.mkdir(parents=True, exist_ok=True)

    profile = {
        "type": "managed",
        "file_system": {
            "type": "restricted",
            "entries": [
                {
                    "path": {"type": "special", "value": {"kind": "root"}},
                    "access": "write",
                },
                {
                    "path": {"type": "path", "path": "/private"},
                    "access": "deny",
                },
            ],
        },
        "network": "enabled",
    }
    results = []
    with tempfile.TemporaryDirectory(
        prefix="nested-bwrap-", dir=args.temporary_parent
    ) as directory:
        fixture = Path(directory)
        shutil.copy2(args.helper, fixture / "sandbox")
        (fixture / "private").mkdir()
        command = [
            str(args.outer_bwrap),
            "--unshare-user",
            "--uid",
            "0",
            "--gid",
            "0",
            "--die-with-parent",
            "--tmpfs",
            "/",
            "--ro-bind",
            "/usr",
            "/usr",
            "--ro-bind",
            "/etc",
            "/etc",
            "--proc",
            "/proc",
            "--dev",
            "/dev",
        ]
        for name in ("/bin", "/sbin", "/lib", "/lib64"):
            path = Path(name)
            if path.is_symlink():
                command.extend(("--symlink", os.readlink(path), name))
            elif path.exists():
                command.extend(("--ro-bind", name, name))
        command.extend(
            (
                "--bind",
                directory,
                "/tmp",
                "--bind",
                str(fixture / "private"),
                "/private",
                "--chdir",
                "/tmp",
                "--setenv",
                "TMPDIR",
                "/tmp",
                "--setenv",
                "PATH",
                "/usr/bin:/bin",
                "--",
                "/tmp/sandbox",
                "--sandbox-policy-cwd",
                "/tmp",
                "--permission-profile",
                json.dumps(profile),
                "--",
                "/bin/true",
            )
        )

        for variant in ("helper-only", "with-bundled-bwrap"):
            if variant == "with-bundled-bwrap":
                shutil.copy2(args.bundled_bwrap, fixture / "bwrap")
            result = subprocess.run(command, capture_output=True, text=True)
            (args.evidence / f"{variant}.stdout").write_text(result.stdout)
            (args.evidence / f"{variant}.stderr").write_text(result.stderr)
            results.append({"variant": variant, "exit_code": result.returncode})
            print(json.dumps(results[-1]), flush=True)

    (args.evidence / "results.json").write_text(json.dumps(results, indent=2) + "\n")
    original_stderr = (args.evidence / "helper-only.stderr").read_text()
    missing_bwrap_reproduced = "bubblewrap is unavailable" in original_stderr
    bundled_fixture_succeeded = results[1]["exit_code"] == 0
    if not missing_bwrap_reproduced:
        raise SystemExit(
            "The original missing-bubblewrap failure did not reproduce; inspect evidence."
        )
    if not bundled_fixture_succeeded:
        raise SystemExit(
            "Adding bundled bubblewrap did not resolve the probe; inspect evidence."
        )


if __name__ == "__main__":
    main()
