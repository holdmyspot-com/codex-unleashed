#!/usr/bin/env bash
set -euo pipefail

if [[ "$#" != 2 ]]; then
  printf 'Usage: %s <trusted-digests> <downloaded-manifest>\n' "$0" >&2
  exit 2
fi
trusted_digests="$1"
manifest_path="$2"
manifest_name="$(basename "$manifest_path")"

trusted_entry="$(awk -v name="$manifest_name" '
  { sub(/\r$/, "") }
  $2 == name || $2 == "*" name { count++; entry=$0 }
  END {
    if (count != 1) {
      printf "Expected exactly one trusted digest for %s; found %d\n", name, count > "/dev/stderr"
      exit 1
    }
    print entry
  }
' "$trusted_digests")"

# Normalize only the trusted record; the checksum tool reads the original manifest bytes.
cd -- "$(dirname "$manifest_path")"
if command -v sha256sum >/dev/null 2>&1; then
  printf '%s\n' "$trusted_entry" | sha256sum --check - > /dev/null
else
  printf '%s\n' "$trusted_entry" | shasum -a 256 --check - > /dev/null
fi
