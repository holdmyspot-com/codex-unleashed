import io
from pathlib import Path
import subprocess
import tarfile
import tempfile
import unittest


REPOSITORY_ROOT = Path(__file__).resolve().parents[2]
TARGETS = (
    "x86_64-unknown-linux-musl", "aarch64-unknown-linux-musl",
    "x86_64-apple-darwin", "aarch64-apple-darwin",
    "x86_64-pc-windows-msvc", "aarch64-pc-windows-msvc",
)


class NpmPublicationTest(unittest.TestCase):
    def test_packs_into_requested_directory_from_each_package_directory(self):
        # Actual npm pack exercises the subprocess cwd and destination boundary.
        with tempfile.TemporaryDirectory(prefix="codex-npm-publication-") as directory:
            root = Path(directory)
            archives = root / "archives"
            archives.mkdir()
            for target in TARGETS:
                with tarfile.open(archives / f"codex-package-{target}.tar.gz", "w:gz") as archive:
                    for name in ("bin/codex", "LICENSE.md", "docs/LICENSE.html",
                                 "docs/terms.html", "docs/privacy.html", "licenses/example/LICENSE"):
                        content = b"test fixture\n"
                        member = tarfile.TarInfo(name)
                        member.size = len(content)
                        archive.addfile(member, io.BytesIO(content))
            for output in (Path("npm-release"), root / "absolute-release"):
                with self.subTest(output=output):
                    result = subprocess.run(
                        ["python3", str(REPOSITORY_ROOT / "scripts/publish_npm_from_release.py"),
                         "--version", "0.160.0+34", "--archive-dir", str(archives),
                         "--output-dir", str(output)],
                        cwd=root, capture_output=True, text=True,
                    )
                    self.assertEqual(result.returncode, 0, result.stderr)
                    destination = root / output
                    self.assertEqual(len(list(destination.glob("*.tgz"))), 14)
                    self.assertIn("Packages were not published", result.stdout)


if __name__ == "__main__":
    unittest.main()
