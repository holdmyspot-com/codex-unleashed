#!/usr/bin/env python3

from pathlib import Path
import os
import re
import subprocess
import tempfile
import unittest


REPOSITORY_ROOT = Path(__file__).resolve().parents[2]
STAGER = REPOSITORY_ROOT / "scripts" / "stage-release-installers.py"
WORKFLOW = Path(os.environ.get(
    "RELEASE_WORKFLOW_PATH",
    REPOSITORY_ROOT / ".github" / "workflows" / "build-release.yml",
))


class ReleaseInstallerStagingTest(unittest.TestCase):
    def test_stager_copies_patched_installers_byte_for_byte(self) -> None:
        with tempfile.TemporaryDirectory(prefix="release-installers-") as directory:
            root = Path(directory)
            source = root / "patched-upstream"
            install_dir = source / "scripts" / "install"
            install_dir.mkdir(parents=True)
            expected = {
                "install.sh": b"#!/bin/sh\n# https://github.com/holdmyspot-com/codex-unleashed/releases\n\x00\xff\n",
                "install.ps1": b"# https://github.com/holdmyspot-com/codex-unleashed/releases\r\nWrite-Output 'stable'\r\n",
            }
            for name, content in expected.items():
                (install_dir / name).write_bytes(content)

            stage = root / "release-stage"
            stage.mkdir()
            result = subprocess.run(
                ["python3", str(STAGER), str(source), str(stage)],
                capture_output=True,
                text=True,
            )

            self.assertEqual(result.returncode, 0, result.stderr)
            for name, content in expected.items():
                self.assertEqual((stage / name).read_bytes(), content)

    def test_publish_workflow_stages_the_patched_installer_bytes(self) -> None:
        # The publish job consumes the patched upstream checkout and release-stage directory.
        workflow = WORKFLOW.read_text()
        step_heading = "      - name: Stage patched release installers\n"
        if step_heading not in workflow:
            self.fail("publish workflow does not stage patched installers")
        step = workflow.split(step_heading, 1)[1]
        step = step.split("\n      - name:", 1)[0]
        command = re.search(r"        run: (.+)", step).group(1)

        with tempfile.TemporaryDirectory(prefix="release-installers-workflow-") as directory:
            root = Path(directory)
            install_dir = root / "upstream-installers" / "scripts" / "install"
            install_dir.mkdir(parents=True)
            expected = {
                "install.sh": b"#!/bin/sh\n# https://github.com/holdmyspot-com/codex-unleashed/releases\n",
                "install.ps1": b"# https://github.com/holdmyspot-com/codex-unleashed/releases\r\n",
            }
            for name, content in expected.items():
                (install_dir / name).write_bytes(content)
            stage = root / "release-stage"
            stage.mkdir()
            command = command.replace("upstream-installers", str(root / "upstream-installers"))
            command = command.replace("release-stage", str(stage))

            subprocess.run(["bash", "-eu", "-c", command], cwd=REPOSITORY_ROOT, check=True)

            for name, content in expected.items():
                self.assertEqual((stage / name).read_bytes(), content)

    def test_stager_rejects_missing_installer_before_copying_anything(self) -> None:
        with tempfile.TemporaryDirectory(prefix="release-installers-") as directory:
            root = Path(directory)
            install_dir = root / "patched-upstream" / "scripts" / "install"
            install_dir.mkdir(parents=True)
            (install_dir / "install.sh").write_bytes(
                b"#!/bin/sh\n# https://github.com/holdmyspot-com/codex-unleashed/releases\n"
            )
            stage = root / "release-stage"
            stage.mkdir()
            (stage / "existing.txt").write_bytes(b"preserve")

            result = subprocess.run(
                ["python3", str(STAGER), str(install_dir.parent.parent), str(stage)],
                capture_output=True,
                text=True,
            )

            self.assertNotEqual(result.returncode, 0)
            self.assertIn("install.ps1", result.stderr)
            self.assertEqual(sorted(path.name for path in stage.iterdir()), ["existing.txt"])
            self.assertEqual((stage / "existing.txt").read_bytes(), b"preserve")

    def test_stager_rejects_unpatched_upstream_installer(self) -> None:
        with tempfile.TemporaryDirectory(prefix="release-installers-") as directory:
            root = Path(directory)
            install_dir = root / "patched-upstream" / "scripts" / "install"
            install_dir.mkdir(parents=True)
            for name in ("install.sh", "install.ps1"):
                (install_dir / name).write_text("upstream updater\n", encoding="utf-8")
            stage = root / "release-stage"
            stage.mkdir()

            result = subprocess.run(
                ["python3", str(STAGER), str(install_dir.parent.parent), str(stage)],
                capture_output=True,
                text=True,
            )

            self.assertNotEqual(result.returncode, 0)
            self.assertIn("holdmyspot-com/codex-unleashed", result.stderr)
            self.assertEqual(list(stage.iterdir()), [])


if __name__ == "__main__":
    unittest.main()
