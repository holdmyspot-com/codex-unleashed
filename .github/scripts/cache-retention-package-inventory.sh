#!/usr/bin/env bash
# Print GHCR version records for an owned namespace. An opt-in release-alias
# namespace can be absent until its first publish. Preserve other API failures.
set -euo pipefail

owner="${1:?Expected package owner}"
package="${2:?Expected package name}"
policy="${3-}"
scratch="$(mktemp -d "${RUNNER_TEMP:?}/cache-package-versions.XXXXXX")"
trap 'rm -rf -- "$scratch"' EXIT
if gh api --paginate "orgs/$owner/packages/container/$package/versions?per_page=100"   --jq '.[] | {id, created_at, metadata}' > "$scratch/versions" 2> "$scratch/error"; then
  cat "$scratch/versions"
elif [[ "$policy" == release-alias && ! -s "$scratch/versions" ]] &&
  grep -Fq '(HTTP 404)' "$scratch/error"; then
  printf 'No accessible version inventory for opt-in package %s (HTTP 404).\n' "$package" >&2
else
  cat "$scratch/error" >&2
  exit 1
fi
