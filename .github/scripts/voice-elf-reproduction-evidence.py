#!/usr/bin/env python3
"""Emit byte and selected ELF section digests at voice packaging boundaries."""

import hashlib
import json
from pathlib import Path, PurePosixPath
import re
import shutil
import subprocess
import sys
import tarfile
import tempfile


LIBRARY = re.compile(r"lib[A-Za-z0-9_+.-]+\.so(?:\.[0-9]+)*")
SECTION = re.compile(
    r"\s*\[\s*\d+\]\s+(\.[\w.]+)\s+(\w+)\s+[0-9a-fA-F]+\s+"
    r"([0-9a-fA-F]+)\s+([0-9a-fA-F]+)"
)
SECTION_PREFIXES = (
    ".debug_", ".zdebug_", ".text", ".rodata", ".data",
    ".note.gnu.build-id", ".comment", ".dynamic", ".dynstr", ".symtab", ".strtab",
)


def digest_region(stream, offset, size):
    """Hash a bounded file region using a fixed read buffer."""
    stream.seek(offset)
    digest = hashlib.sha256()
    while size:
        block = stream.read(min(size, 1024 * 1024))
        if not block:
            raise ValueError("Truncated input while hashing a voice payload")
        digest.update(block)
        size -= len(block)
    return digest.hexdigest()


def fingerprint(path):
    """Hash an entire payload and selected file-backed ELF sections."""
    size = path.stat().st_size
    with path.open("rb") as stream:
        result = {"sha256": digest_region(stream, 0, size), "size": size}
        stream.seek(0)
        if stream.read(4) != b"\x7fELF":
            return result
        sections = subprocess.run(
            ["readelf", "-SW", path], check=True, capture_output=True, text=True
        ).stdout
        result["sections"] = {}
        for line in sections.splitlines():
            entry = SECTION.match(line)
            if not entry:
                continue
            name, kind, offset, length = entry.groups()
            if kind == "NOBITS" or not name.startswith(SECTION_PREFIXES):
                continue
            result["sections"][name] = digest_region(stream, int(offset, 16), int(length, 16))
        if not result["sections"]:
            raise ValueError("readelf returned no selected file-backed sections")
        return result


def runtime_fingerprints(runtime, inventory):
    """Fingerprint only the packaged inventory, using public relative paths."""
    return {name: fingerprint(runtime / name) for name in inventory}


def linked_fingerprints(prefix, scratch):
    """Read every regular shared object from the native prefix without unpacking paths."""
    linked = {}
    with tarfile.open(prefix, "r:*") as archive:
        for item in archive:
            path = PurePosixPath(item.name)
            if not item.isreg() or not LIBRARY.fullmatch(path.name):
                continue
            name = ("plugins/" if path.parent.name == "gstreamer-1.0" else "lib/") + path.name
            if name in linked:
                raise ValueError("Ambiguous shared object in native prefix")
            with archive.extractfile(item) as source, scratch.open("wb") as target:
                shutil.copyfileobj(source, target)
            linked[name] = fingerprint(scratch)
    if not linked:
        raise ValueError("No shared objects found in native prefix")
    return linked


def main():
    """Collect the two published boundaries and their native build inputs."""
    bazel_bin, release = map(Path, sys.argv[1:])
    voice_bin = bazel_bin / "third_party/voice"
    sealed = release / "runtime"
    projected = voice_bin / "native_runtime_linux_x86_64"
    inventory = ["runtime.json"]
    for item in json.loads((sealed / "runtime.json").read_text(encoding="utf-8"))["libraries"]:
        path = PurePosixPath(item["path"])
        if str(path.parent) not in ("lib", "lib/gstreamer-1.0") or not LIBRARY.fullmatch(path.name):
            raise ValueError("Unexpected library path in sealed runtime inventory")
        inventory.append(str(path))
    with tempfile.TemporaryDirectory(dir=release.parent) as temporary:
        linked = linked_fingerprints(
            voice_bin / "native_prefix_linux_x86_64/prefix.tar", Path(temporary) / "library"
        )
    linked["codex-voice-host"] = fingerprint(bazel_bin / "codex-rs/voice-host/codex-voice-host")
    report = {
        "linked": linked,
        "projected": runtime_fingerprints(projected, inventory),
        "sealed": {
            **runtime_fingerprints(sealed, inventory),
            "codex-voice-host": fingerprint(release / "codex-voice-host"),
        },
    }
    print(json.dumps(report, indent=2, sort_keys=True))


if __name__ == "__main__":
    main()
