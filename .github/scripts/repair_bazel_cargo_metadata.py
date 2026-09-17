#!/usr/bin/env python3
"""Repair release-branch Cargo metadata required by rules_rust."""

from __future__ import annotations

import argparse
import tomllib
from pathlib import Path


def repair_cargo_metadata(root: Path) -> bool:
    """Add local paths required by rules_rust's Cargo lockfile parser.

    rust-v0.153.4's workspace dependency table omits several local package
    paths that appear in its lockfile. rules_rust needs those paths to resolve
    the workspace, plus a direct mapping for the stale ``codex-build-info``
    lockfile entry. Apply the local paths as workspace dependencies; workspace
    crates must not all be treated as crates.io patches.
    """
    manifest = root / "Cargo.toml"
    text = manifest.read_text(encoding="utf-8")
    changed = False

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

    if mappings:
        additions = "\n".join(mappings[name] for name in sorted(mappings)) + "\n"
        text = text.replace(workspace_marker, workspace_marker + additions, 1)
        changed = True

    mapping = 'codex-build-info = { path = "build-info" }'
    patch_marker = "[patch.crates-io]\n"
    if patch_marker not in text:
        text = text.rstrip() + "\n\n" + patch_marker + mapping + "\n"
        changed = True
    elif mapping not in text.split(patch_marker, 1)[1].split("\n[", 1)[0]:
        text = text.replace(patch_marker, patch_marker + mapping + "\n", 1)
        changed = True

    if changed:
        manifest.write_text(text, encoding="utf-8")
    return changed


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("cargo_workspace", type=Path)
    args = parser.parse_args()
    repair_cargo_metadata(args.cargo_workspace)


if __name__ == "__main__":
    main()
