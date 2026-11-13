#!/usr/bin/env python3
"""Verify release identity in a source checkout or a canonical compiled CLI package (Python 3.11+)."""

import argparse
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import tomllib

# Upstream scripts/codex_package/layout.py names this metadata file for canonical package layout version 1.
PACKAGE_METADATA = "codex-package.json"
# Layout version 1 has one CLI entrypoint in bin, with the native Windows suffix where applicable.
CLI_ENTRYPOINTS = {"bin/codex", "bin/codex.exe"}


def validate_build(build):
    """Require a nonempty ASCII decimal build string, preserving its exact digits."""
    if not build.isascii() or not build.isdecimal():
        raise ValueError(f"build must contain only ASCII decimal digits; got {build!r}")


def verify_source(source_root, version, build):
    """Require the source workspace version to equal version, without changing its manifest."""
    validate_build(build)
    with (source_root / "codex-rs" / "Cargo.toml").open("rb") as source:
        manifest = tomllib.load(source)
    try:
        actual = manifest["workspace"]["package"]["version"]
    except (KeyError, TypeError) as error:
        raise ValueError("Cargo.toml must define workspace.package.version") from error
    if actual != version:
        raise ValueError(f"Cargo.toml workspace.package.version must equal {version!r}; got {actual!r}")


def package_entrypoint(package_dir):
    """Read layout-version-1 metadata and return its existing canonical CLI executable path."""
    with (package_dir / PACKAGE_METADATA).open(encoding="utf-8") as source:
        metadata = json.load(source)
    if not isinstance(metadata, dict):
        raise ValueError(f"{PACKAGE_METADATA} must be an object")
    layout = metadata.get("layoutVersion")
    if type(layout) is not int or layout != 1:
        raise ValueError(f"{PACKAGE_METADATA} layoutVersion must be integer 1; got {layout!r}")
    entrypoint = metadata.get("entrypoint")
    if not isinstance(entrypoint, str) or entrypoint not in CLI_ENTRYPOINTS:
        raise ValueError(f"{PACKAGE_METADATA} entrypoint must be bin/codex or bin/codex.exe; got {entrypoint!r}")
    executable = package_dir / entrypoint
    if not executable.is_file():
        raise ValueError(f"Package CLI entrypoint does not exist: {executable}")
    return executable


def run_cli(executable, flag):
    """Invoke the actual package entrypoint with one identity flag and capture its result."""
    result = subprocess.run([str(executable), flag], capture_output=True, check=False)
    return subprocess.CompletedProcess(result.args, result.returncode, result.stdout.decode("utf-8"),
                                       result.stderr.decode("utf-8"))


def validate_binary_outputs(version, build, version_result, info_result):
    """Validate captured identity responses, returning the unchanged version stdout on success."""
    for flag, result in (("--version", version_result), ("--build-info", info_result)):
        if result.returncode != 0:
            raise ValueError(f"Package CLI {flag} failed with exit code {result.returncode}")
    expected_banner = f"codex-cli {version}+{build}"
    if version_result.stdout not in (expected_banner, expected_banner + "\n", expected_banner + "\r\n"):
        raise ValueError(f"Package CLI --version must report exactly {expected_banner!r}; got {version_result.stdout!r}")

    expected_fields = {"Codex CLI version": f"{version}+{build}", "Build number": build,
                       "Upstream version": version, "Provided by": "Codex Unleashed"}
    fields = {}
    for line in info_result.stdout.splitlines():
        name, separator, value = line.partition(":")
        name = name.strip()
        if separator and name in expected_fields:
            if name in fields:
                raise ValueError(f"Package CLI --build-info repeats required field {name!r}")
            fields[name] = value.strip()
    for name, expected in expected_fields.items():
        if fields.get(name) != expected:
            raise ValueError(f"Package CLI --build-info field {name!r} must equal {expected!r}; got {fields.get(name)!r}")
    return version_result.stdout


def verify_binary(package_dir, version, build, invoke=run_cli):
    """Run the metadata-selected CLI identity commands and validate both before returning version stdout."""
    validate_build(build)
    executable = package_entrypoint(package_dir.resolve())
    version_result = invoke(executable, "--version")
    info_result = invoke(executable, "--build-info")
    return validate_binary_outputs(version, build, version_result, info_result)


def write_proof(output, banner):
    """Atomically replace output with the validated banner, cleaning temporary data on failure."""
    temporary_path = None
    try:
        with tempfile.NamedTemporaryFile(mode="wb", dir=output.parent, delete=False) as temporary:
            temporary_path = Path(temporary.name)
            temporary.write(banner.encode("utf-8"))
        os.replace(temporary_path, output)
    finally:
        if temporary_path is not None:
            temporary_path.unlink(missing_ok=True)


def main(argv, invoke=run_cli):
    """Run source or binary verification; binary writes --output only after every identity check passes."""
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)
    source = commands.add_parser("source", help="Verify the original Cargo workspace version without rewriting it")
    source.add_argument("--source-root", type=Path, required=True)
    binary = commands.add_parser("binary", help="Verify the actual packaged CLI and atomically write version proof")
    binary.add_argument("--package-dir", type=Path, required=True)
    binary.add_argument("--output", type=Path, required=True)
    for command in (source, binary):
        command.add_argument("--version", required=True)
        command.add_argument("--build", required=True)
    args = parser.parse_args(argv)
    try:
        if args.command == "source":
            verify_source(args.source_root, args.version, args.build)
        else:
            banner = verify_binary(args.package_dir, args.version, args.build, invoke)
            write_proof(args.output, banner)
    except (OSError, ValueError, UnicodeError) as error:
        print(f"package-identity: {error}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
