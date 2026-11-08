#!/usr/bin/env bash
# Fail when a release build's Cargo logs contain a warning.
#
# Rustc lint warnings and linker messages already fail compilation (patch 25). Any warning line left in Cargo's output
# comes from a build script's cargo:warning or from Cargo itself, so this treats it as an error. The release build
# runs it once every binary has built, so the logs are complete. It is a separate script because the release build
# script's hash is a compiler cache input.
# Arguments:
#   The evidence directory that holds bwrap-build.log and cargo-build-*.log.
# Output:
#   Each warning line, prefixed with its log name and line number, on standard error.
# Exit status:
#   0 when no log has a warning line, 1 when one does, 2 when the directory holds no Cargo build log.
set -euo pipefail
cd "$1"
logs=()
for log in bwrap-build.log cargo-build-*.log; do
  if [[ -f "$log" ]]; then
    logs+=("$log")
  fi
done
if (( ${#logs[@]} == 0 )); then
  printf 'No Cargo build log in %s\n' "$1" >&2
  exit 2
fi
if warnings="$(grep -HnE '^warning(:|\[)' -- "${logs[@]}")"; then
  printf 'Cargo reported warnings, which this release treats as errors:\n%s\n' "$warnings" >&2
  exit 1
fi
