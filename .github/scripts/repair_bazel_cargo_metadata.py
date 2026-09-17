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
    workspace_mappings: dict[str, str] = {}
    patch_mappings: dict[str, str] = {}
    for package_manifest in sorted(root.rglob("Cargo.toml")):
        with package_manifest.open("rb") as package_file:
            package = tomllib.load(package_file).get("package")
        if not package or "name" not in package:
            continue
        package_name = package["name"]
        relative_directory = package_manifest.parent.relative_to(root).as_posix() or "."
        mapping = f'{package_name} = {{ path = "{relative_directory}" }}'
        patch_mappings[package_name] = mapping
        if package_name not in workspace_dependencies:
            workspace_mappings[package_name] = mapping
    patch_marker = "[patch.crates-io]\n"
    existing_patch = (
        text.split(patch_marker, 1)[1].split("\n[", 1)[0] if patch_marker in text else ""
    )
    workspace_additions = "\n".join(
        workspace_mappings[name] for name in sorted(workspace_mappings)
    )
    if workspace_additions:
        text = text.replace(workspace_marker, workspace_marker + workspace_additions + "\n", 1)
    missing_patch_mappings = {
        name: mapping
        for name, mapping in patch_mappings.items()
        if mapping not in existing_patch
    }
    if not workspace_additions and not missing_patch_mappings:
        return False
    if missing_patch_mappings:
        patch_additions = "\n".join(
            missing_patch_mappings[name] for name in sorted(missing_patch_mappings)
        ) + "\n"
        if patch_marker in text:
            text = text.replace(patch_marker, patch_marker + patch_additions, 1)
        else:
            text = text.rstrip() + "\n\n" + patch_marker + patch_additions
    manifest.write_text(text, encoding="utf-8")
    return True


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("cargo_workspace", type=Path)
    args = parser.parse_args()
    repair_cargo_metadata(args.cargo_workspace)


if __name__ == "__main__":
    main()
