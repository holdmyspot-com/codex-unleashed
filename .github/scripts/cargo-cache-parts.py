#!/usr/bin/env python3
"""Transfer independently compressed Cargo output groups through ORAS 1.3.1.

Invoke push/pull with a repository, publication reference, previous reference,
target directory and owned temporary directory. Push requires an idle existing
Cargo output directory; it replaces the publication manifest only after every
part is uploaded, reusing unchanged parts from the previous manifest. Pull
overlays the complete referenced snapshot. All subprocess failures propagate;
pull returns 3 for a missing reference and 4 for another artifact format.
Only temporary children created here are removed. Publication/retention aliases
and source/dependency acceptance belong to the calling shell workflow.
CARGO_CACHE_ORAS_COMMAND can supply an explicit ORAS launcher as a nonempty JSON
argument array, such as a Bash executable and a local-layout fixture wrapper.
The default is ["oras"]. Arguments are passed directly without shell evaluation.
"""

import argparse
from concurrent.futures import ThreadPoolExecutor
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


def output_group(relative):
    """Choose a separate archive owner for a relative Cargo unit or metadata path."""
    parts = relative.parts
    for index, component in enumerate(parts[:-1]):
        if component in ("deps", ".fingerprint", "build"):
            owner = parts[index + 1]
            match = re.fullmatch(r"(?:lib)?(.+)-([0-9a-f]{16})(?:\..*)?", owner)
            if match:
                owner = match[1].replace("-", "_") + "-" + match[2]
            return "/".join((*parts[:index], "unit", owner))
    return str(relative.parent / relative.name.split(".")[0])


def collect_groups(target):
    """Hash idle output files and metadata, keeping hardlinked files in one group."""
    groups = {}
    hardlink_groups = {}
    for directory, directories, files in os.walk(target):
        directories.sort()
        for name in sorted(directories + files):
            path = Path(directory) / name
            relative = path.relative_to(target)
            status = path.lstat()
            group = output_group(relative)
            if stat.S_ISREG(status.st_mode) and status.st_nlink > 1:
                group = hardlink_groups.setdefault((status.st_dev, status.st_ino), group)
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
            groups.setdefault(group, []).append([relative.as_posix(), status.st_mode,
                                                status.st_mtime_ns, size, content])
    return groups


def push(command, repository, reference, previous_reference, target, temporary):
    """Publish all current groups atomically, uploading only groups with changed inputs."""
    if target.is_dir() is False:
        raise ValueError(f"Cargo output directory does not exist: {target}")
    previous, _ = fetch_manifest(command, previous_reference)
    if previous is None:
        previous, _ = fetch_manifest(command, previous_reference + "-partial")
    reusable = {}
    if previous and previous.get("artifactType") == ARTIFACT_TYPE:
        reusable = {layer["annotations"][GROUP_ANNOTATION]: layer for layer in previous["layers"]}
    groups = collect_groups(target)
    archive_tar = shutil.which("gtar") or "tar"

    def upload_group(item):
        """Return the reused or uploaded layer and newly uploaded archive byte count."""
        group, entries = item
        signature = hashlib.sha256(json.dumps(entries, separators=(",", ":")).encode()).hexdigest()
        previous_layer = reusable.get(group)
        if previous_layer and previous_layer["annotations"][INPUT_ANNOTATION] == signature:
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


def pull(command, repository, reference, target, temporary):
    """Restore each independent archive; return 3 for a miss or 4 for another format."""
    manifest, digest = fetch_manifest(command, reference)
    if manifest is None:
        return 3
    if manifest.get("artifactType") != ARTIFACT_TYPE:
        return 4
    archive_tar = shutil.which("gtar") or "tar"
    target.mkdir(parents=True, exist_ok=True)
    for layer in manifest["layers"]:
        with tempfile.TemporaryDirectory(prefix="cargo-part-", dir=temporary) as directory:
            archive = Path(directory) / "part.tar.zst"
            oras(command, "blob", "fetch", "--output", str(archive), repository + "@" + layer["digest"])
            with archive.open("rb") as source:
                subprocess.run([archive_tar, "--zstd", "-xf", "-"], cwd=target, stdin=source, check=True)
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
    arguments = parser.parse_args()
    try:
        command = json.loads(os.environ.get("CARGO_CACHE_ORAS_COMMAND", '["oras"]'))
    except json.JSONDecodeError as error:
        parser.error(f"CARGO_CACHE_ORAS_COMMAND must be a JSON argument array: {error}")
    valid_command = isinstance(command, list) and len(command) > 0 and all(isinstance(part, str) for part in command)
    if valid_command is False:
        parser.error("CARGO_CACHE_ORAS_COMMAND must be a nonempty JSON array of strings.")
    if arguments.operation == "push":
        push(command, arguments.repository, arguments.reference, arguments.previous_reference,
             arguments.target.resolve(), arguments.temporary.resolve())
        return 0
    return pull(command, arguments.repository, arguments.reference, arguments.target.resolve(), arguments.temporary.resolve())


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except subprocess.CalledProcessError as error:
        if error.stderr:
            print(error.stderr, file=sys.stderr)
        raise SystemExit(error.returncode) from error
