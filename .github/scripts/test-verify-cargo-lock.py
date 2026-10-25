#!/usr/bin/env python3
"""Exercise the lockfile verifier CLI, retaining evidence and removing owned fixtures."""

import argparse
import json
from pathlib import Path
import subprocess
import sys
import tempfile


def verify(verifier, evidence):
    """Check permitted synchronization and rejected drift through the actual command."""
    verifier = verifier.resolve()
    evidence = evidence.resolve()
    evidence.mkdir(parents=True, exist_ok=True)
    assert verifier.is_file(), verifier
    original = '''version = 4
[[package]]
name = "codex-cli"
version = "0.0.0"
dependencies = ["codex-protocol 0.0.0", "fixture-registry 1.0.0"]
[[package]]
name = "codex-protocol"
version = "0.0.0"
[[package]]
name = "fixture-registry"
version = "1.0.0"
source = "registry+https://example.invalid/index"
checksum = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
'''
    synchronized = original.replace("0.0.0", "0.164.0")
    members = ["fixture#codex-cli@0.164.0", "fixture#codex-protocol@0.164.0"]
    metadata = {"workspace_members": members, "packages": [
        {"id": members[0], "name": "codex-cli", "version": "0.164.0"},
        {"id": members[1], "name": "codex-protocol", "version": "0.164.0"},
    ]}
    cases = [
        ("unchanged", synchronized, synchronized, 0, 0),
        ("workspace-versions", original, synchronized, 0, 2),
        ("equivalent-spelling", original, "# same values\n" + synchronized.replace('"', "'"), 0, 2),
        ("dependency-version", original, synchronized.replace("1.0.0", "1.0.1"), 1, None),
        ("checksum", original, synchronized.replace("a" * 64, "b" * 64), 1, None),
        ("registry-source", original, synchronized.replace("example.invalid", "other.invalid"), 1, None),
        ("wrong-workspace-version", original, synchronized.replace("0.164.0", "0.165.0"), 1, None),
        ("numeric-type", original, synchronized.replace("version = 4\n", "version = 4.0\n"), 1, None),
        ("explicit-source", original, synchronized.replace('name = "codex-cli"',
                                                         'name = "codex-cli"\nsource = ""'), 1, None),
        ("added-package", original, synchronized + '\n[[package]]\nname="extra"\nversion="1.0.0"\n', 1, None),
        ("malformed", original, 'version = "unterminated\n', 1, None),
    ]
    rows = []
    with tempfile.TemporaryDirectory(prefix="lock-guard-fixture-", dir=evidence) as temporary:
        fixture = Path(temporary)
        for label, before, after, expected_exit, expected_updates in cases:
            paths = [fixture / "original.lock", fixture / "current.lock", fixture / "metadata.json"]
            inputs = [before, after, json.dumps(metadata)]
            for path, content in zip(paths, inputs):
                path.write_text(content, encoding="utf-8")
            original_bytes = [path.read_bytes() for path in paths]
            result = subprocess.run([sys.executable, str(verifier), *map(str, paths)],
                                    capture_output=True, text=True)
            row = {"case": label, "exit": result.returncode,
                   "stdout": result.stdout, "stderr": result.stderr}
            (evidence / (label + ".json")).write_text(json.dumps(row, indent=2) + "\n", encoding="utf-8")
            assert result.returncode == expected_exit, row
            assert [path.read_bytes() for path in paths] == original_bytes, label
            if expected_exit == 0:
                receipt = json.loads(result.stdout)
                assert receipt["result"] == "verified", row
                assert len(receipt["workspace_version_updates"]) == expected_updates, row
            else:
                assert "Cargo.lock verification failed:" in result.stderr, row
            rows.append(row)
    receipt = {"verifier": str(verifier), "cases": rows, "fixture_removed": not fixture.exists()}
    assert receipt["fixture_removed"]
    (evidence / "lock-verification.json").write_text(json.dumps(receipt, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(receipt, indent=2))


def main():
    """Read verifier/evidence paths and run the focused CLI cases."""
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("verifier", type=Path)
    parser.add_argument("evidence", type=Path)
    arguments = parser.parse_args()
    verify(arguments.verifier, arguments.evidence)


if __name__ == "__main__":
    main()
