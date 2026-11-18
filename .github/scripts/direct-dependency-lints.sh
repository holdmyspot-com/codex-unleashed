#!/usr/bin/env bash
set -euo pipefail

if [[ -z "${CODEX_TOOLING:-}" || -z "${CODEX_TOOLING_BASH:-}" ]]; then
	echo "CODEX_TOOLING and CODEX_TOOLING_BASH are required; run setup-tooling before invoking this wrapper." >&2
	exit 1
fi

exec "$CODEX_TOOLING_BASH" "$CODEX_TOOLING" direct-dependency-lints "$@"
