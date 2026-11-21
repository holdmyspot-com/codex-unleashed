#!/usr/bin/env python3
"""Inventory sealed voice files and restrict macOS package cache trees."""

import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import stat
import sys


BINARIES = {"codex", "codex-app-server", "codex-code-mode-host", "codex-exec-server"}
CARGO_NAMES = BINARIES | {name.replace("-", "_") for name in BINARIES}
PACKAGE_EXECUTABLE = re.compile(rf"(?:{'|'.join(sorted(CARGO_NAMES))})(?:-[0-9a-f]+)?")


def raise_walk_error(failure):
    """Stop if a directory cannot be read."""
    raise failure


def regular_tree(root, skip_dsym=False):
    """Walk regular cache inputs, optionally treating each dSYM as an opaque output."""
    if not stat.S_ISDIR(root.lstat().st_mode):
        raise ValueError(f"Expected a real directory at {root}")
    for directory, dirs, files in os.walk(root, onerror=raise_walk_error):
        dirs.sort()
        for name in dirs + sorted(files):
            path = Path(directory) / name
            info = path.lstat()
            if skip_dsym and name.endswith(".dSYM"):
                if name in dirs:
                    dirs.remove(name)
                yield path, info
                continue
            if not (stat.S_ISDIR(info.st_mode) or stat.S_ISREG(info.st_mode)):
                raise ValueError(f"Unsupported entry in cache input tree: {path.relative_to(root)}")
            yield path, info


def voice_inventory(root):
    """Hash every staged voice byte along with its stable relative path."""
    expected = root / "runtime/runtime.json"
    if not expected.is_file() or not (root / "codex-voice-host").is_file():
        raise ValueError("Missing sealed voice runtime or helper")
    for path, info in regular_tree(root):
        if stat.S_ISREG(info.st_mode):
            with path.open("rb") as source:
                digest = hashlib.file_digest(source, "sha256").hexdigest()
            print(json.dumps([path.relative_to(root).as_posix(), info.st_mode & 0o777, digest]))


def package_outputs(root):
    """Locate shipped binaries, their Cargo aliases, and packed debug bundles."""
    files = []
    bundles = []
    output_inodes = set()
    for path, info in regular_tree(root, skip_dsym=True):
        if path.name.endswith(".dSYM"):
            bundles.append(path)
        elif stat.S_ISREG(info.st_mode):
            identity = (info.st_dev, info.st_ino)
            files.append((path, identity))
            if PACKAGE_EXECUTABLE.fullmatch(path.name):
                output_inodes.add(identity)
    return bundles, [path for path, identity in files if identity in output_inodes]


def prune(root, target):
    """Require the full build output before pruning each executable and alias."""
    release = root / target / "release"
    if not all(stat.S_ISREG((release / name).lstat().st_mode) for name in BINARIES):
        raise ValueError("Expected all shipped macOS package binaries")
    bundles, files = package_outputs(root)
    for path in files:
        path.unlink()
    for path in bundles:
        if path.is_symlink() or not path.is_dir():
            path.unlink()
        else:
            shutil.rmtree(path)
    if any(package_outputs(root)):
        raise ValueError("Package outputs remain after pruning")


def install(stage, destination, target):
    """Atomically replace the empty target directory with a validated cache."""
    bundles, files = package_outputs(stage)
    release = stage / target / "release"
    if bundles or files or not all(
        (release / name).is_dir() and any((release / name).iterdir())
        for name in ("build", "deps", ".fingerprint")
    ):
        raise ValueError("Cache lacks Cargo dependencies or contains macOS package outputs")
    if destination.is_symlink() or not destination.is_dir() or any(destination.iterdir()):
        raise ValueError("Cargo target directory is not empty")
    # Both paths are siblings. rename also refuses to replace a directory that
    # gains content between the check above and this atomic install.
    os.rename(stage, destination)


if __name__ == "__main__":
    operation, root, *args = sys.argv[1:]
    try:
        if operation == "voice-inventory":
            voice_inventory(Path(root))
        elif operation == "prune":
            prune(Path(root), args[0])
        elif operation == "install":
            install(Path(root), Path(args[0]), args[1])
        else:
            raise ValueError(f"Unknown cache tree operation: {operation}")
    except (OSError, ValueError) as failure:
        sys.exit(str(failure))
