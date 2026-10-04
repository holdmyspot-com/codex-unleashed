#!/usr/bin/env python3

import hashlib
import json
import os
import shutil
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest


REPOSITORY_ROOT = Path(__file__).resolve().parents[2]
CACHE_SCRIPT = REPOSITORY_ROOT / ".github" / "scripts" / "ghcr-cargo-target-cache.sh"


class GhcrCargoTargetCacheTest(unittest.TestCase):
    def test_pull_rejects_unexpected_tag_before_restoring(self) -> None:
        with tempfile.TemporaryDirectory(prefix="codex-ghcr-cache-test-") as directory:
            root = Path(directory)
            stub_dir = root / "bin"
            stub_dir.mkdir()
            oras = stub_dir / "oras"
            oras.write_text(
                '#!/bin/sh\nprintf called > "$CACHE_TEST_MARKER"\n',
                encoding="utf-8",
            )
            oras.chmod(0o755)
            marker = root / "oras-called"
            environment = os.environ.copy()
            environment.update(
                PATH=f"{stub_dir}:{environment['PATH']}",
                CACHE_TEST_MARKER=str(marker),
            )

            result = subprocess.run(
                [
                    "bash",
                    str(CACHE_SCRIPT),
                    "pull",
                    "ghcr.io/example/cargo-cache",
                    "unexpected-tag",
                    str(root / "restored"),
                    "rust-v0.158.0",
                ],
                capture_output=True,
                text=True,
                env=environment,
            )

            self.assertEqual(result.returncode, 2)
            self.assertIn("Cargo cache tag", result.stderr)
            self.assertFalse(marker.exists())

    def test_pull_restores_dependencies_without_release_binaries(self) -> None:
        self.restore_dependencies("cargo-x86_64-apple-darwin-rust-v0.158.0", "rust-v0.158.0")

    def test_pull_reuses_compiled_dependencies_across_upstream_versions(self) -> None:
        tag = "cargo-v2-x86_64-apple-darwin-off-" + "a" * 64
        for upstream_tag in ("rust-v0.159.0", "rust-v0.159.1"):
            with self.subTest(upstream_tag=upstream_tag):
                self.restore_dependencies(tag, upstream_tag)

    def test_pull_migrates_current_release_cache_when_stable_cache_is_absent(self) -> None:
        self.restore_dependencies(
            "cargo-v2-x86_64-apple-darwin-off-" + "a" * 64,
            "rust-v0.159.1",
            stable_cache_missing=True,
        )

    def test_pull_uses_gnu_tar_when_available(self) -> None:
        self.restore_dependencies(
            "cargo-v2-x86_64-apple-darwin-off-" + "a" * 64,
            "rust-v0.160.0",
            gnu_tar_available=True,
        )

    def test_prune_keeps_only_latest_two_stable_release_caches(self) -> None:
        with tempfile.TemporaryDirectory(prefix="codex-ghcr-cache-prune-") as directory:
            root = Path(directory)
            stub_dir = root / "bin"
            stub_dir.mkdir()
            gh = stub_dir / "gh"
            gh.write_text(
                f"#!{sys.executable}\n"
                "import os, sys\n"
                "from pathlib import Path\n"
                "if '--method' in sys.argv:\n"
                "    with Path(os.environ['CACHE_TEST_DELETIONS']).open('a') as output:\n"
                "        output.write(sys.argv[-1].rsplit('/', 1)[-1] + '\\n')\n"
                "elif any('repos/openai/codex/releases' in arg for arg in sys.argv):\n"
                "    print('rust-v0.99.0\\nrust-v0.158.0\\nrust-v0.160.0\\nrust-v0.159.1')\n"
                "else:\n"
                "    print('1\tcargo-v2-x86_64-apple-darwin-off-' + 'a' * 64 + ',cargo-release-' + 'a' * 64 + '-rust-v0.160.0,cargo-x86_64-apple-darwin-rust-v0.158.0')\n"
                "    print('2\tcargo-release-' + 'b' * 64 + '-rust-v0.159.1')\n"
                "    print('3\tcargo-x86_64-apple-darwin-rust-v0.159.1')\n"
                "    print('4\tcargo-x86_64-apple-darwin-rust-v0.158.0')\n"
                "    print('5\t')\n"
                "    print('6\tcargo-release-' + 'c' * 64 + '-rust-v0.158.0')\n"
                "    print('7\tcargo-v2-x86_64-pc-windows-msvc-off-' + 'd' * 64)\n"
                "    print('8\tunrelated-tag')\n",
                encoding="utf-8",
            )
            gh.chmod(0o755)
            deletions = root / "deleted-version-ids"
            environment = os.environ.copy()
            environment.update(PATH=f"{stub_dir}:{environment['PATH']}",
                               CACHE_TEST_DELETIONS=str(deletions))
            subprocess.run([
                "bash", str(CACHE_SCRIPT), "prune", "ghcr.io/example/cargo-cache",
                "cargo-v2-x86_64-apple-darwin-off-" + "a" * 64,
                str(root / "target"), "rust-v0.159.1",
            ], check=True, env=environment, capture_output=True, text=True)

            self.assertEqual(deletions.read_text().splitlines(), ["4", "5", "6", "7"])

    def test_prune_refuses_deletion_when_release_lookup_is_empty_or_fails(self) -> None:
        for lookup_exit in (0, 1):
            with self.subTest(lookup_exit=lookup_exit), tempfile.TemporaryDirectory() as directory:
                root = Path(directory)
                gh = root / "gh"
                marker = root / "deleted"
                gh.write_text(
                    f"#!{sys.executable}\n"
                    "import sys\n"
                    "from pathlib import Path\n"
                    "if any('repos/openai/codex/releases' in arg for arg in sys.argv):\n"
                    f"    sys.exit({lookup_exit})\n"
                    "if '--method' in sys.argv:\n"
                    f"    Path({str(marker)!r}).touch()\n"
                    "else:\n"
                    "    print('1\\tcargo-old-rust-v0.1.0')\n",
                    encoding="utf-8",
                )
                gh.chmod(0o755)
                environment = os.environ.copy()
                environment["PATH"] = f"{root}:{environment['PATH']}"
                result = subprocess.run(
                    ["bash", str(CACHE_SCRIPT), "prune", "ghcr.io/example/cargo-cache",
                     "cargo-v2-target-off-" + "a" * 64, str(root / "target"), "rust-v0.160.0"],
                    env=environment, capture_output=True, text=True,
                )
                self.assertNotEqual(result.returncode, 0)
                self.assertFalse(marker.exists())

    def test_push_and_pull_round_trip_with_drive_letter_temp_path(self) -> None:
        # A relative D: directory reproduces GNU tar's remote-host parsing on
        # Linux. The hosted Windows check exercises the real drive-letter path.
        with tempfile.TemporaryDirectory(prefix="codex-ghcr-cache-drive-") as directory:
            root = Path(directory)
            (root / "D:").mkdir()
            source = root / "source"
            dependency = source / "x86_64-pc-windows-msvc" / "release" / "deps" / "example.rlib"
            dependency.parent.mkdir(parents=True)
            dependency.write_bytes(b"compiled dependency")
            stub_dir = root / "bin"
            stub_dir.mkdir()
            # These fixture processes model the registry and package-list
            # boundaries while the production script runs real Bash and tar.
            oras = stub_dir / "oras"
            oras.write_text(
                f"#!{sys.executable}\n"
                "import json, os, shutil, sys\n"
                "from pathlib import Path\n"
                "remote = Path(os.environ['CACHE_TEST_REMOTE'])\n"
                "with remote.with_suffix('.commands').open('a') as output:\n"
                "    output.write(json.dumps(sys.argv[1:]) + '\\n')\n"
                "if sys.argv[1] == 'push':\n"
                "    shutil.copyfile('cargo-target.tar.zst', remote)\n"
                "elif sys.argv[1] == 'pull':\n"
                "    destination = Path(sys.argv[sys.argv.index('--output') + 1])\n"
                "    shutil.copyfile(remote, destination / 'cargo-target.tar.zst')\n",
                encoding="utf-8",
            )
            oras.chmod(0o755)
            gh = stub_dir / "gh"
            gh.write_text(
                f"#!{sys.executable}\n"
                "import sys\n"
                "if any('repos/openai/codex/releases' in arg for arg in sys.argv):\n"
                "    print('rust-v0.160.0\\nrust-v0.159.1')\n",
                encoding="utf-8",
            )
            gh.chmod(0o755)
            environment = os.environ.copy()
            environment.update(
                PATH=f"{stub_dir}:{environment['PATH']}",
                RUNNER_TEMP="D:",
                CACHE_TEST_REMOTE=str(root / "registry-archive"),
            )
            target = root / "restored"
            for operation, target_directory in (("push", source), ("pull", target)):
                result = subprocess.run(
                    ["bash", str(CACHE_SCRIPT), operation, "ghcr.io/example/cargo-cache",
                     "cargo-v2-x86_64-pc-windows-msvc-off-" + "a" * 64,
                     str(target_directory), "rust-v0.160.0"],
                    cwd=root, env=environment, capture_output=True, text=True,
                )
                self.assertEqual(result.returncode, 0, result.stderr)
            self.assertEqual(
                (target / dependency.relative_to(source)).read_bytes(), b"compiled dependency",
            )
            commands = [json.loads(line) for line in
                        (root / "registry-archive.commands").read_text().splitlines()]
            cache_tag = "cargo-v2-x86_64-pc-windows-msvc-off-" + "a" * 64
            cache_identity = hashlib.sha256(cache_tag.encode()).hexdigest()
            release_reference = f"ghcr.io/example/cargo-cache:cargo-release-{cache_identity}-rust-v0.160.0"
            self.assertEqual(commands[0][:2], ["push", release_reference])
            self.assertEqual(commands[1], ["tag", release_reference, cache_tag])
            self.assertEqual(list((root / "D:").iterdir()), [])

    def restore_dependencies(self, cache_tag: str, upstream_tag: str, stable_cache_missing: bool = False,
                             gnu_tar_available: bool = False) -> None:
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
            (release / "bwrap").write_bytes(b"cached bwrap")
            os.link(release / "codex", dependency.parent / "codex-hashed-binary")
            os.link(release / "bwrap", dependency.parent / "bwrap-hashed-binary")
            symbol = release / "codex.dSYM" / "Contents" / "Resources" / "DWARF" / "codex"
            symbol.parent.mkdir(parents=True)
            symbol.write_bytes(b"cached symbols")

            archive = root / "cargo-target.tar.zst"
            subprocess.run(
                ["tar", "--zstd", "-cf", str(archive), "-C", str(source),
                 "./x86_64-apple-darwin/release/codex",
                 "./x86_64-apple-darwin/release/bwrap",
                 "./x86_64-apple-darwin/release/deps",
                 "./x86_64-apple-darwin/release/codex-code-mode-host",
                 "./x86_64-apple-darwin/release/codex-responses-api-proxy",
                 "./x86_64-apple-darwin/release/codex.dSYM"],
                check=True,
            )
            stub_dir = root / "bin"
            stub_dir.mkdir()
            oras = stub_dir / "oras"
            oras.write_text(
                f"#!{sys.executable}\n"
                "import os, shutil, sys\n"
                "from pathlib import Path\n"
                "with Path(os.environ['CACHE_TEST_REQUESTS']).open('a') as output:\n"
                "    output.write(sys.argv[2] + '\\n')\n"
                "if os.environ.get('CACHE_TEST_STABLE_MISS') == '1' and ':cargo-v2-' in sys.argv[2]:\n"
                "    raise SystemExit(1)\n"
                "shutil.copyfile(os.environ['CACHE_TEST_ARCHIVE'], Path(sys.argv[5]) / 'cargo-target.tar.zst')\n",
                encoding="utf-8",
            )
            oras.chmod(0o755)

            if gnu_tar_available:
                # The native command models the hosted macOS extraction failure;
                # GNU tar must restore this same archive through the production launcher.
                real_tar = shutil.which("gtar") or shutil.which("tar")
                self.assertIsNotNone(real_tar)
                (stub_dir / "gtar").symlink_to(real_tar)
                native_tar = stub_dir / "tar"
                native_tar.write_text(
                    f"#!{sys.executable}\n"
                    "import sys\n"
                    "print('native tar extraction failed', file=sys.stderr)\n"
                    "raise SystemExit(1)\n",
                    encoding="utf-8",
                )
                native_tar.chmod(0o755)

            target = root / "restored"
            cached_release = target / "x86_64-apple-darwin" / "release"
            cached_release.mkdir(parents=True)
            (cached_release / "codex-responses-api-proxy").write_bytes(b"previous cache")
            cached_symbols = cached_release / "codex.dSYM" / "Contents"
            cached_symbols.mkdir(parents=True)
            environment = os.environ.copy()
            environment.update(
                PATH=f"{stub_dir}:{environment['PATH']}",
                RUNNER_TEMP=str(root),
                CACHE_TEST_ARCHIVE=str(archive),
                CACHE_TEST_REQUESTS=str(root / "cache-requests"),
                CACHE_TEST_STABLE_MISS="1" if stable_cache_missing else "0",
            )
            subprocess.run(
                [
                    "bash",
                    str(CACHE_SCRIPT),
                    "pull",
                    "ghcr.io/example/cargo-cache",
                    cache_tag,
                    str(target),
                    upstream_tag,
                ],
                check=True,
                env=environment,
            )

            requests = (root / "cache-requests").read_text().splitlines()
            expected = [f"ghcr.io/example/cargo-cache:{cache_tag}"]
            if stable_cache_missing:
                expected.append(f"ghcr.io/example/cargo-cache:cargo-x86_64-apple-darwin-{upstream_tag}")
            self.assertEqual(requests, expected)

            restored = target / "x86_64-apple-darwin" / "release"
            self.assertEqual((restored / "deps" / dependency.name).read_bytes(), b"reusable dependency")
            self.assertEqual(
                (restored / "deps" / codex_dependency.name).read_bytes(),
                b"reusable codex dependency",
            )
            self.assertEqual((restored / "deps" / "codex-hashed-binary").read_bytes(),
                             b"cached executable")
            self.assertEqual((restored / "deps" / "bwrap-hashed-binary").read_bytes(),
                             b"cached bwrap")
            self.assertFalse((restored / "bwrap").exists())
            for binary in ("codex", "codex-code-mode-host", "codex-responses-api-proxy"):
                self.assertFalse((restored / binary).exists(), binary)
            self.assertFalse((restored / "codex.dSYM").exists())


if __name__ == "__main__":
    unittest.main()
