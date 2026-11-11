#!/usr/bin/env python3
"""Check that the Opus toolchain patch makes CMake install copies of headers, not symbolic links.

Under Bazel on Windows (startup --windows_enable_symlinks) the opusic-sys build script sees the Opus sources through
symbolic links. CMake's install step keeps a symbolic link as one, so the installed include/opus/*.h pointed back into
an external repository directory that Bazel's output validation cannot resolve ("child include/opus/opus_types.h is a
dangling symbolic link").

The patch appends a block to upstream's third_party/voice/opus-toolchain.cmake. This test takes that block from the
patch, uses it as the toolchain file of a small project that installs a symbolic-linked header, and requires a regular
file; the same project without the block must install a symbolic link, which shows the project reproduces the problem.

Run with cmake on the PATH: python3 .github/scripts/test-opus-installed-headers.py
"""

import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path

PATCH = (Path(__file__).resolve().parents[2] / "patches" / "holdmyspot-com" / "codex-unleashed" / "windows-bazel"
         / "opus-installed-headers.patch")
TOOLCHAIN_PATH = "third_party/voice/opus-toolchain.cmake"

PROJECT = """\
cmake_minimum_required(VERSION 3.21)
project(headers NONE)
install(FILES "${CMAKE_SOURCE_DIR}/include/opus/linked.h" DESTINATION include/opus)
"""


def added_toolchain_lines():
    """Return the lines the patch adds to the toolchain file."""
    lines = PATCH.read_text(encoding="utf-8").splitlines()
    start = lines.index(f"+++ b/{TOOLCHAIN_PATH}")
    added = []
    for line in lines[start + 1:]:
        if line.startswith("-- ") or line.startswith("diff --git"):
            break
        if line.startswith("+"):
            added.append(line[1:])
    return "\n".join(added) + "\n"


def run(*command):
    subprocess.run(command, check=True, capture_output=True, text=True)


class OpusInstalledHeadersTest(unittest.TestCase):
    def install_headers(self, toolchain):
        """Configure, build and install the project, and return the installed header's path."""
        cmake = shutil.which("cmake")
        self.assertIsNotNone(cmake, "cmake must be on the PATH")
        with tempfile.TemporaryDirectory() as scratch:
            root = Path(scratch)
            source = root / "source"
            (source / "include" / "opus").mkdir(parents=True)
            (source / "real").mkdir()
            (source / "real" / "linked.h").write_text("#pragma once\n", encoding="utf-8")
            (source / "include" / "opus" / "linked.h").symlink_to(source / "real" / "linked.h")
            (source / "CMakeLists.txt").write_text(PROJECT, encoding="utf-8")
            configure = [cmake, "-S", str(source), "-B", str(root / "build")]
            if toolchain is not None:
                (root / "toolchain.cmake").write_text(toolchain, encoding="utf-8")
                configure.append(f"-DCMAKE_TOOLCHAIN_FILE={root / 'toolchain.cmake'}")
            run(*configure)
            run(cmake, "--install", str(root / "build"), "--prefix", str(root / "prefix"))
            installed = root / "prefix" / "include" / "opus" / "linked.h"
            result = (installed.is_symlink(), installed.read_text(encoding="utf-8"))
        return result

    def test_install_keeps_symbolic_links_without_the_patch(self):
        is_symlink, _ = self.install_headers(None)
        self.assertTrue(is_symlink, "the project no longer reproduces CMake installing a symbolic link")

    def test_install_copies_symbolic_linked_headers_with_the_patch(self):
        is_symlink, content = self.install_headers(added_toolchain_lines())
        self.assertFalse(is_symlink, "the installed header is still a symbolic link")
        self.assertEqual("#pragma once\n", content)


if __name__ == "__main__":
    unittest.main()
