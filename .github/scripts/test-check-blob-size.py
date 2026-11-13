#!/usr/bin/env python3
"""Exercise the blob policy at its Git and command-line boundary."""
import importlib
import io
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path


# Python's import statement cannot name the maintained hyphenated script.
policy = importlib.import_module('check-blob-size')


class BlobSizeDecisionTest(unittest.TestCase):
    """Verify the exact byte threshold without creating Git objects."""

    def test_boundary_and_non_blob_objects(self):
        """The exact limit passes and oversized commits/trees are outside this policy."""
        diagnostics = io.StringIO()
        result = policy.check_sizes(['a blob 512000', 'b tree 999999', 'c commit 999999'], diagnostics)
        self.assertEqual(result, 0)
        self.assertEqual(diagnostics.getvalue(), '')

    def test_reports_oversized_blob_and_recovery(self):
        """A blob one byte over the limit reports its identity and recovery command."""
        diagnostics = io.StringIO()
        result = policy.check_sizes(['abc blob 512001'], diagnostics)
        self.assertEqual(result, 1)
        self.assertIn('Blob abc is 512001 bytes; maximum is 512000', diagnostics.getvalue())
        self.assertIn('git log --find-object=abc', diagnostics.getvalue())


class BlobPolicyTest(unittest.TestCase):
    """Each case owns its repository and invokes the maintained policy command."""

    def test_rejects_new_oversized_blob(self):
        """The CLI accepts existing history and rejects a newly introduced then deleted large blob."""
        with tempfile.TemporaryDirectory() as directory:
            repository = Path(directory)
            def git(*arguments):
                """Run Git in this case's isolated repository and return its output."""
                return subprocess.check_output(['git', '-C', directory, *arguments], text=True).strip()
            git('init', '--quiet')
            git('config', 'user.name', 'Policy fixture')
            git('config', 'user.email', 'policy@example.invalid')
            git('commit', '--quiet', '--allow-empty', '-m', 'base')
            (repository / 'legacy').write_bytes(b'z' * 512001)
            git('add', '--', 'legacy')
            git('commit', '--quiet', '-m', 'existing history')
            base = git('rev-parse', 'HEAD')
            (repository / 'normal asset').write_bytes(b'n' * 512000)
            git('add', '--', 'normal asset')
            git('commit', '--quiet', '-m', 'normal new blob')
            checker = Path(__file__).resolve().with_name('check-blob-size.py')
            accepted = subprocess.run([sys.executable, str(checker), '--base', base, '--head', 'HEAD'],
                                      cwd=repository, capture_output=True, text=True)
            self.assertEqual(accepted.returncode, 0, accepted.stderr)
            (repository / 'large asset').write_bytes(b'x' * 512001)
            git('add', '--', 'large asset')
            git('commit', '--quiet', '-m', 'large blob')
            git('rm', '--quiet', '--', 'large asset')
            git('commit', '--quiet', '-m', 'remove large blob')
            checker = Path(__file__).resolve().with_name('check-blob-size.py')
            result = subprocess.run([sys.executable, str(checker), '--base', base, '--head', 'HEAD'],
                                    cwd=repository, capture_output=True, text=True)
            self.assertEqual(result.returncode, 1, result.stderr)


if __name__ == '__main__':
    unittest.main()
