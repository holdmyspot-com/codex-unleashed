#!/usr/bin/env bash
# Transfer an sccache local-disk cache directory to or from GHCR as one zstd tarball under
# sccache-v1-<target>. sccache keys every entry by its compiler inputs, so a stale or foreign entry is
# never used; the newest push simply replaces the tag. Pull returns 0 on a miss so a cold build proceeds.
# Requires oras to be logged in to the registry. RUNNER_TEMP owns the transient archive.
set -euo pipefail
if (( $# != 4 )) || [[ "$1" != pull && "$1" != push ]]; then
  printf 'usage: %s <pull|push> <repository> <target> <sccache-dir>\n' "$0" >&2
  exit 2
fi
operation=$1 repository=$2 target=$3 dir=$4
reference="$repository:sccache-v1-$target"
work="$RUNNER_TEMP/sccache-transfer"
rm -rf "$work"
mkdir -p "$work" "$dir"
if [[ "$operation" == pull ]]; then
  if ! oras pull --output "$work" "$reference"; then
    printf 'No sccache archive at %s; starting cold.\n' "$reference"
    exit 0
  fi
  if ! tar --zstd -xf "$work/sccache.tar.zst" -C "$dir"; then
    printf 'Could not extract %s; starting cold.\n' "$reference"
    rm -rf "$dir"
    mkdir -p "$dir"
    exit 0
  fi
  printf 'Restored %s from %s.\n' "$(du -sh "$dir" | cut -f1)" "$reference"
else
  tar --zstd -cf "$work/sccache.tar.zst" -C "$dir" .
  (cd "$work" && oras push "$reference" sccache.tar.zst:application/zstd)
  printf 'Saved %s to %s.\n' "$(du -sh "$work/sccache.tar.zst" | cut -f1)" "$reference"
fi
