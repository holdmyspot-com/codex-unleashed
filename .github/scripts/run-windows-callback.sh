#!/usr/bin/env bash
# Executes the single stack-assembly.exe beneath the supplied artifact directory.
# Returns the callback status, or 1 when the directory does not contain exactly one callback.
set -euo pipefail
callback_files="$(find "${1:?Artifact directory is required}" -type f -name stack-assembly.exe)"
binaries=()
while IFS= read -r binary; do
  binaries+=("$binary")
done <<< "$callback_files"
if [[ -z "$callback_files" || ${#binaries[@]} != 1 ]]; then
  printf 'Expected one Windows callback in %s.\n' "$1" >&2
  exit 1
fi
"${binaries[0]}"
