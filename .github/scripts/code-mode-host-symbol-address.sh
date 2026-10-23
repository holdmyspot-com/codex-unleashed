#!/usr/bin/env bash
# Prints the exact demangled code-mode host entrypoint address from debug symbols.
# Arguments: target triple and executable or extracted DWARF/debug file.
# Fails when nm fails or the entrypoint is absent or ambiguous.
set -euo pipefail
if [[ $# != 2 ]]; then
  echo 'Usage: code-mode-host-symbol-address.sh TARGET SYMBOLS' >&2
  exit 2
fi
nm_args=(-C)
if [[ "$1" == *linux* ]]; then
  nm_args+=(--defined-only)
fi
nm "${nm_args[@]}" "$2" | awk '
  $2 ~ /^[tT]$/ {
    address = $1
    sub(/^[[:space:]]*[^[:space:]]+[[:space:]]+[^[:space:]]+[[:space:]]+/, "")
    if ($0 == "codex_code_mode_host::main") {
      entrypoint = address
      count++
    }
  }
  END {
    if (count != 1) {
      print "Expected exactly one codex_code_mode_host::main symbol, found " (count + 0) > "/dev/stderr"
      exit 1
    }
    print entrypoint
  }
'
