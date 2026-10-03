#!/usr/bin/env python3

"""Prepare cached release builds from source contents and record successful inputs.

The default operation marks changed source inputs for Cargo and removes requested
release binaries. --record-source-inputs commits the prepared source snapshot
after compilation succeeds; callers must not invoke it after a failed build.
"""

import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys


LIBRARY_TARGET_KINDS = frozenset({"lib", "rlib", "dylib", "cdylib", "staticlib", "proc-macro"})


def source_inputs(workspace: Path, target_directory: Path, cargo_home: Path | None = None) -> tuple[Path, dict]:
    """Return source digests and timestamps, excluding compiled outputs and Cargo downloads."""
    repository = subprocess.run(["git", "-C", str(workspace), "rev-parse", "--show-toplevel"],
                                capture_output=True, text=True)
    root = Path(repository.stdout.strip()) if repository.returncode == 0 else workspace
    excluded = [target_directory.resolve()]
    if cargo_home is not None:
        excluded.append(cargo_home.resolve())
    if repository.returncode == 0:
        tracked = subprocess.check_output(["git", "-C", str(root), "ls-files", "--cached", "-z"])
        pathspecs = ["."] + [f":(exclude,literal){path.relative_to(root).as_posix()}"
                             for path in excluded if root in path.parents]
        untracked = subprocess.check_output(
            ["git", "-C", str(root), "ls-files", "--others", "--exclude-standard", "-z", "--", *pathspecs])
        names = (tracked + untracked).split(b"\0")
        paths = [root / os.fsdecode(name) for name in names if name]
    else:
        paths = []
        for directory, children, files in os.walk(root):
            children[:] = [name for name in children
                           if name not in {".git", "target", "node_modules"}
                           and (Path(directory) / name).resolve() not in excluded]
            paths.extend(Path(directory) / name for name in files)
    resolved_target = target_directory.resolve()
    sources = [path for path in paths if path.is_file() and resolved_target not in path.resolve().parents]
    files = {path.relative_to(root).as_posix(): hashlib.sha256(path.read_bytes()).hexdigest()
             for path in sources}
    timestamps = set(sources)
    for path in sources:
        timestamps.update(parent for parent in path.parents if parent == root or root in parent.parents)
    mtimes = {path.relative_to(root).as_posix(): path.stat().st_mtime_ns for path in timestamps}
    return root, {"schema_version": 1, "files": files, "mtimes": mtimes}


def write_source_inputs(path: Path, inputs: dict) -> None:
    """Atomically replace a source-input snapshot at the requested cache path."""
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_suffix(".tmp")
    temporary.write_text(json.dumps(inputs, sort_keys=True), encoding="utf-8")
    temporary.replace(path)


def invalidate_cached_binary(release_directory: Path, binary: str) -> None:
    """Invalidate one Cargo binary unit while preserving sibling library units."""
    marker_names = (
        f"bin-{binary}", f"bin-{binary}.json",
        f"dep-bin-{binary}", f"output-bin-{binary}",
    )
    invalidated = 0
    for unit in (release_directory / ".fingerprint").glob("*"):
        if not unit.is_dir():
            continue
        if not any((unit / name).is_file() for name in marker_names[:2]):
            continue
        for name in marker_names:
            (unit / name).unlink(missing_ok=True)
        invalidated += 1

    for artifact in (release_directory / binary, release_directory / f"{binary}.exe"):
        artifact.unlink(missing_ok=True)
    symbols = release_directory / f"{binary}.dSYM"
    if symbols.is_dir() and not symbols.is_symlink():
        shutil.rmtree(symbols)
    else:
        symbols.unlink(missing_ok=True)
    print(f"Invalidated {invalidated} cached binary fingerprints for {binary}; library outputs retained.")


def main() -> int:
    record_sources = sys.argv[1:2] == ["--record-source-inputs"]
    arguments = sys.argv[2:] if record_sources else sys.argv[1:]
    if len(arguments) < (2 if record_sources else 3):
        print("usage: clean-cached-release-binaries.py [--record-source-inputs] <workspace> <target> [<binary>...]", file=sys.stderr)
        return 2

    workspace = Path(arguments[0]).resolve()
    target = arguments[1]
    target_directory_name = Path(target).stem if target.endswith(".json") else target
    if (
        Path(target).name != target
        or target_directory_name in {"", ".", ".."}
        or Path(target_directory_name).name != target_directory_name
    ):
        print(f"Invalid Cargo target directory name: {target}", file=sys.stderr)
        return 2
    binaries = set(arguments[2:])
    manifest = workspace / "Cargo.toml"
    cargo = os.environ.get("CARGO", "cargo")

    metadata = json.loads(
        subprocess.check_output(
            [cargo, "metadata", "--no-deps", "--format-version", "1", "--manifest-path", str(manifest)],
            text=True,
        )
    )
    target_directory = Path(metadata.get("target_directory", os.environ.get("CARGO_TARGET_DIR", workspace / "target")))
    snapshot = target_directory / ".codex-source-inputs" / f"{target_directory_name}.json"
    pending = snapshot.with_suffix(".pending")
    cargo_home = Path(os.environ["CARGO_HOME"]) if os.environ.get("CARGO_HOME") else None
    source_root, current_inputs = source_inputs(workspace, target_directory, cargo_home)
    if record_sources:
        prepared = json.loads(pending.read_text(encoding="utf-8")) if pending.is_file() else None
        # Cargo can create or refresh its lockfile while resolving the build.
        without_lockfiles = lambda inputs: {name: digest for name, digest in inputs["files"].items()
                                           if Path(name).name != "Cargo.lock"}
        if prepared is None or without_lockfiles(prepared) != without_lockfiles(current_inputs):
            print("Source inputs changed during the build or were not prepared; refusing cache snapshot.", file=sys.stderr)
            return 1
        write_source_inputs(snapshot, current_inputs)
        pending.unlink()
        print(f"Recorded {len(current_inputs['files'])} source file digests after successful compilation.")
        return 0
    interrupted_inputs = json.loads(pending.read_text(encoding="utf-8")) if pending.is_file() else None
    write_source_inputs(pending, current_inputs)
    owners = {
        binary: package["name"]
        for package in metadata["packages"]
        for target_info in package["targets"]
        if "bin" in target_info["kind"]
        for binary in [target_info["name"]]
        if binary in binaries
    }
    missing = sorted(binaries - owners.keys())
    if missing:
        print(f"Release binaries absent from Cargo metadata: {', '.join(missing)}", file=sys.stderr)
        return 1

    previous_inputs = json.loads(snapshot.read_text(encoding="utf-8")) if snapshot.is_file() else None
    if previous_inputs is not None:
        if previous_inputs.get("schema_version") != 1:
            print("Unsupported source-input cache snapshot; refusing reuse.", file=sys.stderr)
            return 1
        previous_files = previous_inputs["files"]
        current_files = current_inputs["files"]
        changed = {name for name in previous_files.keys() | current_files.keys()
                   if previous_files.get(name) != current_files.get(name)}
        if interrupted_inputs is not None:
            interrupted_files = interrupted_inputs["files"]
            changed.update(name for name in interrupted_files.keys() | current_files.keys()
                           if interrupted_files.get(name) != current_files.get(name))
        # Identical checkout contents can receive newer normalized timestamps
        # after an upstream release. Preserve the successful build's timestamps
        # before marking actual changes, so Cargo still reuses identical inputs.
        for name, timestamp in previous_inputs.get("mtimes", {}).items():
            path = source_root / name
            if path.resolve() != source_root and source_root not in path.resolve().parents:
                print(f"Source-input cache path escapes checkout: {name}", file=sys.stderr)
                return 1
            if name not in changed and path.exists():
                os.utime(path, ns=(timestamp, timestamp))
        directories = set()
        for name in changed:
            path = source_root / name
            if path.is_absolute() and source_root not in path.resolve().parents:
                print(f"Source-input cache path escapes checkout: {name}", file=sys.stderr)
                return 1
            if path.is_file():
                os.utime(path, None)
            parent = path.parent
            while parent != source_root.parent:
                if parent.is_dir():
                    directories.add(parent)
                parent = parent.parent
        for directory in directories:
            os.utime(directory, None)
        print(f"Source contents changed in {len(changed)} files; Cargo determines affected units; "
              f"{len(current_files) - len(changed & current_files.keys())} unchanged files retained.")
    else:
        command = [cargo, "clean", "--release", "--target", target, "--manifest-path", str(manifest)]
        workspace_members = set(metadata["workspace_members"])
        packages = sorted(package["name"] for package in metadata["packages"]
                          if package["id"] in workspace_members)
        for package in packages:
            command.extend(["-p", package])
        subprocess.run(command, check=True)
        print(f"Source snapshot absent; rebuilding {len(packages)} workspace packages; "
              "external dependencies retained.")
        return 0

    library_owners = {
        package["name"]
        for package in metadata["packages"]
        if any(LIBRARY_TARGET_KINDS.intersection(target_info["kind"])
               for target_info in package["targets"])
    }
    binary_only_owners = set(owners.values()) - library_owners
    if binary_only_owners:
        command = [cargo, "clean", "--release", "--target", target, "--manifest-path", str(manifest)]
        for package in sorted(binary_only_owners):
            command.extend(["-p", package])
        subprocess.run(command, check=True)

    # Package cleanup also deletes libraries consumed by other packages. Cargo's
    # binary fingerprints allow rebuilding these targets without discarding them.
    for binary in sorted(binaries):
        if owners[binary] in library_owners:
            release_directory = Path(metadata["target_directory"]) / target_directory_name / "release"
            invalidate_cached_binary(release_directory, binary)
    return 0


if __name__ == "__main__":
    sys.exit(main())
