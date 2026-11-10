#!/usr/bin/env python3
"""Decide whether an upstream stable release needs a fork release, and render its notes and failure summary.

Commands (each prints its result on standard output and exits 1 with a message on standard error when it cannot):
  detect   --upstream-releases FILE --fork-releases FILE [--requested-tag TAG]
           Print key=value lines (action, reason, version, build, tag, release_name, upstream_tag).
  patches  --readme FILE            Print the patch queue's repository paths, space-separated, in order.
  notes    --version V --build N --upstream-tag T --upstream-commit SHA --fork-commit SHA --readme FILE
  summary  --upstream-tag T --failing TEXT [--log-url URL]
FILE arguments are JSON lists as returned by GET /repos/{owner}/{repo}/releases."""

import argparse
import json
from pathlib import Path
import re
import sys

UPSTREAM = "openai/codex"
FORK = "holdmyspot-com/codex-unleashed"
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


def release_notes(version, build, upstream_tag, upstream_commit, fork_commit, patches):
    """Render the release notes as Markdown."""
    tag = f"rust-v{version}%2B{build}"
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

## Patches applied

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
    patches = commands.add_parser("patches")
    patches.add_argument("--readme", type=Path, required=True)
    notes = commands.add_parser("notes")
    notes.add_argument("--version", required=True)
    notes.add_argument("--build", type=int, required=True)
    notes.add_argument("--upstream-tag", required=True)
    notes.add_argument("--upstream-commit", required=True)
    notes.add_argument("--fork-commit", required=True)
    notes.add_argument("--readme", type=Path, required=True)
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
        elif args.command == "patches":
            print(" ".join(patch_queue(args.readme.read_text())))
        elif args.command == "notes":
            print(release_notes(args.version, args.build, args.upstream_tag, args.upstream_commit, args.fork_commit,
                                patch_queue(args.readme.read_text())))
        else:
            print(failure_summary(args.upstream_tag, args.failing, args.log_url))
    except (ValueError, OSError, json.JSONDecodeError) as error:
        print(f"upstream-auto-release: {error}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
