#!/usr/bin/env bash
# Fail when a Cargo or nextest log contains a warning.
#
# Rustc lint warnings and linker messages already fail compilation (patch 25). Any warning line left in Cargo's output
# comes from a build script's cargo:warning or from Cargo itself, and nextest reports configuration problems the same
# way, so this treats each as an error. Callers run it once every build has finished, so the logs are complete. It is a
# separate script because the release build script's hash is a compiler cache input.
# Arguments:
#   One or more log files.
# Output:
#   Each warning line, prefixed with its log name and line number, on standard error.
# Exit status:
#   0 when no log has a warning line, 1 when one does, 2 when no log is given or one is missing.
set -euo pipefail
if (( $# == 0 )); then
  printf 'Usage: %s LOG...\n' "$0" >&2
  exit 2
fi
for log in "$@"; do
  if [[ ! -f "$log" ]]; then
    printf 'Missing log: %s\n' "$log" >&2
    exit 2
  fi
done
if warnings="$(grep -HnE '^warning(:|\[)' -- "$@")"; then
  printf 'The build reported warnings, which are treated as errors:\n%s\n' "$warnings" >&2
  exit 1
fi
