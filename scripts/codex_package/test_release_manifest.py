#!/usr/bin/env python3

import hashlib
from pathlib import Path
import subprocess
import tempfile
import unittest


REPOSITORY_ROOT = Path(__file__).resolve().parents[2]


class ReleaseManifestTest(unittest.TestCase):
    def test_release_verifies_without_the_builders_upstream_checkout(self) -> None:
        """A release consumer needs the active queue, not the builder's scratch checkout."""
        with tempfile.TemporaryDirectory(prefix="release-manifest-") as directory:
            root = Path(directory)
            builder = root / "builder"
            consumer = root / "consumer"
            for checkout in (builder, consumer):
                queue = checkout / "patches" / "owner" / "repo" / "issue-1"
                queue.mkdir(parents=True)
                (queue / "fix.patch").write_bytes(b"active queue patch\n")
            upstream_patch = builder / "upstream-installers" / "codex-rs" / "shell-escalation" / "patches"
            upstream_patch.mkdir(parents=True)
            (upstream_patch / "zsh-exec-wrapper.patch").write_bytes(b"upstream internal patch\n")
            release = root / "release"
            release.mkdir()
            (release / "install.sh").write_bytes(b"installer fixture\n")
            digest = hashlib.sha256((release / "install.sh").read_bytes()).hexdigest()
            (release / "SHA256SUMS").write_text(f"{digest}  install.sh\n")
            subprocess.run(
                ["python3", str(REPOSITORY_ROOT / "scripts/generate-release-manifest.py"),
                 "--release-dir", str(release), "--output", str(release / "release-manifest.json"),
                 "--patch-repo", str(builder), "--upstream-commit", "test-upstream-commit",
                 "--consolidated-checksums", "SHA256SUMS"],
                check=True, capture_output=True, text=True,
            )

            result = subprocess.run(
                ["bash", str(REPOSITORY_ROOT / "scripts/verify-release.sh"), str(release),
                 "--patch-repo", str(consumer)],
                capture_output=True, text=True,
            )

            self.assertEqual(result.returncode, 0, result.stdout + result.stderr)


if __name__ == "__main__":
    unittest.main()
