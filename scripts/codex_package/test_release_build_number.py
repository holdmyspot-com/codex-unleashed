import os
from pathlib import Path
import subprocess
import tempfile
import textwrap
import unittest


REPOSITORY_ROOT = Path(__file__).resolve().parents[2]


class ReleaseBuildNumberTest(unittest.TestCase):
    def test_prepare_numbers_each_upstream_version_independently(self):
        workflow = (REPOSITORY_ROOT / ".github/workflows/build-release.yml").read_text()
        step = workflow.split("      - name: Determine upstream release\n", 1)[1]
        command = textwrap.dedent(step.split("        run: |\n", 1)[1].split("\n      - name:", 1)[0])
        with tempfile.TemporaryDirectory(prefix="codex-build-number-") as directory:
            root = Path(directory)
            gh = root / "gh"
            # The fixture represents GitHub's existing tag inventory and release lookup.
            gh.write_text(
                "#!/usr/bin/env python3\n"
                "import sys\n"
                "if sys.argv[1:3] == ['release', 'view']: sys.exit(0)\n"
                "path = sys.argv[2]\n"
                "if path.endswith('/tags'): print('rust-v0.160.0+29\\nrust-v0.159.1+23')\n"
                "elif '/releases/tags/' in path: print('false')\n"
            )
            gh.chmod(0o755)
            for upstream, override, expected in (
                ("rust-v0.161.0", "", "1"),
                ("rust-v0.160.0", "", "30"),
                ("rust-v0.161.0", "7", "7"),
            ):
                with self.subTest(upstream=upstream, override=override):
                    output = root / "output"
                    output.write_text("")
                    environment = os.environ.copy()
                    environment.update(
                        PATH=f"{root}:{environment['PATH']}", GITHUB_OUTPUT=str(output),
                        INPUT_UPSTREAM_TAG=upstream, INPUT_BUILD_NUMBER=override,
                        INPUT_ARTIFACT_RUN_ID="", INPUT_FOCUS_TARGET="", INPUT_FOCUS_BUNDLE="",
                        GITHUB_EVENT_NAME="workflow_dispatch", GITHUB_REF_TYPE="branch",
                        GITHUB_REF_NAME="main", GITHUB_REPOSITORY="owner/repo",
                        GITHUB_RUN_ID="100", GITHUB_RUN_NUMBER="99", GITHUB_SHA="a" * 40,
                        GITHUB_WORKFLOW="Build release",
                    )
                    result = subprocess.run(["bash", "-c", command], cwd=REPOSITORY_ROOT,
                                            env=environment, capture_output=True, text=True)
                    self.assertEqual(result.returncode, 0, result.stderr)
                    self.assertIn(f"patched_tag={upstream}+{expected}\n", output.read_text())


if __name__ == "__main__":
    unittest.main()
