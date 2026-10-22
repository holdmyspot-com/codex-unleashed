#!/usr/bin/env bash
set -euo pipefail

attempt="$(mktemp -d "${PROFILE_ATTEMPT:?}/linker.XXXXXX")"
printf '%s\n' "$(date +%s.%N)" > "$attempt/start.txt"
status=0
/usr/bin/time -v -o "$attempt/resources.txt" "${PROFILE_REAL_LINKER:?}" "$@" || status=$?
printf '%s\n' "$(date +%s.%N)" > "$attempt/end.txt"
printf '%s\n' "$status" > "$attempt/status.txt"
exit "$status"
