#!/usr/bin/env python3

import hashlib
import json
import os
from pathlib import Path
import subprocess
import sys
import tarfile
import tempfile
import time
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from codex_package.archive import resolve_zstd_command
from codex_package.archive import write_archive


class WriteArchiveTest(unittest.TestCase):
    def stage_package_tree(
        self, package_dir: Path, *, reverse_creation_order: bool
    ) -> None:
        package_dir.mkdir()
        bin_dir = package_dir / "bin"
        share_dir = package_dir / "share"
        directories = (
            [share_dir, bin_dir] if reverse_creation_order else [bin_dir, share_dir]
        )
        for directory in directories:
            directory.mkdir(mode=0o750 if reverse_creation_order else 0o700)

        executable = bin_dir / "codex"
        windows_executable = bin_dir / "codex.exe"
        readme = share_dir / "readme.txt"
        link = share_dir / "current"
        entries = (
            [readme, link, windows_executable, executable]
            if reverse_creation_order
            else [executable, windows_executable, link, readme]
        )
        for entry in entries:
            if entry == link:
                entry.symlink_to("../bin/codex")
            else:
                contents = (
                    b"readme\n"
                    if entry == readme
                    else b"windows executable\n"
                    if entry == windows_executable
                    else b"executable\n"
                )
                entry.write_bytes(contents)

        executable.chmod(0o755)
        windows_executable.chmod(0o640)
        readme.chmod(0o640 if reverse_creation_order else 0o600)
        file_mtime_ns = (
            1_600_000_000_000_000_000
            if reverse_creation_order
            else 1_700_000_000_000_000_000
        )
        for entry in (executable, windows_executable, readme, link):
            os.utime(entry, ns=(file_mtime_ns, file_mtime_ns), follow_symlinks=False)
        for directory in (bin_dir, share_dir):
            directory.chmod(0o750 if reverse_creation_order else 0o700)
            os.utime(directory, ns=(file_mtime_ns, file_mtime_ns))

    def archive_digest(self, package_dir: Path, archive_path: Path) -> str:
        write_archive(package_dir, archive_path, force=False)
        return hashlib.sha256(archive_path.read_bytes()).hexdigest()

    def test_metadata_differences_do_not_change_archive_sha256(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            first_tree = root / "first-package"
            second_tree = root / "second-package"
            self.stage_package_tree(first_tree, reverse_creation_order=False)
            self.stage_package_tree(second_tree, reverse_creation_order=True)

            first_digest = self.archive_digest(
                first_tree, root / "first-output" / "first.tar.gz"
            )
            second_digest = self.archive_digest(
                second_tree, root / "second-output" / "second.tar.gz"
            )
            self.assertEqual(first_digest, second_digest)

            first_zstd_digest = self.archive_digest(
                first_tree, root / "first-output" / "first.tar.zst"
            )
            second_zstd_digest = self.archive_digest(
                second_tree, root / "second-output" / "second.tar.zst"
            )
            self.assertEqual(first_zstd_digest, second_zstd_digest)

            archive_path = root / "first-output" / "first.tar.gz"
            with tarfile.open(archive_path, "r:gz") as archive:
                members = {member.name: member for member in archive.getmembers()}
                self.assertEqual(
                    archive.extractfile("bin/codex").read(), b"executable\n"
                )
                self.assertEqual(
                    archive.extractfile("share/readme.txt").read(), b"readme\n"
                )
                self.assertEqual(members["bin/codex"].mode & 0o111, 0o111)
                self.assertEqual(members["share/readme.txt"].mode & 0o111, 0)
                self.assertTrue(members["share/current"].issym())
                self.assertEqual(members["share/current"].linkname, "../bin/codex")
                for member in members.values():
                    self.assertEqual(
                        (
                            member.uid,
                            member.gid,
                            member.uname,
                            member.gname,
                            member.mtime,
                        ),
                        (0, 0, "", "", 0),
                    )
                    self.assertEqual(member.pax_headers, {})

            (second_tree / "share" / "readme.txt").write_bytes(b"changed\n")
            changed_digest = self.archive_digest(
                second_tree, root / "changed-output" / "changed.tar.gz"
            )
            self.assertNotEqual(first_digest, changed_digest)
            changed_zstd_digest = self.archive_digest(
                second_tree, root / "changed-output" / "changed.tar.zst"
            )
            self.assertNotEqual(first_zstd_digest, changed_zstd_digest)

    @unittest.skipUnless(hasattr(os, "mkfifo"), "requires FIFO support")
    def test_special_files_are_rejected(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            package_dir = root / "package"
            package_dir.mkdir()
            os.mkfifo(package_dir / "pipe")

            with self.assertRaises(RuntimeError):
                write_archive(package_dir, root / "package.tar.gz", force=False)

    def test_windows_executables_keep_their_role_without_host_execute_bits(
        self,
    ) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            package_dir = root / "package"
            self.stage_package_tree(package_dir, reverse_creation_order=False)
            archive_path = root / "package.tar.gz"
            write_archive(package_dir, archive_path, force=False)

            with tarfile.open(archive_path, "r:gz") as archive:
                member = archive.getmember("bin/codex.exe")
                self.assertEqual(member.mode & 0o111, 0o111)


class SymbolsArchiveBoundaryTest(unittest.TestCase):
    def create_symbols_archive(
        self, release_dir: Path, archive_dir: Path, runner_temp: Path
    ) -> Path:
        repo_root = Path(__file__).resolve().parents[2]
        archive_path = archive_dir / "codex-symbols-windows.tar.gz"
        environment = {
            "CODEX_REPO_ROOT": str(repo_root),
            "PATH": os.pathsep.join((str(Path(sys.executable).parent), os.defpath)),
            "PYTHONPATH": str(repo_root / "scripts"),
            "RUNNER_TEMP": str(runner_temp),
        }
        subprocess.run(
            [
                "bash",
                str(
                    repo_root
                    / ".github/scripts/archive-release-symbols-and-strip-binaries.sh"
                ),
                "--target",
                "x86_64-pc-windows-msvc",
                "--artifact-name",
                "windows",
                "--release-dir",
                str(release_dir),
                "--archive-dir",
                str(archive_dir),
                "--binaries",
                "codex",
            ],
            cwd=repo_root,
            env=environment,
            check=True,
        )
        return archive_path

    def test_symbols_script_normalizes_equal_payloads_with_different_source_mtimes(
        self,
    ) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            first_release = root / "first-release"
            second_release = root / "second-release"
            first_release.mkdir()
            second_release.mkdir()
            first_symbols = first_release / "codex.pdb"
            second_symbols = second_release / "codex.pdb"
            first_symbols.write_bytes(b"symbols\n")
            second_symbols.write_bytes(b"symbols\n")
            os.utime(first_symbols, ns=(1_600_000_000_000_000_000,) * 2)
            os.utime(second_symbols, ns=(1_700_000_000_000_000_000,) * 2)

            first_archive = self.create_symbols_archive(
                first_release,
                root / "first-output",
                root / "first-runner-temp",
            )
            time.sleep(1.1)
            second_archive = self.create_symbols_archive(
                second_release,
                root / "second-output",
                root / "second-runner-temp",
            )
            self.assertEqual(
                hashlib.sha256(first_archive.read_bytes()).digest(),
                hashlib.sha256(second_archive.read_bytes()).digest(),
            )

            with tarfile.open(first_archive, "r:gz") as archive:
                members = {member.name: member for member in archive.getmembers()}
                self.assertIn("codex-symbols-windows", members)
                self.assertEqual(
                    archive.extractfile("codex-symbols-windows/codex.pdb").read(),
                    b"symbols\n",
                )


class ArchiveModuleCommandTest(unittest.TestCase):
    def setUp(self) -> None:
        self.repo_root = Path(__file__).resolve().parents[2]
        self.environment = os.environ.copy()
        self.environment["CODEX_REPO_ROOT"] = str(self.repo_root)
        self.environment["PYTHONPATH"] = os.pathsep.join(
            (str(self.repo_root / "scripts"), self.environment.get("PYTHONPATH", ""))
        ).rstrip(os.pathsep)

    def run_archive_command(self, *arguments: str) -> subprocess.CompletedProcess[str]:
        return subprocess.run(
            [sys.executable, "-m", "codex_package.archive", *arguments],
            cwd=self.repo_root,
            env=self.environment,
            capture_output=True,
            text=True,
            check=False,
        )

    def test_module_write_matches_direct_writer_for_gzip_and_zstd(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            package_dir = root / "package with spaces"
            package_dir.mkdir()
            (package_dir / "payload.txt").write_bytes(b"module archive payload\n")
            (package_dir / "payload.txt").chmod(0o640)

            for suffix in ("tar.gz", "tar.zst"):
                direct_archive = root / f"direct.{suffix}"
                module_archive = root / f"module.{suffix}"
                write_archive(package_dir, direct_archive, force=False)

                result = self.run_archive_command(
                    "write",
                    "--package-dir",
                    str(package_dir),
                    "--archive-output",
                    str(module_archive),
                )

                self.assertEqual(result.returncode, 0, result.stderr)
                self.assertEqual(result.stdout, "")
                self.assertEqual(result.stderr, "")
                self.assertEqual(
                    module_archive.read_bytes(), direct_archive.read_bytes()
                )

    def test_resolve_zstd_prints_existing_resolver_argv_as_json(self) -> None:
        result = self.run_archive_command("resolve-zstd")

        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(result.stdout, json.dumps(resolve_zstd_command()) + "\n")
        self.assertEqual(result.stderr, "")

    def test_invalid_command_exits_two_without_creating_archive(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            package_dir = Path(temp_dir) / "package"
            package_dir.mkdir()
            archive_path = Path(temp_dir) / "invalid.tar.gz"
            result = self.run_archive_command(
                "write",
                "--package-dir",
                str(package_dir),
                "--archive-output",
                str(archive_path),
                "--unknown",
            )

            self.assertEqual(result.returncode, 2)
            self.assertNotEqual(result.stderr, "")
            self.assertFalse(archive_path.exists())

    def test_resolver_failure_exits_one_with_stderr_diagnostic(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            self.environment["PATH"] = temp_dir
            result = self.run_archive_command("resolve-zstd")

            self.assertEqual(result.returncode, 1)
            self.assertEqual(result.stdout, "")
            self.assertIn("zstd is required", result.stderr)

    def test_writer_failure_exits_one_and_successful_force_is_silent(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            package_dir = root / "package"
            package_dir.mkdir()
            (package_dir / "payload.txt").write_bytes(b"first\n")
            output_path = root / "output.unsupported"
            failed = self.run_archive_command(
                "write",
                "--package-dir",
                str(package_dir),
                "--archive-output",
                str(output_path),
            )
            self.assertEqual(failed.returncode, 1)
            self.assertEqual(failed.stdout, "")
            self.assertNotEqual(failed.stderr, "")
            self.assertFalse(output_path.exists())

            output_path = root / "output.tar.gz"
            output_path.write_bytes(b"previous archive")
            without_force = self.run_archive_command(
                "write",
                "--package-dir",
                str(package_dir),
                "--archive-output",
                str(output_path),
            )
            self.assertEqual(without_force.returncode, 1)
            self.assertEqual(output_path.read_bytes(), b"previous archive")

            expected_path = root / "expected.tar.gz"
            write_archive(package_dir, expected_path, force=False)
            forced = self.run_archive_command(
                "write",
                "--package-dir",
                str(package_dir),
                "--archive-output",
                str(output_path),
                "--force",
            )
            self.assertEqual(forced.returncode, 0, forced.stderr)
            self.assertEqual(forced.stdout, "")
            self.assertEqual(forced.stderr, "")
            self.assertEqual(output_path.read_bytes(), expected_path.read_bytes())


class ResolveZstdCommandTest(unittest.TestCase):
    def test_prefers_zstd_from_path(self) -> None:
        def which(name: str) -> str | None:
            return {"zstd": "/usr/bin/zstd", "dotslash": "/usr/bin/dotslash"}.get(name)

        self.assertEqual(resolve_zstd_command(which=which), ["/usr/bin/zstd"])

    def test_falls_back_to_dotslash_manifest(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            manifest = Path(temp_dir) / "zstd"
            manifest.write_text("#!/usr/bin/env dotslash\n{}\n", encoding="utf-8")

            def which(name: str) -> str | None:
                return {"dotslash": "/usr/bin/dotslash"}.get(name)

            self.assertEqual(
                resolve_zstd_command(dotslash_manifest=manifest, which=which),
                ["/usr/bin/dotslash", str(manifest)],
            )

    def test_errors_when_no_zstd_or_dotslash_manifest_is_available(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            missing_manifest = Path(temp_dir) / "zstd"

            with self.assertRaisesRegex(RuntimeError, "zstd is required"):
                resolve_zstd_command(
                    dotslash_manifest=missing_manifest,
                    which=lambda _name: None,
                )


if __name__ == "__main__":
    unittest.main()
