#!/usr/bin/env python3

import os
from pathlib import Path
import subprocess
import tempfile
import unittest


REPOSITORY_ROOT = Path(__file__).resolve().parents[2]
CACHE_SCRIPT = REPOSITORY_ROOT / ".github" / "scripts" / "ghcr-cargo-target-cache.sh"


class GhcrCargoTargetCacheTest(unittest.TestCase):
    def test_pull_restores_dependencies_without_release_binaries(self) -> None:
        with tempfile.TemporaryDirectory(prefix="codex-ghcr-cache-test-") as directory:
            root = Path(directory)
            source = root / "source"
            release = source / "x86_64-apple-darwin" / "release"
            dependency = release / "deps" / "libcodex_example.rlib"
            dependency.parent.mkdir(parents=True)
            dependency.write_bytes(b"reusable dependency")
            codex_dependency = dependency.parent / "codex-generated-data"
            codex_dependency.write_bytes(b"reusable codex dependency")
            for binary in ("codex", "codex-code-mode-host", "codex-responses-api-proxy"):
                (release / binary).write_bytes(b"cached executable")
            symbol = release / "codex.dSYM" / "Contents" / "Resources" / "DWARF" / "codex"
            symbol.parent.mkdir(parents=True)
            symbol.write_bytes(b"cached symbols")

            archive = root / "cargo-target.tar.zst"
            subprocess.run(
                ["tar", "--zstd", "-cf", str(archive), "-C", str(source), "."],
                check=True,
            )
            stub_dir = root / "bin"
            stub_dir.mkdir()
            oras = stub_dir / "oras"
            oras.write_text(
                '#!/bin/sh\ncp "$CACHE_TEST_ARCHIVE" "$5/cargo-target.tar.zst"\n',
                encoding="utf-8",
            )
            oras.chmod(0o755)

            target = root / "restored"
            environment = os.environ.copy()
            environment.update(
                PATH=f"{stub_dir}:{environment['PATH']}",
                RUNNER_TEMP=str(root),
                CACHE_TEST_ARCHIVE=str(archive),
            )
            subprocess.run(
                [
                    "bash",
                    str(CACHE_SCRIPT),
                    "pull",
                    "ghcr.io/example/cargo-cache",
                    "cargo-x86_64-apple-darwin-rust-v0.158.0",
                    str(target),
                    "rust-v0.158.0",
                ],
                check=True,
                env=environment,
            )

            restored = target / "x86_64-apple-darwin" / "release"
            self.assertEqual((restored / "deps" / dependency.name).read_bytes(), b"reusable dependency")
            self.assertEqual(
                (restored / "deps" / codex_dependency.name).read_bytes(),
                b"reusable codex dependency",
            )
            for binary in ("codex", "codex-code-mode-host", "codex-responses-api-proxy"):
                self.assertFalse((restored / binary).exists(), binary)
            self.assertFalse((restored / "codex.dSYM").exists())


if __name__ == "__main__":
    unittest.main()
