#!/usr/bin/env python3
"""Exercise upstream stable-release detection, versioning, patch-queue listing and release notes."""

import importlib.util
from contextlib import redirect_stdout, redirect_stderr
import io
import json
import re
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

import yaml

SCRIPT = Path(__file__).with_name("upstream-auto-release.py")


def load():
    """Import the script under test, which has a hyphenated name, as a module."""
    spec = importlib.util.spec_from_file_location("upstream_auto_release", SCRIPT)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def upstream(tag, draft=False, prerelease=False):
    """Return the fields of an upstream GitHub release that detection reads."""
    return {"tag_name": tag, "draft": draft, "prerelease": prerelease}


def fork(tag, draft=False, prerelease=False):
    """Return the fields of this fork's GitHub release that detection reads."""
    return {"tag_name": tag, "draft": draft, "prerelease": prerelease}


class DetectTest(unittest.TestCase):
    """Check which upstream release is selected and whether it needs releasing."""

    def setUp(self):
        self.module = load()

    def test_newest_stable_ignores_drafts_prereleases_and_alphas(self):
        """Only non-draft, non-prerelease rust-vX.Y.Z tags count, ordered by version, not by list order."""
        releases = [upstream("rust-v0.169.0-alpha.7", prerelease=True), upstream("rust-v0.168.0", draft=True),
                    upstream("rust-v0.170.0", prerelease=True), upstream("rust-v0.9.0"),
                    upstream("rust-v0.167.0"), upstream("v0.200.0"), upstream("rust-v0.167.0-beta.1")]
        self.assertEqual(self.module.newest_stable(releases)["tag_name"], "rust-v0.167.0")

    def test_versions_compare_numerically(self):
        """0.10.0 is newer than 0.9.0."""
        self.assertEqual(self.module.newest_stable([upstream("rust-v0.9.0"), upstream("rust-v0.10.0")])["tag_name"],
                         "rust-v0.10.0")

    def test_no_stable_release_fails(self):
        """A list without a stable release raises instead of returning a default."""
        with self.assertRaises(ValueError):
            self.module.newest_stable([upstream("rust-v0.1.0-alpha.1", prerelease=True)])

    def test_new_version_gets_build_one(self):
        """A version the fork has not released is released as build 1 under an immutable rust-v tag."""
        decision = self.module.decide([upstream("rust-v0.167.0")], [fork("rust-v0.163.0+4")])
        self.assertEqual((decision["action"], decision["version"], decision["build"], decision["tag"]),
                         ("release", "0.167.0", 1, "rust-v0.167.0+1"))
        self.assertEqual(decision["upstream_tag"], "rust-v0.167.0")
        self.assertEqual(decision["release_name"], "0.167.0+1")

    def test_already_released_is_a_noop(self):
        """Any published build of the newest version makes the run a no-op."""
        decision = self.module.decide([upstream("rust-v0.167.0")], [fork("rust-v0.167.0+3"), fork("rust-v0.166.0+9")])
        self.assertEqual(decision["action"], "noop")
        self.assertIn("0.167.0", decision["reason"])

    def test_fork_ahead_of_upstream_is_a_noop(self):
        """A fork release newer than the newest stable upstream is not downgraded."""
        decision = self.module.decide([upstream("rust-v0.167.0")], [fork("rust-v0.168.0+1")])
        self.assertEqual(decision["action"], "noop")

    def test_draft_does_not_count_as_released_but_advances_build(self):
        """A leftover draft is not a release, and its tag's build number is not reused."""
        decision = self.module.decide([upstream("rust-v0.167.0")], [fork("rust-v0.167.0+1", draft=True)])
        self.assertEqual((decision["action"], decision["build"]), ("release", 2))

    def test_requested_older_release_is_released_even_if_fork_is_ahead(self):
        """An explicitly requested stable tag is released unless that exact version is already published."""
        releases = [upstream("rust-v0.167.0"), upstream("rust-v0.165.0")]
        decision = self.module.decide(releases, [fork("rust-v0.167.0+1")], requested="rust-v0.165.0")
        self.assertEqual((decision["action"], decision["version"], decision["build"]), ("release", "0.165.0", 1))
        again = self.module.decide(releases, [fork("rust-v0.165.0+1")], requested="0.165.0")
        self.assertEqual(again["action"], "noop")

    def test_requested_tag_must_be_a_stable_upstream_release(self):
        """A prerelease, draft or unknown request fails."""
        releases = [upstream("rust-v0.167.0"), upstream("rust-v0.168.0", prerelease=True),
                    upstream("rust-v0.166.0", draft=True)]
        for requested in ("rust-v0.168.0", "rust-v0.166.0", "rust-v0.1.0", "bogus"):
            with self.subTest(requested=requested), self.assertRaises(ValueError):
                self.module.decide(releases, [], requested=requested)


class PublicationPlanTest(unittest.TestCase):
    """The publisher retains the built version and reconciles GitHub release snapshots on retry."""

    def setUp(self):
        self.module = load()
        self.tag = "rust-v0.167.0+1"
        self.commit = "a" * 40
        self.digests = {"codex-package-test.tar.gz": "b" * 64}
        self.request = dict(tag=self.tag, fork_commit=self.commit, upstream_tag="rust-v0.167.0",
                            upstream_commit="d" * 40, tag_commit=self.commit, asset_digests=self.digests)

    def release(self, draft=True, prerelease=True):
        """Represent GitHub's release response, with its saved publication request and asset digests."""
        return dict(tag_name=self.tag, target_commitish=self.commit, draft=draft, prerelease=prerelease,
                    body=self.module.publication_marker(**{key: value for key, value in self.request.items()
                                                          if key != "tag_commit"}),
                    assets=[dict(name=name, digest="sha256:" + digest) for name, digest in self.digests.items()])

    def test_leftover_draft_keeps_built_tag_and_resumes_upload(self):
        """A draft of build one resumes build one instead of selecting build two."""
        release = self.release()
        release["assets"] = []
        plan = self.module.publication_plan([release], **self.request)
        self.assertEqual(plan, {"action": "release", "phase": "upload", "tag": self.tag})

    def test_published_prerelease_resumes_install_verification(self):
        """An already published matching request skips uploads and proceeds to installation."""
        self.assertEqual(self.module.publication_plan([self.release(draft=False)], **self.request),
                         {"action": "release", "phase": "verify", "tag": self.tag})

    def test_unknown_create_outcome_reuses_the_original_request(self):
        """After the create response is lost, reconciliation resumes the same persisted request."""
        first = self.module.publication_plan([], **self.request)
        self.assertEqual(first["phase"], "create")
        recovered = self.module.publication_plan([self.release()], **self.request)
        self.assertEqual(recovered["tag"], first["tag"])
        self.assertEqual(recovered["phase"], "upload")

    def test_changed_artifacts_cannot_claim_the_original_tag(self):
        """A changed package digest claiming the retained tag is rejected before upload."""
        release = self.release()
        with self.assertRaisesRegex(ValueError, "different publication request"):
            self.module.publication_plan([release], **(self.request | {"asset_digests": {"new": "c" * 64}}))

    def test_published_asset_mismatch_is_rejected(self):
        """A saved request does not excuse missing or changed published release assets."""
        release = self.release(draft=False)
        release["assets"] = []
        with self.assertRaisesRegex(ValueError, "assets"):
            self.module.publication_plan([release], **self.request)

    def test_completed_stable_release_is_a_noop(self):
        """A completed release does not repeat installation or promotion."""
        plan = self.module.publication_plan([self.release(draft=False, prerelease=False)], **self.request)
        self.assertEqual((plan["action"], plan["phase"]), ("noop", "complete"))

    def test_wrong_tag_target_fails_before_any_publication(self):
        """An existing tag cannot redirect the same package request to another source commit."""
        with self.assertRaisesRegex(ValueError, "targets"):
            self.module.publication_plan([], **(self.request | {"tag_commit": "e" * 40}))

    def test_wrong_upstream_tag_or_commit_is_rejected(self):
        """The publication request must retain the built upstream identity."""
        for change in (dict(upstream_tag="rust-v0.168.0"), dict(upstream_commit="bad")):
            with self.subTest(change=change), self.assertRaisesRegex(ValueError, "upstream"):
                self.module.publication_plan([], **(self.request | change))

    def test_draft_with_changed_existing_asset_is_rejected(self):
        """A draft with conflicting bytes is rejected rather than clobbered."""
        release = self.release()
        release["assets"][0]["digest"] = "sha256:" + "e" * 64
        with self.assertRaisesRegex(ValueError, "assets differ"):
            self.module.publication_plan([release], **self.request)

    def test_racing_published_build_prevents_resuming_a_draft(self):
        """Another build published after detection prevents a duplicate even when this request has a draft."""
        plan = self.module.publication_plan([self.release(), fork("rust-v0.167.0+2")], **self.request)
        self.assertEqual(plan["action"], "noop")

    def test_another_published_build_is_a_noop(self):
        """A racing publisher of the same upstream version prevents a duplicate release."""
        self.assertEqual(self.module.publication_plan([fork("rust-v0.167.0+2")], **self.request)["action"], "noop")


class ReleaseIdentityTest(unittest.TestCase):
    """Same-run reruns restore the original selected build or reject inconsistent retained state."""

    def setUp(self):
        self.module = load()
        self.decision = self.module.decide([upstream("rust-v0.167.0")], [])
        self.identity = dict(format_version=1, run_id="123", fork_commit="a" * 40,
                             upstream_commit="b" * 40, decision=self.decision)

    def test_restore_keeps_original_build(self):
        """The immutable run record returns build one even after a draft exists externally."""
        restored = self.module.restore_identity(self.identity, "123", "a" * 40)
        self.assertEqual(restored["tag"], "rust-v0.167.0+1")
        self.assertEqual(restored["upstream_commit"], "b" * 40)

    def test_inconsistent_identity_is_rejected(self):
        """A changed run, fork source, tag/build relationship or upstream commit is rejected."""
        changes = [dict(run_id="456"), dict(fork_commit="c" * 40), dict(upstream_commit="bad"),
                   dict(decision=self.decision | {"build": 2}), dict(unrecognized="field")]
        for change in changes:
            with self.subTest(change=change), self.assertRaises(ValueError):
                self.module.restore_identity(self.identity | change, "123", "a" * 40)


class IdentityCommandTest(unittest.TestCase):
    """The workflow commands persist and restore identity and reconcile actual local asset hashes."""

    def test_saved_identity_restores_outputs_and_rejects_missing_record(self):
        """Save and restore preserve the initial build, while a missing retained record fails closed."""
        module = load()
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            decision = module.decide([upstream("rust-v0.167.0")], [])
            selected = root / "decision"
            selected.write_text("\n".join(f"{key}={value}" for key, value in decision.items()))
            identity = root / "identity.json"
            common = ["--identity", str(identity), "--run-id", "123", "--fork-commit", "a" * 40]
            self.assertEqual(module.main(["save-identity", *common, "--decision-output", str(selected),
                                          "--upstream-commit", "b" * 40]), 0)
            output = io.StringIO()
            with redirect_stdout(output):
                result = module.main(["restore-identity", *common])
            self.assertEqual(result, 0)
            self.assertIn("tag=rust-v0.167.0+1\n", output.getvalue())
            self.assertIn("upstream_commit=" + "b" * 40 + "\n", output.getvalue())
            identity.unlink()
            with redirect_stderr(io.StringIO()):
                self.assertEqual(module.main(["restore-identity", *common]), 1)

    def test_publication_command_binds_bytes_before_create_and_rejects_changed_retry(self):
        """The real command hashes the input assets and writes the request marker used by later reconciliation."""
        module = load()
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            assets = root / "assets"
            assets.mkdir()
            package = assets / "codex-package-test.tar.gz"
            package.write_bytes(b"original package")
            releases = root / "releases.json"
            releases.write_text("[]")
            notes = root / "notes.md"
            notes.write_text("Release notes\n")
            arguments = ["publication", "--fork-releases", str(releases), "--tag", "rust-v0.167.0+1",
                         "--fork-commit", "a" * 40, "--upstream-tag", "rust-v0.167.0",
                         "--upstream-commit", "b" * 40, "--tag-commit", "", "--assets-directory", str(assets),
                         "--notes", str(notes)]
            with redirect_stdout(io.StringIO()) as output:
                self.assertEqual(module.main(arguments), 0)
            self.assertIn("phase=create", output.getvalue())
            releases.write_text(json.dumps([dict(tag_name="rust-v0.167.0+1", target_commitish="a" * 40,
                                                  draft=True, prerelease=True, body=notes.read_text(), assets=[])]))
            with redirect_stdout(io.StringIO()) as output:
                self.assertEqual(module.main(arguments), 0)
            self.assertIn("phase=upload", output.getvalue())
            package.write_bytes(b"changed package")
            with redirect_stderr(io.StringIO()) as error:
                self.assertEqual(module.main(arguments), 1)
            self.assertIn("different publication request", error.getvalue())


class PatchQueueTest(unittest.TestCase):
    """Check that the patch list is read from the README in its documented order."""

    def test_order_and_paths(self):
        """Entries become repository paths in numbered order; other links are ignored."""
        readme = ("# Patch queue\n\nSee [guide](docs/x.md).\n\n1. [a/one.patch](holdmyspot-com/x/a/one.patch)\n"
                  "2. [b/two.patch](holdmyspot-com/x/b/two.patch)\n\nTrailer [t](holdmyspot-com/x/t.txt)\n")
        self.assertEqual(load().patch_queue(readme),
                         ["patches/holdmyspot-com/x/a/one.patch", "patches/holdmyspot-com/x/b/two.patch"])

    def test_gap_or_empty_queue_fails(self):
        """A skipped number or an empty queue raises, since either misorders or drops patches."""
        module = load()
        with self.assertRaises(ValueError):
            module.patch_queue("1. [a](p/a.patch)\n3. [c](p/c.patch)\n")
        with self.assertRaises(ValueError):
            module.patch_queue("no entries\n")

    def test_real_queue_lists_distinct_existing_patches(self):
        """The repository's queue lists each existing patch once."""
        root = SCRIPT.parents[2]
        queue = load().patch_queue((root / "patches/README.md").read_text())
        self.assertTrue(queue)
        self.assertEqual(len(set(queue)), len(queue))
        for path in queue:
            self.assertTrue((root / path).is_file(), path)


class NotesTest(unittest.TestCase):
    """Check the release notes and failure summary content."""

    def setUp(self):
        self.module = load()

    def test_notes_link_upstream_release_patches_and_installers(self):
        """Notes name the vendor version, link the upstream release and commit, list patches and give both installers."""
        notes = self.module.release_notes(version="0.167.0", build=2, upstream_tag="rust-v0.167.0",
                                          upstream_commit="a" * 40, fork_commit="b" * 40,
                                          patches=["patches/holdmyspot-com/x/one.patch"])
        self.assertIn("0.167.0+2", notes)
        self.assertIn("https://github.com/openai/codex/releases/tag/rust-v0.167.0", notes)
        self.assertIn("a" * 40, notes)
        self.assertIn("https://github.com/holdmyspot-com/codex-unleashed/blob/" + "b" * 40
                      + "/patches/holdmyspot-com/x/one.patch", notes)
        self.assertIn("releases/latest/download/install.sh | bash", notes)
        self.assertIn("releases/latest/download/install.ps1 | iex", notes)
        self.assertIn("releases/download/rust-v0.167.0%2B2/install.sh", notes)
        self.assertIn("SHA256SUMS", notes)

    def test_changelog_section_for_upstream_tag(self):
        """Only the body of the section for the release's upstream tag is returned, without its heading."""
        changelog = ("# Changelog\n\nIntro.\n\n## Upstream rust-v0.168.0\n\n- newer\n\n"
                     "## Upstream rust-v0.167.0\n\n### Removed\n\n- older\n\n## Upstream rust-v0.166.0\n\n- oldest\n")
        self.assertEqual(self.module.changelog_section(changelog, "rust-v0.167.0"), "### Removed\n\n- older")
        self.assertIsNone(self.module.changelog_section(changelog, "rust-v0.165.0"))

    def test_notes_include_changes_when_given(self):
        """Notes carry a Changes section with the changelog entry, and none when there is no entry."""
        common = dict(version="0.167.0", build=1, upstream_tag="rust-v0.167.0", upstream_commit="a" * 40,
                      fork_commit="b" * 40, patches=["patches/holdmyspot-com/x/one.patch"])
        notes = self.module.release_notes(**common, changes="### Removed\n\n- older")
        self.assertIn("## Changes\n\n### Removed\n\n- older\n", notes)
        self.assertLess(notes.index("## Changes"), notes.index("## Patches applied"))
        self.assertNotIn("## Changes", self.module.release_notes(**common))

    def test_real_changelog_sections_name_upstream_tags(self):
        """Every second-level heading of the repository's changelog names an upstream stable tag."""
        root = SCRIPT.parents[2]
        headings = [line for line in (root / "CHANGELOG.md").read_text().splitlines() if line.startswith("## ")]
        self.assertTrue(headings)
        for heading in headings:
            self.assertRegex(heading, r"^## Upstream rust-v\d+\.\d+\.\d+$")
        versions = [tuple(int(part) for part in re.findall(r"\d+", heading)) for heading in headings]
        self.assertEqual(versions, sorted(versions, reverse=True), "newest upstream release first")

    def test_failure_summary_names_version_and_failing_item(self):
        """The summary names the upstream version, the failing patch or check, and the log."""
        summary = self.module.failure_summary(upstream_tag="rust-v0.167.0", failing="patch 7: a/b.patch",
                                              log_url="https://example.invalid/run/1")
        for text in ("rust-v0.167.0", "patch 7: a/b.patch", "https://example.invalid/run/1", "Nothing was published"):
            self.assertIn(text, summary)


class CommandLineTest(unittest.TestCase):
    """Check the command-line interface the workflow calls."""

    def run_cli(self, *arguments):
        return subprocess.run([sys.executable, "-I", SCRIPT.as_posix(), *arguments], capture_output=True, text=True)

    def test_detect_prints_output_lines(self):
        """detect prints key=value lines for GITHUB_OUTPUT and exits 0."""
        with tempfile.TemporaryDirectory() as directory:
            (Path(directory) / "u.json").write_text(json.dumps([upstream("rust-v0.167.0")]))
            (Path(directory) / "f.json").write_text(json.dumps([fork("rust-v0.163.0+4")]))
            result = self.run_cli("detect", "--upstream-releases", f"{directory}/u.json",
                                  "--fork-releases", f"{directory}/f.json")
        self.assertEqual(result.returncode, 0, result.stderr)
        lines = result.stdout.splitlines()
        for line in ("action=release", "version=0.167.0", "build=1", "tag=rust-v0.167.0+1",
                     "upstream_tag=rust-v0.167.0"):
            self.assertIn(line, lines)

    def test_detect_failure_exits_nonzero_with_message(self):
        """An invalid request exits 1 and explains on stderr."""
        with tempfile.TemporaryDirectory() as directory:
            (Path(directory) / "u.json").write_text(json.dumps([upstream("rust-v0.167.0")]))
            (Path(directory) / "f.json").write_text("[]")
            result = self.run_cli("detect", "--upstream-releases", f"{directory}/u.json",
                                  "--fork-releases", f"{directory}/f.json", "--requested-tag", "rust-v9.9.9")
        self.assertEqual(result.returncode, 1)
        self.assertIn("rust-v9.9.9", result.stderr)

    def test_patches_prints_space_separated_paths(self):
        """patches prints the real queue on one line, in order."""
        readme = SCRIPT.parents[2] / "patches/README.md"
        result = self.run_cli("patches", "--readme", readme.as_posix())
        self.assertEqual(result.returncode, 0, result.stderr)
        paths = result.stdout.split()
        self.assertEqual(paths, load().patch_queue(readme.read_text()))
        self.assertTrue(paths[0].startswith("patches/") and paths[0].endswith("bash-snapshot-replay-startup.patch"))


    def test_notes_reads_the_changelog_section(self):
        """notes --changelog puts the upstream tag's changelog entry into the notes."""
        root = SCRIPT.parents[2]
        with tempfile.TemporaryDirectory() as directory:
            changelog = Path(directory) / "CHANGELOG.md"
            changelog.write_text("# Changelog\n\n## Upstream rust-v0.167.0\n\n- removed one.patch\n")
            result = self.run_cli("notes", "--version", "0.167.0", "--build", "1", "--upstream-tag", "rust-v0.167.0",
                                  "--upstream-commit", "a" * 40, "--fork-commit", "b" * 40,
                                  "--readme", (root / "patches/README.md").as_posix(),
                                  "--changelog", changelog.as_posix())
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn("## Changes\n\n- removed one.patch\n", result.stdout)


LEVELS = {"none": 0, "read": 1, "write": 2}


def granted(permissions):
    """Return a job's or workflow's permissions as a scope-to-level map; a missing block grants only contents: read."""
    if permissions is None:
        return {"contents": LEVELS["read"]}
    return {scope: LEVELS[level] for scope, level in permissions.items()}


class ReusableWorkflowPermissionsTest(unittest.TestCase):
    """Check that each gate grants what its called workflow requests, which GitHub otherwise refuses at startup."""

    def test_callers_grant_every_permission_their_called_jobs_request(self):
        """A called job asking for more than its caller grants makes the whole run a startup failure."""
        workflows = SCRIPT.parents[1] / "workflows"
        caller = yaml.safe_load((workflows / "upstream-auto-release.yml").read_text())
        default = caller.get("permissions")
        for name, job in caller["jobs"].items():
            uses = job.get("uses", "")
            if not uses.startswith("./.github/workflows/"):
                continue
            grant = granted(job.get("permissions", default))
            called = yaml.safe_load((workflows / Path(uses).name).read_text())
            requests = [called.get("permissions")] + [j.get("permissions") for j in called["jobs"].values()]
            for request in requests:
                for scope, level in granted(request).items():
                    with self.subTest(job=name, scope=scope):
                        self.assertLessEqual(level, grant.get(scope, 0))

if __name__ == "__main__":
    unittest.main()
