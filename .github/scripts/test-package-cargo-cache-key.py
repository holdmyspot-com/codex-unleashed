#!/usr/bin/env python3
"""Exercise the package cache key receipt with isolated tool and native-input fixtures."""

import os
from pathlib import Path
import re
import shutil
import subprocess
import tempfile
import unittest


TARGET = "x86_64-unknown-linux-musl"
CACHE_SCRIPT = Path(__file__).with_name("package-cargo-cache.sh").resolve()
COMPONENTS = ["source", "patches", "wrappers", "helper", "compiler", "v8", "musl", "voice"]


def write(path, text):
    """Create a fixture file and any missing parent directories."""
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(text)
    return path


@unittest.skipUnless(shutil.which("bash") and shutil.which("sha256sum"), "Linux command-line tools required")
class PackageCargoCacheKeyTest(unittest.TestCase):
    """Check the format and contents retained for exact-key diagnostics."""

    def setUp(self):
        temporary = tempfile.TemporaryDirectory(prefix="package-cache-key-test-")
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)
        tools = self.root / "bin"
        source = self.root / "private-source"
        head = "a" * 40
        git = write(tools / "git", f"""#!/bin/bash
case "$1" in
  rev-parse) printf '%s\\n' '{head}';;
  status) ;;
  *) exit 2;;
esac
""")
        git.chmod(0o755)
        tool = write(tools / "fixture-tool", "#!/bin/bash\nprintf 'fixed tool identity\\n'\n")
        tool.chmod(0o755)
        for name in ("cargo", "rustc", "zig", "gcc", "clang", "strip", "dpkg-query"):
            (tools / name).symlink_to(tool)
        launcher = write(self.root / "tooling", """#!/bin/bash
set -euo pipefail
tag="$(cat "${@:3}" | sha256sum | cut -d ' ' -f 1)"
printf 'tag=cargo-v3-%s-%s\\n' "$2" "$tag"
printf 'seed_tag=ignored\\n'
""")
        write(source / ".github/scripts/codex-package-operations.sh", "printf 'fixed operation\\n'\n")
        write(source / "codex-rs/Cargo.lock", "lock\n")
        write(source / "codex-rs/rust-toolchain.toml", "toolchain\n")
        runner = self.root / "private-runner-temp"
        metadata = runner / "manifest-patch-repo/.github"
        write(metadata / "scripts/ghcr-cargo-target-cache.sh", "transport\n")
        write(metadata / "scripts/package-cargo-cache.sh", CACHE_SCRIPT.read_text())
        write(metadata / "workflows/upstream-auto-release.yml", "workflow\n")
        write(runner / "downstream-patches/fixture.patch", "fixed patch\n")
        (runner / f"codex-musl-tools-{TARGET}/libcap-1/prefix").mkdir(parents=True)
        openssl = self.root / "private-openssl"
        write(openssl / "include/openssl.h", "header\n")
        write(openssl / "lib/libcrypto.a", "native\n")
        voice = self.root / "private-voice"
        self.voice = write(voice / "helper", "sealed voice\n")
        self.receipt = runner / "package-cargo-cache-key.txt"
        self.output = self.root / "output"
        self.source = source
        self.environment = {
            **os.environ, "PATH": f"{tools}:{os.environ['PATH']}",
            "RUNNER_TEMP": str(runner), "RUNNER_OS": "Linux", "RUNNER_ARCH": "X64", "TARGET": TARGET,
            "UPSTREAM_COMMIT": "b" * 40, "UPSTREAM_TAG": "rust-v0.167.0", "VERSION": "0.167.0",
            "CODEX_UNLEASHED_BUILD_NUMBER": "1", "VOICE_BUILD_COMMIT": head, "PATCHES": "patches/fixture.patch",
            "ImageOS": "ubuntu24", "ImageVersion": "fixture", "GITHUB_WORKSPACE": str(source),
            "CARGO_TARGET_DIR": str(self.root / "private-cargo-target"), "SOURCE_DATE_EPOCH": "123",
            "CARGO_TARGET_X86_64_UNKNOWN_LINUX_MUSL_LINKER": str(tool), "CC": str(tool), "CXX": str(tool),
            "RUSTFLAGS": "private-rustflag-fixture", "X86_64_UNKNOWN_LINUX_MUSL_OPENSSL_DIR": str(openssl),
            "RUSTY_V8_ARCHIVE": str(write(self.root / "v8", "v8 archive\n")),
            "RUSTY_V8_SRC_BINDING_PATH": str(write(self.root / "v8.rs", "binding\n")),
            "VOICE_RELEASE_DIR": str(voice), "CODEX_TOOLING": str(launcher), "GITHUB_OUTPUT": str(self.output),
        }

    def run_inputs(self):
        """Run the normal key command and return only the retained evidence."""
        self.output.write_text("")
        result = subprocess.run(
            ["bash", CACHE_SCRIPT, "inputs"], cwd=self.source, env=self.environment,
            capture_output=True, text=True, check=False,
        )
        self.assertEqual(result.returncode, 0, result.stderr)
        tag, *digests = self.receipt.read_text().splitlines()
        self.assertRegex(tag, rf"^tag=cargo-v3-{TARGET}-pkg-[0-9a-f]{{64}}$")
        self.assertEqual(self.output.read_text(), f"{tag}\n")
        self.assertEqual(result.stdout.splitlines(), digests)
        self.assertEqual([name for _, name in (line.split() for line in digests)], COMPONENTS)
        self.assertTrue(all(re.fullmatch(r"[0-9a-f]{64}  [a-z0-9]+", line) for line in digests))
        return tag, {name: digest for digest, name in (line.split() for line in digests)}

    def test_records_only_the_exact_tag_and_component_digests(self):
        initial = self.run_inputs()
        self.assertEqual(self.run_inputs(), initial)
        self.assertNotIn(str(self.root), self.receipt.read_text())
        self.assertNotIn(self.environment["RUSTFLAGS"], self.receipt.read_text())

    def test_locates_generated_voice_changes_without_exposing_their_path(self):
        initial_key, initial_components = self.run_inputs()
        self.voice.write_text("changed sealed voice\n")
        key, components = self.run_inputs()
        self.assertNotEqual(initial_key, key)
        self.assertEqual([name for name in COMPONENTS if initial_components[name] != components[name]], ["voice"])


if __name__ == "__main__":
    unittest.main()
