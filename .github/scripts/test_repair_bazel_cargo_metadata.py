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
            bwrap_dir = root / "bwrap"
            bwrap_dir.mkdir(parents=True)
            (root / "Cargo.toml").write_text(
                "[workspace.dependencies]\ncodex-v8-poc = { path = \"v8-poc\" }\n",
                encoding="utf-8",
            )
            (bwrap_dir / "Cargo.toml").write_text(
                "[package]\nname = \"codex-bwrap\"\nversion = \"0.0.0\"\n", encoding="utf-8"
            )
            v8_poc_dir = root / "v8-poc"
            v8_poc_dir.mkdir()
            (v8_poc_dir / "Cargo.toml").write_text(
                "[package]\nname = \"codex-v8-poc\"\nversion = \"0.0.0\"\n", encoding="utf-8"
            )

            self.assertTrue(repair_cargo_metadata(root))
            repaired = (root / "Cargo.toml").read_text(encoding="utf-8")
            self.assertIn('codex-bwrap = { path = "bwrap" }', repaired)
            patch_section = repaired.split("[patch.crates-io]\n", 1)[1]
            self.assertIn('codex-bwrap = { path = "bwrap" }', patch_section)
            self.assertIn('codex-v8-poc = { path = "v8-poc" }', patch_section)
            self.assertFalse(repair_cargo_metadata(root))


if __name__ == "__main__":
    unittest.main()
