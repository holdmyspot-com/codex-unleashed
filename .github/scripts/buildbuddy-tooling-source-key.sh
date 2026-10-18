#!/usr/bin/env bash
# Fingerprints verification inputs by default, or the separately reusable VM environment.
set -euo pipefail

case "${1:-verification}" in
  verification) inputs=(tooling .github scripts patches) ;;
  # Product and Java edits are verified inside the restored VM; they do not replace its installed toolchains.
  environment) inputs=(.github/scripts/buildbuddy-bootstrap.sh .github/scripts/buildbuddy-tools.sh) ;;
  *) printf 'Unknown tooling input scope: %s\n' "$1" >&2; exit 1 ;;
esac

git ls-files -z -- "${inputs[@]}" | LC_ALL=C sort -z \
  | xargs -0 sha256sum | sha256sum | cut -d ' ' -f 1
