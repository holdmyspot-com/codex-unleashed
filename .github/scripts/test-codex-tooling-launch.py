#!/usr/bin/env python3
"""Check that release shell helpers execute the packaged codex-tooling launcher for each runner OS.

Linux packages a native ELF launcher, which Bash cannot read as a script; other runners package a Bash launcher.
"""
import os
import shutil
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

SCRIPTS = Path(__file__).resolve().parent
DIGEST = '0' * 64


class CodexToolingLaunchTest(unittest.TestCase):
    """Runs the cache pull path against a controlled launcher."""

    def pull(self, root, runner_os, launcher):
        """Pull one parts-mode reference through the launcher and return the completed process."""
        env = dict(os.environ, RUNNER_OS=runner_os, RUNNER_TEMP=str(root), CARGO_CACHE_PARTS='1',
                   CODEX_SOURCE_CACHE_TOOL=str(launcher))
        return subprocess.run(['bash', str(SCRIPTS / 'ghcr-cargo-target-cache.sh'), 'pull', str(root / 'registry'),
                               f'cargo-v3-x86_64-unknown-linux-musl-{DIGEST}', str(root / 'target'), 'rust-v0.0.0'],
                              env=env, capture_output=True, text=True)

    @unittest.skipUnless(sys.platform.startswith('linux'), 'The packaged ELF launcher is a Linux contract')
    def test_linux_executes_native_launcher(self):
        """A Linux runner executes the launcher directly rather than parsing it as Bash source."""
        with tempfile.TemporaryDirectory(dir=os.environ.get('TMPDIR')) as tmp:
            root = Path(tmp)
            launcher = root / 'codex-tooling'
            launcher.symlink_to(shutil.which('true'))
            result = self.pull(root, 'Linux', launcher)
            self.assertEqual(result.returncode, 0, result.stderr)

    def test_other_runners_source_bash_launcher(self):
        """Other runners keep passing their non-executable Bash launcher to Bash."""
        with tempfile.TemporaryDirectory(dir=os.environ.get('TMPDIR')) as tmp:
            root = Path(tmp)
            launcher = root / 'codex-tooling'
            launcher.write_text('exit 0\n')
            launcher.chmod(0o644)
            result = self.pull(root, 'macOS', launcher)
            self.assertEqual(result.returncode, 0, result.stderr)


if __name__ == '__main__':
    unittest.main()
