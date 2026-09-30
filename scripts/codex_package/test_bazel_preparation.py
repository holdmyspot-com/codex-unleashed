from pathlib import Path
import unittest


ROOT = Path(__file__).resolve().parents[2]
ACTION = ROOT / '.github/actions/prepare-bazel-ci/action.yml'


class BazelPreparationTest(unittest.TestCase):
    def test_bazel_setup_preserves_upstream_sources_and_dependency_resolution(self):
        action = ACTION.read_text()
        for mutation in ('write_text(', 'generate-lockfile', 'validate_lockfile = False',
                         'rm -f MODULE.bazel.lock'):
            with self.subTest(mutation=mutation):
                self.assertTrue(mutation not in action,
                                'Bazel setup must preserve upstream sources and lockfiles')

    def test_bazel_setup_retains_shared_caches_and_execution_logs(self):
        action = ACTION.read_text()
        for required in ('./.github/actions/setup-bazel-ci', 'actions/cache/restore@',
                         "hashFiles('upstream/MODULE.bazel'", 'CODEX_BAZEL_EXECUTION_LOG_COMPACT_DIR'):
            self.assertTrue(required in action, f'Bazel setup requires {required}')


if __name__ == '__main__':
    unittest.main()
