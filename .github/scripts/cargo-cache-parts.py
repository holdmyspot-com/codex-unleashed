#!/usr/bin/env python3
"""Transfer compressed build-step output groups through ORAS 1.3.1.

Invoke push/pull with a repository, publication reference, previous reference,
target directory and owned temporary directory. Push requires an idle existing
Cargo output directory; it replaces the publication manifest only after every
part is uploaded, reusing matching parts from verified and partial manifests. Pull
overlays the complete referenced snapshot. All subprocess failures propagate;
pull returns 3 for a missing reference and 4 for another artifact format.
Only temporary children created here are removed. Publication/retention aliases
and source/dependency acceptance belong to the calling shell workflow.
CARGO_CACHE_ORAS_COMMAND can supply an explicit ORAS launcher as a nonempty JSON
argument array, such as a Bash executable and a local-layout fixture wrapper.
The default is ["oras"]. Arguments are passed directly without shell evaluation.
CARGO_CACHE_STAGE (or --step) identifies the step owning new outputs; existing
ownership travels in target-relative .codex-cache-groups.json. Imported caches
without that record start in bootstrap. Directories, dependency reports and cache
bookkeeping share a metadata group. Push requires a listed build step; invalid
CLI inputs exit 2.
"""

import argparse
from concurrent.futures import ThreadPoolExecutor
from enum import StrEnum
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import stat
import subprocess
import sys
import tempfile


ARTIFACT_TYPE = "application/vnd.codex-unleashed.cargo-target.v2"
LAYER_TYPE = "application/vnd.codex-unleashed.cargo-target.tar+zstd"
GROUP_ANNOTATION = "io.holdmyspot.cargo.group"
INPUT_ANNOTATION = "io.holdmyspot.cargo.inputs"
# Ownership travels with cached outputs, relative to the Cargo target directory.
GROUP_OWNERSHIP_FILE = ".codex-cache-groups.json"
# JSON field mapping slash-separated target-relative paths to their saving steps.
OWNERSHIP_PATHS_FIELD = "target_relative_owners"


class BuildStep(StrEnum):
    """Owners of cached files: release steps, imported state, final state or metadata."""

    BOOTSTRAP = "bootstrap"
    BWRAP = "bwrap"
    PROXY = "codex-responses-api-proxy"
    CODE_MODE = "codex-code-mode-host"
    CLI = "codex"
    APP_SERVER = "codex-app-server"
    WINDOWS_SETUP = "codex-windows-sandbox-setup"
    WINDOWS_SERVICE = "codex-windows-sandbox-service"
    COMMAND_RUNNER = "codex-command-runner"
    FINAL = "final"
    METADATA = "metadata"


def oras(command, *arguments):
    """Run the installed authenticated ORAS CLI and return stdout, or raise on failure."""
    return subprocess.check_output([*command, *arguments], text=True)


def fetch_manifest(command, reference):
    """Return the resolved manifest and digest, or (None, None) for a registry miss."""
    result = subprocess.run([*command, "manifest", "fetch", "--format", "json", reference], capture_output=True, text=True)
    if result.returncode == 0:
        resolved = json.loads(result.stdout)
        return resolved["content"], resolved["digest"]
    missing = re.search(r"not found|MANIFEST_UNKNOWN|NAME_UNKNOWN|404 Not Found", result.stderr)
    if missing:
        return None, None
    raise subprocess.CalledProcessError(result.returncode, result.args, result.stdout, result.stderr)


def describe_output(target, path):
    """Return a target-relative content/metadata entry and lstat; reject special files."""
    relative = path.relative_to(target)
    status = path.lstat()
    if stat.S_ISREG(status.st_mode):
        with path.open("rb") as source:
            content = hashlib.file_digest(source, "sha256").hexdigest()
    elif stat.S_ISLNK(status.st_mode):
        content = os.readlink(path)
    elif stat.S_ISDIR(status.st_mode):
        content = "directory"
    else:
        raise ValueError(f"Unsupported cache output type: {relative}")
    size = status.st_size if stat.S_ISREG(status.st_mode) else 0
    return [relative.as_posix(), status.st_mode, status.st_mtime_ns, size, content], status


def collect_groups(target, step):
    """Group idle outputs by their first saving step, retaining hardlinks and ownership.

    New files belong to step; existing files retain their recorded owner even
    when changed. Directories, .d dependency reports, timings and freshness
    snapshots belong to the metadata group. The ownership record stores
    slash-separated paths relative to target, travels with the cache and is
    replaced atomically only if changed.
    Missing ownership imports existing outputs into step. Invalid stored owners
    raise ValueError rather than silently discarding the record.
    """
    ownership_path = target / GROUP_OWNERSHIP_FILE
    previous = {}
    if ownership_path.exists():
        previous = json.loads(ownership_path.read_text())[OWNERSHIP_PATHS_FIELD]
        previous = {path: BuildStep(owner) for path, owner in previous.items()}

    entries = {}
    owners = {}
    hardlinks = {}
    for directory, directories, files in os.walk(target):
        directories.sort()
        for name in sorted(directories + files):
            path = Path(directory) / name
            if path == ownership_path:
                continue
            entry, status = describe_output(target, path)
            relative = path.relative_to(target)
            is_metadata = stat.S_ISDIR(status.st_mode) or relative.suffix == ".d" or any(
                component in (".codex-source-inputs", "cargo-timings", ".rustc_info.json")
                for component in relative.parts)
            owner = BuildStep.METADATA if is_metadata else previous.get(entry[0], step)
            entries[entry[0]] = entry
            owners[entry[0]] = owner
            if stat.S_ISREG(status.st_mode) and status.st_nlink > 1:
                hardlinks.setdefault((status.st_dev, status.st_ino), []).append(entry[0])

    for paths in hardlinks.values():
        retained_owners = [previous[path] for path in paths if path in previous]
        owner = retained_owners[0] if retained_owners else owners[paths[0]]
        for path in paths:
            owners[path] = owner

    record = json.dumps({OWNERSHIP_PATHS_FIELD: owners}, sort_keys=True).encode()
    ownership_changed = ownership_path.exists() is False or ownership_path.read_bytes() != record
    if ownership_changed:
        with tempfile.TemporaryDirectory(prefix=".cache-owners-", dir=target) as directory:
            pending = Path(directory) / "owners.json"
            pending.write_bytes(record)
            pending.replace(ownership_path)

    entry, _ = describe_output(target, ownership_path)
    entries[entry[0]] = entry
    owners[entry[0]] = BuildStep.METADATA
    groups = {}
    for path, entry in sorted(entries.items()):
        groups.setdefault(owners[path], []).append(entry)
    return groups


def push(command, archive_tar, repository, reference, previous_reference, target, temporary, step):
    """Publish the idle target's step groups, reusing matching verified/partial blobs.

    The step owns new files; retained ownership keeps unchanged earlier output
    archives reusable. Ownership is recorded locally before upload, while the
    publication reference changes only after all required blobs are available.
    """
    if target.is_dir() is False:
        raise ValueError(f"Cargo output directory does not exist: {target}")
    checkpoint_reference = previous_reference
    if previous_reference.endswith("-partial") is False:
        checkpoint_reference = previous_reference + "-partial"
    reusable = {}
    for candidate in dict.fromkeys((checkpoint_reference, previous_reference)):
        previous, _ = fetch_manifest(command, candidate)
        if previous and previous.get("artifactType") == ARTIFACT_TYPE:
            for layer in previous["layers"]:
                annotations = layer["annotations"]
                reusable[(annotations[GROUP_ANNOTATION], annotations[INPUT_ANNOTATION])] = layer
    groups = collect_groups(target, step)

    def upload_group(item):
        """Return the reused or uploaded layer and newly uploaded archive byte count."""
        group, entries = item
        signature = hashlib.sha256(json.dumps(entries, separators=(",", ":")).encode()).hexdigest()
        previous_layer = reusable.get((group, signature))
        if previous_layer:
            return previous_layer, 0
        with tempfile.TemporaryDirectory(prefix="cargo-part-", dir=temporary) as directory:
            scratch = Path(directory)
            paths = scratch / "paths"
            paths.write_bytes(b"\0".join(entry[0].encode() for entry in entries) + b"\0")
            archive = scratch / "part.tar.zst"
            with archive.open("wb") as output:
                subprocess.run([archive_tar, "--format=pax", "--zstd", "--no-recursion", "--null",
                                "-T", str(paths), "-cf", "-"], cwd=target, stdout=output, check=True)
            layer = json.loads(oras(command, "blob", "push", "--descriptor", "--media-type", LAYER_TYPE,
                                    repository, str(archive)))
            layer["annotations"] = {
                GROUP_ANNOTATION: group,
                INPUT_ANNOTATION: signature,
                "org.opencontainers.image.title": signature + ".tar.zst"
            }
            return layer, layer["size"]

    with ThreadPoolExecutor(max_workers=5) as workers:
        uploaded = list(workers.map(upload_group, sorted(groups.items())))
    with tempfile.TemporaryDirectory(prefix="cargo-manifest-", dir=temporary) as directory:
        scratch = Path(directory)
        config = scratch / "config.json"
        config.write_text("{}")
        descriptor = json.loads(oras(command, "blob", "push", "--descriptor", "--media-type",
                                     "application/vnd.oci.empty.v1+json", repository, str(config)))
        manifest = {"schemaVersion": 2, "mediaType": "application/vnd.oci.image.manifest.v1+json",
                    "artifactType": ARTIFACT_TYPE, "config": descriptor,
                    "layers": [layer for layer, size in uploaded]}
        manifest_file = scratch / "manifest.json"
        manifest_file.write_text(json.dumps(manifest))
        print(oras(command, "manifest", "push", reference, str(manifest_file)), end="")
    print(f"cache_parts={len(uploaded)} uploaded_archive_bytes={sum(size for layer, size in uploaded)} "
          f"reused_parts={sum(size == 0 for layer, size in uploaded)}")


def pull(command, archive_tar, repository, reference, target, temporary):
    """Restore all referenced archives and adopt old outputs into bootstrap ownership.

    Accept multipart v2 and legacy single-archive v1 snapshots. Return 3 for a
    missing reference or 4 for another artifact type; subprocess failures raise.
    """
    manifest, digest = fetch_manifest(command, reference)
    if manifest is None:
        return 3
    supported_format = manifest.get("artifactType") in (
        ARTIFACT_TYPE, "application/vnd.codex-unleashed.cargo-target.v1")
    if supported_format is False:
        return 4
    target.mkdir(parents=True, exist_ok=True)
    for layer in manifest["layers"]:
        with tempfile.TemporaryDirectory(prefix="cargo-part-", dir=temporary) as directory:
            archive = Path(directory) / "part.tar.zst"
            oras(command, "blob", "fetch", "--output", str(archive), repository + "@" + layer["digest"])
            with archive.open("rb") as source:
                subprocess.run([archive_tar, "--zstd", "-xf", "-"], cwd=target, stdin=source, check=True)
    if (target / GROUP_OWNERSHIP_FILE).exists() is False:
        collect_groups(target, BuildStep.BOOTSTRAP)
    print(f"cache_result=hit digest={digest} cache_parts={len(manifest['layers'])}")
    return 0


def main():
    """Dispatch the CLI operation; argparse rejects unsupported operations or missing inputs."""
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("operation", choices=["push", "pull"])
    parser.add_argument("repository")
    parser.add_argument("reference")
    parser.add_argument("previous_reference")
    parser.add_argument("target", type=Path)
    parser.add_argument("temporary", type=Path)
    parser.add_argument("--step", type=BuildStep, choices=list(BuildStep),
                        default=os.environ.get("CARGO_CACHE_STAGE", BuildStep.BOOTSTRAP.value),
                        help="Build step owning newly cached files; defaults to bootstrap for imported state")
    arguments = parser.parse_args()
    try:
        command = json.loads(os.environ.get("CARGO_CACHE_ORAS_COMMAND", '["oras"]'))
    except json.JSONDecodeError as error:
        parser.error(f"CARGO_CACHE_ORAS_COMMAND must be a JSON argument array: {error}")
    valid_command = isinstance(command, list) and len(command) > 0 and all(isinstance(part, str) for part in command)
    if valid_command is False:
        parser.error("CARGO_CACHE_ORAS_COMMAND must be a nonempty JSON array of strings.")

    # A bare tar name launches Windows bsdtar before the GNU tar on PATH.
    # Resolve the executable so archive creation and extraction use that GNU tar.
    archive_tar = shutil.which("gtar") or shutil.which("tar") or "tar"
    if arguments.operation == "push":
        push(command, archive_tar, arguments.repository, arguments.reference, arguments.previous_reference,
             arguments.target.resolve(), arguments.temporary.resolve(), arguments.step)
        return 0
    return pull(command, archive_tar, arguments.repository, arguments.reference,
                arguments.target.resolve(), arguments.temporary.resolve())


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except subprocess.CalledProcessError as error:
        if error.stderr:
            print(error.stderr, file=sys.stderr)
        raise SystemExit(error.returncode) from error
