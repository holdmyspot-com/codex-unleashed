#!/usr/bin/env python3
"""Install Ubuntu packages, retrying from a mirror outside ubuntu.com when the runner image's servers fail.

GitHub-hosted runners fetch packages from Ubuntu servers under ubuntu.com: the x64 image through the mirror list
/etc/apt/apt-mirrors.txt, the ports image (arm64) from ports.ubuntu.com directly. When those names stopped resolving
on the runners (actions/runner-images #15296, #15297), every package install failed. This script runs
`apt-get update` and `apt-get install` as before; only when they fail does it point the Ubuntu sources at a mirror
under another domain and run them once more. The mirror serves the same archive, and each source keeps its Signed-By
keyring, so apt verifies the mirror's content with the same Ubuntu keys.

apt's own mirror list is not used for the fallback: with an extra entry there, apt stalled for 15 minutes after the
image's servers failed (one-off verification run 43094373128).

Usage: sudo python3 apt-install-with-fallback.py PACKAGE...
"""

import argparse
import os
import re
import subprocess
import sys
from pathlib import Path

FALLBACKS = {
    "ubuntu": "https://mirrors.edge.kernel.org/ubuntu/",
    "ubuntu-ports": "https://mirrors.ocf.berkeley.edu/ubuntu-ports/",
}
IMAGE_APT_DIR = "/etc/apt"
URIS = re.compile(r"^URIs:\s*(\S+)\s*$", re.MULTILINE)
UBUNTU_URI = re.compile(r"^https?://[a-z0-9.-]*ubuntu\.com/(ubuntu|ubuntu-ports)/?$")


def fallback_for(architecture):
    return FALLBACKS["ubuntu" if architecture in ("amd64", "i386") else "ubuntu-ports"]


def is_ubuntu_source(uri, apt_dir):
    """Tell whether a source URI names only Ubuntu archive servers under ubuntu.com."""
    if UBUNTU_URI.match(uri):
        return True
    if not uri.startswith("mirror+file:"):
        return False
    mirror_list = Path(uri.removeprefix("mirror+file:"))
    if not mirror_list.is_relative_to(IMAGE_APT_DIR):
        return False
    entries = [line.split("\t", 1)[0] for line in
               (apt_dir / mirror_list.relative_to(IMAGE_APT_DIR)).read_text(encoding="utf-8").splitlines() if line]
    return bool(entries) and all(UBUNTU_URI.match(entry) for entry in entries)


def switch_to_fallback(apt_dir, fallback):
    """Point every Ubuntu archive source at the fallback mirror; return whether any source changed."""
    changed = False
    for sources in sorted((apt_dir / "sources.list.d").glob("*.sources")):
        text = sources.read_text(encoding="utf-8")
        replaced = URIS.sub(
            lambda m: f"URIs: {fallback}" if is_ubuntu_source(m.group(1), apt_dir) else m.group(0), text)
        if replaced != text:
            sources.write_text(replaced, encoding="utf-8")
            changed = True
    return changed


def update_and_install(apt_get, packages):
    """Run apt-get update and install; return the first failing exit code, or 0."""
    environment = dict(os.environ, DEBIAN_FRONTEND="noninteractive")
    for command in ([apt_get, "update", "-y"], [apt_get, "install", "-y", "--no-install-recommends", *packages]):
        code = subprocess.run(command, env=environment).returncode
        if code != 0:
            return code
    return 0


def main():
    parser = argparse.ArgumentParser(description=__doc__.split("\n\n", 1)[0])
    parser.add_argument("--apt-dir", type=Path, default=Path(IMAGE_APT_DIR))
    parser.add_argument("--architecture")
    parser.add_argument("--apt-get", default="apt-get")
    parser.add_argument("packages", nargs="+")
    args = parser.parse_args()
    code = update_and_install(args.apt_get, args.packages)
    if code == 0:
        return 0
    architecture = args.architecture or subprocess.run(
        ["dpkg", "--print-architecture"], check=True, capture_output=True, text=True).stdout.strip()
    fallback = fallback_for(architecture)
    if not switch_to_fallback(args.apt_dir, fallback):
        print(f"apt-get failed with exit code {code}, and no Ubuntu archive source was found in "
              f"{args.apt_dir / 'sources.list.d'} to retry from a fallback mirror", file=sys.stderr)
        return code
    print(f"apt-get failed with exit code {code}; retrying from {fallback}", file=sys.stderr)
    return update_and_install(args.apt_get, args.packages)


if __name__ == "__main__":
    sys.exit(main())
