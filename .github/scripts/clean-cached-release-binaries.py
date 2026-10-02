#!/usr/bin/env python3

"""Invalidate cached Cargo outputs for release binaries while retaining dependencies."""

import json
import os
from pathlib import Path
import shutil
import subprocess
import sys


LIBRARY_TARGET_KINDS = frozenset({"lib", "rlib", "dylib", "cdylib", "staticlib", "proc-macro"})


def invalidate_cached_binary(release_directory: Path, binary: str) -> None:
    """Invalidate one Cargo binary unit while preserving sibling library units."""
    marker_names = (
        f"bin-{binary}", f"bin-{binary}.json",
        f"dep-bin-{binary}", f"output-bin-{binary}",
    )
    invalidated = 0
    for unit in (release_directory / ".fingerprint").glob("*"):
        if not unit.is_dir():
            continue
        if not any((unit / name).is_file() for name in marker_names[:2]):
            continue
        for name in marker_names:
            (unit / name).unlink(missing_ok=True)
        invalidated += 1

    for artifact in (release_directory / binary, release_directory / f"{binary}.exe"):
        artifact.unlink(missing_ok=True)
    symbols = release_directory / f"{binary}.dSYM"
    if symbols.is_dir() and not symbols.is_symlink():
        shutil.rmtree(symbols)
    else:
        symbols.unlink(missing_ok=True)
    print(f"Invalidated {invalidated} cached binary fingerprints for {binary}; library outputs retained.")


def main() -> int:
    if len(sys.argv) < 4:
        print("usage: clean-cached-release-binaries.py <workspace> <target> <binary>...", file=sys.stderr)
        return 2

    workspace = Path(sys.argv[1]).resolve()
    target = sys.argv[2]
    target_directory_name = Path(target).stem if target.endswith(".json") else target
    if (
        Path(target).name != target
        or target_directory_name in {"", ".", ".."}
        or Path(target_directory_name).name != target_directory_name
    ):
        print(f"Invalid Cargo target directory name: {target}", file=sys.stderr)
        return 2
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

    library_owners = {
        package["name"]
        for package in metadata["packages"]
        if any(LIBRARY_TARGET_KINDS.intersection(target_info["kind"])
               for target_info in package["targets"])
    }
    binary_only_owners = set(owners.values()) - library_owners
    if binary_only_owners:
        command = [cargo, "clean", "--release", "--target", target, "--manifest-path", str(manifest)]
        for package in sorted(binary_only_owners):
            command.extend(["-p", package])
        subprocess.run(command, check=True)

    # Package cleanup also deletes libraries consumed by other packages. Cargo's
    # binary fingerprints allow rebuilding these targets without discarding them.
    for binary in sorted(binaries):
        if owners[binary] in library_owners:
            release_directory = Path(metadata["target_directory"]) / target_directory_name / "release"
            invalidate_cached_binary(release_directory, binary)
    return 0


if __name__ == "__main__":
    sys.exit(main())
