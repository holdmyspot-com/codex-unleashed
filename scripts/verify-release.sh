#!/usr/bin/env bash
# Verify a downloaded release's checksums and reconstructed source, optionally including GitHub attestations.
# Arguments: Release directory, --patch-repo DIR, --upstream-source DIR, and optional --verify-attestations.
# Output: The helper's verification result or diagnostic. Exit status: 0 on verification, 1 on failure, 2 on usage error.
set -euo pipefail

if [[ $# == 0 ]]; then
  printf '%s\n' 'Usage: verify-release.sh RELEASE_DIR --patch-repo DIR --upstream-source DIR [--verify-attestations]' >&2
  exit 2
fi
release_dir=$1
shift
script_dir=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
exec python3 -I "$script_dir/../.github/scripts/release-manifest.py" verify --release-dir "$release_dir" "$@"
