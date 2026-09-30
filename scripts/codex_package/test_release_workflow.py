#!/usr/bin/env python3

from pathlib import Path
import re
import subprocess
import unittest


REPOSITORY_ROOT = Path(__file__).resolve().parents[2]
WORKFLOW_PATH = REPOSITORY_ROOT / ".github" / "workflows" / "build-release.yml"


class ReleaseWorkflowTest(unittest.TestCase):
    def test_verification_and_packaging_have_read_only_tokens(self) -> None:
        workflow = WORKFLOW_PATH.read_text(encoding="utf-8")
        for job_name in ("verify", "build-windows-package"):
            with self.subTest(job=job_name):
                job = re.split(r"\n  \S", workflow.split(f"\n  {job_name}:\n", 1)[1], maxsplit=1)[0]
                self.assertTrue("    permissions:\n      contents: read\n" in job,
                                f"{job_name} must not inherit release write permissions")

    def test_codespell_referenced_files_exist(self) -> None:
        self.assertTrue((REPOSITORY_ROOT / ".codespellignore").is_file())
        self.assertTrue((REPOSITORY_ROOT / ".github/codespell-matcher.json").is_file())

    def test_release_entry_points_use_one_workflow(self) -> None:
        workflow = WORKFLOW_PATH.read_text(encoding="utf-8")
        self.assertFalse((WORKFLOW_PATH.parent / "rust-release.yml").exists())
        self.assertIn('  push:\n    tags:\n      - "rust-v*.*.*"', workflow)
        self.assertIn("  workflow_dispatch:", workflow)
        self.assertIn("  workflow_call:", workflow)
        detector = (WORKFLOW_PATH.parent / "upstream-release-check.yml").read_text()
        self.assertIn("gh workflow run build-release.yml", detector)

    def test_release_permissions_cover_required_operations(self) -> None:
        workflow = WORKFLOW_PATH.read_text(encoding="utf-8")
        self.assertTrue("\npermissions:\n" in workflow, "workflow must declare token permissions")
        permissions = workflow.split("\npermissions:\n", 1)[1].split("\n\n", 1)[0]
        for permission in ("actions: read", "contents: write", "packages: write",
                           "id-token: write", "attestations: write"):
            self.assertIn(permission, permissions)

    def test_pushed_tag_selects_its_upstream_version(self) -> None:
        workflow = WORKFLOW_PATH.read_text(encoding="utf-8")
        start = workflow.index('          if [[ -n "${INPUT_UPSTREAM_TAG:-}" ]]')
        end = workflow.index('\n          if [[ -z "${upstream_tag}"', start)
        script = workflow[start:end]
        for input_tag, ref_type, ref_name, expected in (
            ("", "tag", "rust-v0.159.1+23", "rust-v0.159.1"),
            ("rust-v0.159.2", "tag", "rust-v0.159.1+23", "rust-v0.159.2"),
            ("", "branch", "main", "rust-v0.159.2"),
        ):
            with self.subTest(input_tag=input_tag, ref_type=ref_type):
                result = subprocess.run(
                    ["bash", "-eu", "-c", '''
gh() { printf 'false\\n'; }
check_upstream_release() { printf '{"tag_name":"rust-v0.159.2","prerelease":false,"draft":false}\\n'; }
''' + script.replace("./scripts/check-upstream-release.sh", "check_upstream_release")
                     + '\nprintf "%s" "$upstream_tag"'],
                    env={"INPUT_UPSTREAM_TAG": input_tag, "GITHUB_REF_TYPE": ref_type,
                         "GITHUB_REF_NAME": ref_name, "PATH": "/usr/bin:/bin"},
                    capture_output=True, text=True, check=True,
                )
                self.assertEqual(result.stdout, expected)

    def test_tag_push_keeps_npm_publication_enabled(self) -> None:
        workflow = WORKFLOW_PATH.read_text(encoding="utf-8")
        self.assertEqual(workflow.count("if: ${{ github.event_name == 'push' || inputs.publish_npm }}"), 2)

    def test_normal_build_does_not_export_empty_rustflags(self) -> None:
        workflow = WORKFLOW_PATH.read_text(encoding="utf-8")

        self.assertNotIn(
            "RUSTFLAGS: ${{ (inputs.reproducibility_mode || 'off') == "
            "'deterministic' && '-Ccodegen-units=1 -Zno-parallel-backend' || '' }}",
            workflow,
        )
        self.assertNotIn(
            "RUSTC_BOOTSTRAP: ${{ (inputs.reproducibility_mode || 'off') == "
            "'deterministic' && '1' || '' }}",
            workflow,
        )
        self.assertEqual(
            workflow.count("- name: Configure deterministic compiler mode"),
            2,
        )


if __name__ == "__main__":
    unittest.main()
