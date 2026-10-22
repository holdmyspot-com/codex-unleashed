#!/usr/bin/env bash
set -euo pipefail

crate=
previous=
for argument in "$@"; do
  if [[ "$previous" == --crate-name ]]; then
    crate="$argument"
  fi
  previous="$argument"
done
if [[ "$crate" != codex ]]; then
  exec "${PROFILE_REAL_RUSTC:?}" "$@"
fi

attempt="$(mktemp -d "${PROFILE_OUTPUT:?}/compiler.XXXXXX")"
export PROFILE_ATTEMPT="$attempt"
printf '%s\n' "$(date +%s.%N)" > "$attempt/start.txt"
status=0
RUSTC_BOOTSTRAP=codex /usr/bin/time -v -o "$attempt/resources.txt" "${PROFILE_REAL_RUSTC:?}" "$@" \
  -Ztime-passes -Ztime-llvm-passes -Clinker="${PROFILE_LINKER_WRAPPER:?}" \
  2> "$attempt/stderr.log" || status=$?
printf '%s\n' "$(date +%s.%N)" > "$attempt/end.txt"
printf '%s\n' "$status" > "$attempt/status.txt"
if [[ "$status" != 0 ]]; then
  cat "$attempt/stderr.log" >&2
fi
exit "$status"
