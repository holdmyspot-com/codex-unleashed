import importlib.util
from pathlib import Path
import unittest
from urllib.error import HTTPError


SCRIPT = Path(__file__).resolve().parents[2] / '.github/scripts/ensure-release-tag.py'


class ReleaseTagTest(unittest.TestCase):
    def ensure_tag(self, api, sha='a' * 40):
        spec = importlib.util.spec_from_file_location('release_tag', SCRIPT)
        module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(module)
        return module.ensure_release_tag('owner/repo', 'rust-v0.159.1+23', sha, api)

    def test_reserves_exact_build_commit_before_publication(self):
        calls = []

        def api(method, path, payload=None):
            calls.append((method, path, payload))
            if method == 'GET':
                raise HTTPError(path, 404, 'Not Found', None, None)
            return {'object': {'type': 'commit', 'sha': 'a' * 40}}

        self.ensure_tag(api)
        self.assertTrue(calls, 'release source tag is not reserved')
        self.assertEqual(calls[-1], ('POST', 'repos/owner/repo/git/refs', {
            'ref': 'refs/tags/rust-v0.159.1+23', 'sha': 'a' * 40
        }))

    def test_retry_after_unknown_creation_result_keeps_original_commit(self):
        stored = {}

        def api(method, path, payload=None):
            if method == 'GET':
                if stored:
                    return stored
                raise HTTPError(path, 404, 'Not Found', None, None)
            stored.update(object={'type': 'commit', 'sha': payload['sha']})
            raise TimeoutError('response lost after GitHub creates the tag')

        with self.assertRaises(TimeoutError):
            self.ensure_tag(api)
        self.ensure_tag(api)
        with self.assertRaisesRegex(ValueError, 'different commit'):
            self.ensure_tag(api, 'b' * 40)
        self.assertEqual(stored['object']['sha'], 'a' * 40)

    def test_existing_tag_is_verified_without_replacement(self):
        calls = []

        def api(method, path, payload=None):
            calls.append(method)
            self.assertEqual(method, 'GET')
            return {'object': {'type': 'commit', 'sha': 'a' * 40}}

        self.ensure_tag(api)
        self.assertEqual(calls, ['GET'])

    def test_permission_error_does_not_attempt_tag_creation(self):
        def api(method, path, payload=None):
            self.assertEqual(method, 'GET')
            raise HTTPError(path, 403, 'Forbidden', None, None)

        with self.assertRaises(HTTPError):
            self.ensure_tag(api)

    def test_invalid_tag_is_rejected_before_api_call(self):
        spec = importlib.util.spec_from_file_location('release_tag', SCRIPT)
        module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(module)

        def api(*args):
            self.fail('invalid input reached GitHub')

        with self.assertRaises(ValueError):
            module.ensure_release_tag('owner/repo', '../other', 'a' * 40, api)

    def test_artifact_reuse_preserves_original_build_provenance(self):
        spec = importlib.util.spec_from_file_location('release_tag', SCRIPT)
        module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(module)
        build_run = {'repository': {'full_name': 'owner/repo'},
                     'path': '.github/workflows/build-release.yml', 'head_sha': 'a' * 40,
                     'head_branch': 'main', 'run_attempt': 1}
        self.assertEqual(module.resolve_build_source(build_run, 'owner/repo'), {
            'source_sha': 'a' * 40, 'source_ref': 'refs/heads/main', 'source_run_attempt': 1
        })
        with self.assertRaisesRegex(ValueError, 'release build workflow'):
            module.resolve_build_source({**build_run, 'path': 'other.yml'}, 'owner/repo')


if __name__ == '__main__':
    unittest.main()
