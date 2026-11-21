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
COMPILER = ["compiler-context", "compiler-toolchain", "compiler-packages", "compiler-settings", "compiler-lockfiles"]
VOICE = ["voice/codex-voice-host", "voice/runtime/lib/libfixture.so.1", "voice/runtime/runtime.json"]


def write(path, text):
    """Create a fixture file and any missing parent directories."""
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(text)
    return path


@unittest.skipUnless(all(shutil.which(tool) for tool in ("bash", "jq", "sha256sum")), "Linux command-line tools required")
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
        for name in ("cargo", "rustc", "gcc", "clang", "strip", "dpkg-query"):
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
        self.voice = write(voice / "codex-voice-host", "sealed voice\n")
        write(voice / "runtime/runtime.json", '{"libraries":[{"path":"lib/libfixture.so.1"}]}\n')
        write(voice / "runtime/lib/libfixture.so.1", "library bytes\n")
        self.receipt = runner / "package-cargo-cache-key.txt"
        self.output = self.root / "output"
        self.source = source
        self.original_zig = self.root / "temporary-extraction-id-1"
        self.stable_zig = runner / "package-cache-zig"
        write(self.original_zig / "zig", "#!/bin/bash\nprintf '0.14.0\n'\n").chmod(0o755)
        write(self.original_zig / "lib/std/std.zig", "Zig standard library fixture\n")
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
            "GITHUB_ENV": str(self.root / "github-env"), "GITHUB_PATH": str(self.root / "github-path"),
        }
        self.stage_zig(self.original_zig)

    def stage_zig(self, origin):
        """Stage a fixture Zig distribution and apply the exported runner settings."""
        path_file = Path(self.environment["GITHUB_PATH"])
        env_file = Path(self.environment["GITHUB_ENV"])
        path_file.write_text("")
        env_file.write_text("")
        result = subprocess.run(
            ["bash", CACHE_SCRIPT, "stage-zig"],
            cwd=self.source, env={**self.environment, "PATH": f"{origin}:{self.environment['PATH']}"},
            capture_output=True, text=True, check=False,
        )
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(env_file.read_text(), f"CODEX_PACKAGE_CACHE_ZIG={self.stable_zig}/zig\n")
        self.assertEqual(path_file.read_text(), f"{self.stable_zig}\n")
        self.environment["CODEX_PACKAGE_CACHE_ZIG"] = str(self.stable_zig / "zig")
        self.environment["PATH"] = f"{self.stable_zig}:{self.environment['PATH']}"

    def execute_inputs(self):
        """Invoke the key command with a fresh workflow step output."""
        self.output.write_text("")
        return subprocess.run(
            ["bash", CACHE_SCRIPT, "inputs"], cwd=self.source, env=self.environment,
            capture_output=True, text=True, check=False,
        )

    def run_inputs(self):
        """Return the retained tag and labeled digests from a successful call."""
        result = self.execute_inputs()
        self.assertEqual(result.returncode, 0, result.stderr)
        tag, *digests = self.receipt.read_text().splitlines()
        self.assertRegex(tag, rf"^tag=cargo-v3-{TARGET}-pkg-[0-9a-f]{{64}}$")
        self.assertEqual(self.output.read_text(), f"{tag}\n")
        self.assertEqual(result.stdout.splitlines(), digests[:len(COMPONENTS)])
        self.assertEqual([name for _, name in (line.split() for line in digests)], COMPONENTS + COMPILER + VOICE)
        self.assertTrue(all(re.fullmatch(r"[0-9a-f]{64}  [a-z0-9/.-]+", line) for line in digests))
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
        names = COMPONENTS + COMPILER + VOICE
        self.assertEqual([name for name in names if initial_components[name] != components[name]],
                         ["voice", "voice/codex-voice-host"])

    def test_zig_installation_uuid_does_not_change_the_key(self):
        initial = self.run_inputs()
        different_origin = self.root / "temporary-extraction-id-2"
        shutil.copytree(self.original_zig, different_origin)
        shutil.rmtree(self.stable_zig)
        self.stage_zig(different_origin)
        self.assertEqual(self.run_inputs(), initial)

    def test_zig_standard_library_bytes_change_the_key(self):
        initial_key, initial_components = self.run_inputs()
        (self.stable_zig / "lib/std/std.zig").write_text("different Zig standard library\n")
        key, components = self.run_inputs()
        self.assertNotEqual(initial_key, key)
        self.assertNotEqual(initial_components["compiler-toolchain"], components["compiler-toolchain"])

    def test_locates_drift_in_the_compiler_context(self):
        initial_key, initial_components = self.run_inputs()
        self.environment["ImageVersion"] = "new-runner-image"
        key, components = self.run_inputs()
        self.assertNotEqual(initial_key, key)
        names = COMPONENTS + COMPILER + VOICE
        self.assertEqual([name for name in names if initial_components[name] != components[name]],
                         ["compiler", "compiler-context"])

    def test_rejects_unexpected_paths_without_publishing_them(self):
        unexpected = self.voice.parent / "runtime/lib/libprivate-fixture.so"
        write(unexpected, "unexpected file\n")
        result = self.execute_inputs()
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("do not match the runtime inventory", result.stderr)
        self.assertNotIn(unexpected.name, result.stdout + result.stderr)
        self.assertFalse(self.receipt.exists())
        self.assertEqual(self.output.read_text(), "")


if __name__ == "__main__":
    unittest.main()
