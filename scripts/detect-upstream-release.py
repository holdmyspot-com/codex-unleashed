#!/usr/bin/env python3
"""Detect missing stable releases; never treat API errors as absent releases."""

import json
import os
import re
import subprocess
from pathlib import Path


def main():
    upstream = json.loads(subprocess.check_output(
        ["gh", "api", "repos/openai/codex/releases/latest"], text=True
    ))
    tag = upstream["tag_name"]
    if upstream["draft"] or upstream["prerelease"] or not re.fullmatch(
        r"rust-v\d+\.\d+\.\d+", tag
    ):
        raise ValueError(f"Not a stable upstream release: {tag!r}")
    repository = os.environ["GITHUB_REPOSITORY"]
    # Enumerate releases instead of treating a rate-limit or network failure
    # from `release view` as evidence that we should build a new release.
    releases = json.loads(subprocess.check_output([
        "gh", "api", "--paginate", "--slurp",
        f"repos/{repository}/releases?per_page=100",
    ], text=True))
    build_tag = re.compile(rf"{re.escape(tag)}\+\d+")
    needed = not any(
        build_tag.fullmatch(release["tag_name"])
        for page in releases for release in page
    )
    with Path(os.environ["GITHUB_OUTPUT"]).open("a") as output:
        output.write(f"upstream_tag={tag}\nneeded={str(needed).lower()}\n")
    with Path(os.environ["GITHUB_STEP_SUMMARY"]).open("a") as summary:
        summary.write(f"## Upstream release\n\n- Upstream: `{tag}`\n"
                      f"- Public build needed: **{needed}**\n"
                      "- No compilation performed. Use `build-release.yml` to publish; "
                      "automatic dispatch requires `AUTO_RELEASE=true`.\n")


if __name__ == "__main__":
    main()
