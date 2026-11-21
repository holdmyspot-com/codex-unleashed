#!/usr/bin/env bash
# Serialize the Java cache tree helper's inventory as the existing JSON Lines receipt.
set -euo pipefail

script_dir="$(cd -- "$(dirname -- "$0")" && pwd -P)"
tree="$script_dir/MacosPackageCacheTree.java"
if [[ "${1:?Expected a cache tree operation}" == voice-inventory ]]; then
  java "$tree" "$@" |
    jq -aRsc 'split("\u0000") as $values |
      range(0; ($values | length) - 1; 3) as $i |
      [$values[$i], ($values[$i + 1] | tonumber), $values[$i + 2]]' |
    sed -E 's/,([0-9]+),"([0-9a-f]{64})"\]$/, \1, "\2"]/'
else
  exec java "$tree" "$@"
fi
