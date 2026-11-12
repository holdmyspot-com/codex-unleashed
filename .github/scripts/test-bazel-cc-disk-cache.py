#!/usr/bin/env python3
"""Exercise the Bazel C++ action cache script against a fake GHCR: restoring and merging a shared lane, what save
uploads, the exists check, and which inputs change the cache tags."""

import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

SCRIPT = Path(__file__).with_name("bazel-cc-disk-cache.sh")

# Stands in for ghcr-cargo-target-cache.sh: keeps each pushed tag as a directory under FAKE_STORE and logs every call.
FAKE_TRANSPORT = r"""#!/usr/bin/env bash
set -euo pipefail
printf '%s\n' "$*" >> "$FAKE_STORE/calls.log"
operation="$1" tag="$3" directory="$4"
case "$operation" in
  pull)
    if [[ -n "${FAKE_FAIL_TAG:-}" && "$tag" == "$FAKE_FAIL_TAG"* ]]; then
      printf 'partial' > "$directory/partial-download"
      exit 42
    fi
    [[ -d "$FAKE_STORE/$tag" ]] || exit 3
    cp -pR "$FAKE_STORE/$tag/." "$directory/"
    ;;
  push)
    for name in "$tag" "$6"; do
      rm -rf -- "${FAKE_STORE:?}/$name"
      mkdir -p "$FAKE_STORE/$name"
      cp -pR "$directory/." "$FAKE_STORE/$name/"
    done
    ;;
esac
"""

# Stands in for oras: login succeeds, and resolve finds the tags that FAKE_STORE holds.
FAKE_ORAS = r"""#!/usr/bin/env bash
set -euo pipefail
case "$1" in
  login) cat > /dev/null ;;
  resolve)
    if [[ -n "${FAKE_ORAS_ERROR:-}" ]]; then
      printf 'Error: %s\n' "$FAKE_ORAS_ERROR" >&2
      exit 1
    fi
    if [[ -d "$FAKE_STORE/${2##*:}" ]]; then
      printf 'sha256:0\n'
    else
      printf 'Error: %s: not found\n' "$2" >&2
      exit 1
    fi
    ;;
esac
"""


def remove_tree(directory):
    """Remove the directory tree, including the files that Git on Windows writes read-only."""
    for path in directory.rglob("*"):
        if path.is_file() and not path.is_symlink():
            path.chmod(0o644)
    shutil.rmtree(directory)


class BazelCcDiskCacheTest(unittest.TestCase):
    """Check the tags, merges and uploads of the C++ action cache script."""

    def setUp(self):
        self.root = Path(tempfile.mkdtemp())
        self.addCleanup(remove_tree, self.root)
        scripts = self.root / "scripts"
        scripts.mkdir()
        shutil.copy(SCRIPT, scripts / SCRIPT.name)
        (scripts / "ghcr-cargo-target-cache.sh").write_text(FAKE_TRANSPORT)
        self.script = scripts / SCRIPT.name
        bin_directory = self.root / "bin"
        bin_directory.mkdir()
        oras = bin_directory / "oras"
        oras.write_text(FAKE_ORAS)
        oras.chmod(0o755)
        self.store = self.root / "store"
        self.store.mkdir()
        self.source = self.root / "source"
        self.source.mkdir()
        for name, text in {".bazelversion": "9.2.0\n", "MODULE.bazel": "module()\n",
                           "third_party/v8/BUILD.bazel": "v8\n", "codex-rs/core/lib.rs": "fn main() {}\n"}.items():
            path = self.source / name
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(text)
        self.commit_source()
        self.cache = self.root / "cache"
        self.output = self.root / "github-output"
        self.output.touch()
        self.environment = dict(os.environ, PATH=bin_directory.as_posix() + os.pathsep + os.environ["PATH"],
                                FAKE_STORE=self.store.as_posix(), GITHUB_OUTPUT=self.output.as_posix(),
                                GITHUB_TOKEN="token", GITHUB_ACTOR="actor", RUNNER_OS="Linux", RUNNER_ARCH="X64",
                                ImageOS="ubuntu24")
        for name in ("FAKE_FAIL_TAG", "FAKE_ORAS_ERROR", "VCToolsInstallDir", "WindowsSDKVersion", "UCRTVersion"):
            self.environment.pop(name, None)

    def commit_source(self):
        """Commit every file of the source checkout, which the script reads from its HEAD commit."""
        git = ["git", "-C", self.source.as_posix(), "-c", "user.name=test", "-c", "user.email=test@example.com"]
        if not (self.source / ".git").exists():
            subprocess.run([*git, "init", "-q"], check=True)
        subprocess.run([*git, "add", "-A"], check=True)
        subprocess.run([*git, "commit", "-q", "--allow-empty", "-m", "source"], check=True)

    def run_script(self, operation, *lanes, **environment):
        """Run the script for the lanes with extra environment values and return its completed process."""
        return subprocess.run(
            [shutil.which("bash"), self.script.as_posix(), operation, self.source.as_posix(), self.cache.as_posix(),
             "ghcr.io/owner/cache", "rust-v0.167.0", *lanes],
            capture_output=True, text=True, env=dict(self.environment, **environment))

    def outputs(self):
        """Return the GITHUB_OUTPUT lines written so far."""
        return self.output.read_text().splitlines()

    def saved_tags(self):
        """Return the names of the stored tags."""
        return sorted(path.name for path in self.store.iterdir() if path.is_dir())

    def save_lane(self, lane, files):
        """Save the files, a mapping from relative path to text, under the lane's exact and fallback tags."""
        self.assertEqual(self.run_script("restore", lane).returncode, 0)
        for name, text in files.items():
            path = self.cache / name
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(text)
        result = self.run_script("save", lane)
        self.assertEqual(result.returncode, 0, result.stderr)

    def test_restore_merges_the_shared_lane_and_save_leaves_it_out(self):
        """A lint shard's cache gets the shared lane's entries, and its save uploads only the shard's own entries."""
        self.save_lane("clippy-v8", {"cas/aa": "v8", "cas/bb": "shared and own"})
        self.save_lane("clippy-1", {"cas/bb": "shared and own", "cas/cc": "own"})
        shutil.rmtree(self.cache)

        result = self.run_script("restore", "clippy-1", "clippy-v8")
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(sorted(p.relative_to(self.cache).as_posix() for p in self.cache.rglob("*") if p.is_file()),
                         ["cas/aa", "cas/bb", "cas/cc"])
        self.assertIn("hit=exact", self.outputs())
        (self.cache / "cas" / "dd").write_text("new")

        result = self.run_script("save", "clippy-1", "clippy-v8")
        self.assertEqual(result.returncode, 0, result.stderr)
        own = [tag for tag in self.saved_tags() if tag.startswith("cargo-v3-bazel-cc-clippy-1-")]
        self.assertEqual(len(own), 1)
        self.assertEqual(sorted(p.name for p in (self.store / own[0] / "cas").iterdir()), ["cc", "dd"])

    def test_missing_shared_lane_still_restores_the_own_lane(self):
        """When the shared lane was never saved, restore still reports and keeps the own lane's hit."""
        self.save_lane("clippy-1", {"cas/cc": "own"})
        shutil.rmtree(self.cache)
        result = self.run_script("restore", "clippy-1", "clippy-v8")
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertTrue((self.cache / "cas" / "cc").is_file())
        self.assertIn("hit=exact", self.outputs())

    def test_failed_shared_pull_leaves_no_partial_entries(self):
        """A shared lane pull that fails part way warns and adds nothing to the cache."""
        self.save_lane("clippy-v8", {"cas/aa": "v8"})
        shutil.rmtree(self.cache)
        result = self.run_script("restore", "clippy-1", "clippy-v8", FAKE_FAIL_TAG="cargo-v3-bazel-cc-clippy-v8-")
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn("::warning::Pulling the C++ action cache cargo-v3-bazel-cc-clippy-v8-", result.stderr)
        # The fallback tag shares the exact tag's contents, so the second candidate restores the entry.
        self.assertEqual(sorted(p.name for p in self.cache.rglob("*") if p.is_file()), ["aa"])

    def test_save_skips_an_unchanged_exact_cache(self):
        """Save uploads nothing when restore found the exact tag and the build added no entry."""
        self.save_lane("clippy-v8", {"cas/aa": "v8"})
        shutil.rmtree(self.cache)
        self.assertEqual(self.run_script("restore", "clippy-v8").returncode, 0)
        result = self.run_script("save", "clippy-v8")
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn("skipping the upload", result.stdout)
        pushes = [line for line in (self.store / "calls.log").read_text().splitlines() if line.startswith("push ")]
        self.assertEqual(len(pushes), 1)

    def test_exists_reports_whether_the_exact_tag_is_stored(self):
        """Exists reports false before a save and true after it, and false with a warning when GHCR cannot answer."""
        result = self.run_script("exists", "clippy-v8")
        self.assertEqual(result.returncode, 0, result.stderr)
        self.save_lane("clippy-v8", {"cas/aa": "v8"})
        self.assertEqual(self.run_script("exists", "clippy-v8").returncode, 0)
        result = self.run_script("exists", "clippy-v8", FAKE_ORAS_ERROR="connection refused")
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn("::warning::Could not check for the C++ action cache", result.stdout)
        self.assertEqual([line for line in self.outputs() if line.startswith("exists=")],
                         ["exists=false", "exists=true", "exists=false"])

    def exact_tag(self, **environment):
        """Return the exact tag of lane clippy-v8 for the current source and the extra environment values."""
        self.output.write_text("")
        result = self.run_script("exists", "clippy-v8", **environment)
        self.assertEqual(result.returncode, 0, result.stderr)
        return result.stdout.split()[3]

    def test_exact_tag_follows_bazel_inputs_and_not_rust_sources(self):
        """The exact tag changes with third_party and root files, and not with a Rust source."""
        original = self.exact_tag()
        (self.source / "codex-rs" / "core" / "lib.rs").write_text("fn main() { }\n")
        self.commit_source()
        self.assertEqual(self.exact_tag(), original)
        (self.source / "third_party" / "v8" / "BUILD.bazel").write_text("v8 changed\n")
        self.commit_source()
        changed = self.exact_tag()
        self.assertNotEqual(changed, original)
        (self.source / "defs.bzl").write_text("defs\n")
        self.commit_source()
        self.assertNotEqual(self.exact_tag(), changed)

    def test_windows_tag_follows_the_msvc_identity(self):
        """On Windows the tag changes with the MSVC tools, and a missing identity fails with status 2."""
        windows = {"RUNNER_OS": "Windows", "WindowsSDKVersion": "10.0.26100.0\\", "UCRTVersion": "10.0.26100.0"}
        first = self.exact_tag(VCToolsInstallDir="C:\\VC\\Tools\\MSVC\\14.44.35207\\", **windows)
        second = self.exact_tag(VCToolsInstallDir="C:\\VC\\Tools\\MSVC\\14.45.36000\\", **windows)
        self.assertNotEqual(first, second)
        result = self.run_script("exists", "clippy-v8", **windows)
        self.assertEqual(result.returncode, 2)
        self.assertIn("VCToolsInstallDir must name the MSVC tools", result.stderr)

    def test_invalid_lane_is_rejected(self):
        """A lane outside 1-40 characters of [A-Za-z0-9_-] fails with status 2."""
        result = self.run_script("restore", "clippy-1", "bad lane")
        self.assertEqual(result.returncode, 2)
        self.assertIn("Lane must be", result.stderr)


if __name__ == "__main__":
    unittest.main()
