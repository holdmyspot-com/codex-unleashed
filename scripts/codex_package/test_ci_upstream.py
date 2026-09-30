from pathlib import Path
import subprocess
import tempfile
import unittest


ROOT = Path(__file__).resolve().parents[2]
ACTION = ROOT / '.github/actions/prepare-patched-upstream/action.yml'


class CiUpstreamTest(unittest.TestCase):
    def test_default_upstream_names_a_stable_release(self):
        pin = ROOT / '.github/upstream-ref'
        self.assertTrue(pin.is_file(), 'CI must have one shared upstream version')
        self.assertRegex(pin.read_text().strip(), r'^rust-v\d+\.\d+\.\d+$')

    def test_upstream_selection_preserves_explicit_overrides(self):
        action = ACTION.read_text()
        marker = '        if [[ -z "${UPSTREAM_REF}" ]]; then'
        self.assertTrue(marker in action, 'checkout must resolve its default from the shared pin')
        start = action.index(marker)
        end = action.index('        fi', start) + len('        fi')
        script = action[start:end] + '\nprintf "%s" "$UPSTREAM_REF"'
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / '.github').mkdir()
            (root / '.github/upstream-ref').write_text('rust-v0.159.2\n')
            for supplied, expected in (('', 'rust-v0.159.2'), ('my-test-branch', 'my-test-branch')):
                with self.subTest(supplied=supplied):
                    result = subprocess.run(['bash', '-eu', '-c', script],
                                            env={'UPSTREAM_REF': supplied, 'GITHUB_WORKSPACE': directory,
                                                 'PATH': '/usr/bin:/bin'},
                                            capture_output=True, text=True, check=True)
                    self.assertEqual(result.stdout, expected)
            (root / '.github/upstream-ref').unlink()
            result = subprocess.run(['bash', '-eu', '-c', script],
                                    env={'UPSTREAM_REF': '', 'GITHUB_WORKSPACE': directory,
                                         'PATH': '/usr/bin:/bin'}, capture_output=True)
            self.assertNotEqual(result.returncode, 0, 'a missing pin must not select upstream main')

    def test_ci_callers_do_not_override_the_shared_default(self):
        stale_sha = '848b3845884e3aaf3359867047751dfff12dc448'
        for path in (ROOT / '.github/workflows').glob('*.yml'):
            with self.subTest(workflow=path.name):
                text = path.read_text()
                self.assertTrue(stale_sha not in text, f'{path.name} retains the stale upstream pin')
                self.assertTrue('default: main' not in text, f'{path.name} overrides the supported release')
                self.assertTrue('fallback-to-default-branch: "true"' not in text,
                                f'{path.name} silently changes the requested source')


if __name__ == '__main__':
    unittest.main()
