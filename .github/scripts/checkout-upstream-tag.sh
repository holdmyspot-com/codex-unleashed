#!/usr/bin/env bash
# Check out a release tag's commit in a new, shallow, detached repository.
set -euo pipefail

if (( $# != 3 )); then
  echo 'Usage: checkout-upstream-tag.sh <repository> <tag> <destination>' >&2
  exit 2
fi

repository=$1
tag=$2
destination=$3
if [[ -z "$repository" || -z "$tag" || -z "$destination" ]]; then
  echo 'Repository, tag, and destination must be non-empty.' >&2
  exit 2
fi
if [[ -e "$destination" || -L "$destination" ]]; then
  printf 'error: Checkout destination already exists: %s\n' "$destination" >&2
  exit 128
fi
release_ref="refs/tags/$tag"
git check-ref-format "$release_ref"

mkdir -- "$destination"
trap 'rm -rf -- "$destination"' EXIT
git -C "$destination" init --initial-branch=release
git -C "$destination" remote add -- origin "$repository"
git -C "$destination" fetch --depth 1 origin "$release_ref:$release_ref"
git -C "$destination" checkout --detach "$release_ref^{commit}"
trap - EXIT
