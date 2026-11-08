#!/usr/bin/env python3
"""Exercise the direct-dependency lint wrapper: its dependency listing and its rustc argument rewriting."""

import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

SCRIPT = Path(__file__).with_name("direct-dependency-lints.py")


def package(name, version, source="registry+https://github.com/rust-lang/crates.io-index"):
    """Return a cargo metadata package id and package record."""
    identifier = f"{name} {version} ({source})"
    return identifier, {"id": identifier, "name": name, "version": version, "source": source}


class DirectDependencyLintsTest(unittest.TestCase):
    """Check which packages count as direct dependencies and that only they lose Cargo's lint cap."""

    def test_list_names_only_non_member_direct_dependencies(self):
        """
        The listing names each non-member package a workspace member depends on directly, once, by name and version;
        transitive dependencies and members themselves are left out.
        """
        member_a, a = package("codex-a", "0.0.0", None)
        member_b, b = package("codex-b", "0.0.0", None)
        serde, serde_record = package("serde", "1.0.0")
        old_rand, old_rand_record = package("rand", "0.8.5")
        new_rand, new_rand_record = package("rand", "0.9.0")
        libc, libc_record = package("libc", "0.2.0")
        metadata = {
            "packages": [a, b, serde_record, old_rand_record, new_rand_record, libc_record],
            "workspace_members": [member_a, member_b],
            "resolve": {"nodes": [
                {"id": member_a, "deps": [{"pkg": member_b}, {"pkg": serde}, {"pkg": new_rand}]},
                {"id": member_b, "deps": [{"pkg": serde}]},
                {"id": serde, "deps": []},
                {"id": new_rand, "deps": [{"pkg": old_rand}, {"pkg": libc}]},
                {"id": old_rand, "deps": []},
                {"id": libc, "deps": []},
            ]},
        }
        result = subprocess.run([sys.executable, "-I", SCRIPT.as_posix(), "list"], input=json.dumps(metadata),
                                capture_output=True, text=True)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(result.stdout, "rand 0.9.0\nserde 1.0.0\n")

    def wrap(self, name, version, arguments):
        """Run the wrapper for one rustc call of package name at version and return the arguments rustc received."""
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            listing = root / "direct.txt"
            listing.write_text("serde 1.0.0\nrand 0.9.0\n")
            rustc = root / "rustc"
            rustc.write_text("#!/usr/bin/env python3\nimport json, sys\nprint(json.dumps(sys.argv[1:]))\n")
            rustc.chmod(0o755)
            environment = dict(os.environ, CODEX_DIRECT_DEPENDENCIES=listing.as_posix(), CARGO_PKG_NAME=name,
                               CARGO_PKG_VERSION=version)
            result = subprocess.run([sys.executable, "-I", SCRIPT.as_posix(), rustc.as_posix(), *arguments],
                                    env=environment, capture_output=True, text=True)
            self.assertEqual(result.returncode, 0, result.stderr)
            return json.loads(result.stdout)

    def test_direct_dependency_loses_the_cap_in_both_spellings(self):
        """A direct dependency's call loses --cap-lints, whether its value is separate or joined with '='."""
        self.assertEqual(self.wrap("serde", "1.0.0", ["--crate-name", "serde", "--cap-lints", "allow", "src/lib.rs"]),
                         ["--crate-name", "serde", "src/lib.rs"])
        self.assertEqual(self.wrap("rand", "0.9.0", ["--cap-lints=warn", "src/lib.rs"]), ["src/lib.rs"])

    def test_other_packages_keep_every_argument(self):
        """A transitive dependency, another version of a direct dependency and a probe keep their arguments."""
        arguments = ["--crate-name", "rand", "--cap-lints", "allow", "src/lib.rs"]
        self.assertEqual(self.wrap("rand", "0.8.5", arguments), arguments)
        self.assertEqual(self.wrap("libc", "0.2.0", arguments), arguments)
        self.assertEqual(self.wrap("", "", ["-vV"]), ["-vV"])


    def test_build_script_warnings_of_direct_dependencies_are_reported(self):
        """
        The build-warnings mode prints each cargo:warning that a direct dependency's build script recorded, in either
        directive spelling, with its package, and exits 1; other packages' warnings and other directives are ignored.
        """
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            listing = root / "direct.txt"
            listing.write_text("aws-lc-rs 1.0.0\nring 0.17.0\n")
            build = root / "target" / "x86_64-unknown-linux-musl" / "debug" / "build"
            for name, text in {"aws-lc-rs-0123abcd": "cargo:rustc-link-lib=crypto\ncargo:warning=old compiler\n",
                               "ring-4567ef01": "cargo::warning=missing assembler\n",
                               "aws-lc-sys-89abcdef": "cargo:warning=transitive\n",
                               "ring-0000ffff": ""}.items():
                (build / name).mkdir(parents=True)
                (build / name / "output").write_text(text)
            result = subprocess.run([sys.executable, "-I", SCRIPT.as_posix(), "build-warnings", listing.as_posix(),
                                     (root / "target").as_posix()], capture_output=True, text=True)
            self.assertEqual(result.returncode, 1, result.stderr)
            self.assertEqual(result.stdout, "aws-lc-rs: old compiler\nring: missing assembler\n")
            listing.write_text("ring 0.17.0\n")
            (build / "ring-4567ef01" / "output").write_text("")
            result = subprocess.run([sys.executable, "-I", SCRIPT.as_posix(), "build-warnings", listing.as_posix(),
                                     (root / "target").as_posix()], capture_output=True, text=True)
            self.assertEqual((result.returncode, result.stdout), (0, ""), result.stderr)


if __name__ == "__main__":
    unittest.main()
