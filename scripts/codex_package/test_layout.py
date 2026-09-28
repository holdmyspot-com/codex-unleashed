#!/usr/bin/env python3

import os
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

import codex_package.layout as layout
from codex_package.layout import build_package_dir
from codex_package.layout import validate_package_dir
from codex_package.targets import PACKAGE_VARIANTS
from codex_package.targets import PackageInputs
from codex_package.targets import TARGET_SPECS


class PackageLayoutTest(unittest.TestCase):
    def test_app_server_package_places_code_mode_host_beside_entrypoint(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            package_dir = root / "package"
            package_dir.mkdir()
            inputs = PackageInputs(
                entrypoint_bin=touch_executable(root / "codex-app-server"),
                code_mode_host_bin=touch_executable(root / "codex-code-mode-host"),
                rg_bin=touch_executable(root / "rg"),
                zsh_bin=None,
                bwrap_bin=touch_executable(root / "bwrap"),
                codex_command_runner_bin=None,
                codex_windows_sandbox_setup_bin=None,
            )

            build_package_dir(
                package_dir,
                "1.2.3",
                PACKAGE_VARIANTS["codex-app-server"],
                TARGET_SPECS["x86_64-unknown-linux-musl"],
                inputs,
            )
            validate_package_dir(
                package_dir,
                PACKAGE_VARIANTS["codex-app-server"],
                TARGET_SPECS["x86_64-unknown-linux-musl"],
                include_zsh=False,
            )

            self.assertTrue((package_dir / "bin" / "codex-code-mode-host").is_file())

    def test_package_copies_prepared_cargo_license_files(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            prepared_licenses = root / "prepared-rust-licenses"
            dependency_licenses = prepared_licenses / "example-crate-1.2.3"
            dependency_licenses.mkdir(parents=True)
            (dependency_licenses / "LICENSE-MIT").write_text(
                "Example dependency license.\n", encoding="utf-8"
            )
            (prepared_licenses / "THIRD_PARTY_NOTICES.md").write_text(
                "- `example-crate 1.2.3` — `MIT`\n",
                encoding="utf-8",
            )

            package_dir = root / "package"
            package_dir.mkdir()
            inputs = PackageInputs(
                entrypoint_bin=touch_executable(root / "codex"),
                code_mode_host_bin=touch_executable(root / "codex-code-mode-host"),
                rg_bin=touch_executable(root / "rg"),
                zsh_bin=None,
                bwrap_bin=touch_executable(root / "bwrap"),
                codex_command_runner_bin=None,
                codex_windows_sandbox_setup_bin=None,
            )

            with patch.dict(
                os.environ,
                {
                    "CODEX_PACKAGE_RUST_LICENSES_DIR": str(prepared_licenses),
                },
            ), patch(
                "subprocess.run",
                side_effect=AssertionError("package generation reran Cargo metadata"),
            ):
                layout.build_package_dir(
                    package_dir,
                    "1.2.3",
                    PACKAGE_VARIANTS["codex"],
                    TARGET_SPECS["x86_64-unknown-linux-musl"],
                    inputs,
                )

            copied_license = (
                package_dir
                / "licenses"
                / "rust"
                / "example-crate-1.2.3"
                / "LICENSE-MIT"
            )
            self.assertEqual(
                copied_license.read_text(encoding="utf-8"),
                "Example dependency license.\n",
            )
            notices = (
                package_dir / "licenses" / "rust" / "THIRD_PARTY_NOTICES.md"
            ).read_text(encoding="utf-8")
            self.assertIn("`example-crate 1.2.3`", notices)

    def test_package_rejects_missing_prepared_cargo_licenses(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            package_dir = root / "package"
            package_dir.mkdir()
            missing_licenses = root / "missing-rust-licenses"
            inputs = PackageInputs(
                entrypoint_bin=touch_executable(root / "codex"),
                code_mode_host_bin=touch_executable(root / "codex-code-mode-host"),
                rg_bin=touch_executable(root / "rg"),
                zsh_bin=None,
                bwrap_bin=touch_executable(root / "bwrap"),
                codex_command_runner_bin=None,
                codex_windows_sandbox_setup_bin=None,
            )

            with patch.dict(
                os.environ,
                {"CODEX_PACKAGE_RUST_LICENSES_DIR": str(missing_licenses)},
            ):
                with self.assertRaisesRegex(
                    FileNotFoundError,
                    "Prepared Cargo license directory does not exist",
                ):
                    layout.build_package_dir(
                        package_dir,
                        "1.2.3",
                        PACKAGE_VARIANTS["codex"],
                        TARGET_SPECS["x86_64-unknown-linux-musl"],
                        inputs,
                    )

    def test_package_rejects_prepared_cargo_licenses_without_notices(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            prepared_licenses = root / "prepared-rust-licenses"
            dependency_licenses = prepared_licenses / "example-crate-1.2.3"
            dependency_licenses.mkdir(parents=True)
            (dependency_licenses / "LICENSE-MIT").write_text(
                "Example dependency license.\n", encoding="utf-8"
            )
            package_dir = root / "package"
            package_dir.mkdir()
            inputs = PackageInputs(
                entrypoint_bin=touch_executable(root / "codex"),
                code_mode_host_bin=touch_executable(root / "codex-code-mode-host"),
                rg_bin=touch_executable(root / "rg"),
                zsh_bin=None,
                bwrap_bin=touch_executable(root / "bwrap"),
                codex_command_runner_bin=None,
                codex_windows_sandbox_setup_bin=None,
            )

            with patch.dict(
                os.environ,
                {"CODEX_PACKAGE_RUST_LICENSES_DIR": str(prepared_licenses)},
            ):
                with self.assertRaisesRegex(
                    FileNotFoundError,
                    "Prepared Cargo license notices do not exist",
                ):
                    layout.build_package_dir(
                        package_dir,
                        "1.2.3",
                        PACKAGE_VARIANTS["codex"],
                        TARGET_SPECS["x86_64-unknown-linux-musl"],
                        inputs,
                    )


def touch_executable(path: Path) -> Path:
    path.touch(mode=0o755)
    return path


if __name__ == "__main__":
    unittest.main()
