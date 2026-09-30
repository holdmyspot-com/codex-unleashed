import importlib.util
from pathlib import Path
import unittest


SCRIPT = Path(__file__).resolve().parents[2] / '.github/scripts/check-cargo-shear.py'


class CargoShearBaselineTest(unittest.TestCase):
    def checker(self):
        self.assertTrue(SCRIPT.exists(), 'CI requires a release-baseline check')
        spec = importlib.util.spec_from_file_location('cargo_shear_check', SCRIPT)
        module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(module)
        return module

    def test_accepts_only_verified_unchanged_release_warnings(self):
        module = self.checker()
        baseline = {'source_sha': 'a' * 40, 'findings': [
            {'code': 'shear/unlinked_files', 'severity': 'warning', 'message': 'known orphan'}]}
        report = {'summary': {'errors': 0, 'warnings': 1}, 'findings': baseline['findings']}
        self.assertTrue(module.check_report(report, baseline, 'a' * 40, False))
        self.assertFalse(module.check_report(report, baseline, 'b' * 40, False))
        self.assertFalse(module.check_report(report, baseline, 'a' * 40, True))

    def test_rejects_new_warnings_and_every_error(self):
        module = self.checker()
        baseline = {'source_sha': 'a' * 40, 'findings': []}
        for severity in ('warning', 'error'):
            report = {'summary': {'errors': int(severity == 'error'),
                                  'warnings': int(severity == 'warning')},
                      'findings': [{'code': 'shear/unused_dependency', 'severity': severity}]}
            self.assertFalse(module.check_report(report, baseline, 'a' * 40, False))
        self.assertTrue(module.check_report({'summary': {'errors': 0, 'warnings': 0},
                                            'findings': []}, baseline, 'b' * 40, False))


if __name__ == '__main__':
    unittest.main()
