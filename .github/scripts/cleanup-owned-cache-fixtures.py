#!/usr/bin/env python3
"""Print present fixture IDs only after their digests and complete alias sets match the request."""

import json
from pathlib import Path
import sys


def verify_version(expected, actual):
    """Validate an observed fixture version against its recorded identity.

    Both mappings contain id, name (manifest digest) and tags (complete alias list).
    Ignore tag order. Return None when all three match; raise ValueError on a
    mismatch and KeyError for a missing required field. Change neither mapping."""
    if actual["id"] != expected["id"]:
        raise ValueError(f"Package version ID differs: {actual['id']}")
    if actual["name"] != expected["name"]:
        raise ValueError(f"Package manifest differs: {actual['name']}")
    if sorted(actual["tags"]) != sorted(expected["tags"]):
        raise ValueError(f"Package aliases differ: {actual['tags']}")


def approved_versions(expected, actual):
    """Return the IDs of recorded fixtures present in a GitHub package-version listing.

    expected contains recorded id/name/tags mappings; actual contains GitHub
    version records with metadata.container.tags. Ignore unrelated or absent IDs.
    Preserve expected order and return an empty list when none is present. Validate
    every present fixture's manifest digest and complete aliases before returning;
    raise ValueError on a mismatch, returning no partial approval. Do not delete
    registry versions or modify the inputs."""
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
