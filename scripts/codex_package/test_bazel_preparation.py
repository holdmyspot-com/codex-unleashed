from pathlib import Path
import subprocess
import tempfile
import tomllib
import unittest


ROOT = Path(__file__).resolve().parents[2]
ACTION = ROOT / '.github/actions/prepare-bazel-ci/action.yml'


class BazelPreparationTest(unittest.TestCase):
    def test_bazel_setup_refreshes_release_package_versions_with_cargo(self):
        action = ACTION.read_text()
        command = next(line.strip() for line in action.splitlines()
                       if line.strip().startswith('cargo metadata '))
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / 'src').mkdir()
            (root / 'src/lib.rs').write_text('')
            (root / 'dependency/src').mkdir(parents=True)
            (root / 'dependency/src/lib.rs').write_text('')
            (root / 'dependency/Cargo.toml').write_text(
                '[package]\nname = "dependency"\nversion = "0.12.28"\nedition = "2021"\n')
            (root / 'Cargo.toml').write_text(
                '[package]\nname = "release-package"\nversion = "0.159.2"\nedition = "2021"\n'
                '[dependencies]\ndependency = { path = "dependency", version = "0.12.28" }\n')
            (root / 'Cargo.lock').write_text(
                'version = 4\n[[package]]\nname = "release-package"\nversion = "0.0.0"\n'
                'dependencies = ["dependency"]\n[[package]]\nname = "dependency"\nversion = "0.12.28"\n')
            subprocess.run(['bash', '-eu', '-c', command], cwd=root, capture_output=True, check=True)
            packages = tomllib.loads((root / 'Cargo.lock').read_text())['package']
            versions = {package['name']: package['version'] for package in packages}
            self.assertEqual(versions['release-package'], '0.159.2')
            self.assertEqual(versions['dependency'], '0.12.28')

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
