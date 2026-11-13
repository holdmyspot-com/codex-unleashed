"""Exercise staging and the legal-content archive consumer with canonical package fixtures."""

import importlib.util
import io
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
