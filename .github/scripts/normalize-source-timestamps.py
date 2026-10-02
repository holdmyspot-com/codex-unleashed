#!/usr/bin/env python3

"""Give source files stable timestamps so Cargo can reuse cached dependencies."""

import os
from pathlib import Path
import sys


def main() -> int:
    if len(sys.argv) != 3:
        print("usage: normalize-source-timestamps.py <checkout> <source-date-epoch>", file=sys.stderr)
        return 2
    checkout = Path(sys.argv[1]).resolve(strict=True)
    timestamp = int(sys.argv[2])
    directories = []
    for root, children, files in os.walk(checkout):
        children[:] = [child for child in children if child != ".git"]
        for filename in files:
            if filename == ".git":
                continue
            os.utime(Path(root) / filename, (timestamp, timestamp))
        directories.append(root)
    # Normalize parents after their children to retain build-script fingerprints.
    for directory in reversed(directories):
        os.utime(directory, (timestamp, timestamp))
    return 0


if __name__ == "__main__":
    sys.exit(main())
