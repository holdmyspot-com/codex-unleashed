import importlib.util
from pathlib import Path
import tempfile
import unittest


SCRIPT = Path(__file__).with_name("reclaim-baseline-macos-xcodes.py")
SPEC = importlib.util.spec_from_file_location("reclaim_baseline_macos_xcodes", SCRIPT)
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)


class InactiveXcodeTests(unittest.TestCase):
    """Check inactive Xcode selection while preserving selected tools, unrelated directories and symlinks."""
    def setUp(self):
        """Create test-owned selected and older Xcode directory fixtures and register their cleanup."""
        self.scratch = tempfile.TemporaryDirectory()
        self.addCleanup(self.scratch.cleanup)
        self.applications = Path(self.scratch.name) / "Applications"
        self.applications.mkdir()
        self.selected = self.make_xcode("Xcode_16.4.app")
        self.old = self.make_xcode("Xcode_16.3.app")

    def make_xcode(self, name):
        """Create an Xcode application fixture named name and return its Contents/Developer directory."""
        developer = self.applications / name / "Contents" / "Developer"
        developer.mkdir(parents=True)
        return developer

    def test_preserves_selected_xcode_and_unrelated_applications(self):
        """Require selection of the older version only, preserving active and unrelated directories."""
        unrelated = self.applications / "Browser.app"
        unrelated.mkdir()
        self.assertEqual(
            MODULE.inactive_xcodes(self.applications, self.selected),
            [self.old.parent.parent],
        )
        self.assertTrue(self.selected.is_dir())
        self.assertTrue(unrelated.is_dir())

    def test_preserves_selected_xcode_through_unversioned_alias(self):
        """Require a selected unversioned symlink to preserve its versioned Xcode target."""
        alias = self.applications / "Xcode.app"
        alias.symlink_to(self.selected.parent.parent, target_is_directory=True)
        selected_alias = alias / "Contents" / "Developer"
        self.assertEqual(
            MODULE.inactive_xcodes(self.applications, selected_alias),
            [self.old.parent.parent],
        )

    def test_does_not_select_symlinks_to_other_trees(self):
        """Require inactive-Xcode selection to skip symlinks and preserve their external targets."""
        outside = Path(self.scratch.name) / "external-tools"
        outside.mkdir()
        (self.applications / "Xcode_alias.app").symlink_to(
            outside, target_is_directory=True
        )
        self.assertEqual(
            MODULE.inactive_xcodes(self.applications, self.selected),
            [self.old.parent.parent],
        )
        self.assertTrue(outside.is_dir())

    def test_rejects_command_line_tools_before_selecting_removals(self):
        """Require ValueError for a selected directory outside an Xcode app, preserving older Xcode."""
        tools = Path(self.scratch.name) / "CommandLineTools"
        tools.mkdir()
        with self.assertRaisesRegex(ValueError, "not an Xcode application"):
            MODULE.inactive_xcodes(self.applications, tools)
        self.assertTrue(self.old.is_dir())

    def test_rejects_missing_selected_developer_directory(self):
        """Require FileNotFoundError when the selected developer directory is missing."""
        with self.assertRaises(FileNotFoundError):
            MODULE.inactive_xcodes(self.applications, self.selected / "missing")
        self.assertTrue(self.old.is_dir())


if __name__ == "__main__":
    unittest.main()
