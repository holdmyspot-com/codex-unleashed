#!/usr/bin/env python3
"""Remove the Cargo outputs of workspace crates from a copy of a target directory, leaving compiled dependencies.

Usage: prune-workspace-outputs.py WORKSPACE_MANIFEST_DIR TARGET_COPY

The workspace's own crates change with every patch set, so a cache of them is never reused; the compiled third-party
crates and build-script outputs are. This reads `cargo metadata --no-deps` for the names of the workspace packages and
their targets, then deletes from each profile directory under TARGET_COPY:
  deps/        files and directories named <name>-<16 hex digits>[.<ext>] (rlib, rmeta, d, so, executables)
  .fingerprint/ and build/   directories named <name>-<16 hex digits>
  top-level executables, examples/, incremental/
`name` is each package and target name with "-" written as "_". Run it on a hard-linked copy, never on the live directory.
"""

import json
import re
import shutil
import subprocess
import sys
from pathlib import Path

HASHED = re.compile(r"^(?:lib)?(?P<name>.+?)-(?P<hash>[0-9a-f]{16})(?:\..*)?$")


def workspace_names(manifest_directory):
    output = subprocess.run(["cargo", "metadata", "--no-deps", "--format-version", "1"],
                            cwd=manifest_directory, check=True, capture_output=True, text=True).stdout
    names = set()
    for package in json.loads(output)["packages"]:
        names.add(package["name"].replace("-", "_"))
        for target in package["targets"]:
            names.add(target["name"].replace("-", "_"))
    return names


def hashed_owner(entry_name):
    match = HASHED.match(entry_name)
    return match.group("name") if match else None


def remove(path):
    if path.is_dir() and not path.is_symlink():
        shutil.rmtree(path)
    else:
        path.unlink()


def prune_profile(profile, names):
    """Prunes one <target>/<profile> directory and returns the number of entries removed."""
    removed = 0
    for sub in ("deps", ".fingerprint", "build"):
        directory = profile / sub
        if not directory.is_dir():
            continue
        for entry in list(directory.iterdir()):
            owner = hashed_owner(entry.name)
            if owner is not None and owner.replace("-", "_") in names:
                remove(entry)
                removed += 1
    for sub in ("examples", "incremental"):
        if (profile / sub).exists():
            remove(profile / sub)
            removed += 1
    for entry in list(profile.iterdir()):
        # Final binaries and their debug files sit next to deps/.
        if entry.is_file() and not entry.name.startswith("."):
            remove(entry)
            removed += 1
    return removed


def profile_directories(target):
    """Yields every directory under the target that holds a deps/ directory."""
    for deps in sorted(target.rglob("deps")):
        if deps.is_dir() and (deps.parent / ".fingerprint").is_dir():
            yield deps.parent


def main(argv):
    if len(argv) != 3:
        print(__doc__, file=sys.stderr)
        return 2
    names = workspace_names(argv[1])
    target = Path(argv[2])
    total = 0
    for profile in profile_directories(target):
        count = prune_profile(profile, names)
        print(f"{profile}: removed {count} workspace entries")
        total += count
    print(f"Pruned {total} entries for {len(names)} workspace names.")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
