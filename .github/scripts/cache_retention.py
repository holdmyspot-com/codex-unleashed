#!/usr/bin/env python3
"""Prune release-associated Actions caches using the latest two stable releases."""

import argparse
import json
import re
import subprocess
from collections.abc import Callable, Sequence


STABLE_TAG = re.compile(r"rust-v([0-9]+)\.([0-9]+)\.([0-9]+)\Z")
CACHE_RELEASE = re.compile(r"(?:^|-)upstream-(rust-v[0-9]+\.[0-9]+\.[0-9]+)(?:-|$)")
LEGACY_DEPENDENCY_CACHE = re.compile(
    r"^(pnpm-|node-cache-.*-pnpm-|apt-|rusty-v8-|bazel-cache-|setup-uv-|"
    r"v[0-9]+-rust-(?:codex-release-downloads-|repo-checks-))"
)
STABLE_RELEASE_QUERY = (
    '.[] | select(.draft == false and .prerelease == false) | .tag_name '
    '| select(test("^rust-v[0-9]+\\\\.[0-9]+\\\\.[0-9]+$"))'
)


def cache_prefix(upstream_ref: str) -> str:
    """Return the release association prefix for dependency cache keys."""
    version = upstream_ref if STABLE_TAG.fullmatch(upstream_ref) else 'unreleased'
    return f'upstream-{version}-'


def retained_stable_tags(tags: Sequence[str]) -> tuple[str, ...]:
    """Return the two highest stable versions, or refuse an empty discovery."""
    versions = {}
    for tag in tags:
        match = STABLE_TAG.fullmatch(tag)
        if match:
            versions[tag] = tuple(int(part) for part in match.groups())
    if not versions:
        raise ValueError("No stable upstream releases found; refusing to delete caches")
    return tuple(sorted(versions, key=versions.__getitem__, reverse=True)[:2])


def stale_actions_cache_ids(caches: Sequence[dict], retained: Sequence[str]) -> list[int]:
    """Return managed Actions cache IDs that are outside retained releases."""
    obsolete = []
    for cache in caches:
        key = cache['key']
        association = CACHE_RELEASE.search(key)
        if association:
            stale = association.group(1) not in retained
        else:
            stale = bool(LEGACY_DEPENDENCY_CACHE.match(key)) or 'upstream-unreleased-' in key
        if stale:
            obsolete.append(cache['id'])
    return obsolete


def gh_command(arguments: Sequence[str]) -> str:
    """Run GitHub CLI, propagating API failures before dependent deletions."""
    return subprocess.run(
        ['gh', *arguments], check=True, stdout=subprocess.PIPE, text=True,
    ).stdout


def discover_retained_releases(run: Callable[[Sequence[str]], str] = gh_command) -> tuple[str, ...]:
    """Discover stable upstream releases and select the retained versions."""
    tags = run(['api', '--paginate', 'repos/openai/codex/releases?per_page=100',
                '--jq', STABLE_RELEASE_QUERY]).splitlines()
    return retained_stable_tags(tags)


def prune_actions_caches(
    repository: str, dry_run: bool, run: Callable[[Sequence[str]], str] = gh_command,
) -> dict:
    """Discover stable releases, then delete obsolete managed caches by ID.

    Dry runs report the same selection without deleting anything. Lookup or
    decoding failures propagate before any deletion; a deletion failure stops
    further deletions, and a retry relists the surviving caches.
    """
    if not re.fullmatch(r'[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+', repository):
        raise ValueError('repository must have owner/name form')
    retained = discover_retained_releases(run)
    records = run([
        'api', '--paginate', f'repos/{repository}/actions/caches?per_page=100',
        '--jq', '.actions_caches[] | tojson',
    ])
    caches = [json.loads(line) for line in records.splitlines() if line.strip()]
    obsolete = stale_actions_cache_ids(caches, retained)
    if not dry_run:
        for cache_id in obsolete:
            run(['api', '--method', 'DELETE', f'repos/{repository}/actions/caches/{cache_id}'])
    return {'retained_releases': retained, 'obsolete_cache_ids': obsolete, 'dry_run': dry_run}


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest='operation', required=True)
    commands.add_parser('stable-releases', help='Discover the two retained upstream releases')
    prefix = commands.add_parser('cache-prefix', help='Associate a dependency key with an upstream ref')
    prefix.add_argument('--upstream-ref', required=True)
    actions = commands.add_parser('actions', help='Prune managed Actions dependency caches')
    actions.add_argument('--repository', required=True)
    actions.add_argument('--dry-run', action='store_true')
    args = parser.parse_args()
    if args.operation == 'stable-releases':
        print('\n'.join(discover_retained_releases()))
    elif args.operation == 'cache-prefix':
        print(cache_prefix(args.upstream_ref))
    else:
        print(json.dumps(prune_actions_caches(args.repository, args.dry_run)))


if __name__ == '__main__':
    main()
