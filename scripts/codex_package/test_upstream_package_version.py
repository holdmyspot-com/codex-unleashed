import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from codex_package import version


class UpstreamPackageVersionTest(unittest.TestCase):
    def test_uses_the_patched_version_provider(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / "scripts").mkdir()
            (root / "scripts/get_codex_package_version.py").write_text(
                "import os\n"
                "assert os.environ['CODEX_REPO_ROOT'] == " + repr(str(root)) + "\n"
                "print('0.160.0+' + os.environ['CODEX_UNLEASHED_BUILD_NUMBER'])\n",
                encoding="utf-8",
            )
            with patch.object(version, "WORKSPACE_ROOT", root):
                with patch.dict(os.environ, {"CODEX_UNLEASHED_BUILD_NUMBER": "41"}):
                    self.assertEqual(version.read_package_version(), "0.160.0+41")

    def test_missing_provider_reports_the_required_checkout(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            with patch.object(version, "WORKSPACE_ROOT", Path(directory)):
                with self.assertRaisesRegex(RuntimeError, "patched upstream"):
                    version.read_package_version()

    def test_provider_failure_does_not_fall_back_to_an_unbranded_version(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / "scripts").mkdir()
            (root / "scripts/get_codex_package_version.py").write_text(
                "raise SystemExit(7)\n", encoding="utf-8"
            )
            with patch.object(version, "WORKSPACE_ROOT", root):
                with self.assertRaises(subprocess.CalledProcessError) as failure:
                    version.read_package_version()
            self.assertEqual(failure.exception.returncode, 7)


if __name__ == "__main__":
    unittest.main()
