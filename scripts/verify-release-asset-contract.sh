#!/usr/bin/env bash
set -euo pipefail

usage() {
  echo "Usage: $0 <upstream-repository> <upstream-tag> <release-directory>" >&2
}

if [[ "$#" -ne 3 ]]; then
  usage
  exit 2
fi

upstream_repository="$1"
upstream_tag="$2"
release_directory="$3"

mapfile -t upstream_assets < <(
  gh api "repos/${upstream_repository}/releases/tags/${upstream_tag}" --jq '.assets[].name'
)

if ((${#upstream_assets[@]} == 0)); then
  echo "Upstream release has no assets: ${upstream_repository}@${upstream_tag}" >&2
  exit 1
fi

is_upstream_asset() {
  local candidate="$1"
  local asset
  for asset in "${upstream_assets[@]}"; do
    [[ "$asset" == "$candidate" ]] && return 0
  done
  return 1
}

checked=0
while IFS= read -r -d '' file; do
  name="$(basename "$file")"
  case "$name" in
    SHA256SUMS|release-manifest.json|reproduce-release.sh)
      # Release metadata is produced by this repository and is intentionally
      # not part of upstream's package-asset subset.
      continue
      ;;
    codex-package-*.tar.gz|install.sh|install.ps1)
      ;;
    *)
      echo "Release contains an unexpected asset: ${name}" >&2
      exit 1
      ;;
  esac
  normalized="$name"

  if ! is_upstream_asset "$normalized"; then
    echo "Release asset is not an upstream asset: ${name}" >&2
    exit 1
  fi
  checked=$((checked + 1))
done < <(find "$release_directory" -maxdepth 1 -type f -print0 | sort -z)

if ((checked == 0)); then
  echo "Release directory contains no assets: ${release_directory}" >&2
  exit 1
fi

echo "Release asset contract passed: ${checked} assets are a subset of ${upstream_repository}@${upstream_tag}."
