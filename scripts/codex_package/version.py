"""Version discovery for Codex packages."""

import os
import re
import subprocess
import sys

from .targets import WORKSPACE_ROOT


WORKSPACE_VERSION_PATTERN = re.compile(r'^version\s*=\s*"([^"]+)"')


def read_package_version() -> str:
    provider = WORKSPACE_ROOT / "scripts/get_codex_package_version.py"
    if not provider.is_file():
        raise RuntimeError(
            "Cannot find the patched upstream package version helper. Apply the patch "
            "queue and set CODEX_PACKAGE_WORKSPACE_ROOT to that checkout."
        )
    result = subprocess.run(
        [sys.executable, str(provider)],
        env={**os.environ, "CODEX_REPO_ROOT": str(WORKSPACE_ROOT)},
        check=True,
        capture_output=True,
        text=True,
    )
    return result.stdout.strip()


def read_workspace_version() -> str:
    cargo_toml = WORKSPACE_ROOT / "codex-rs" / "Cargo.toml"
    in_workspace_package = False
    with open(cargo_toml, encoding="utf-8") as fh:
        for line in fh:
            stripped = line.strip()
            if stripped == "[workspace.package]":
                in_workspace_package = True
                continue

            if in_workspace_package and stripped.startswith("["):
                break

            if in_workspace_package:
                match = WORKSPACE_VERSION_PATTERN.match(stripped)
                if match is not None:
                    return match.group(1)

    raise RuntimeError(f"Could not find [workspace.package].version in {cargo_toml}")
