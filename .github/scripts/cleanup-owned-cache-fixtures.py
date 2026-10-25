#!/usr/bin/env python3
"""Print present fixture IDs only after their digests and complete alias sets match the request."""

import json
from pathlib import Path
import sys


def verify_version(expected, actual):
    """Reject a version whose ID, manifest digest or complete tag list differs."""
    if actual["id"] != expected["id"]:
        raise ValueError(f"Package version ID differs: {actual['id']}")
    if actual["name"] != expected["name"]:
        raise ValueError(f"Package manifest differs: {actual['name']}")
    if sorted(actual["tags"]) != sorted(expected["tags"]):
        raise ValueError(f"Package aliases differ: {actual['tags']}")


def approved_versions(expected, actual):
    """Return present owned IDs only after every matching GitHub version passes validation."""
    versions = {version["id"]: version for version in actual}
    approved = []
    for fixture in expected:
        version = versions.get(fixture["id"])
        if version is None:
            continue
        verify_version(fixture, {"id": version["id"], "name": version["name"],
                                 "tags": version["metadata"]["container"]["tags"]})
        approved.append(version["id"])
    return approved


if __name__ == "__main__":
    expected = json.loads(Path(sys.argv[1]).read_text())
    pages = json.loads(Path(sys.argv[2]).read_text())
    actual = [version for page in pages for version in page]
    approved = approved_versions(expected, actual)
    for version_id in approved:
        print(version_id)
