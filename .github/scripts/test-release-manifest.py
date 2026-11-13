#!/usr/bin/env python3
"""Exercise receipt, assembly and verification commands on tiny real Git sources and controlled release assets.

The fake gh executable proves fixed policy arguments and nonzero exit propagation, not hosted attestation validity.
Run with Python's isolated mode; fixtures are test-owned and removed after their final consumer.
"""

import hashlib
import io
import json
from pathlib import Path
import shutil
import subprocess
import sys
import tarfile
import tempfile
import unittest

# Six release targets and their payload names are the public manifest contract exercised by these commands.
TARGETS = (
    "aarch64-apple-darwin", "aarch64-pc-windows-msvc", "aarch64-unknown-linux-musl",
    "x86_64-apple-darwin", "x86_64-pc-windows-msvc", "x86_64-unknown-linux-musl"
)
HELPER = Path(__file__).with_name("release-manifest.py")
REPOSITORY = "holdmyspot-com/codex-unleashed"
WORKFLOW = REPOSITORY + "/.github/workflows/upstream-auto-release.yml"


def process(arguments, cwd=None, env=None):
    """Run a test-controlled public command and return all output and its exit status."""
    return subprocess.run([str(arg) for arg in arguments], cwd=cwd, env=env, text=True, capture_output=True)


def git(root, *arguments):
    """Run a fixture Git operation with an explicit identity and fail immediately on fixture errors."""
    result = process(["git", "-C", root, "-c", "user.name=Release Fixture", "-c", "user.email=fixture@example.invalid", *arguments])
    if result.returncode:
        raise RuntimeError(result.stderr)
    return result.stdout.strip()


def dump(path, value):
    """Write one test-owned JSON document without mutating shared fixtures."""
    path.write_text(json.dumps(value, sort_keys=True, indent=2) + "\n", encoding="utf-8")


def archive(path, name):
    """Create a tiny real tar.gz with a regular executable member that the helper hashes without executing."""
    data = b"fixture executable bytes\n"
    with tarfile.open(path, "w:gz") as output:
        member = tarfile.TarInfo(name)
        member.size = len(data)
        member.mode = 0o755
        output.addfile(member, io.BytesIO(data))


class ReleaseManifestCommands(unittest.TestCase):
    """Check complete release verification and specific trust/inventory rejection boundaries."""

    @classmethod
    def setUpClass(cls):
        """Create immutable valid source and six-target release templates through the public commands."""
        cls.fixture_owner = tempfile.TemporaryDirectory(prefix="manifest-fixture-")
        cls.addClassCleanup(cls.fixture_owner.cleanup)
        cls.template = Path(cls.fixture_owner.name)
        source = cls.template / "source"
        source.mkdir()
        git(source, "init", "-q")
        (source / "codex-rs").mkdir()
        (source / "codex-rs/Cargo.lock").write_text('version = 3\n', encoding="utf-8")
        (source / "source.txt").write_text("upstream\n", encoding="utf-8")
        git(source, "add", ".")
        git(source, "commit", "-qm", "upstream")
        cls.upstream = git(source, "rev-parse", "HEAD")
        git(source, "tag", "rust-v1.2.3")
        (source / "source.txt").write_text("patched\n", encoding="utf-8")
        git(source, "add", ".")
        git(source, "commit", "-qm", "patch")
        patch = process(["git", "-C", source, "format-patch", "-1", "--stdout"])
        if patch.returncode:
            raise RuntimeError(patch.stderr)
        patch_repo = cls.template / "patch_repo"
        (patch_repo / "patches").mkdir(parents=True)
        (patch_repo / "patches/1.patch").write_text(patch.stdout, encoding="utf-8")
        (patch_repo / "patches/README.md").write_text("1. [One](1.patch)\n", encoding="utf-8")
        git(patch_repo, "init", "-q")
        git(patch_repo, "add", ".")
        git(patch_repo, "commit", "-qm", "queue")
        cls.fork = git(patch_repo, "rev-parse", "HEAD")
        assets = cls.template / "assets"
        assets.mkdir()
        for target in TARGETS:
            lane = cls.template / ("lane-" + target)
            lane.mkdir()
            archive(lane / f"codex-package-{target}.tar.gz", "bin/codex.exe" if "windows" in target else "bin/codex")
            archive(lane / f"codex-symbols-{target}.tar.gz", "debug/symbols")
            (lane / f"codex-version-{target}.txt").write_text("codex-cli 1.2.3+4\n", encoding="utf-8")
            if target == "x86_64-unknown-linux-musl":
                (lane / "install.sh").write_text("installer\n", encoding="utf-8")
                (lane / "install.ps1").write_text("installer\n", encoding="utf-8")
            result = process([sys.executable, "-I", HELPER, *cls.receipt_arguments(source, patch_repo, lane, target)])
            if result.returncode:
                raise RuntimeError("receipt fixture failed: " + result.stderr)
            for path in lane.iterdir():
                shutil.copy2(path, assets / path.name)
        result = process([sys.executable, "-I", HELPER, "assemble", "--receipts", assets, "--assets", assets,
                          "--output", assets / "release-manifest.json"])
        if result.returncode:
            raise RuntimeError("assembly fixture failed: " + result.stderr)

    @classmethod
    def receipt_arguments(cls, source, patch_repo, assets, target):
        """Return the public receipt invocation for the fixture's pinned identities and packaged version proof."""
        return ["receipt", "--source", source, "--patch-repo", patch_repo, "--target", target, "--version", "1.2.3+4",
                "--build-number", "4", "--upstream-tag", "rust-v1.2.3", "--upstream-commit", cls.upstream,
                "--fork-commit", cls.fork, "--run-id", "123", "--run-attempt", "1", "--assets", assets,
                "--output", assets / f"package-receipt-{target}.json"]

    def setUp(self):
        """Give each test independent writable repositories/assets copied from the immutable template."""
        self.owner = tempfile.TemporaryDirectory(prefix="manifest-case-")
        self.addCleanup(self.owner.cleanup)
        self.root = Path(self.owner.name) / "fixture"
        shutil.copytree(self.template, self.root)
        self.assets = self.root / "assets"
        self.source = self.root / "source"
        self.patch_repo = self.root / "patch_repo"
        self.manifest = self.assets / "release-manifest.json"

    def invoke(self, *arguments, env=None):
        """Return the helper command's result with only this test's supplied environment when needed."""
        return process([sys.executable, "-I", HELPER, *arguments], env=env)

    def verify(self, *arguments, env=None):
        """Run consumer verification against this test's moved source, patch and release directories."""
        return self.invoke("verify", "--release-dir", self.assets, "--patch-repo", self.patch_repo,
                           "--upstream-source", self.source, *arguments, env=env)

    def assemble(self):
        """Run assembly over this test's release directory, including the published target receipts."""
        return self.invoke("assemble", "--receipts", self.assets, "--assets", self.assets, "--output", self.manifest)

    def rewrite_manifest(self, transform):
        """Apply an explicit manifest mutation to reach semantic checks independent of checksum correctness."""
        value = json.loads(self.manifest.read_text())
        transform(value)
        dump(self.manifest, value)
        self.rechecksum()

    def rechecksum(self):
        """Recompute test-owned checksum entries to distinguish integrity checks from semantic/source checks."""
        names = sorted(path.name for path in self.assets.iterdir() if path.name != "SHA256SUMS")
        (self.assets / "SHA256SUMS").write_text("".join(
            hashlib.sha256((self.assets / name).read_bytes()).hexdigest() + "  " + name + "\n" for name in names), encoding="utf-8")

    def reject(self, result, diagnostic):
        """Assert the consumer rejects the changed input at the named public diagnostic boundary."""
        self.assertEqual(result.returncode, 1, result.stdout + result.stderr)
        self.assertIn(diagnostic, result.stderr)

    def test_valid_release_verifies_after_checkout_move(self):
        """Moving both Git checkouts and assets preserves source and payload verification."""
        result = self.verify()
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn("source only", result.stdout)
        self.assertEqual(len(list(self.assets.iterdir())), 28)

    def test_mutated_package_fails_content_verification(self):
        """Changed package bytes are rejected before source or attestation verification."""
        path = self.assets / f"codex-package-{TARGETS[0]}.tar.gz"
        path.write_bytes(path.read_bytes() + b"changed")
        self.reject(self.verify(), "Asset digest or size mismatch")

    def test_mutated_patch_fails_pinned_fork_identity(self):
        """Raw patch bytes differing from the fork commit cannot authenticate the supplied source."""
        path = self.patch_repo / "patches/1.patch"
        path.write_bytes(path.read_bytes() + b"changed\n")
        self.reject(self.verify(), "Patch differs from fork commit")

    def test_mutated_source_fails_clean_identity(self):
        """A tracked source modification cannot be hidden behind the recorded HEAD and tree."""
        (self.source / "source.txt").write_text("changed\n", encoding="utf-8")
        self.reject(self.verify(), "Source checkout must be clean")

    def test_other_clean_source_commit_is_rejected(self):
        """A new clean source commit is rejected rather than accepted based on an unchanged lockfile."""
        (self.source / "source.txt").write_text("changed\n", encoding="utf-8")
        git(self.source, "add", ".")
        git(self.source, "commit", "-qm", "other source")
        self.reject(self.verify(), "Source HEAD, tree or Cargo.lock differs")

    def test_consistently_forged_source_identity_fails_raw_patch_replay(self):
        """Even agreeing manifests and receipts cannot authenticate a tree not produced by the pinned raw patches."""
        git(self.source, "reset", "--hard", self.upstream)
        (self.source / "source.txt").write_text("forged\n", encoding="utf-8")
        git(self.source, "add", ".")
        git(self.source, "commit", "-qm", "forged replacement patch")
        identity = {"commit": git(self.source, "rev-parse", "HEAD"), "tree": git(self.source, "rev-parse", "HEAD^{tree}"),
                    "cargo_lock_sha256": hashlib.sha256((self.source / "codex-rs/Cargo.lock").read_bytes()).hexdigest()}
        manifest = json.loads(self.manifest.read_text())
        manifest["source"] = identity
        for target in TARGETS:
            name = f"package-receipt-{target}.json"
            path = self.assets / name
            receipt = json.loads(path.read_text())
            receipt["source"] = identity
            dump(path, receipt)
            for entry in manifest["artifacts"]:
                if entry["name"] == name:
                    entry.update(size=path.stat().st_size, sha256=hashlib.sha256(path.read_bytes()).hexdigest())
        dump(self.manifest, manifest)
        self.rechecksum()
        self.reject(self.verify(), "Source tree is not pinned upstream plus the ordered raw patches")

    def test_changed_source_manifest_is_rejected(self):
        """A recomputed checksum cannot conceal a manifest source identity differing from package receipts."""
        self.rewrite_manifest(lambda value: value["source"].update(tree="0" * 40))
        self.reject(self.verify(), "Receipt identity differs")

    def test_missing_target_receipt_prevents_assembly(self):
        """Assembly requires a receipt for every supported target."""
        (self.assets / f"package-receipt-{TARGETS[0]}.json").unlink()
        self.reject(self.assemble(), "Exactly six unique target receipts")

    def test_duplicate_target_receipt_prevents_assembly(self):
        """A second target filename carrying an existing target cannot satisfy the six-target gate."""
        first = self.assets / f"package-receipt-{TARGETS[0]}.json"
        second = self.assets / f"package-receipt-{TARGETS[1]}.json"
        second.write_bytes(first.read_bytes())
        self.reject(self.assemble(), "Receipt filename differs from target")

    def test_missing_payload_prevents_assembly(self):
        """An absent symbol archive cannot be published based on its receipt alone."""
        (self.assets / f"codex-symbols-{TARGETS[0]}.tar.gz").unlink()
        self.reject(self.assemble(), "Missing regular asset")

    def test_missing_installer_prevents_assembly(self):
        """Both installers are required in the published asset set."""
        (self.assets / "install.ps1").unlink()
        self.reject(self.assemble(), "Missing regular asset")

    def test_duplicate_asset_manifest_is_rejected(self):
        """Repeated asset names are rejected before digest checking."""
        self.rewrite_manifest(lambda value: value["artifacts"].append(value["artifacts"][0].copy()))
        self.reject(self.verify(), "Duplicate manifest asset name")

    def test_unsafe_asset_name_is_rejected(self):
        """Manifest paths cannot escape the release directory."""
        self.rewrite_manifest(lambda value: value["artifacts"][0].update(name="../escape"))
        self.reject(self.verify(), "Unsafe asset name")

    def test_unsafe_patch_path_is_rejected(self):
        """Patch paths cannot escape the pinned patch checkout."""
        self.rewrite_manifest(lambda value: value["patches"][0].update(path="patches/../escape.patch"))
        self.reject(self.verify(), "Unsafe patch path")

    def test_app_server_archive_requires_its_own_executable(self):
        """Receipt validation rejects a distributed server archive containing no recorded server executable."""
        target = TARGETS[0]
        lane = self.root / ("lane-" + target)
        archive(lane / f"codex-app-server-package-{target}.tar.gz", "debug/unrelated")
        self.reject(self.invoke(*self.receipt_arguments(self.source, self.patch_repo, lane, target)),
                    "App-server archive contains no recorded codex-app-server executable")

    def test_app_server_archive_records_its_executable(self):
        """A supported server archive records its own binary separately from the CLI binary."""
        target = TARGETS[0]
        lane = self.root / ("lane-" + target)
        name = f"codex-app-server-package-{target}.tar.gz"
        archive(lane / name, "bin/codex-app-server")
        result = self.invoke(*self.receipt_arguments(self.source, self.patch_repo, lane, target))
        self.assertEqual(result.returncode, 0, result.stderr)
        value = json.loads((lane / f"package-receipt-{target}.json").read_text())
        self.assertEqual({entry["archive"] for entry in value["binaries"]},
                         {name, f"codex-package-{target}.tar.gz"})
        for path in lane.iterdir():
            shutil.copy2(path, self.assets / path.name)
        result = self.assemble()
        self.assertEqual(result.returncode, 0, result.stderr)
        result = self.verify()
        self.assertEqual(result.returncode, 0, result.stderr)

    def test_mismatched_observed_cli_version_is_rejected(self):
        """An archive filename cannot substitute for the native CLI version observation."""
        target = TARGETS[0]
        lane = self.root / ("lane-" + target)
        (lane / f"codex-version-{target}.txt").write_text("codex-cli 1.2.3\n", encoding="utf-8")
        self.reject(self.invoke(*self.receipt_arguments(self.source, self.patch_repo, lane, target)), "Packaged CLI version differs")

    def test_wrong_repository_policy_is_rejected(self):
        """A manifest cannot choose a different attestation repository."""
        self.rewrite_manifest(lambda value: value.update(repository="attacker/repository"))
        self.reject(self.verify(), "Release repository violates consumer policy")

    def test_wrong_workflow_policy_is_rejected(self):
        """A manifest cannot choose a different signer workflow."""
        self.rewrite_manifest(lambda value: value.update(signer_workflow=REPOSITORY + "/.github/workflows/other.yml"))
        self.reject(self.verify(), "Signer workflow violates consumer policy")

    def test_extra_payload_is_rejected(self):
        """An uninventoried download cannot silently pass verification."""
        (self.assets / "other.txt").write_text("extra\n", encoding="utf-8")
        self.reject(self.verify(), "missing or uninventoried assets")

    def test_receipt_source_disagreement_prevents_assembly(self):
        """Different source identities across otherwise valid target receipts cannot assemble a release."""
        path = self.assets / f"package-receipt-{TARGETS[0]}.json"
        value = json.loads(path.read_text())
        value["source"]["tree"] = "0" * 40
        dump(path, value)
        self.reject(self.assemble(), "Receipts disagree")

    def test_duplicate_json_field_is_rejected(self):
        """Duplicate policy keys cannot override an earlier identity during JSON decoding."""
        text = self.manifest.read_text()
        self.manifest.write_text(text.replace('"format_version": 1,', '"format_version": 1, "format_version": 1,'))
        self.reject(self.verify(), "Duplicate JSON field")

    def fake_gh(self, status):
        """Return a process environment whose fake gh records arguments and exits with the documented status convention."""
        tools = self.root / "tools"
        tools.mkdir()
        log = self.root / "attestation_calls.jsonl"
        executable = tools / "gh"
        executable.write_text(f"#!{sys.executable}\nimport json, sys\nwith open({str(log)!r}, 'a') as output:\n output.write(json.dumps(sys.argv[1:]) + '\\n')\nsys.exit({status})\n", encoding="utf-8")
        executable.chmod(0o755)
        return {"PATH": str(tools) + ":/usr/bin:/bin"}, log

    def test_attestation_subprocess_receives_fixed_policy_for_every_asset(self):
        """The consumer forwards fixed repository/workflow policy for payloads, receipts, manifest and checksums."""
        env, log = self.fake_gh(0)
        result = self.verify("--verify-attestations", env=env)
        self.assertEqual(result.returncode, 0, result.stderr)
        calls = [json.loads(line) for line in log.read_text().splitlines()]
        self.assertEqual({Path(call[2]).name for call in calls}, {path.name for path in self.assets.iterdir()})
        self.assertEqual(len(calls), 28)
        for call in calls:
            self.assertEqual(call[:2], ["attestation", "verify"])
            self.assertEqual(call[3:], ["--repo", REPOSITORY, "--signer-workflow", WORKFLOW])

    def test_failed_attestation_fails_whole_verification(self):
        """A nonzero verifier status cannot be reported as completed attestation verification."""
        env, log = self.fake_gh(3)
        self.reject(self.verify("--verify-attestations", env=env), "gh attestation failed (exit 3)")
        self.assertEqual(len(log.read_text().splitlines()), 1)


if __name__ == "__main__":
    if len(sys.argv) >= 3 and sys.argv[1] == "--helper":
        HELPER = Path(sys.argv[2]).resolve()
        del sys.argv[1:3]
    unittest.main()
