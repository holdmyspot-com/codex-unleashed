#!/usr/bin/env python3
"""Test apt-install-with-fallback.py against copies of the hosted runner images' apt source layouts.

Run with: python3 .github/scripts/test-apt-install-with-fallback.py
"""

import os
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

SCRIPT = Path(__file__).resolve().parent / "apt-install-with-fallback.py"
AMD64_MIRROR = "https://mirrors.edge.kernel.org/ubuntu/"
PORTS_MIRROR = "https://mirrors.ocf.berkeley.edu/ubuntu-ports/"

# Both layouts are copied from the ubuntu-24.04 and ubuntu-24.04-arm images of 2026-11.
# The x64 image replaces the archive URI with a mirror list (runner-images configure-apt-sources.sh).
X64_SOURCES = """\
Types: deb
URIs: mirror+file:/etc/apt/apt-mirrors.txt
Suites: noble noble-updates noble-backports
Components: main universe restricted multiverse
Signed-By: /usr/share/keyrings/ubuntu-archive-keyring.gpg

Types: deb
URIs: mirror+file:/etc/apt/apt-mirrors.txt
Suites: noble-security
Components: main universe restricted multiverse
Signed-By: /usr/share/keyrings/ubuntu-archive-keyring.gpg
"""
X64_MIRRORS = ("http://azure.archive.ubuntu.com/ubuntu/\tpriority:1\n"
               "https://archive.ubuntu.com/ubuntu/\tpriority:2\n"
               "https://security.ubuntu.com/ubuntu/\tpriority:3\n")
PORTS_SOURCES = X64_SOURCES.replace("mirror+file:/etc/apt/apt-mirrors.txt", "http://ports.ubuntu.com/ubuntu-ports")
OTHER_SOURCES = """\
Types: deb
URIs: https://packages.microsoft.com/ubuntu/24.04/prod
Suites: noble
Components: main
Signed-By: /usr/share/keyrings/microsoft-prod.gpg
"""

# A stand-in for apt-get: it records each call and fails while the sources still name an ubuntu.com server.
FAKE_APT_GET = """\
#!/bin/sh
printf '%s\\n' "$*" >> "$APT_TEST_LOG"
if grep -q 'ubuntu\\.com\\|apt-mirrors' "$APT_TEST_DIR"/sources.list.d/ubuntu.sources; then
  [ "$1" = update ] && exit 0
  exit 100
fi
exit 0
"""


class AptInstallWithFallbackTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        root = Path(self.temp.name)
        self.apt = root / "apt"
        (self.apt / "sources.list.d").mkdir(parents=True)
        (self.apt / "sources.list.d" / "microsoft-prod.sources").write_text(OTHER_SOURCES)
        self.log = root / "calls.log"
        self.apt_get = root / "apt-get"
        self.apt_get.write_text(FAKE_APT_GET)
        self.apt_get.chmod(0o755)

    def tearDown(self):
        self.temp.cleanup()

    def run_script(self, architecture, apt_get=None):
        env = dict(os.environ, APT_TEST_LOG=str(self.log), APT_TEST_DIR=str(self.apt))
        return subprocess.run(
            [sys.executable, "-I", str(SCRIPT), "--apt-dir", str(self.apt), "--architecture", architecture,
             "--apt-get", str(apt_get or self.apt_get), "zsh", "bubblewrap"],
            env=env, capture_output=True, text=True)

    def failing_apt_get(self):
        """Return an apt-get stand-in whose install always fails."""
        failing = self.apt_get.with_name("failing-apt-get")
        failing.write_text("#!/bin/sh\nprintf '%s\\n' \"$*\" >> \"$APT_TEST_LOG\"\n[ \"$1\" = update ] || exit 100\n")
        failing.chmod(0o755)
        return failing

    def calls(self):
        return self.log.read_text().splitlines()

    def sources(self):
        return (self.apt / "sources.list.d" / "ubuntu.sources").read_text()

    def test_healthy_primary_installs_once_and_keeps_the_sources(self):
        healthy = self.apt_get.with_name("healthy-apt-get")
        healthy.write_text("#!/bin/sh\nprintf '%s\\n' \"$*\" >> \"$APT_TEST_LOG\"\n")
        healthy.chmod(0o755)
        (self.apt / "sources.list.d" / "ubuntu.sources").write_text(X64_SOURCES)
        (self.apt / "apt-mirrors.txt").write_text(X64_MIRRORS)
        result = self.run_script("amd64", healthy)
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual(["update -y", "install -y --no-install-recommends zsh bubblewrap"], self.calls())
        self.assertEqual(X64_SOURCES, self.sources())

    def test_x64_failure_retries_on_the_fallback_mirror(self):
        (self.apt / "sources.list.d" / "ubuntu.sources").write_text(X64_SOURCES)
        (self.apt / "apt-mirrors.txt").write_text(X64_MIRRORS)
        result = self.run_script("amd64")
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual(["update -y", "install -y --no-install-recommends zsh bubblewrap"] * 2, self.calls())
        self.assertEqual(X64_SOURCES.replace("mirror+file:/etc/apt/apt-mirrors.txt", AMD64_MIRROR), self.sources())
        self.assertEqual(OTHER_SOURCES, (self.apt / "sources.list.d" / "microsoft-prod.sources").read_text())
        self.assertIn(AMD64_MIRROR, result.stderr)

    def test_ports_failure_retries_on_the_ports_fallback_mirror(self):
        (self.apt / "sources.list.d" / "ubuntu.sources").write_text(PORTS_SOURCES)
        result = self.run_script("arm64")
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual(PORTS_SOURCES.replace("http://ports.ubuntu.com/ubuntu-ports", PORTS_MIRROR), self.sources())

    def test_failure_on_the_fallback_mirror_fails(self):
        failing = self.failing_apt_get()
        (self.apt / "sources.list.d" / "ubuntu.sources").write_text(PORTS_SOURCES)
        result = self.run_script("arm64", failing)
        self.assertEqual(100, result.returncode)
        self.assertEqual(4, len(self.calls()))

    def test_unknown_layout_fails_without_retrying(self):
        failing = self.failing_apt_get()
        (self.apt / "sources.list.d" / "ubuntu.sources").write_text(
            PORTS_SOURCES.replace("http://ports.ubuntu.com/ubuntu-ports", "http://example.com/ubuntu"))
        result = self.run_script("arm64", failing)
        self.assertEqual(100, result.returncode)
        self.assertIn("no Ubuntu archive source", result.stderr)
        self.assertEqual(2, len(self.calls()))


if __name__ == "__main__":
    unittest.main()
