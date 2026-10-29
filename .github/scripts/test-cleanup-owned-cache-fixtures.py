#!/usr/bin/env python3
"""Check manifest and alias validation before a fixture cleanup can delete anything."""

import importlib.util
from pathlib import Path
import unittest


class VersionIdentityTest(unittest.TestCase):
    """Only the exact recorded version, digest and aliases permit cleanup."""

    def setUp(self):
        """Load the checker and create an isolated expected package-version record."""
        script = Path(__file__).with_name("cleanup-owned-cache-fixtures.py")
        specification = importlib.util.spec_from_file_location("fixture_cleanup", script)
        module = importlib.util.module_from_spec(specification)
        specification.loader.exec_module(module)
        self.verify_version = module.verify_version
        self.module = module
        self.expected = {"id": 7, "name": "sha256:" + "a" * 64,
                         "tags": ["fixture-partial", "fixture-release"]}

    def test_matching_version_is_accepted(self):
        """The recorded manifest and tags pass the deletion prerequisite."""
        self.verify_version(self.expected, dict(self.expected))

    def test_tag_order_does_not_change_identity(self):
        """Registry tag ordering does not reject the same complete alias set."""
        actual = dict(self.expected, tags=list(reversed(self.expected["tags"])))
        self.verify_version(self.expected, actual)

    def test_different_version_is_refused(self):
        """Another version ID cannot inherit the recorded fixture's authorization."""
        with self.assertRaises(ValueError):
            self.verify_version(self.expected, dict(self.expected, id=8))

    def test_changed_manifest_is_refused(self):
        """A changed digest cannot use the expected version's cleanup request."""
        with self.assertRaises(ValueError):
            self.verify_version(self.expected, dict(self.expected, name="sha256:" + "b" * 64))

    def test_shared_manifest_with_another_alias_is_refused(self):
        """An additional alias prevents deleting a version another cache may use."""
        with self.assertRaises(ValueError):
            self.verify_version(self.expected, dict(self.expected, tags=self.expected["tags"] + ["another-cache"]))

    def test_missing_recorded_alias_is_refused(self):
        """A version missing its recorded release association requires investigation."""
        with self.assertRaises(ValueError):
            self.verify_version(self.expected, dict(self.expected, tags=["fixture-partial"]))

    def test_only_present_owned_versions_are_approved(self):
        """The supplied version listing yields only matching recorded fixture IDs, ignoring unrelated or absent IDs."""
        actual = dict(self.expected)
        actual["metadata"] = {"container": {"tags": actual.pop("tags")}}
        unrelated = {"id": 9, "name": "unrelated", "metadata": {"container": {"tags": []}}}
        self.assertEqual(self.module.approved_versions([self.expected], [actual, unrelated]), [7])
        self.assertEqual(self.module.approved_versions([self.expected], [unrelated]), [])

    def test_mismatch_aborts_the_entire_approval(self):
        """A mismatched recorded manifest raises ValueError instead of returning approved fixture IDs."""
        actual = dict(self.expected, name="changed", metadata={"container": {"tags": self.expected["tags"]}})
        with self.assertRaises(ValueError):
            self.module.approved_versions([self.expected], [actual])


if __name__ == "__main__":
    unittest.main()
