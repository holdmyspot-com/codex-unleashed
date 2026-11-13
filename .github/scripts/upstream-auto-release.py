#!/usr/bin/env python3
"""Decide whether an upstream stable release needs a fork release, and render its notes and failure summary.

Commands (each prints its result on standard output and exits 1 with a message on standard error when it cannot):
  detect   --upstream-releases FILE --fork-releases FILE [--requested-tag TAG]
           Print key=value lines (action, reason, version, build, tag, release_name, upstream_tag).
  save-identity --identity FILE --run-id ID --fork-commit SHA --decision-output FILE [--upstream-commit SHA]
           Save the selected decision and pinned commits as this run's immutable identity JSON.
  restore-identity --identity FILE --run-id ID --fork-commit SHA
           Validate this run's saved selection and print its outputs, including upstream_commit.
  publication --fork-releases FILE --tag T --fork-commit SHA --upstream-tag T --upstream-commit SHA
           --tag-commit SHA --assets-directory DIR --notes FILE (empty tag-commit means the tag is absent).
           Reconcile the original request, append its fingerprint to notes, and print action, phase and tag.
  patches  --readme FILE            Print the patch queue's repository paths, space-separated, in order.
  notes    --version V --build N --upstream-tag T --upstream-commit SHA --fork-commit SHA --readme FILE
           [--changelog FILE]   Include the changelog's "## Upstream T" section as the notes' Changes.
  summary  --upstream-tag T --failing TEXT [--log-url URL]
Release-list FILE arguments are JSON lists as returned by GET /repos/{owner}/{repo}/releases."""

import argparse
import hashlib
import json
from pathlib import Path
import re
import sys

UPSTREAM = "openai/codex"
FORK = "holdmyspot-com/codex-unleashed"
# Release notes persist this request fingerprint so retries cannot change source or assets for the same tag.
PUBLICATION_MARKER_PREFIX = "<!-- codex-unleashed-publication: "
UPSTREAM_TAG = re.compile(r"rust-v(\d+)\.(\d+)\.(\d+)")
FORK_TAG = re.compile(r"rust-v(\d+)\.(\d+)\.(\d+)\+(\d+)")
QUEUE_ENTRY = re.compile(r"^(\d+)\. \[[^\]]*\]\(([^)]+\.patch)\)\s*$")


def stable_version(release):
    """Return the (major, minor, patch) of a non-draft, non-prerelease rust-vX.Y.Z release, or None for any other."""
    if release.get("draft") or release.get("prerelease"):
        return None
    match = UPSTREAM_TAG.fullmatch(release["tag_name"])
    return tuple(int(part) for part in match.groups()) if match else None


def newest_stable(releases):
    """Return the stable release with the highest version; raise ValueError when there is none."""
    stable = [(version, release) for release in releases if (version := stable_version(release))]
    if not stable:
        raise ValueError("The upstream release list contains no stable rust-vX.Y.Z release")
    return max(stable, key=lambda pair: pair[0])[1]


def fork_version(release):
    """Return ((major, minor, patch), build) for a fork release tag rust-vX.Y.Z+BUILD, or None for any other tag."""
    match = FORK_TAG.fullmatch(release["tag_name"])
    if not match:
        return None
    numbers = tuple(int(part) for part in match.groups())
    return numbers[:3], numbers[3]


def dotted(version):
    return ".".join(str(part) for part in version)


def decide(upstream_releases, fork_releases, requested=None):
    """Return the decision for the newest stable upstream release, or for the requested one.

    action is "release" or "noop". A published fork release of the candidate version makes it a no-op, as does a
    fork whose latest published release is newer than an automatically selected candidate. Drafts are not releases,
    but their build numbers are never reused. Raise ValueError when requested is not a stable upstream release."""
    if requested is None:
        release = newest_stable(upstream_releases)
    else:
        tag = requested if requested.startswith("rust-v") else f"rust-v{requested}"
        matches = [r for r in upstream_releases if r["tag_name"] == tag and stable_version(r)]
        if not matches:
            raise ValueError(f"{tag} is not a stable (non-draft, non-prerelease) upstream release")
        release = matches[0]
    candidate = stable_version(release)
    published = [v[0] for r in fork_releases if not r.get("draft") and (v := fork_version(r))]
    builds = [v[1] for r in fork_releases if (v := fork_version(r)) and v[0] == candidate]
    build = max(builds, default=0) + 1
    decision = {"upstream_tag": release["tag_name"], "version": dotted(candidate), "build": build,
                "tag": f"rust-v{dotted(candidate)}+{build}", "release_name": f"{dotted(candidate)}+{build}"}
    if candidate in published:
        decision.update(action="noop", reason=f"Upstream {release['tag_name']} is already released by the fork")
    elif requested is None and published and max(published) > candidate:
        decision.update(action="noop", reason=f"The fork's latest release {dotted(max(published))} is newer than "
                                              f"the newest stable upstream release {release['tag_name']}")
    else:
        decision.update(action="release", reason=f"Upstream {release['tag_name']} is not released by the fork")
    return decision


def publication_marker(tag, fork_commit, upstream_tag, upstream_commit, asset_digests):
    """Bind a release tag to its source commit and exact asset hashes in its persisted notes."""
    request = json.dumps(dict(tag=tag, fork_commit=fork_commit, upstream_tag=upstream_tag,
                              upstream_commit=upstream_commit, asset_digests=asset_digests), sort_keys=True)
    return PUBLICATION_MARKER_PREFIX + hashlib.sha256(request.encode()).hexdigest() + " -->"


def publication_plan(fork_releases, tag, fork_commit, upstream_tag, upstream_commit, tag_commit, asset_digests):
    """Choose publication's next phase from a GitHub release snapshot and the original built request."""
    identity = fork_version(dict(tag_name=tag))
    if identity is None:
        raise ValueError(f"Invalid fork release tag: {tag}")
    if upstream_tag != tag.split("+", 1)[0] or re.fullmatch(r"[0-9a-f]{40}", upstream_commit) is None:
        raise ValueError("The publication upstream tag or commit is inconsistent with the built identity")
    if tag_commit and tag_commit != fork_commit:
        raise ValueError(f"{tag} targets {tag_commit}, not the original fork commit {fork_commit}")
    other_published_build = any(
        not release.get("draft") and release["tag_name"] != tag
        and (version := fork_version(release)) and version[0] == identity[0]
        for release in fork_releases)
    matching = [release for release in fork_releases if release["tag_name"] == tag]
    if len(matching) > 1:
        raise ValueError(f"Multiple release records for {tag}")
    if matching:
        release = matching[0]
        marker = publication_marker(tag, fork_commit, upstream_tag, upstream_commit, asset_digests)
        if release.get("target_commitish") != fork_commit or marker not in (release.get("body") or ""):
            raise ValueError(f"{tag} belongs to a different publication request; retry its original workflow run")
        recorded = {asset["name"]: asset.get("digest") for asset in release["assets"]}
        expected = {name: "sha256:" + digest for name, digest in asset_digests.items()}
        for name, digest in recorded.items():
            if expected.get(name) != digest:
                raise ValueError(f"{tag} assets differ from the original request: {name}")
        if release["draft"]:
            if other_published_build:
                return dict(action="noop", phase="complete", tag=tag)
            phase = "upload"
        else:
            if not tag_commit:
                raise ValueError(f"{tag} is published but its Git tag is missing")
            if recorded != expected:
                raise ValueError(f"{tag} published assets are incomplete; refusing to modify a published release")
            phase = "verify" if release["prerelease"] else "complete"
        return dict(action="noop" if phase == "complete" else "release", phase=phase, tag=tag)

    if other_published_build:
        return dict(action="noop", phase="complete", tag=tag)
    return dict(action="release", phase="create", tag=tag)


def publication_assets(directory):
    """Hash each regular release asset in directory, rejecting an empty asset directory."""
    digests = {}
    for path in sorted(directory.iterdir()):
        if path.is_file():
            with path.open("rb") as source:
                digests[path.name] = hashlib.file_digest(source, "sha256").hexdigest()
    if not digests:
        raise ValueError(f"No release assets in {directory}")
    return digests


def restore_identity(identity, run_id, fork_commit):
    """Restore a same-run decision, rejecting unknown fields, changed sources or inconsistent versions."""
    fields = {"format_version", "run_id", "fork_commit", "upstream_commit", "decision"}
    if not isinstance(identity, dict) or set(identity) != fields:
        raise ValueError("Release identity has invalid fields; rerun failed jobs from the original attempt")
    valid_version = type(identity["format_version"]) is int and identity["format_version"] == 1
    if not valid_version or identity["run_id"] != run_id or identity["fork_commit"] != fork_commit:
        raise ValueError("Release identity does not belong to this run and fork commit")
    decision = identity["decision"]
    decision_fields = {"action", "reason", "version", "build", "tag", "release_name", "upstream_tag"}
    if not isinstance(decision, dict) or set(decision) != decision_fields:
        raise ValueError("Release identity has invalid decision fields")
    if decision["action"] not in ("release", "noop") or not isinstance(decision["reason"], str):
        raise ValueError("Release identity has an invalid action or reason")
    version = decision["version"]
    build = decision["build"]
    valid_build = type(build) is int and build >= 1
    valid_version = isinstance(version, str) and re.fullmatch(r"[0-9]+\.[0-9]+\.[0-9]+", version)
    if not valid_build or not valid_version:
        raise ValueError("Release identity has an invalid version or build")
    expected = dict(tag=f"rust-v{version}+{build}", release_name=f"{version}+{build}", upstream_tag=f"rust-v{version}")
    if any(decision[key] != value for key, value in expected.items()):
        raise ValueError("Release identity has inconsistent version, build and tag")
    upstream_commit = identity["upstream_commit"]
    if not isinstance(upstream_commit, str):
        raise ValueError("Release identity has an invalid upstream commit")
    if decision["action"] == "release" and re.fullmatch(r"[0-9a-f]{40}", upstream_commit or "") is None:
        raise ValueError("Release identity has an invalid upstream commit")
    return decision | dict(upstream_commit=upstream_commit)


def patch_queue(readme):
    """Return the repository paths of the README's numbered patch entries in order.

    Raise ValueError when there is no entry or the numbers are not consecutive from 1."""
    entries = [m.groups() for line in readme.splitlines() if (m := QUEUE_ENTRY.match(line))]
    if not entries:
        raise ValueError("The patch README lists no numbered patch")
    for expected, (number, _) in enumerate(entries, 1):
        if int(number) != expected:
            raise ValueError(f"Patch entry {number} where {expected} was expected")
    return ["patches/" + path for _, path in entries]


def changelog_section(changelog, upstream_tag):
    """Return the body of the changelog's "## Upstream <upstream_tag>" section, stripped, or None when it has none."""
    body = None
    for line in changelog.splitlines():
        if line.startswith("## "):
            if body is not None:
                break
            if line.rstrip() == f"## Upstream {upstream_tag}":
                body = []
        elif body is not None:
            body.append(line)
    return "\n".join(body).strip() if body is not None else None


def release_notes(version, build, upstream_tag, upstream_commit, fork_commit, patches, changes=None):
    """Render the release notes as Markdown; changes, when given, becomes a Changes section before the patch list."""
    tag = f"rust-v{version}%2B{build}"
    changes_section = f"## Changes\n\n{changes}\n\n" if changes else ""
    base = f"https://github.com/{FORK}/releases"
    patch_lines = "\n".join(f"{i}. [`{p.removeprefix('patches/')}`](https://github.com/{FORK}/blob/{fork_commit}/{p})"
                            for i, p in enumerate(patches, 1))
    return f"""# Codex Unleashed {version}+{build}

> **Unofficial:** This distribution is not affiliated with or endorsed by OpenAI.

Built from upstream [`{upstream_tag}`](https://github.com/{UPSTREAM}/releases/tag/{upstream_tag}) at commit \
[`{upstream_commit}`](https://github.com/{UPSTREAM}/commit/{upstream_commit}) with this repository's patch queue \
applied in order.

## Install

Linux and macOS:

```sh
curl -fsSL {base}/latest/download/install.sh | bash
```

Windows:

```powershell
powershell -ExecutionPolicy ByPass -c "irm {base}/latest/download/install.ps1 | iex"
```

To install exactly this release, replace `latest/download` with `download/{tag}` in either URL, for example \
`{base}/download/{tag}/install.sh`.

## Verify

Each `codex-package-<target>.tar.gz` asset is listed in `SHA256SUMS`, and GitHub shows the same SHA-256 digest on \
the asset. The installers verify the digest before installing.

`codex-symbols-<target>.tar.gz` holds the debug symbols stripped from that target's binaries, for symbolicating \
crash reports. It is listed in `SHA256SUMS` too.

{changes_section}## Patches applied

The patch queue at [`{fork_commit[:12]}`](https://github.com/{FORK}/tree/{fork_commit}/patches), in order:

{patch_lines}
"""


def failure_summary(upstream_tag, failing, log_url=None):
    """Render the job summary for a run that published nothing."""
    log = f"\n\nLog: {log_url}" if log_url else ""
    return (f"## Automatic release of upstream {upstream_tag} failed\n\n"
            f"Failing: **{failing}**{log}\n\nNothing was published: no tag, release or asset was created.\n")


def main(argv):
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    commands = parser.add_subparsers(dest="command", required=True)
    detect = commands.add_parser("detect")
    detect.add_argument("--upstream-releases", type=Path, required=True)
    detect.add_argument("--fork-releases", type=Path, required=True)
    detect.add_argument("--requested-tag", default="")
    publication = commands.add_parser("publication")
    publication.add_argument("--fork-releases", type=Path, required=True)
    publication.add_argument("--tag", required=True)
    publication.add_argument("--fork-commit", required=True)
    publication.add_argument("--upstream-tag", required=True)
    publication.add_argument("--upstream-commit", required=True)
    publication.add_argument("--tag-commit", required=True)
    publication.add_argument("--assets-directory", type=Path, required=True)
    publication.add_argument("--notes", type=Path, required=True)
    for command in ("save-identity", "restore-identity"):
        identity = commands.add_parser(command)
        identity.add_argument("--identity", type=Path, required=True)
        identity.add_argument("--run-id", required=True)
        identity.add_argument("--fork-commit", required=True)
        if command == "save-identity":
            identity.add_argument("--decision-output", type=Path, required=True)
            identity.add_argument("--upstream-commit", default="")
    patches = commands.add_parser("patches")
    patches.add_argument("--readme", type=Path, required=True)
    notes = commands.add_parser("notes")
    notes.add_argument("--version", required=True)
    notes.add_argument("--build", type=int, required=True)
    notes.add_argument("--upstream-tag", required=True)
    notes.add_argument("--upstream-commit", required=True)
    notes.add_argument("--fork-commit", required=True)
    notes.add_argument("--readme", type=Path, required=True)
    notes.add_argument("--changelog", type=Path, default=None)
    summary = commands.add_parser("summary")
    summary.add_argument("--upstream-tag", required=True)
    summary.add_argument("--failing", required=True)
    summary.add_argument("--log-url", default=None)
    args = parser.parse_args(argv)
    try:
        if args.command == "detect":
            decision = decide(json.loads(args.upstream_releases.read_text()),
                              json.loads(args.fork_releases.read_text()), args.requested_tag or None)
            for key in ("action", "reason", "version", "build", "tag", "release_name", "upstream_tag"):
                print(f"{key}={decision[key]}")
        elif args.command in ("save-identity", "restore-identity"):
            if args.command == "save-identity":
                decision = dict(line.split("=", 1) for line in args.decision_output.read_text().splitlines())
                decision["build"] = int(decision["build"])
                identity = dict(format_version=1, run_id=args.run_id, fork_commit=args.fork_commit,
                                upstream_commit=args.upstream_commit, decision=decision)
                restore_identity(identity, args.run_id, args.fork_commit)
                args.identity.write_text(json.dumps(identity) + "\n")
            else:
                decision = restore_identity(json.loads(args.identity.read_text()), args.run_id, args.fork_commit)
                for key, value in decision.items():
                    print(f"{key}={value}")
        elif args.command == "publication":
            digests = publication_assets(args.assets_directory)
            decision = publication_plan(json.loads(args.fork_releases.read_text()), args.tag, args.fork_commit,
                                        args.upstream_tag, args.upstream_commit, args.tag_commit, digests)
            with args.notes.open("a") as notes_file:
                notes_file.write("\n" + publication_marker(args.tag, args.fork_commit, args.upstream_tag,
                                                         args.upstream_commit, digests) + "\n")
            for key, value in decision.items():
                print(f"{key}={value}")
        elif args.command == "patches":
            print(" ".join(patch_queue(args.readme.read_text())))
        elif args.command == "notes":
            changes = changelog_section(args.changelog.read_text(), args.upstream_tag) if args.changelog else None
            print(release_notes(args.version, args.build, args.upstream_tag, args.upstream_commit, args.fork_commit,
                                patch_queue(args.readme.read_text()), changes))
        else:
            print(failure_summary(args.upstream_tag, args.failing, args.log_url))
    except (ValueError, OSError, json.JSONDecodeError) as error:
        print(f"upstream-auto-release: {error}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
