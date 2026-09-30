#!/usr/bin/env python3
"""Check Cargo Shear, allowing only unchanged warnings from a verified release.

All errors and new warnings fail CI. A baseline warning is accepted only at its
recorded upstream commit and when the affected source files match that commit.
The baseline contains diagnostics from a pristine checkout of that release.
"""

import argparse
import json
from pathlib import Path
import subprocess


def check_report(report, baseline, source_sha, baseline_sources_changed):
    if report['summary']['errors']:
        return False
    if not report['findings']:
        return report['summary']['warnings'] == 0
    if source_sha != baseline['source_sha'] or baseline_sources_changed:
        return False
    return all(finding['severity'] == 'warning'
               and finding in baseline['findings'] for finding in report['findings'])


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('checkout', type=Path)
    parser.add_argument('--cargo-shear', default='cargo-shear')
    args = parser.parse_args()
    baseline = json.loads(Path(__file__).with_name('cargo-shear-baseline.json').read_text())
    source_sha = subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=args.checkout,
                                         text=True).strip()
    changed = subprocess.check_output(['git', 'diff', 'HEAD', '--name-only', '--',
                                       *baseline['source_paths']], cwd=args.checkout, text=True)
    result = subprocess.run([args.cargo_shear, '--deny-warnings', '--format=json'],
                            cwd=args.checkout / 'codex-rs', text=True, capture_output=True)
    print(result.stderr, end='')
    print(result.stdout, end='')
    if result.returncode not in (0, 1):
        return result.returncode
    report = json.loads(result.stdout)
    if not check_report(report, baseline, source_sha, bool(changed)):
        return 1
    if report['findings']:
        print('Only verified, unchanged upstream release warnings remain.')
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
