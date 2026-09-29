#!/usr/bin/env python3
"""Derive reusable release cache identities."""

import argparse
import re
from enum import Enum


class Mode(Enum):
    OFF = "off"
    DETERMINISTIC = "deterministic"


SAFE_IDENTIFIER = re.compile(r"[A-Za-z0-9_.-]+\Z")
FINGERPRINT = re.compile(r"[0-9a-f]{64}\Z")


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--target", required=True)
    parser.add_argument("--compiler-fingerprint", required=True)
    parser.add_argument("--v8-version", required=True)
    parser.add_argument("--mode", choices=tuple(mode.value for mode in Mode), default="off")
    args = parser.parse_args()

    if (not SAFE_IDENTIFIER.fullmatch(args.target) or "-" not in args.target
            or not FINGERPRINT.fullmatch(args.compiler_fingerprint)
            or not SAFE_IDENTIFIER.fullmatch(args.v8_version)):
        parser.error("target, compiler fingerprint, or V8 version is invalid")

    mode = Mode(args.mode)
    print(f"cargo_download_key=codex-release-downloads-v5-{args.target}")
    print(f"cargo_target_tag=cargo-v2-{args.target}-{mode.value}-{args.compiler_fingerprint}")
    print(f"rusty_v8_key=rusty-v8-v2-{args.target}-{args.v8_version}")
    print(f"rusty_v8_version={args.v8_version}")


if __name__ == "__main__":
    main()
