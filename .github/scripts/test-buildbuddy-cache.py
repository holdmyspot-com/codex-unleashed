#!/usr/bin/env python3
"""Exercise buildbuddy-cache.sh: which runs receive the key, what it writes, and that finish removes and redacts it."""

import os
from pathlib import Path
import subprocess
import tempfile
import unittest

SCRIPT = Path(__file__).with_name("buildbuddy-cache.sh")
KEY = "abcDEF0123456789"
TRUSTED = {
    "GITHUB_ACTIONS": "true",
    "GITHUB_REPOSITORY": "holdmyspot-com/codex-unleashed",
    "GITHUB_EVENT_NAME": "workflow_dispatch",
}


class BuildBuddyCacheTest(unittest.TestCase):
    def setUp(self):
        self._temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self._temporary.cleanup)
        self.root = Path(self._temporary.name)
        self.source = self.root / "source"
        self.source.mkdir()
        self.evidence = self.root / "evidence"
        self.evidence.mkdir()

    def run_script(self, *args, **environment):
        env = {name: value for name, value in os.environ.items() if not name.startswith(("GITHUB_", "BUILDBUDDY_"))}
        env["RUNNER_TEMP"] = str(self.root)
        env.update(environment)
        return subprocess.run(["bash", str(SCRIPT), *args], env=env, capture_output=True, text=True, check=False)

    def configure(self, **environment):
        return self.run_script("configure", str(self.source), **environment)

    def test_without_a_key_nothing_is_written(self):
        result = self.configure(**TRUSTED)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertFalse((self.source / "user.bazelrc").exists())

    def test_trusted_runs_get_the_remote_cache_and_no_remote_execution(self):
        for event in ("push", "workflow_dispatch", "schedule"):
            (self.source / "user.bazelrc").unlink(missing_ok=True)
            result = self.configure(**{**TRUSTED, "GITHUB_EVENT_NAME": event}, BUILDBUDDY_API_KEY=KEY)
            self.assertEqual(result.returncode, 0, result.stderr)
            lines = (self.source / "user.bazelrc").read_text().splitlines()
            self.assertIn("common --remote_cache=grpcs://remote.buildbuddy.io", lines)
            self.assertIn(f"common --remote_header=x-buildbuddy-api-key={KEY}", lines)
            self.assertFalse(any("remote_executor" in line or "config=" in line for line in lines))
            self.assertNotIn(KEY, result.stdout + result.stderr)

    def test_untrusted_runs_do_not_get_the_key(self):
        for override in (
            {"GITHUB_EVENT_NAME": "pull_request"},
            {"GITHUB_EVENT_NAME": "pull_request_target"},
            {"GITHUB_REPOSITORY": "someone/codex-unleashed"},
            {"GITHUB_ACTIONS": ""},
        ):
            result = self.configure(**{**TRUSTED, **override}, BUILDBUDDY_API_KEY=KEY)
            self.assertEqual(result.returncode, 0, result.stderr)
            self.assertFalse((self.source / "user.bazelrc").exists(), override)
            self.assertNotIn(KEY, result.stdout + result.stderr)

    def test_a_key_that_could_add_rc_lines_is_rejected(self):
        result = self.configure(**TRUSTED, BUILDBUDDY_API_KEY="abc\ncommon --remote_executor=grpcs://evil")
        self.assertEqual(result.returncode, 1)
        self.assertFalse((self.source / "user.bazelrc").exists())

    def test_finish_keeps_other_rc_lines_and_redacts_evidence(self):
        rc = self.source / "user.bazelrc"
        rc.write_text("common --remote_upload_local_results=false\n")
        self.configure(**TRUSTED, BUILDBUDDY_API_KEY=KEY)
        (self.evidence / "bazel.log").write_text(f"flags x-buildbuddy-api-key={KEY}\n")
        (self.evidence / "clean.log").write_text("nothing\n")
        result = self.run_script("finish", str(self.source), str(self.evidence), BUILDBUDDY_API_KEY=KEY)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertNotIn(KEY, rc.read_text())
        self.assertIn("common --remote_upload_local_results=false\n", rc.read_text())
        self.assertEqual((self.evidence / "bazel.log").read_text(), "flags x-buildbuddy-api-key=***\n")
        self.assertEqual((self.evidence / "clean.log").read_text(), "nothing\n")
        self.assertNotIn(KEY, result.stdout + result.stderr)

    def test_finish_without_a_key_succeeds(self):
        result = self.run_script("finish", str(self.source), str(self.evidence))
        self.assertEqual(result.returncode, 0, result.stderr)


if __name__ == "__main__":
    unittest.main()
