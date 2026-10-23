#!/usr/bin/env bash
# Invokes PROFILE_REAL_RUSTC with unchanged arguments, adding four ThinLTO workers for the codex executable.
# Requires the direct LLD linker configuration and PROFILE_OUTPUT directory from the linker benchmark.
# Records that the executable's compiler invocation received the option and preserves the compiler's exit status.
set -euo pipefail

crate=
previous=
for argument in "$@"; do
  if [[ "$previous" == --crate-name ]]; then
    crate="$argument"
  fi
  previous="$argument"
done

if [[ "$crate" == codex ]]; then
  printf '%s\n' '--thinlto-jobs=4' > "${PROFILE_OUTPUT:?}/worker4-linker-option.txt"
  exec "${PROFILE_REAL_RUSTC:?}" "$@" -Clink-arg=--thinlto-jobs=4
fi
exec "${PROFILE_REAL_RUSTC:?}" "$@"
