#!/usr/bin/env python3
"""Stage the patched public installer scripts as release assets."""

import argparse
from pathlib import Path
import shutil
import sys


INSTALLERS = ("install.sh", "install.ps1")
RELEASES_URL = b"holdmyspot-com/codex-unleashed"


def stage_installers(upstream_checkout: Path, release_directory: Path) -> None:
    source_directory = upstream_checkout / "scripts" / "install"
    sources = [source_directory / name for name in INSTALLERS]

    missing = [str(path) for path in sources if not path.is_file()]
    if missing:
        raise ValueError("required installer file is missing: " + ", ".join(missing))

    unpatched = [str(path) for path in sources if RELEASES_URL not in path.read_bytes()]
    if unpatched:
        raise ValueError(
            "installer does not point to holdmyspot-com/codex-unleashed releases: "
            + ", ".join(unpatched)
        )

    if not release_directory.is_dir():
        raise ValueError(f"release directory does not exist: {release_directory}")

    for source in sources:
        shutil.copyfile(source, release_directory / source.name)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("upstream_checkout", type=Path)
    parser.add_argument("release_directory", type=Path)
    args = parser.parse_args()

    try:
        stage_installers(args.upstream_checkout, args.release_directory)
    except (OSError, ValueError) as error:
        print(f"ERROR: {error}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
