#!/usr/bin/env bash
set -euo pipefail

usage() {
  cat <<'EOF'
Usage: run-upstream-repo-checks.sh <upstream-checkout>

Run the upstream Codex repository checks against a patched upstream checkout.
EOF
}

if [[ "${1:-}" == "-h" || "${1:-}" == "--help" ]]; then
  usage
  exit 0
fi

if [[ $# -ne 1 ]]; then
  usage >&2
  exit 1
fi

target_dir="$(cd "${1}" && pwd)"

if [[ ! -d "${target_dir}/.git" ]]; then
  echo "ERROR: target is not a git checkout: ${target_dir}" >&2
  exit 1
fi

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
tooling="${CODEX_UNLEASHED_TOOLING:-${repo_root}/tooling/bin/codex-tooling}"

pushd "${target_dir}" >/dev/null
export CODEX_REPO_ROOT="${target_dir}"
"${tooling}" verify-cargo-workspace-manifests "${target_dir}" --upstream
"${tooling}" verify-tui-core-boundary "${target_dir}"
"${tooling}" verify-bazel-clippy-lints "${target_dir}"
python3 -m unittest discover -s scripts/codex_package -p 'test_*.py'
if [[ -d scripts/install ]]; then
  python3 -m unittest discover -s scripts/install -p 'test_*.py'
fi
just fmt-check
pnpm run format
popd >/dev/null
