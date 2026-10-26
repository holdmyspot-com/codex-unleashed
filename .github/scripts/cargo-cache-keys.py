#!/usr/bin/env python3
"""Derive exact and compatible Cargo cache tags from recorded build inputs."""

import argparse
import hashlib
from pathlib import Path
import re


def derive_tags(target, upstream, wrappers, helper, compiler):
    """Return exact and compatible tags for the supplied recorded input bytes.

    Target is a nonempty target name containing only ASCII letters, digits,
    underscores or hyphens. The remaining values are the recorded input bytes;
    this operation reads no ambient environment and changes no files. Compiler
    inputs must include Rust and Cargo identity, host and build configuration.
    Exact tags preserve the release workflow's input byte order; compatible tags
    omit only wrapper inputs, retaining helper identity and its snapshot semantics.
    Neither tag establishes build acceptance. Invalid target names raise ValueError.
    """
    if re.fullmatch(r"[A-Za-z0-9_-]+", target) is None:
        raise ValueError("Target must contain only ASCII letters, digits, underscores or hyphens.")
    identity = hashlib.sha256(upstream + wrappers + helper + compiler).hexdigest()
    seed_identity = hashlib.sha256(upstream + helper + compiler).hexdigest()
    return {
        "tag": "cargo-v3-" + target + "-" + identity,
        "seed_tag": "cargo-seed-v1-" + target + "-" + seed_identity
    }


def main():
    """Read recorded input files and print workflow outputs; invalid inputs exit 2."""
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("target")
    parser.add_argument("upstream", type=Path)
    parser.add_argument("wrappers", type=Path)
    parser.add_argument("helper", type=Path)
    parser.add_argument("compiler", type=Path)
    arguments = parser.parse_args()
    try:
        outputs = derive_tags(arguments.target, arguments.upstream.read_bytes(),
                         arguments.wrappers.read_bytes(), arguments.helper.read_bytes(),
                         arguments.compiler.read_bytes())
    except (OSError, ValueError) as error:
        parser.error(str(error))
    for name, value in outputs.items():
        print(name + "=" + value)


if __name__ == "__main__":
    main()
