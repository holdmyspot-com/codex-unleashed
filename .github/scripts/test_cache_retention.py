#!/usr/bin/env python3

import json
import subprocess
import unittest

import cache_retention


class CacheRetentionTest(unittest.TestCase):
    def test_cache_prefix_distinguishes_stable_and_development_refs(self):
        self.assertEqual(cache_retention.cache_prefix('rust-v0.160.0'), 'upstream-rust-v0.160.0-')
        self.assertEqual(cache_retention.cache_prefix('main'), 'upstream-unreleased-')

    def test_retains_two_highest_stable_versions(self):
        self.assertEqual(cache_retention.retained_stable_tags([
            'rust-v0.99.0', 'rust-v0.160.0', 'rust-v0.159.3',
            'rust-v0.161.0-alpha.1', 'rust-v0.160.0',
        ]), ('rust-v0.160.0', 'rust-v0.159.3'))

    def test_actions_cleanup_deletes_old_managed_caches_on_all_refs(self):
        requests = []
        caches = [
            {'id': 1, 'key': 'upstream-rust-v0.160.0-pnpm-Linux-hash'},
            {'id': 2, 'key': 'v0-upstream-rust-v0.159.3-rust-downloads'},
            {'id': 3, 'key': 'upstream-rust-v0.159.2-bazel-cache-sdk'},
            {'id': 4, 'key': 'pnpm-Linux-legacy-hash'},
            {'id': 5, 'key': 'rusty-v8-v2-target-legacy'},
            {'id': 6, 'key': 'v0-rust-codex-release-downloads-v5-target'},
            {'id': 7, 'key': 'unrelated-cache'},
            {'id': 8, 'key': 'setup-uv-2-old-dependencies'},
            {'id': 9, 'key': 'setup-uv-2-upstream-rust-v0.160.0-hash'},
        ]

        def run(arguments):
            requests.append(arguments)
            if '--method' in arguments:
                return ''
            if '.actions_caches[] | tojson' in arguments:
                return '\n'.join(json.dumps(cache) for cache in caches)
            return 'rust-v0.160.0\nrust-v0.159.3\n'

        result = cache_retention.prune_actions_caches('example/codex', False, run)
        self.assertEqual(result['obsolete_cache_ids'], [3, 4, 5, 6, 8])
        self.assertEqual([request[-1] for request in requests if '--method' in request], [
            f'repos/example/codex/actions/caches/{cache_id}' for cache_id in (3, 4, 5, 6, 8)
        ])

    def test_invalid_repository_is_rejected_before_api_calls(self):
        def run(arguments):
            self.fail('Invalid repository must not reach GitHub')

        with self.assertRaisesRegex(ValueError, 'owner/name'):
            cache_retention.prune_actions_caches('../other/repo', False, run)

    def test_malformed_cache_inventory_prevents_deletion(self):
        requests = []

        def run(arguments):
            requests.append(arguments)
            if '.actions_caches[] | tojson' in arguments:
                return '{broken JSON'
            return 'rust-v0.160.0\nrust-v0.159.3\n'

        with self.assertRaises(json.JSONDecodeError):
            cache_retention.prune_actions_caches('example/codex', False, run)
        self.assertFalse(any('--method' in request for request in requests))

    def test_delete_failure_stops_remaining_deletions(self):
        deletions = []

        def run(arguments):
            if '--method' in arguments:
                deletions.append(arguments[-1])
                raise subprocess.CalledProcessError(1, ['gh', *arguments])
            if '.actions_caches[] | tojson' in arguments:
                return '\n'.join(json.dumps({'id': cache_id, 'key': 'pnpm-legacy'})
                                 for cache_id in (1, 2))
            return 'rust-v0.160.0\nrust-v0.159.3\n'

        with self.assertRaises(subprocess.CalledProcessError):
            cache_retention.prune_actions_caches('example/codex', False, run)
        self.assertEqual(deletions, ['repos/example/codex/actions/caches/1'])

    def test_lookup_failure_prevents_all_deletions(self):
        requests = []

        def run(arguments):
            requests.append(arguments)
            raise subprocess.CalledProcessError(1, ['gh', *arguments])

        with self.assertRaises(subprocess.CalledProcessError):
            cache_retention.prune_actions_caches('example/codex', False, run)
        self.assertEqual(len(requests), 1)

    def test_empty_release_discovery_prevents_cache_lookup_and_deletion(self):
        requests = []

        def run(arguments):
            requests.append(arguments)
            return ''

        with self.assertRaisesRegex(ValueError, 'No stable upstream releases'):
            cache_retention.prune_actions_caches('example/codex', False, run)
        self.assertEqual(len(requests), 1)

    def test_dry_run_reports_obsolete_ids_without_deleting(self):
        requests = []

        def run(arguments):
            requests.append(arguments)
            if '.actions_caches[] | tojson' in arguments:
                return json.dumps({'id': 8, 'key': 'apt-Linux-legacy'})
            return 'rust-v0.160.0\nrust-v0.159.3\n'

        result = cache_retention.prune_actions_caches('example/codex', True, run)
        self.assertEqual(result['obsolete_cache_ids'], [8])
        self.assertFalse(any('--method' in request for request in requests))


if __name__ == '__main__':
    unittest.main()
