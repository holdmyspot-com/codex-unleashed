#!/usr/bin/env bash
# Samples CLI help startup twenty times; this does not measure agent or model-request processing performance.
# Requires PROFILE_OUTPUT, PROFILE_BINARY, and PROFILE_PHASE (seed, warm, changed, or worker4).
# Writes wall seconds, user seconds, system seconds, and peak RSS KiB per sample to <phase>-startup.tsv.
# Exits nonzero for an invalid phase, missing executable/output directory, or failed invocation.
set -euo pipefail

: "${PROFILE_OUTPUT:?}"
: "${PROFILE_BINARY:?}"
: "${PROFILE_PHASE:?}"
case "$PROFILE_PHASE" in
  seed|warm|changed|worker4) ;;
  *) echo 'PROFILE_PHASE must be seed, warm, changed, or worker4.' >&2; exit 2 ;;
esac
[[ -d "$PROFILE_OUTPUT" ]] || {
  printf 'PROFILE_OUTPUT must name an existing directory: %s\n' "$PROFILE_OUTPUT" >&2
  exit 2
}
[[ -f "$PROFILE_BINARY" && -x "$PROFILE_BINARY" ]] || {
  printf 'PROFILE_BINARY must name an executable file: %s\n' "$PROFILE_BINARY" >&2
  exit 2
}

rm -f "$PROFILE_OUTPUT/$PROFILE_PHASE-startup.tsv"
for _ in {1..20}; do
  /usr/bin/time -f '%e\t%U\t%S\t%M' -a -o "$PROFILE_OUTPUT/$PROFILE_PHASE-startup.tsv" \
    "$PROFILE_BINARY" --help > /dev/null
done
