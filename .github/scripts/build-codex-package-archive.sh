#!/usr/bin/env bash
set -euo pipefail

usage() {
  cat <<'EOF'
Usage: build-codex-package-archive.sh \
  --target <rust-target> \
  --bundle <primary|app-server> \
  --entrypoint-dir <dir> \
  --archive-dir <dir> \
  [--bwrap-bin <path>] \
  [--code-mode-host-bin <path>] \
  [--zsh-manifest <path>] \
  [--rg-manifest <path>] \
  [--codex-command-runner-bin <path>] \
  [--codex-windows-sandbox-setup-bin <path>] \
  [--target-suffixed-entrypoint]
EOF
}

target=""
bundle=""
entrypoint_dir=""
archive_dir=""
target_suffixed_entrypoint="false"
resource_args=()
bwrap_bin_provided="false"
code_mode_host_bin_provided="false"
command_runner_bin_provided="false"
sandbox_setup_bin_provided="false"

while [[ $# -gt 0 ]]; do
  case "$1" in
    --target)
      target="${2:?--target requires a value}"
      shift 2
      ;;
    --bundle)
      bundle="${2:?--bundle requires a value}"
      shift 2
      ;;
    --entrypoint-dir)
      entrypoint_dir="${2:?--entrypoint-dir requires a value}"
      shift 2
      ;;
    --archive-dir)
      archive_dir="${2:?--archive-dir requires a value}"
      shift 2
      ;;
    --bwrap-bin)
      resource_args+=(--bwrap-bin "${2:?--bwrap-bin requires a value}")
      bwrap_bin_provided="true"
      shift 2
      ;;
    --code-mode-host-bin)
      resource_args+=(--code-mode-host-bin "${2:?--code-mode-host-bin requires a value}")
      code_mode_host_bin_provided="true"
      shift 2
      ;;
    --zsh-manifest)
      resource_args+=(--zsh-manifest "${2:?--zsh-manifest requires a value}")
      shift 2
      ;;
    --rg-manifest)
      resource_args+=(--rg-manifest "${2:?--rg-manifest requires a value}")
      shift 2
      ;;
    --codex-command-runner-bin)
      resource_args+=(
        --codex-command-runner-bin
        "${2:?--codex-command-runner-bin requires a value}"
      )
      command_runner_bin_provided="true"
      shift 2
      ;;
    --codex-windows-sandbox-setup-bin)
      resource_args+=(
        --codex-windows-sandbox-setup-bin
        "${2:?--codex-windows-sandbox-setup-bin requires a value}"
      )
      sandbox_setup_bin_provided="true"
      shift 2
      ;;
    --target-suffixed-entrypoint)
      target_suffixed_entrypoint="true"
      shift
      ;;
    -h|--help)
      usage
      exit 0
      ;;
    *)
      echo "Unexpected argument: $1" >&2
      usage >&2
      exit 1
      ;;
  esac
done

if [[ -z "$target" || -z "$bundle" || -z "$entrypoint_dir" || -z "$archive_dir" ]]; then
  usage >&2
  exit 1
fi

case "$bundle" in
  primary)
    variant="codex"
    entrypoint="codex"
    archive_stem="codex-package"
    ;;
  app-server)
    variant="codex-app-server"
    entrypoint="codex-app-server"
    archive_stem="codex-app-server-package"
    ;;
  *)
    echo "No Codex package variant for bundle: $bundle" >&2
    exit 1
    ;;
esac

exe_suffix=""
case "$target" in
  *windows*)
    exe_suffix=".exe"
    ;;
esac

code_mode_host_bin="${entrypoint_dir%/}/codex-code-mode-host${exe_suffix}"
if [[ "$code_mode_host_bin_provided" == "false" && -f "$code_mode_host_bin" ]]; then
  resource_args+=(--code-mode-host-bin "$code_mode_host_bin")
fi

entrypoint_name="$entrypoint"
if [[ "$target_suffixed_entrypoint" == "true" ]]; then
  entrypoint_name="${entrypoint_name}-${target}"
fi

case "$target" in
  *linux*)
    bwrap_bin="${entrypoint_dir%/}/bwrap"
    if [[ "$bwrap_bin_provided" == "false" && -f "$bwrap_bin" ]]; then
      resource_args+=(--bwrap-bin "$bwrap_bin")
    fi
    ;;
  *windows*)
    command_runner_bin="${entrypoint_dir%/}/codex-command-runner.exe"
    sandbox_setup_bin="${entrypoint_dir%/}/codex-windows-sandbox-setup.exe"
    if [[ "$command_runner_bin_provided" == "false" && -f "$command_runner_bin" ]]; then
      resource_args+=(--codex-command-runner-bin "$command_runner_bin")
    fi
    if [[ "$sandbox_setup_bin_provided" == "false" && -f "$sandbox_setup_bin" ]]; then
      resource_args+=(--codex-windows-sandbox-setup-bin "$sandbox_setup_bin")
    fi
    ;;
esac

repo_root="${GITHUB_WORKSPACE:-}"
if [[ -z "$repo_root" ]]; then
  repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
fi
workspace_root="${CODEX_PACKAGE_WORKSPACE_ROOT:-}"
if [[ -z "$workspace_root" && -d "${repo_root}/upstream/codex-rs" ]]; then
  workspace_root="${repo_root}/upstream"
fi
if [[ -n "$workspace_root" ]]; then
  export CODEX_PACKAGE_WORKSPACE_ROOT="$workspace_root"
fi

tooling="${CODEX_UNLEASHED_TOOLING:-${repo_root}/tooling/bin/codex-tooling}"

if ! command -v zstd >/dev/null 2>&1 && [[ -x "${repo_root}/.github/workflows/zstd" ]]; then
  export PATH="${repo_root}/.github/workflows:${PATH}"
fi

mkdir -p "$archive_dir"
package_work="$(mktemp -d "${RUNNER_TEMP:-${TMPDIR:-/tmp}}/${archive_stem}.XXXXXX")"
trap 'rm -rf -- "$package_work" || exit 1' EXIT
package_dir="${package_work}/package"
gzip_archive_path="${archive_dir}/${archive_stem}-${target}.tar.gz"
zstd_archive_path="${archive_dir}/${archive_stem}-${target}.tar.zst"

package_args=(
  build-codex-package
  --repo "$repo_root"
  --cache-root "${package_work}/cache"
  --target "$target"
  --variant "$variant"
  --entrypoint-bin "${entrypoint_dir%/}/${entrypoint_name}${exe_suffix}"
  --cargo-profile release
  --package-dir "$package_dir"
  --archive-output "$gzip_archive_path"
  --archive-output "$zstd_archive_path"
)
if ((${#resource_args[@]} > 0)); then
  package_args+=("${resource_args[@]}")
fi
package_args+=(--force)

"$tooling" "${package_args[@]}"
