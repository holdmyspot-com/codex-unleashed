#!/usr/bin/env python3

from contextlib import redirect_stderr
import io
import json
from pathlib import Path
import sys
import tempfile
import unittest
from subprocess import CalledProcessError
from subprocess import CompletedProcess
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

import collect_third_party_licenses


class CargoMetadataTest(unittest.TestCase):
    def test_main_copies_licenses_from_cargo_metadata(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            manifest, metadata, output = metadata_fixture(root)

            with patch.object(
                sys,
                "argv",
                [
                    "collect_third_party_licenses.py",
                    "--manifest",
                    str(manifest),
                    "--output",
                    str(output),
                    "--require-license-evidence",
                ],
            ), patch.object(
                collect_third_party_licenses.subprocess,
                "run",
                return_value=CompletedProcess([], 0, stdout=metadata, stderr=""),
            ) as cargo:
                collect_third_party_licenses.main()

            cargo.assert_called_once_with(
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
                encoding="utf-8",
            )
            copied_license = output / "example-crate-1.2.3" / "LICENSE-MIT"
            self.assertEqual(
                copied_license.read_text(encoding="utf-8"),
                "Example dependency license.\n",
            )
            notices = (output / "THIRD_PARTY_NOTICES.md").read_text(encoding="utf-8")
            self.assertIn("`example-crate 1.2.3`", notices)

    def test_main_accepts_declared_license_without_payload_file(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            manifest, metadata, output = metadata_fixture(
                root,
                include_license_file=False,
            )

            with patch.object(
                sys,
                "argv",
                [
                    "collect_third_party_licenses.py",
                    "--manifest",
                    str(manifest),
                    "--output",
                    str(output),
                    "--require-license-evidence",
                ],
            ), patch.object(
                collect_third_party_licenses.subprocess,
                "run",
                return_value=CompletedProcess([], 0, stdout=metadata, stderr=""),
            ):
                collect_third_party_licenses.main()

            notices = (output / "THIRD_PARTY_NOTICES.md").read_text(encoding="utf-8")
            self.assertIn("`example-crate 1.2.3` — `MIT`", notices)
            self.assertIn(
                "No license payload file was present in the crate source.",
                notices,
            )

    def test_main_rejects_dependency_without_license_evidence(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            manifest, metadata, output = metadata_fixture(
                root,
                include_license_file=False,
                declared_license=None,
            )

            with patch.object(
                sys,
                "argv",
                [
                    "collect_third_party_licenses.py",
                    "--manifest",
                    str(manifest),
                    "--output",
                    str(output),
                    "--require-license-evidence",
                ],
            ), patch.object(
                collect_third_party_licenses.subprocess,
                "run",
                return_value=CompletedProcess([], 0, stdout=metadata, stderr=""),
            ):
                with self.assertRaisesRegex(
                    RuntimeError,
                    "Missing license evidence for: example-crate 1.2.3",
                ):
                    collect_third_party_licenses.main()

    def test_main_reports_cargo_metadata_stderr(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            manifest = Path(temp_dir) / "Cargo.toml"
            manifest.write_text("[workspace]\n", encoding="utf-8")
            command = [
                "cargo",
                "metadata",
                "--format-version",
                "1",
                "--locked",
                "--manifest-path",
                str(manifest),
            ]
            failure = CalledProcessError(
                101,
                command,
                stderr="Cargo cannot update Cargo.lock while --locked is active.\n",
            )
            error_output = io.StringIO()

            with patch.object(
                sys,
                "argv",
                [
                    "collect_third_party_licenses.py",
                    "--manifest",
                    str(manifest),
                    "--output",
                    str(Path(temp_dir) / "licenses"),
                ],
            ), patch.object(
                collect_third_party_licenses.subprocess,
                "run",
                side_effect=failure,
            ), redirect_stderr(error_output):
                with self.assertRaises(CalledProcessError):
                    collect_third_party_licenses.main()

            self.assertIn(
                "Cargo cannot update Cargo.lock while --locked is active.",
                error_output.getvalue(),
            )


def metadata_fixture(
    root: Path,
    *,
    include_license_file: bool = True,
    declared_license: str | None = "MIT",
) -> tuple[Path, str, Path]:
    manifest = root / "workspace" / "codex-rs" / "Cargo.toml"
    manifest.parent.mkdir(parents=True)
    manifest.write_text("[workspace]\n", encoding="utf-8")

    dependency_manifest = root / "registry" / "example-crate" / "Cargo.toml"
    dependency_manifest.parent.mkdir(parents=True)
    dependency_manifest.write_text("[package]\n", encoding="utf-8")
    if include_license_file:
        (dependency_manifest.parent / "LICENSE-MIT").write_text(
            "Example dependency license.\n", encoding="utf-8"
        )

    metadata = json.dumps(
        {
            "workspace_members": ["codex-rs 0.1.0"],
            "packages": [
                {
                    "id": "codex-rs 0.1.0",
                    "name": "codex-rs",
                    "version": "0.1.0",
                    "manifest_path": str(manifest),
                    "license": "MIT",
                    "license_file": None,
                },
                {
                    "id": "example-crate 1.2.3",
                    "name": "example-crate",
                    "version": "1.2.3",
                    "manifest_path": str(dependency_manifest),
                    "license": declared_license,
                    "license_file": None,
                },
            ],
        }
    )
    return manifest, metadata, root / "licenses"


if __name__ == "__main__":
    unittest.main()
