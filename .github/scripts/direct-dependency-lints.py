#!/usr/bin/env python3
"""
Check Codex's direct dependencies with their own lint levels instead of Cargo's cap.

Cargo passes --cap-lints to every non-workspace package, which hides their lint warnings, and it leaves out the flags
that the package's own [lints] table declares. Used as RUSTC_WRAPPER, this script replaces the cap with those flags for
the packages a workspace member depends on directly, so they compile as Cargo compiles a workspace package and their
warnings print as they would in their own repositories. Every other package, including transitive dependencies, keeps
the cap.

Usage:
  direct-dependency-lints.py list < cargo-metadata.json > direct.txt
      Print "name version" for each non-member package that a workspace member depends on directly, sorted.
  direct-dependency-lints.py build-warnings direct.txt TARGET_DIR
      Print "package: message" for each cargo:warning a listed package's build script recorded under TARGET_DIR, and
      exit 1 if there is one. Cargo shows these only for workspace packages. Build directories carry the package name
      but not its version, so every version of a listed name is included.
  CODEX_DIRECT_DEPENDENCIES=direct.txt direct-dependency-lints.py RUSTC ARGUMENT...
      Run RUSTC with the arguments. When CARGO_PKG_NAME and CARGO_PKG_VERSION name a listed package, --cap-lints is
      replaced with the lint flags of the [lints] table in the Cargo.toml that CARGO_MANIFEST_PATH names.
"""

import json
import os
from pathlib import Path
import sys
import tomllib


def direct_dependencies(metadata):
    """Return the sorted "name version" lines of non-member packages that a workspace member depends on directly."""
    members = set(metadata["workspace_members"])
    packages = {record["id"]: record for record in metadata["packages"]}
    found = set()
    for node in metadata["resolve"]["nodes"]:
        if node["id"] not in members:
            continue
        for dependency in node["deps"]:
            if dependency["pkg"] not in members:
                record = packages[dependency["pkg"]]
                found.add(f"{record['name']} {record['version']}")
    return sorted(found)


def build_script_warnings(names, target_dir):
    """Return "package: message" for each cargo:warning recorded by the build scripts of the named packages."""
    found = []
    for output in sorted(Path(target_dir).glob("**/build/*/output")):
        name = output.parent.name.rsplit("-", 1)[0]
        if name not in names:
            continue
        for line in output.read_text(encoding="utf-8", errors="replace").splitlines():
            for directive in ("cargo::warning=", "cargo:warning="):
                if line.startswith(directive):
                    found.append(f"{name}: {line[len(directive):]}")
                    break
    return found


def lint_flags(manifest):
    """
    Return the rustc flags that Cargo derives from a parsed Cargo.toml's [lints] table when it builds a workspace
    package, in Cargo's order (lints_to_rustflags in Cargo's manifest parser).

    Each rust and clippy lint becomes --LEVEL=NAME, with clippy names prefixed by "clippy::"; cargo lints are Cargo's
    own and are left out. The flags are sorted by priority, then by name in reverse, then by flag. Each entry of
    lints.rust.unexpected_cfgs.check-cfg then follows as a --check-cfg pair.
    """
    lints = manifest.get("lints", {})
    ordered = []
    for tool, table in lints.items():
        if tool == "cargo":
            continue
        for name, config in table.items():
            level = config if isinstance(config, str) else config["level"]
            priority = 0 if isinstance(config, str) else config.get("priority", 0)
            option = f"--{level}={name}" if tool == "rust" else f"--{level}={tool}::{name}"
            ordered.append((priority, name, option))
    # Python sorts stably, so sorting by each key from the least significant reproduces Cargo's tuple order.
    ordered.sort(key=lambda entry: entry[2])
    ordered.sort(key=lambda entry: entry[1], reverse=True)
    ordered.sort(key=lambda entry: entry[0])
    flags = [option for _, _, option in ordered]
    unexpected_cfgs = lints.get("rust", {}).get("unexpected_cfgs")
    if isinstance(unexpected_cfgs, dict):
        for check_cfg in unexpected_cfgs.get("check-cfg", []):
            flags += ["--check-cfg", check_cfg]
    return flags


def replace_cap(arguments, flags):
    """
    Return the rustc arguments with --cap-lints, in either its separate or its joined spelling, replaced by flags.

    Cargo places a package's lint flags before RUSTFLAGS, so later RUSTFLAGS keep their precedence; the flags go
    where the cap was, or last when there was none.
    """
    result = []
    skip = False
    inserted = False
    for argument in arguments:
        if skip:
            skip = False
        elif argument == "--cap-lints" or argument.startswith("--cap-lints="):
            skip = argument == "--cap-lints"
            if not inserted:
                result += flags
                inserted = True
        else:
            result.append(argument)
    if not inserted:
        result += flags
    return result


def main(arguments):
    """Run the selected mode and return its exit status."""
    if arguments == ["list"]:
        for line in direct_dependencies(json.load(sys.stdin)):
            print(line)
        return 0
    if len(arguments) == 3 and arguments[0] == "build-warnings":
        with open(arguments[1], encoding="utf-8") as listing:
            names = {line.split()[0] for line in listing if line.strip()}
        warnings = build_script_warnings(names, arguments[2])
        for line in warnings:
            print(line)
        return 1 if warnings else 0
    if not arguments:
        print(__doc__, file=sys.stderr)
        return 2
    rustc, *rest = arguments
    with open(os.environ["CODEX_DIRECT_DEPENDENCIES"], encoding="utf-8") as listing:
        direct = {line.strip() for line in listing if line.strip()}
    current = f"{os.environ.get('CARGO_PKG_NAME', '')} {os.environ.get('CARGO_PKG_VERSION', '')}"
    if current in direct:
        with open(os.environ["CARGO_MANIFEST_PATH"], "rb") as manifest:
            rest = replace_cap(rest, lint_flags(tomllib.load(manifest)))
    os.execvp(rustc, [rustc, *rest])
    return 1


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
