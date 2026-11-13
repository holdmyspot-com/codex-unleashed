"""Exercise staging and the legal-content archive consumer with canonical package fixtures."""

import importlib.util
import io
from contextlib import contextmanager, redirect_stderr
from pathlib import Path
import tarfile
import tempfile
import unittest

spec = importlib.util.spec_from_file_location("package_license", Path(__file__).parent / "package-license.py")
package_license = importlib.util.module_from_spec(spec)
spec.loader.exec_module(package_license)


class PackageLicenseTest(unittest.TestCase):
    """Verify exact legal bytes, preservation, and rejection before packaging/publishing."""

    def setUp(self):
        """Create isolated source and canonical upstream package directory inputs."""
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.metadata = self.root / "metadata"
        self.upstream = self.root / "upstream"
        self.package = self.root / "package"
        for relative in package_license.DOWNSTREAM_DOCUMENTS:
            self.write(self.metadata / relative, f"complete downstream {relative}\n".encode())
        self.write(self.metadata / "licenses/openai-codex/LICENSE-APACHE-2.0", b"supplied Apache terms")
        for relative in package_license.UPSTREAM_NOTICES:
            self.write(self.upstream / relative, f"original {relative}\n".encode())
        # This is upstream layout version 1, with relative bin/resources/path roots.
        for relative in ("codex-package.json", "bin/codex", "bin/codex-code-mode-host",
                         "bin/codex-exec-server", "codex-path/rg", "codex-resources/bwrap",
                         "codex-resources/existing-NOTICE"):
            self.write(self.package / relative, f"original {relative}".encode())
        self.documents = package_license.supplied_documents(self.metadata, self.upstream)

    def write(self, path, content):
        """Write one test-owned fixture file and create its parent directories."""
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(content)

    def archive(self, extra=(), omit=(), replace=None):
        """Serialize the fixture with upstream's root-relative regular-file tar contract."""
        stream = io.BytesIO()
        with tarfile.open(fileobj=stream, mode="w:gz") as archive:
            for path in sorted(self.package.rglob("*")):
                relative = path.relative_to(self.package).as_posix()
                if relative in omit:
                    continue
                if replace and relative in replace:
                    data = replace[relative]
                    member = tarfile.TarInfo(relative)
                    member.size = len(data)
                    archive.addfile(member, io.BytesIO(data))
                else:
                    archive.add(path, arcname=relative, recursive=False)
            for member in extra:
                archive.addfile(member, io.BytesIO(b"x" * member.size))
        stream.seek(0)
        return stream

    def package_tar(self, package_dir, compressed):
        """Create the archive bytes supplied by the in-memory upstream-writer stand-in."""
        stream = io.BytesIO()
        mode = "w:gz" if compressed else "w"
        with tarfile.open(fileobj=stream, mode=mode) as archive:
            for path in sorted(package_dir.rglob("*")):
                archive.add(path, arcname=path.relative_to(package_dir).as_posix(), recursive=False)
        return stream.getvalue()

    def test_supplied_documents_accepts_pinned_bubblewrap_notice_layout(self):
        """Read the original COPYING notice when upstream LICENSE is its symlink alias."""
        alias = self.upstream / "codex-rs/vendor/bubblewrap/LICENSE"
        alias.unlink(missing_ok=True)
        alias.symlink_to("COPYING")
        documents = package_license.supplied_documents(self.metadata, self.upstream)
        self.assertEqual(
            (self.upstream / "codex-rs/vendor/bubblewrap/COPYING").read_bytes(),
            documents["codex-resources/legal/licenses/upstream/codex-rs/vendor/bubblewrap/COPYING"],
        )

    def test_finalize_stages_both_variants_before_writing_and_checks_every_archive(self):
        """Finalization stages both canonical packages, writes four outputs, then validates each output."""
        package_root = self.root / "staging"
        archive_root = self.root / "dist"
        packages = {
            "codex-package-x86_64-unknown-linux-gnu": package_root / "codex-package-x86_64-unknown-linux-gnu",
            "codex-app-server-package-x86_64-unknown-linux-gnu": package_root / "codex-app-server-package-x86_64-unknown-linux-gnu",
        }
        for package in packages.values():
            self.write(package / "codex-package.json", b"canonical package")

        writes = []

        def write_archive(package_dir, archive_path, *, force):
            writes.append((package_dir, archive_path, force))
            self.assertEqual(
                {name: (package_dir / name).read_bytes() for name in self.documents},
                self.documents,
            )
            archive_path.parent.mkdir(parents=True, exist_ok=True)
            archive_path.write_bytes(self.package_tar(package_dir, compressed=archive_path.name.endswith(".tar.gz")))

        @contextmanager
        def decompress_zstd(archive_path):
            """The in-memory collaborator exposes the plain tar stream required after zstd decoding."""
            with archive_path.open("rb") as stream:
                yield stream

        package_license.finalize_packages(
            self.metadata,
            self.upstream,
            package_root,
            archive_root,
            "x86_64-unknown-linux-gnu",
            write_archive=write_archive,
            decompress_zstd=decompress_zstd,
        )

        self.assertEqual(
            [(package_root / f"{variant}-package-x86_64-unknown-linux-gnu",
              archive_root / f"{variant}-package-x86_64-unknown-linux-gnu{suffix}", True)
             for variant in ("codex", "codex-app-server")
             for suffix in (".tar.gz", ".tar.zst")],
            writes,
        )

    def test_finalize_stops_when_first_archive_write_fails(self):
        """A failed first upstream write prevents later archive writes from succeeding."""
        package_root = self.root / "staging"
        archive_root = self.root / "dist"
        for variant in ("codex", "codex-app-server"):
            self.write(package_root / f"{variant}-package-target/codex-package.json", b"canonical package")
        writes = []

        def fail_first_write(package_dir, archive_path, *, force):
            writes.append(archive_path)
            raise OSError("writer failed")

        with self.assertRaisesRegex(OSError, "writer failed"):
            package_license.finalize_packages(
                self.metadata,
                self.upstream,
                package_root,
                archive_root,
                "target",
                write_archive=fail_first_write,
                decompress_zstd=lambda archive_path: self.fail("decompression must not run"),
            )
        self.assertEqual([archive_root / "codex-package-target.tar.gz"], writes)

    def test_finalize_stops_when_first_archive_validation_fails(self):
        """A malformed first gzip archive prevents writer calls for dependent outputs."""
        package_root = self.root / "staging"
        archive_root = self.root / "dist"
        for variant in ("codex", "codex-app-server"):
            self.write(package_root / f"{variant}-package-target/codex-package.json", b"canonical package")
        writes = []

        def write_invalid_first(package_dir, archive_path, *, force):
            writes.append(archive_path)
            archive_path.parent.mkdir(parents=True, exist_ok=True)
            archive_path.write_bytes(b"not an archive")

        with self.assertRaises(tarfile.TarError):
            package_license.finalize_packages(
                self.metadata,
                self.upstream,
                package_root,
                archive_root,
                "target",
                write_archive=write_invalid_first,
                decompress_zstd=lambda archive_path: self.fail("decompression must not run"),
            )
        self.assertEqual([archive_root / "codex-package-target.tar.gz"], writes)

    def test_finalize_missing_arguments_exit_two(self):
        """The finalize CLI rejects missing staging, archive, or target arguments with status two."""
        with redirect_stderr(io.StringIO()):
            with self.assertRaises(SystemExit) as result:
                package_license.main([
                    "finalize",
                    "--metadata-root", str(self.metadata),
                    "--upstream-root", str(self.upstream),
                ])
        self.assertEqual(2, result.exception.code)

    def test_stage_and_check_preserve_package_and_include_complete_sources(self):
        """Staging leaves existing resources intact and the archive checker accepts all supplied bytes."""
        package_license.stage_documents(self.package, self.documents)
        package_license.stage_documents(self.package, self.documents)
        package_license.check_archive(self.archive(), self.documents)
        self.assertEqual((self.package / "codex-resources/existing-NOTICE").read_bytes(),
                         b"original codex-resources/existing-NOTICE")

    def test_command_stage_and_check(self):
        """The command entry point stages legal inputs and checks its archived result."""
        common = ["--metadata-root", str(self.metadata), "--upstream-root", str(self.upstream)]
        self.assertEqual(package_license.main(["stage", *common, "--package-dir", str(self.package)]), 0)
        archive_path = self.root / "package.tar.gz"
        archive_path.write_bytes(self.archive().getvalue())
        self.assertEqual(package_license.main(["check", *common, "--archive", str(archive_path)]), 0)

    def test_missing_and_mismatched_notices_are_rejected(self):
        """The consumer refuses an archive missing any required source or carrying altered bytes."""
        package_license.stage_documents(self.package, self.documents)
        for name in self.documents:
            with self.subTest(name=name, case="missing"):
                with self.assertRaisesRegex(ValueError, "Missing legal documents"):
                    package_license.check_archive(self.archive(omit=(name,)), self.documents)
            with self.subTest(name=name, case="mismatched"):
                with self.assertRaisesRegex(ValueError, "Legal document differs"):
                    package_license.check_archive(self.archive(replace={name: self.documents[name][:-1] + b"!"}), self.documents)

    def test_duplicate_and_unsafe_members_are_rejected(self):
        """The consumer rejects ambiguous paths and link members without extracting them."""
        package_license.stage_documents(self.package, self.documents)
        for name in ("bin/codex", "../outside", "/outside", "a/../outside", "./bin/codex", "C:/outside", "a\\outside"):
            with self.subTest(name=name):
                member = tarfile.TarInfo(name)
                with self.assertRaisesRegex(ValueError, "Duplicate|Unsafe"):
                    package_license.check_archive(self.archive(extra=(member,)), self.documents)
        member = tarfile.TarInfo("linked")
        member.type = tarfile.SYMTYPE
        member.linkname = "outside"
        with self.assertRaisesRegex(ValueError, "not a regular"):
            package_license.check_archive(self.archive(extra=(member,)), self.documents)

    def test_stage_rejects_conflict_before_writing_other_documents(self):
        """A conflicting legal destination prevents staging from partially changing a package."""
        last = list(self.documents)[-1]
        self.write(self.package / last, b"existing unrelated notice")
        with self.assertRaisesRegex(ValueError, "Existing legal document differs"):
            package_license.stage_documents(self.package, self.documents)
        self.assertFalse((self.package / list(self.documents)[0]).exists())

    def test_missing_source_is_rejected(self):
        """The source boundary refuses incomplete upstream legal inputs."""
        (self.upstream / "NOTICE").unlink()
        with self.assertRaisesRegex(ValueError, "Missing regular legal source"):
            package_license.supplied_documents(self.metadata, self.upstream)


if __name__ == "__main__":
    unittest.main()
