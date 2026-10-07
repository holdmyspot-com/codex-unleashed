#!/usr/bin/env bash

set -euo pipefail

tooling="${CODEX_UNLEASHED_TOOLING:-}"
if [[ -z "$tooling" ]]; then
  project_root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd)"
  tooling="$project_root/tooling/bin/codex-tooling"
fi

exec "$tooling" run-bazel-with-buildbuddy "$@"
