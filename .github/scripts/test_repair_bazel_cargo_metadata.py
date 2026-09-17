#!/usr/bin/env python3
"""Tests for the Bazel Cargo-metadata compatibility repair."""

from __future__ import annotations

import tempfile
import unittest
from pathlib import Path

from repair_bazel_cargo_metadata import repair_cargo_metadata


class RepairCargoMetadataTest(unittest.TestCase):
    def test_adds_missing_workspace_dependency_mapping(self) -> None:
        with tempfile.TemporaryDirectory() as temporary_directory:
            root = Path(temporary_directory) / "codex-rs"
            build_info_dir = root / "build-info"
            bwrap_dir = root / "bwrap"
            build_info_dir.mkdir(parents=True)
            bwrap_dir.mkdir()
            (root / "Cargo.toml").write_text(
                """[workspace.dependencies]
anyhow = "1"
""",
                encoding="utf-8",
            )
            (build_info_dir / "Cargo.toml").write_text(
                """[package]
name = "codex-build-info"
version = "0.0.0"
""",
                encoding="utf-8",
            )
            (bwrap_dir / "Cargo.toml").write_text(
                """[package]
name = "codex-bwrap"
version = "0.0.0"
""",
                encoding="utf-8",
            )

            self.assertTrue(repair_cargo_metadata(root))
            repaired = (root / "Cargo.toml").read_text(encoding="utf-8")
            workspace_section = repaired.split("[workspace.dependencies]\n", 1)[1]
            self.assertIn('codex-build-info = { path = "build-info" }', workspace_section)
            self.assertIn('codex-bwrap = { path = "bwrap" }', workspace_section)
            patch_section = repaired.split("[patch.crates-io]\n", 1)[1]
            self.assertIn('codex-build-info = { path = "build-info" }', patch_section)
            self.assertFalse(repair_cargo_metadata(root))


if __name__ == "__main__":
    unittest.main()
