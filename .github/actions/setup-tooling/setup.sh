#!/usr/bin/env bash
# Verifies tooling on a cache miss and probes its runtime before publishing the launcher path.
set -euo pipefail

: "${GITHUB_ACTION_PATH:?GITHUB_ACTION_PATH is required}"
: "${GITHUB_OUTPUT:?GITHUB_OUTPUT is required}"
: "${GITHUB_ENV:?GITHUB_ENV is required}"
project_root="$(CDPATH= cd -- "$GITHUB_ACTION_PATH/../../.." && pwd)"
if [[ "${RUNNER_OS:-}" == Linux ]]; then
  bash "$project_root/.github/scripts/disable-ci-swap.sh"
fi

if [[ "${TOOLING_RUNTIME_CACHE_HIT:-false}" != true ]]; then
  "$project_root/tooling/mvnw" -q verify </dev/null
fi

launcher="$project_root/tooling/bin/codex-tooling"
probe="$("$launcher" next-release-build-number rust-v0.0.0 </dev/null)"
if [[ "$probe" != 1 ]]; then
  echo "ERROR: tooling probe returned '$probe'; expected build number 1." >&2
  exit 1
fi
printf 'launcher=%s\n' "$launcher" >> "$GITHUB_OUTPUT"
printf 'CODEX_UNLEASHED_TOOLING=%s\n' "$launcher" >> "$GITHUB_ENV"
