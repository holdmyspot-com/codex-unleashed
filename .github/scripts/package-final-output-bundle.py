#!/usr/bin/env python3
"""Capture and verify the pre-strip Linux x64 package executable bundle."""

import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import stat
import struct
import sys
import tarfile
import tempfile

TARGET = "x86_64-unknown-linux-musl"
TAG_PREFIX = f"cargo-v3-{TARGET}-pkg-"
MAX_FILE = 2 * 1024**3
MAX_BUNDLE_CONTENTS = 8 * 1024**3
MAX_METADATA = 64 * 1024
CHUNK = 1024**2


def require(condition, message):
    if not condition:
        raise ValueError(message)


def final_identity(tag, source, repository):
    require(re.fullmatch(re.escape(TAG_PREFIX) + r"[0-9a-f]{64}", tag), "Invalid dependency tag")
    require(re.fullmatch(r"[0-9a-f]{40}", source), "Invalid source commit")
    require(re.fullmatch(r"[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+", repository), "Invalid repository")
    return {
        "format_version": 1,
        "target": TARGET,
        "dependency_tag": tag,
        "cache_tag": f"{TAG_PREFIX}final-v1-{tag.removeprefix(TAG_PREFIX)}",
        "source_commit": source,
        "producer_repository": repository,
    }


def executable_names(names):
    require(1 < len(names) <= 32 and len(names) == len(set(names)) and "bwrap" in names,
            "Invalid executable list")
    require(all(re.fullmatch(r"[a-z0-9][a-z0-9-]*", name) for name in names),
            "Invalid executable name")
    return sorted(names)


def mode_allowed(mode):
    return type(mode) is int and 0 <= mode <= 0o777 and mode & 0o100 and not mode & 0o022


def transfer(source, destination, size):
    """Write at most the declared size and verify the ELF class, type, and machine."""
    digest = hashlib.sha256()
    header = source.read(64)
    require(len(header) == 64 and header[:7] == b"\x7fELF\x02\x01\x01",
            "Expected little-endian ELF64 executable")
    elf_type, machine, version = struct.unpack_from("<HHI", header, 16)
    require(elf_type in (2, 3) and machine == 62 and version == 1, "Expected Linux x64 ELF")
    digest.update(header)
    destination.write(header)
    remaining = size - len(header)
    while remaining:
        block = source.read(min(remaining, CHUNK))
        require(block, "Truncated executable")
        digest.update(block)
        destination.write(block)
        remaining -= len(block)
    require(not source.read(1), "Unexpected data after executable")
    return digest.hexdigest()


def record(source, destination):
    details = source.lstat()
    mode = stat.S_IMODE(details.st_mode)
    require(stat.S_ISREG(details.st_mode) and mode_allowed(mode)
            and 64 <= details.st_size <= MAX_FILE, "Invalid executable file")
    with source.open("rb") as raw, destination.open("xb") as detached:
        digest = transfer(raw, detached, details.st_size)
    destination.chmod(mode)
    return {"mode": mode, "size": details.st_size, "sha256": digest}


def metadata_bytes(metadata):
    return json.dumps(metadata, indent=2, sort_keys=True).encode() + b"\n"


def unique_object(pairs):
    fields = dict(pairs)
    require(len(fields) == len(pairs), "Duplicate metadata field")
    return fields


def parse_metadata(raw, identity, names, oci_digest=None):
    metadata = json.loads(raw, object_pairs_hook=unique_object)
    require(isinstance(metadata, dict), "Invalid manifest")
    producer = {"producer_run_id", "producer_run_attempt"}
    keys = set(identity) | producer | {"files"}
    if oci_digest is not None:
        keys.add("oci_digest")
    require(set(metadata) == keys and all(type(metadata[key]) is type(value) and metadata[key] == value
                                        for key, value in identity.items()),
            "Bundle identity mismatch")
    for key in producer:
        require(isinstance(metadata[key], str) and re.fullmatch(r"[1-9][0-9]{0,19}", metadata[key]),
                "Invalid producer run identity")
    if oci_digest is not None:
        require(metadata["oci_digest"] == oci_digest, "OCI digest differs")
    files = metadata["files"]
    require(isinstance(files, dict) and set(files) == set(names), "Executable inventory mismatch")
    for entry in files.values():
        require(isinstance(entry, dict) and set(entry) == {"mode", "size", "sha256"}
                and mode_allowed(entry["mode"])
                and type(entry["size"]) is int and 64 <= entry["size"] <= MAX_FILE
                and isinstance(entry["sha256"], str) and re.fullmatch(r"[0-9a-f]{64}", entry["sha256"]),
                "Invalid executable metadata")
    require(sum(entry["size"] for entry in files.values()) <= MAX_BUNDLE_CONTENTS,
            "Bundle contents exceed size limit")
    return metadata


def publish_directory(staging, output):
    require(not output.exists() and not output.is_symlink(), "Destination exists")
    staging.rename(output)


def capture(archive, release, identity, names):
    with tempfile.TemporaryDirectory(dir=archive.parent, prefix="capture.") as scratch:
        temporary = Path(scratch)
        metadata = dict(identity, producer_run_id=os.environ["GITHUB_RUN_ID"],
                        producer_run_attempt=os.environ["GITHUB_RUN_ATTEMPT"],
                        files={name: record(release / name, temporary / name) for name in names})
        content = metadata_bytes(metadata)
        parse_metadata(content, identity, names)
        with tarfile.open(archive, "x:gz", format=tarfile.USTAR_FORMAT, compresslevel=1) as bundle:
            manifest = temporary / "manifest.json"
            manifest.write_bytes(content)
            bundle.add(manifest, arcname="manifest.json")
            for name in names:
                bundle.add(temporary / name, arcname=f"bin/{name}")


def restore(archive, output, identity, names, oci_digest):
    require(re.fullmatch(r"sha256:[0-9a-f]{64}", oci_digest), "Invalid OCI digest")
    with tempfile.TemporaryDirectory(dir=output.parent, prefix="restore.") as scratch:
        staging = Path(scratch) / "verified"
        binaries = staging / "bin"
        binaries.mkdir(parents=True)
        with tarfile.open(archive, "r|gz") as bundle:
            member = bundle.next()
            require(member and member.name == "manifest.json" and member.type == tarfile.REGTYPE
                    and 0 < member.size <= MAX_METADATA, "Expected bundle manifest")
            with bundle.extractfile(member) as stream:
                metadata = parse_metadata(stream.read(), identity, names)
            for name in names:
                member = bundle.next()
                expected = metadata["files"][name]
                require(member and member.name == f"bin/{name}" and member.type == tarfile.REGTYPE
                        and member.size == expected["size"] and member.mode == expected["mode"],
                        "Unexpected bundle member")
                binary = binaries / name
                with bundle.extractfile(member) as source, binary.open("xb") as destination:
                    require(transfer(source, destination, member.size) == expected["sha256"],
                            "Executable checksum mismatch")
                binary.chmod(member.mode)
            require(bundle.next() is None, "Unexpected bundle member")
        (staging / "provenance.json").write_bytes(metadata_bytes(dict(metadata, oci_digest=oci_digest)))
        publish_directory(staging, output)


def install(restored, output, identity, names):
    require(restored.is_dir() and not restored.is_symlink()
            and {path.name for path in restored.iterdir()} == {"bin", "provenance.json"},
            "Unexpected restore directory")
    binaries = restored / "bin"
    provenance = restored / "provenance.json"
    details = provenance.lstat()
    require(binaries.is_dir() and not binaries.is_symlink()
            and {path.name for path in binaries.iterdir()} == set(names)
            and stat.S_ISREG(details.st_mode) and 0 < details.st_size <= MAX_METADATA,
            "Unexpected staged inventory")
    raw = provenance.read_bytes()
    unverified = json.loads(raw)
    digest = unverified.get("oci_digest") if isinstance(unverified, dict) else None
    require(isinstance(digest, str) and re.fullmatch(r"sha256:[0-9a-f]{64}", digest),
            "Invalid OCI digest")
    metadata = parse_metadata(raw, identity, names, digest)
    with tempfile.TemporaryDirectory(dir=output.parent, prefix="install.") as scratch:
        staging = Path(scratch) / "release"
        staging.mkdir()
        for name in names:
            require(record(binaries / name, staging / name) == metadata["files"][name],
                    "Staged executable changed")
        publish_directory(staging, output)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="operation", required=True)
    for operation in ("capture", "restore"):
        command = commands.add_parser(operation)
        command.add_argument("archive", type=Path)
        command.add_argument("directory", type=Path)
        if operation == "restore":
            command.add_argument("digest")
        command.add_argument("binaries", nargs="+")
    command = commands.add_parser("install")
    command.add_argument("--restored-dir", required=True, type=Path)
    command.add_argument("--release-dir", required=True, type=Path)
    command.add_argument("--dependency-tag", required=True)
    command.add_argument("--source-commit", required=True)
    command.add_argument("--binaries", required=True, nargs="+")
    args = parser.parse_args()
    names = executable_names(args.binaries)
    repository = os.environ["GITHUB_REPOSITORY"]
    if args.operation == "install":
        identity = final_identity(args.dependency_tag, args.source_commit, repository)
        install(args.restored_dir, args.release_dir, identity, names)
        return
    require(os.environ["TARGET"] == TARGET, "Unsupported target")
    identity = final_identity(os.environ["CACHE_TAG"], os.environ["PACKAGE_FINAL_SOURCE"], repository)
    if args.operation == "capture":
        capture(args.archive, args.directory, identity, names)
    else:
        restore(args.archive, args.directory, identity, names, args.digest)


if __name__ == "__main__":
    try:
        main()
    except (OSError, ValueError, KeyError, EOFError, tarfile.TarError) as error:
        sys.exit(f"Final output bundle failed: {error}")
