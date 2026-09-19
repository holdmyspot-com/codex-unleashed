#!/usr/bin/env python3
"""Collect license payloads from the resolved upstream Cargo dependency graph."""

from __future__ import annotations

import argparse
import json
import shutil
import subprocess
from pathlib import Path


LICENSE_NAMES = ("LICENSE", "LICENCE", "COPYING", "NOTICE", "AUTHORS")


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument("--manifest", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--require-license-files", action="store_true")
    return parser.parse_args()


def cargo_metadata(manifest: Path) -> dict:
    result = subprocess.run(
        [
            "cargo",
            "metadata",
            "--format-version",
            "1",
            "--locked",
            "--manifest-path",
            str(manifest),
        ],
        check=True,
        capture_output=True,
        text=True,
    )
    return json.loads(result.stdout)


def license_files(package: dict) -> list[Path]:
    root = Path(package["manifest_path"]).parent
    candidates = []
    for path in sorted(root.iterdir()):
        if path.is_file() and path.name.upper().startswith(LICENSE_NAMES):
            candidates.append(path)
    license_file = package.get("license_file")
    if license_file:
        path = root / license_file
        if path.is_file() and path not in candidates:
            candidates.append(path)
    return candidates


def safe_name(value: str) -> str:
    return "".join(char if char.isalnum() or char in ".-_" else "_" for char in value)


def collect(metadata: dict, output: Path, require_license_files: bool) -> None:
    output.mkdir(parents=True, exist_ok=True)
    workspace_ids = set(metadata.get("workspace_members", []))
    entries = []
    missing = []
    for package in sorted(metadata["packages"], key=lambda item: (item["name"], item["version"])):
        if package["id"] in workspace_ids:
            continue
        files = license_files(package)
        destination = output / f"{safe_name(package['name'])}-{safe_name(package['version'])}"
        copied = []
        for source in files:
            target = destination / source.name
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(source, target)
            copied.append(target.relative_to(output).as_posix())
        if not copied:
            missing.append(f"{package['name']} {package['version']}")
        entries.append((package, copied))

    lines = [
        "# Third-party Cargo licenses",
        "",
        "Generated from the locked upstream Cargo dependency graph during release packaging.",
        "License files below are copied from the resolved crate packages without modification.",
        "",
    ]
    for package, copied in entries:
        license_name = package.get("license") or "license expression not declared"
        lines.append(f"- `{package['name']} {package['version']}` — `{license_name}`")
        if copied:
            lines.extend(f"  - `{path}`" for path in copied)
        else:
            lines.append("  - No license payload file was present in the crate source.")
    if missing:
        lines.extend(["", "## Missing payload files", ""])
        lines.extend(f"- `{item}`" for item in missing)
    (output / "THIRD_PARTY_NOTICES.md").write_text("\n".join(lines) + "\n", encoding="utf-8")
    if missing and require_license_files:
        raise RuntimeError("Missing license payload files for: " + ", ".join(missing))


def main() -> None:
    args = parse_args()
    collect(cargo_metadata(args.manifest), args.output, args.require_license_files)


if __name__ == "__main__":
    main()
