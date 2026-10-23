#!/usr/bin/env bash
# Publishes a release only after uploading each staged asset separately.
# Arguments: repository, tag, title, notes file, asset directory, latest (true/false).
# Environment: GH_TOKEN authenticates gh. GitHub CLI diagnostics pass through.
# Errors leave the draft unpublished for inspection; no existing release is replaced.
set -euo pipefail
if [[ $# != 6 || ( "$6" != true && "$6" != false ) ]]; then
  echo 'Usage: publish-github-release.sh REPOSITORY TAG TITLE NOTES ASSETS true|false' >&2
  exit 2
fi
repository="$1"
tag="$2"
title="$3"
notes="$4"
asset_directory="$5"
make_latest="$6"

shopt -s nullglob
assets=("$asset_directory"/*)
if [[ ${#assets[@]} == 0 ]]; then
  echo "No release assets found in $asset_directory" >&2
  exit 1
fi
for asset in "${assets[@]}"; do
  if [[ ! -f "$asset" ]]; then
    echo "Release asset is not a regular file: $asset" >&2
    exit 1
  fi
done

gh release create "$tag" --repo "$repository" --verify-tag \
  --draft --latest=false --title "$title" --notes-file "$notes"
# Serial requests avoid GitHub's secondary rate limits (openai/codex#46303).
for asset in "${assets[@]}"; do
  gh release upload "$tag" "$asset" --repo "$repository"
done
gh release edit "$tag" --repo "$repository" --draft=false --latest="$make_latest"
