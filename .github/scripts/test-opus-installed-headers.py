#!/usr/bin/env python3
"""Check that the Opus headers patch makes CMake install copies of headers, not symbolic links.

Under Bazel on Windows (startup --windows_enable_symlinks) the opusic-sys build script sees the Opus sources through
symbolic links. CMake's install step keeps a symbolic link as one, so each installed include/opus/*.h pointed back into
an external repository directory that Bazel's output validation cannot resolve ("child include/opus/opus_types.h is a
dangling symbolic link").

The patch backports upstream's opusic_sys_install_headers.patch, which copies the public headers into the build
directory and installs those copies. This test takes that patch's change out of the queue's patch and applies it to a
small project that installs a symbolic-linked header the way Opus's CMakeLists.txt does, and requires a regular file;
the same project without the change must install a symbolic link, which shows the project reproduces the problem.

Run with cmake and a C compiler on the PATH: python3 .github/scripts/test-opus-installed-headers.py
"""

import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path

PATCH = (Path(__file__).resolve().parents[2] / "patches" / "holdmyspot-com" / "codex-unleashed" / "windows-bazel"
         / "opus-installed-headers.patch")
CRATE_PATCH_PATH = "patches/opusic_sys_install_headers.patch"

PROJECT = """\
cmake_minimum_required(VERSION 3.21)
project(headers C)
file(WRITE "${{CMAKE_CURRENT_BINARY_DIR}}/library.c" "int headers_test(void) {{ return 0; }}\\n")
set(Opus_PUBLIC_HEADER "${{CMAKE_CURRENT_SOURCE_DIR}}/include/opus/linked.h")
{added}
add_library(opus STATIC "${{CMAKE_CURRENT_BINARY_DIR}}/library.c")
set_target_properties(opus PROPERTIES PUBLIC_HEADER {public_header})
install(TARGETS opus ARCHIVE DESTINATION lib PUBLIC_HEADER DESTINATION include/opus)
"""


def crate_patch():
    """Return the lines of the crate patch that the queue's patch adds as a new file."""
    lines = PATCH.read_text(encoding="utf-8").splitlines()
    start = lines.index(f"+++ b/{CRATE_PATCH_PATH}")
    patch = []
    for line in lines[start + 2:]:
        if not line.startswith("+"):
            break
        patch.append(line[1:])
    return patch


def public_header_value(line):
    """Return the quoted header list that a PUBLIC_HEADER property line of the crate patch sets."""
    return line[1:].strip().removesuffix(")")


def project(patched):
    """Return the test project with or without the crate patch's change."""
    patch = crate_patch()
    removed = [line for line in patch if line.startswith("-") and not line.startswith("---")]
    added = [line[1:] for line in patch if line.startswith("+") and not line.startswith("+++")]
    removed_value = [public_header_value(line) for line in removed if "Opus_PUBLIC_HEADER" in line]
    added_value = [public_header_value(line) for line in added if line.strip().startswith('"${')]
    if patched:
        block = "\n".join(line for line in added if not line.strip().startswith('"${'))
        return PROJECT.format(added=block, public_header=added_value[-1])
    return PROJECT.format(added="", public_header=removed_value[-1])


def run(*command):
    """Run a command; on failure, raise an error that includes its output."""
    completed = subprocess.run(command, capture_output=True, text=True)
    if completed.returncode != 0:
        raise AssertionError(f"{command} exited with {completed.returncode}:\n{completed.stdout}{completed.stderr}")


class OpusInstalledHeadersTest(unittest.TestCase):
    def install_headers(self, patched):
        """Configure, build and install the project.

        Returns whether the installed header is a link, and its content when it is a regular file.
        """
        cmake = shutil.which("cmake")
        self.assertIsNotNone(cmake, "cmake must be on the PATH")
        with tempfile.TemporaryDirectory() as scratch:
            root = Path(scratch)
            source = root / "source"
            (source / "include" / "opus").mkdir(parents=True)
            (source / "real").mkdir()
            (source / "real" / "linked.h").write_text("#pragma once\n", encoding="utf-8")
            (source / "include" / "opus" / "linked.h").symlink_to(source / "real" / "linked.h")
            (source / "CMakeLists.txt").write_text(project(patched), encoding="utf-8")
            run(cmake, "-S", str(source), "-B", str(root / "build"))
            # A multi-configuration generator, such as Visual Studio, builds Debug by default but installs Release.
            run(cmake, "--build", str(root / "build"), "--config", "Release")
            run(cmake, "--install", str(root / "build"), "--config", "Release", "--prefix", str(root / "prefix"))
            installed = root / "prefix" / "include" / "opus" / "linked.h"
            # On Windows an installed link may not resolve, as in the Bazel failure, so read only a regular file.
            is_symlink = installed.is_symlink()
            result = (is_symlink, None if is_symlink else installed.read_text(encoding="utf-8"))
        return result

    def test_install_keeps_symbolic_links_without_the_patch(self):
        is_symlink, _ = self.install_headers(False)
        self.assertTrue(is_symlink, "the project no longer reproduces CMake installing a symbolic link")

    def test_install_copies_symbolic_linked_headers_with_the_patch(self):
        is_symlink, content = self.install_headers(True)
        self.assertFalse(is_symlink, "the installed header is still a symbolic link")
        self.assertEqual("#pragma once\n", content)


if __name__ == "__main__":
    unittest.main()
