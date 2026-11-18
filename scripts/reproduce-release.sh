#!/usr/bin/env bash
# Rebuild an authenticated release through the trusted prebuilt Java driver.
# Arguments: MANIFEST --target TARGET --output-dir NEW_EMPTY_DIRECTORY, or
#   --prepared-state STATE --project-root ROOT --release-dir RELEASE --workspace WORKSPACE --output-dir OUTPUT.
# Environment: CODEX_TOOLING names the trusted prebuilt Codex tooling launcher.
# Output: Java command output and diagnostics. Exit status: Java status, or 2 for invalid launcher/arguments.
set -euo pipefail

if [[ $# == 0 ]]; then
  printf '%s\n' 'Usage: reproduce-release.sh MANIFEST --target TARGET --output-dir NEW_EMPTY_DIRECTORY' >&2
  exit 2
fi
if [[ -z ${CODEX_TOOLING:-} || ! -f "$CODEX_TOOLING" ]]; then
  printf '%s\n' 'reproduce-release.sh: set CODEX_TOOLING to the trusted prebuilt Codex tooling launcher' >&2
  exit 2
fi
if [[ $1 == --prepared-state ]]; then
  if [[ $# -lt 2 ]]; then
    printf '%s\n' 'reproduce-release.sh: --prepared-state requires the project-relative state name' >&2
    exit 2
  fi
  state=$2
  shift 2
  exec bash "$CODEX_TOOLING" release-reproduction run-prepared --state "$state" "$@"
fi
project_root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd -P)"
exec bash "$CODEX_TOOLING" release-reproduction "$@" --project-root "$project_root"
