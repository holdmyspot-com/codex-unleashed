#!/usr/bin/env python3

from pathlib import Path
import unittest


REPOSITORY_ROOT = Path(__file__).resolve().parents[2]
WORKFLOW_PATH = REPOSITORY_ROOT / ".github" / "workflows" / "build-release.yml"


class ReleaseWorkflowTest(unittest.TestCase):
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
