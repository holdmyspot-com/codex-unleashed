#!/usr/bin/env bash
# Verify a release through the prebuilt Codex tooling launcher, optionally including GitHub attestations.
# Arguments: Release directory, --patch-repo DIR, --upstream-source DIR, and optional --verify-attestations.
# Environment: CODEX_TOOLING names the prebuilt Codex tooling launcher.
# Output: The verifier's result or diagnostic. Exit status: The verifier's status, or 2 for usage or launcher configuration errors.
set -euo pipefail

if [[ $# == 0 ]]; then
  printf '%s\n' 'Usage: verify-release.sh RELEASE_DIR --patch-repo DIR --upstream-source DIR [--verify-attestations]' >&2
  exit 2
fi
if [[ -z ${CODEX_TOOLING:-} ]]; then
  printf '%s\n' 'verify-release.sh: CODEX_TOOLING is unset or empty; set it to the prebuilt Codex tooling launcher' >&2
  exit 2
fi
release_dir=$1
shift
exec bash "$CODEX_TOOLING" release-manifest verify --release-dir "$release_dir" "$@"
