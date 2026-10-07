#!/usr/bin/env bash
set -euo pipefail

usage() {
  cat <<'EOF'
Usage: build-release.sh <upstream-checkout> [output-dir]

Applies repository patches, runs upstream checks, and builds release artifacts.
EOF
}

if [[ "${1:-}" == "-h" || "${1:-}" == "--help" ]]; then
  usage
  exit 0
fi

if [[ $# -lt 1 || $# -gt 2 ]]; then
  usage >&2
  exit 1
fi

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
target_dir="$(cd "${1}" && pwd)"
output_dir="${2:-${repo_root}/dist}"
output_dir="$(mkdir -p "${output_dir}" && cd "${output_dir}" && pwd)"
build_target="${UPSTREAM_TARGET:-}"
build_date="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
original_upstream_sha="$(git -C "${target_dir}" rev-parse HEAD)"

infer_host_target() {
  case "$(uname -s):$(uname -m)" in
    Linux:x86_64)
      printf '%s\n' "x86_64-unknown-linux-gnu"
      ;;
    Linux:aarch64|Linux:arm64)
      printf '%s\n' "aarch64-unknown-linux-gnu"
      ;;
    Darwin:x86_64)
      printf '%s\n' "x86_64-apple-darwin"
      ;;
    Darwin:arm64|Darwin:aarch64)
      printf '%s\n' "aarch64-apple-darwin"
      ;;
    MINGW*:x86_64|MSYS*:x86_64|CYGWIN*:x86_64|Windows_NT:x86_64)
      printf '%s\n' "x86_64-pc-windows-msvc"
      ;;
    MINGW*:aarch64|MSYS*:aarch64|CYGWIN*:aarch64|Windows_NT:aarch64)
      printf '%s\n' "aarch64-pc-windows-msvc"
      ;;
    *)
      return 1
      ;;
  esac
}

infer_default_release_target() {
  case "$(uname -s)" in
    Linux)
      printf '%s\n' "x86_64-unknown-linux-musl"
      ;;
    Darwin)
      printf '%s\n' "$(uname -m)-apple-darwin"
      ;;
    MINGW*|MSYS*|CYGWIN*|Windows_NT)
      if [[ "$(uname -m)" == "aarch64" ]]; then
        printf '%s\n' "aarch64-pc-windows-msvc"
      else
        printf '%s\n' "x86_64-pc-windows-msvc"
      fi
      ;;
    *)
      return 1
      ;;
  esac
}

default_release_binaries() {
  local target="${1:-}"

  if [[ -n "${UPSTREAM_BINARIES:-}" ]]; then
    printf '%s\n' "${UPSTREAM_BINARIES}"
    return 0
  fi

  case "${target}" in
    *windows*)
      printf '%s\n' "codex codex-code-mode-host codex-responses-api-proxy"
      ;;
    *linux*)
      printf '%s\n' "codex codex-code-mode-host codex-responses-api-proxy bwrap"
      ;;
    *)
      printf '%s\n' "codex codex-code-mode-host codex-responses-api-proxy"
      ;;
  esac
}

configure_rusty_v8_overrides() {
  local target="${1:-}"
  local version=""
  local release_tag=""
  local base_url=""
  local binding_dir=""
  local archive_name=""
  local archive_path=""
  local binding_path=""
  local checksums_path=""

  if [[ -z "${target}" ]]; then
    echo "ERROR: configure_rusty_v8_overrides requires a target triple" >&2
    exit 1
  fi

  if ! command -v curl >/dev/null 2>&1; then
    echo "ERROR: curl is required to configure rusty_v8 artifact overrides" >&2
    exit 1
  fi

  version="$("${repo_root}/tooling/bin/codex-tooling" resolved-v8-crate-version "${target_dir}")"
  release_tag="rusty-v8-v${version}"
  base_url="https://github.com/openai/codex/releases/download/${release_tag}"
  binding_dir="${RUSTY_V8_CACHE_DIR:-${TMPDIR:-/tmp}/rusty_v8}/${target}"
  profile="ptrcomp_sandbox_release"
  if [[ "${target}" == *windows* ]]; then
    archive_name="rusty_v8_${profile}_${target}.lib.gz"
  else
    archive_name="librusty_v8_${profile}_${target}.a.gz"
  fi
  archive_path="${binding_dir}/${archive_name}"
  binding_name="src_binding_${profile}_${target}.rs"
  binding_path="${binding_dir}/${binding_name}"
  checksums_name="rusty_v8_${profile}_${target}.sha256"
  checksums_path="${binding_dir}/${checksums_name}"

  mkdir -p "${binding_dir}"
  curl -fsSL "${base_url}/${archive_name}" -o "${archive_path}"
  curl -fsSL "${base_url}/${binding_name}" -o "${binding_path}"
  curl -fsSL "${base_url}/${checksums_name}" -o "${checksums_path}"

  if [[ "$(wc -l < "${checksums_path}")" -ne 2 ]]; then
    echo "ERROR: expected exactly two checksums for ${target} in ${checksums_path}" >&2
    exit 1
  fi

  if command -v sha256sum >/dev/null 2>&1; then
    (cd "${binding_dir}" && tr -d '\r' < "${checksums_path}" | sha256sum -c -)
  else
    (cd "${binding_dir}" && tr -d '\r' < "${checksums_path}" | shasum -a 256 -c -)
  fi

  export RUSTY_V8_ARCHIVE="${archive_path}"
  export RUSTY_V8_SRC_BINDING_PATH="${binding_path}"
}

should_configure_rusty_v8_overrides() {
  local target="${1:-}"
  [[ -n "${target}" ]] \
    && [[ -z "${RUSTY_V8_ARCHIVE:-}" || -z "${RUSTY_V8_SRC_BINDING_PATH:-}" ]]
}

run_with_heartbeat() {
  local heartbeat_pid=""
  local command_pid=""
  local process_group_started=false

  (
    while true; do
      sleep "${HEARTBEAT_INTERVAL_SECONDS:-60}"
      printf 'Heartbeat: command still running at %s\n' "$(date -u +%FT%TZ)"
    done
  ) &
  heartbeat_pid=$!

  terminate_children() {
    trap - INT TERM HUP
    if [[ -z "${command_pid}" ]] || ! kill -0 "${command_pid}" 2>/dev/null; then
      return
    fi
    if [[ "${process_group_started}" == true ]]; then
      kill -TERM -- "-${command_pid}" 2>/dev/null || true
    elif command -v taskkill.exe >/dev/null 2>&1; then
      taskkill.exe //PID "${command_pid}" //T //F >/dev/null 2>&1 || true
    else
      kill -TERM "${command_pid}" 2>/dev/null || true
    fi
    for _ in 1 2 3 4 5; do
      kill -0 "${command_pid}" 2>/dev/null || return
      sleep 1
    done
    if [[ "${process_group_started}" == true ]]; then
      kill -KILL -- "-${command_pid}" 2>/dev/null || true
    elif kill -0 "${command_pid}" 2>/dev/null; then
      kill -KILL "${command_pid}" 2>/dev/null || true
    fi
    if [[ -n "${heartbeat_pid}" ]] && kill -0 "${heartbeat_pid}" 2>/dev/null; then
      kill -TERM "${heartbeat_pid}" 2>/dev/null || true
    fi
  }
  trap terminate_children INT TERM HUP

  set +e
  if command -v setsid >/dev/null 2>&1; then
    setsid "$@" &
    process_group_started=true
  else
    "$@" &
  fi
  command_pid=$!
  wait "${command_pid}"
  local command_status=$?
  set -e

  trap - INT TERM HUP
  kill "${heartbeat_pid}" >/dev/null 2>&1 || true
  wait "${heartbeat_pid}" 2>/dev/null || true

  return "${command_status}"
}

prepare_linux_bwrap_digest() {
  local target="${1:-}"
  local release_dir=""
  local bwrap_path=""
  local digest=""

  if [[ -z "${target}" ]]; then
    echo "ERROR: prepare_linux_bwrap_digest requires a target triple" >&2
    exit 1
  fi

  if [[ -n "${CARGO_TARGET_DIR:-}" ]]; then
    release_dir="${CARGO_TARGET_DIR}/${target}/release"
  else
    release_dir="codex-rs/target/${target}/release"
  fi
  pushd codex-rs >/dev/null
  run_with_heartbeat cargo build --target "${target}" --release --timings --bin bwrap
  popd >/dev/null

  bwrap_path="${release_dir}/bwrap"
  if [[ ! -f "${bwrap_path}" ]]; then
    echo "ERROR: expected bwrap artifact not found at ${bwrap_path}" >&2
    exit 1
  fi

  strip --strip-debug --strip-unneeded "${bwrap_path}"
  digest="$(sha256sum "${bwrap_path}" | awk '{print $1}')"
  export CODEX_BWRAP_SHA256="${digest}"
}

if [[ ! -d "${target_dir}/.git" ]]; then
  echo "ERROR: target is not a git checkout: ${target_dir}" >&2
  exit 1
fi

if [[ ! -f "${target_dir}/pnpm-lock.yaml" || ! -f "${target_dir}/codex-rs/Cargo.toml" ]]; then
  echo "ERROR: ${target_dir} does not match the current upstream Codex layout" >&2
  exit 1
fi

if [[ "${PATCHES_ALREADY_APPLIED:-0}" == "1" ]]; then
  echo "Skipping patch application because PATCHES_ALREADY_APPLIED=1"
else
  "${repo_root}/scripts/apply-patches.sh" "${target_dir}"
fi

pushd "${target_dir}" >/dev/null

if [[ "${SKIP_UPSTREAM_TESTS:-0}" == "1" ]]; then
  echo "Skipping upstream tests because SKIP_UPSTREAM_TESTS=1"
elif [[ -n "${UPSTREAM_TEST_COMMAND:-}" ]]; then
  echo "Running UPSTREAM_TEST_COMMAND"
  test_target="${UPSTREAM_TEST_TARGET:-${build_target:-}}"
  if [[ -z "${test_target}" ]]; then
    test_target="$(infer_host_target)"
  fi
  if should_configure_rusty_v8_overrides "${test_target}"; then
    configure_rusty_v8_overrides "${test_target}"
  fi
  bash -lc "${UPSTREAM_TEST_COMMAND}"
else
  echo "Running default upstream Codex checks."
  if ! command -v pnpm >/dev/null 2>&1; then
    echo "ERROR: pnpm is required for the current upstream Codex default test path" >&2
    exit 1
  fi
  if ! command -v cargo >/dev/null 2>&1; then
    echo "ERROR: cargo is required for the current upstream Codex default test path" >&2
    exit 1
  fi
  if ! command -v just >/dev/null 2>&1; then
    echo "ERROR: just is required for the current upstream Codex default test path" >&2
    exit 1
  fi
  test_target="${UPSTREAM_TEST_TARGET:-${build_target:-}}"
  if [[ -z "${test_target}" ]]; then
    test_target="$(infer_host_target)"
  fi
  if should_configure_rusty_v8_overrides "${test_target}"; then
    configure_rusty_v8_overrides "${test_target}"
  fi
  pnpm install --frozen-lockfile
  "${repo_root}/scripts/run-upstream-repo-checks.sh" .
fi

if [[ "${SKIP_UPSTREAM_BUILD:-0}" == "1" ]]; then
  echo "Skipping upstream build because SKIP_UPSTREAM_BUILD=1"
elif [[ -n "${UPSTREAM_BUILD_COMMAND:-}" ]]; then
  echo "Running UPSTREAM_BUILD_COMMAND"
  bash -lc "${UPSTREAM_BUILD_COMMAND}"
else
  echo "Running default upstream Codex release build."
  if [[ -z "${build_target}" ]]; then
    build_target="$(infer_default_release_target)" || {
      echo "ERROR: unable to infer default UPSTREAM_TARGET for $(uname -s)" >&2
      exit 1
    }
  fi

  if ! command -v rustup >/dev/null 2>&1; then
    echo "ERROR: rustup is required for target-aware current upstream Codex builds" >&2
    exit 1
  fi
  rustup target add "${build_target}"
  if should_configure_rusty_v8_overrides "${build_target}"; then
    configure_rusty_v8_overrides "${build_target}"
  fi

  release_binaries="$(default_release_binaries "${build_target}")"
  build_args=(--release --target "${build_target}")
  for binary in ${release_binaries}; do
    if [[ "${binary}" == "bwrap" ]]; then
      continue
    fi
    build_args+=(--bin "${binary}")
  done

  if [[ "${build_target}" == "x86_64-pc-windows-msvc" ]]; then
    export LIBSQLITE3_FLAGS=SQLITE_DISABLE_INTRINSIC
  fi

  if [[ "${build_target}" == *linux* ]]; then
    prepare_linux_bwrap_digest "${build_target}"
  fi

  build_args+=(--timings)
  pushd codex-rs >/dev/null
  run_with_heartbeat cargo build "${build_args[@]}"
  popd >/dev/null
fi

if [[ -n "${UPSTREAM_ARTIFACT_GLOB:-}" ]]; then
  echo "Collecting artifacts from ${UPSTREAM_ARTIFACT_GLOB}"
  shopt -s nullglob
  artifacts=(${UPSTREAM_ARTIFACT_GLOB})
  shopt -u nullglob
  if [[ ${#artifacts[@]} -eq 0 ]]; then
    echo "ERROR: no artifacts matched UPSTREAM_ARTIFACT_GLOB=${UPSTREAM_ARTIFACT_GLOB}" >&2
    exit 1
  fi
  for artifact in "${artifacts[@]}"; do
    cp -f "${artifact}" "${output_dir}/"
  done
elif [[ "${SKIP_ARTIFACT_COLLECTION:-0}" == "1" ]]; then
  echo "Skipping artifact collection because SKIP_ARTIFACT_COLLECTION=1"
  popd >/dev/null
  echo "Artifacts were intentionally skipped."
  exit 0
else
  echo "Collecting default upstream Codex release binaries."
  artifact_target="${build_target}"
  if [[ -z "${artifact_target}" ]]; then
    echo "ERROR: artifact collection requires UPSTREAM_TARGET or an inferred build target" >&2
    exit 1
  fi

  release_binaries="$(default_release_binaries "${artifact_target}")"
  if [[ -n "${CARGO_TARGET_DIR:-}" ]]; then
    release_dir="${CARGO_TARGET_DIR}/${artifact_target}/release"
  else
    release_dir="codex-rs/target/${artifact_target}/release"
  fi
  for binary in ${release_binaries}; do
    if [[ -f "${release_dir}/${binary}" ]]; then
      cp -f "${release_dir}/${binary}" "${output_dir}/"
    elif [[ -f "${release_dir}/${binary}.exe" ]]; then
      cp -f "${release_dir}/${binary}.exe" "${output_dir}/"
    else
      echo "ERROR: expected artifact not found for ${binary} under ${release_dir}" >&2
      exit 1
    fi
  done
fi

popd >/dev/null

if command -v sha256sum >/dev/null 2>&1; then
  (cd "${output_dir}" && sha256sum ./* > SHA256SUMS)
elif command -v shasum >/dev/null 2>&1; then
  (cd "${output_dir}" && shasum -a 256 ./* > SHA256SUMS)
else
  echo "ERROR: need sha256sum or shasum to generate checksums" >&2
  exit 1
fi

"${repo_root}/tooling/bin/codex-tooling" generate-release-manifest \
  --release-dir "${output_dir}" \
  --output "${output_dir}/release-manifest.json" \
  --patch-repo "${repo_root}" \
  --patch-repository "${PATCH_REPOSITORY:-holdmyspot-com/codex-unleashed}" \
  --upstream-repository "${UPSTREAM_REPOSITORY:-openai/codex}" \
  --upstream-tag "${UPSTREAM_TAG:-}" \
  --upstream-commit "${UPSTREAM_SOURCE_SHA:-${original_upstream_sha}}" \
  --patched-tag "${PATCHED_TAG:-}" \
  --build-number "${CODEX_UNLEASHED_BUILD_NUMBER:-}" \
  --build-date "${build_date}" \
  --builder-type "local-script" \
  --supported-targets "${build_target}" \
  --consolidated-checksums "SHA256SUMS" \
  --attestation-bundle ""

echo "Artifacts written to ${output_dir}"
