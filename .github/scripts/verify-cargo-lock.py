#!/usr/bin/env python3
"""Refuse Cargo.lock drift beyond workspace-version synchronization.

Requires Python 3.11 or newer and its standard library. Arguments are the trusted
upstream lockfile, the post-build lockfile and JSON from actual Cargo metadata
--no-deps --format-version 1 for the unchanged workspace. Success prints JSON;
invalid inputs or other changes exit 1. This command never writes its inputs.
"""

import argparse
import json
from pathlib import Path
import sys
import tomllib


def verify(original, current, metadata):
    """Compare typed lockfile contents after synchronizing original workspace versions."""
    expected = tomllib.loads(original.read_text(encoding="utf-8"))
    observed = tomllib.loads(current.read_text(encoding="utf-8"))
    cargo = json.loads(metadata.read_text(encoding="utf-8"))
    members = set(cargo["workspace_members"])
    versions = {}
    for package in cargo["packages"]:
        if package["id"] in members:
            name = package["name"]
            version = package["version"]
            valid_package = isinstance(name, str) and isinstance(version, str)
            if not valid_package:
                raise ValueError("Cargo metadata workspace names and versions must be strings")
            versions[name] = version
    complete_workspace = len(versions) == len(members)
    if not complete_workspace:
        raise ValueError("Cargo metadata must identify each workspace package once")

    replacements = {}
    updates = {}
    for package in expected["package"]:
        name = package["name"]
        workspace_package = "source" not in package and name in versions
        if workspace_package:
            previous = package["version"]
            desired = versions[name]
            if previous != desired:
                replacements[f"{name} {previous}"] = f"{name} {desired}"
                updates[name] = {"from": previous, "to": desired}
                package["version"] = desired
    for package in expected["package"]:
        if "dependencies" in package:
            package["dependencies"] = [replacements.get(value, value)
                                       for value in package["dependencies"]]

    expected_value = json.dumps(expected, sort_keys=True, allow_nan=False)
    observed_value = json.dumps(observed, sort_keys=True, allow_nan=False)
    if expected_value != observed_value:
        raise ValueError("Cargo.lock changed beyond workspace package versions; refusing cache snapshot")
    return {"result": "verified", "workspace_version_updates": updates}


def main():
    """Validate paths, print the comparison receipt and preserve failure status."""
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("original", type=Path)
    parser.add_argument("current", type=Path)
    parser.add_argument("metadata", type=Path)
    arguments = parser.parse_args()
    try:
        result = verify(arguments.original, arguments.current, arguments.metadata)
    except (OSError, ValueError, KeyError, TypeError) as failure:
        print(f"Cargo.lock verification failed: {failure}", file=sys.stderr)
        return 1
    print(json.dumps(result, sort_keys=True))
    return 0


if __name__ == "__main__":
    sys.exit(main())
