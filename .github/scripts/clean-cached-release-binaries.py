#!/usr/bin/env python3

"""Invalidate cached Cargo outputs for release binaries while retaining dependencies."""

import json
import os
from pathlib import Path
import subprocess
import sys


def main() -> int:
    if len(sys.argv) < 4:
        print("usage: clean-cached-release-binaries.py <workspace> <target> <binary>...", file=sys.stderr)
        return 2

    workspace = Path(sys.argv[1]).resolve()
    target = sys.argv[2]
    binaries = set(sys.argv[3:])
    manifest = workspace / "Cargo.toml"
    cargo = os.environ.get("CARGO", "cargo")

    metadata = json.loads(
        subprocess.check_output(
            [cargo, "metadata", "--no-deps", "--format-version", "1", "--manifest-path", str(manifest)],
            text=True,
        )
    )
    owners = {
        binary: package["name"]
        for package in metadata["packages"]
        for target_info in package["targets"]
        if "bin" in target_info["kind"]
        for binary in [target_info["name"]]
        if binary in binaries
    }
    missing = sorted(binaries - owners.keys())
    if missing:
        print(f"Release binaries absent from Cargo metadata: {', '.join(missing)}", file=sys.stderr)
        return 1

    command = [cargo, "clean", "--release", "--target", target, "--manifest-path", str(manifest)]
    for package in sorted(set(owners.values())):
        command.extend(["-p", package])
    subprocess.run(command, check=True)
    return 0


if __name__ == "__main__":
    sys.exit(main())
