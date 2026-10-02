#!/usr/bin/env python3
"""Print the next vendor build number from the repository's tag inventory."""

import argparse
import re
import sys


def next_build_number(upstream_tag, tags):
    if not re.fullmatch(r"rust-v\d+\.\d+\.\d+", upstream_tag):
        raise ValueError("upstream tag must be rust-vX.Y.Z")
    pattern = re.compile(re.escape(upstream_tag) + r"\+([1-9][0-9]*)")
    numbers = [int(match.group(1)) for tag in tags
               if (match := pattern.fullmatch(tag.strip()))]
    return max(numbers, default=0) + 1


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("upstream_tag")
    args = parser.parse_args()
    print(next_build_number(args.upstream_tag, sys.stdin))


if __name__ == "__main__":
    main()
