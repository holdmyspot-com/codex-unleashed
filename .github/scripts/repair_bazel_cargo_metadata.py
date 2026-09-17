#!/usr/bin/env python3
"""Repair release-branch Cargo metadata required by rules_rust."""

from __future__ import annotations

import argparse
import tomllib
from pathlib import Path


def repair_cargo_metadata(root: Path) -> bool:
    """Add missing local workspace paths required by rules_rust.

    Cargo permits workspace members to appear only in Cargo.lock. rules_rust
    also needs a path mapping in the workspace manifest to build its crate
    graph, so add mappings only for local packages that are absent there.
    """
    manifest = root / "Cargo.toml"
    text = manifest.read_text(encoding="utf-8")
    workspace_marker = "[workspace.dependencies]\n"
    if workspace_marker not in text:
        raise RuntimeError("Cargo workspace has no [workspace.dependencies] table")
    document = tomllib.loads(text)
    workspace_dependencies = document["workspace"]["dependencies"]
    mappings: dict[str, str] = {}
    for package_manifest in sorted(root.rglob("Cargo.toml")):
        with package_manifest.open("rb") as package_file:
            package = tomllib.load(package_file).get("package")
        if not package or "name" not in package or package["name"] in workspace_dependencies:
            continue
        package_name = package["name"]
        relative_directory = package_manifest.parent.relative_to(root).as_posix() or "."
        mappings[package_name] = f'{package_name} = {{ path = "{relative_directory}" }}'
    if not mappings:
        return False
    additions = "\n".join(mappings[name] for name in sorted(mappings)) + "\n"
    manifest.write_text(text.replace(workspace_marker, workspace_marker + additions, 1), encoding="utf-8")
    return True


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("cargo_workspace", type=Path)
    args = parser.parse_args()
    repair_cargo_metadata(args.cargo_workspace)


if __name__ == "__main__":
    main()
