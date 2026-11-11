#!/usr/bin/env bash
set -euo pipefail

# Restores and saves compiled Cargo dependencies for the lint workflows through the private GHCR compiled-cache
# transport that the release builds use: ghcr-cargo-target-cache.sh and the bundled codex-tooling helper, which derives
# the tags. This script only sequences those tools for a lint job.
#
#   preserve <restart-directory> <workflow-path> [input-file ...]
#       Run before the upstream checkout cleans the workspace. Copies the transport, the helper sources and the cache
#       package name out of the restart checkout, and records the hashes of the files that determine the cache tags:
#       the workflow, both scripts, the helper and each extra input file (relative to the restart directory).
#   restore <evidence-directory> <cache-name> <target-directory> <command>
#       Run after the toolchain and build environment are final and before Cargo. Builds the helper once, derives the
#       exact and compatible tags from the upstream commit, the applied patches, the lockfile and toolchain files named
#       by LINT_CACHE_INPUT_FILES (default: codex-rs/Cargo.lock and codex-rs/rust-toolchain.toml), the compiler and
#       the command text, then restores the first state found, in this order: exact verified, exact partial, compatible
#       verified, compatible partial. The cache name is the Cargo target name that the tags embed, at most 32
#       characters of [A-Za-z0-9_-].
#   save <evidence-directory> <cache-name> <target-directory>
#       Run after Cargo. Publishes the target directory under the exact tag: a verified state when LINT_OUTCOME is
#       success, otherwise a partial one. A verified exact hit that a successful run leaves unchanged is not uploaded.
#
# Cargo runs in full after every restore, so its own fingerprints decide what is rebuilt and the lint output is the same
# with and without the cache. A restore or save failure never fails the lint: restore leaves an empty target directory
# and the job runs cold; either one reports a workflow warning. Evidence goes to <evidence-directory>.
# Requires RUNNER_TEMP and RUNNER_OS; restore and save need GHCR_TOKEN, GITHUB_ACTOR, GITHUB_REPOSITORY_OWNER, oras,
# Maven and a JDK.

digest() {
  if command -v sha256sum > /dev/null 2>&1; then
    sha256sum "$@"
  else
    shasum -a 256 "$@"
  fi
}

run_codex_tooling() {
  # Linux packages a native launcher; other runners package a Bash launcher.
  if [[ "$RUNNER_OS" == Linux ]]; then
    "$CODEX_SOURCE_CACHE_TOOL" "$@"
  else
    bash "$CODEX_SOURCE_CACHE_TOOL" "$@"
  fi
}

usage() {
  printf 'usage: %s preserve <restart-directory> <workflow-path> [input-file ...]\n' "$0" >&2
  printf '       %s restore <evidence-directory> <cache-name> <target-directory> <command>\n' "$0" >&2
  printf '       %s save <evidence-directory> <cache-name> <target-directory>\n' "$0" >&2
  exit 2
}

: "${RUNNER_TEMP:?RUNNER_TEMP must name owned cache-transfer storage}"
cache_root="$RUNNER_TEMP/lint-cache"
helper_tool="$cache_root/build/runtime/bin/codex-tooling"
export TMPDIR="$cache_root/tmp"

preserve() {
  local restart="$1" workflow="$2"
  shift 2
  local java=tooling/cli/src/main/java/com/holdmyspot/codexunleashed/tooling
  local hasher=(sha256sum)
  command -v sha256sum > /dev/null 2>&1 || hasher=(shasum -a 256)
  mkdir -p "$cache_root" "$TMPDIR"
  cp "$restart/.github/scripts/ghcr-cargo-target-cache.sh" "$restart/.github/scripts/lint-compiled-cache.sh" \
    "$restart/.github/cargo-cache-package" "$cache_root/"
  cp -R "$restart/tooling/cli" "$cache_root/source-cache-helper"
  (
    cd "$restart"
    digest "$workflow" .github/scripts/ghcr-cargo-target-cache.sh .github/scripts/lint-compiled-cache.sh \
      "$java"/{Main,CargoCacheKeys,CargoCacheParts,VerifyCargoLock}.java "$@"
  ) > "$cache_root/wrappers.txt"
  (
    cd "$restart"
    git ls-files -z -- tooling/cli/pom.xml tooling/cli/src/main tooling/cli/src/build \
      | sort -z | xargs -0 "${hasher[@]}"
  ) > "$cache_root/helper.txt"
}

build_helper() {
  [[ -e "$helper_tool" ]] && return 0
  mvn -B -ntp -f "$cache_root/source-cache-helper/pom.xml" -Pruntime -DskipTests \
    "-Dtooling.build.directory=$cache_root/build" "-Dmaven.repo.local=$cache_root/maven" \
    "-Djava.io.tmpdir=$TMPDIR" package > "$cache_root/helper-build.log" 2>&1 || {
    tail -n 60 "$cache_root/helper-build.log" >&2
    return 1
  }
}

# Prints the exact and compatible tag, then the cache repository, as tag=, seed_tag= and repository= lines.
derive_tags() {
  local evidence="$1" cache_name="$2" command="$3"
  local compiler="$evidence/lint-cache-compiler-inputs.txt"
  {
    rustc -vV
    cargo --version
    printf 'cache=%s\ncommand=%s\n' "$cache_name" "$command"
    printf '%s\n' "$RUNNER_OS" "${RUNNER_ARCH:-}" "${ImageOS:-}" "${ImageVersion:-}"
    env | LC_ALL=C sort | grep -E '^(RUSTC|RUSTC_WRAPPER|RUSTFLAGS|RUSTDOCFLAGS|RUSTY_V8_[A-Z_]+|CARGO_INCREMENTAL|CARGO_BUILD_TARGET|CARGO_BUILD_RUSTFLAGS|CARGO_ENCODED_RUSTFLAGS|CARGO_PROFILE_[A-Z_]+|CARGO_TARGET_[A-Z0-9_]+_LINKER|CC(_[A-Za-z0-9_]+)?|CXX(_[A-Za-z0-9_]+)?|AR(_[A-Za-z0-9_]+)?|TARGET_CC|TARGET_CXX|CFLAGS(_[A-Za-z0-9_]+)?|CXXFLAGS(_[A-Za-z0-9_]+)?|CMAKE_[A-Z_]+|([A-Z0-9_]+_)?OPENSSL_[A-Z_]+|AWS_LC_SYS_NO_JITTER_ENTROPY[A-Za-z0-9_]*|LIBSQLITE3_FLAGS|MACOSX_DEPLOYMENT_TARGET|PKG_CONFIG_[A-Za-z0-9_]+)=' || true
    local input_files
    read -r -a input_files <<< "${LINT_CACHE_INPUT_FILES:-codex-rs/Cargo.lock codex-rs/rust-toolchain.toml}"
    digest "${input_files[@]}"
  } > "$compiler"
  run_codex_tooling cargo-cache-keys "$cache_name" \
    "$evidence/upstream-commit" "$cache_root/wrappers.txt" "$cache_root/helper.txt" "$compiler" \
    "$evidence/applied-patches.txt"
  local owner
  owner="$(printf '%s' "$GITHUB_REPOSITORY_OWNER" | LC_ALL=C tr '[:upper:]' '[:lower:]')"
  printf 'repository=ghcr.io/%s/%s\n' "$owner" "$(cat "$cache_root/cargo-cache-package")"
}

read_state() {
  local state="$1" key="$2"
  sed -n "s/^$key=//p" "$state"
}

restore_state() {
  local evidence="$1" cache_name="$2" target_dir="$3" command="$4"
  local state="$cache_root/$cache_name.state"
  mkdir -p "$evidence" "$target_dir"
  build_helper
  export CODEX_SOURCE_CACHE_TOOL="$helper_tool" CARGO_CACHE_PARTS=1
  printf '%s' "$GHCR_TOKEN" | oras login ghcr.io --username "$GITHUB_ACTOR" --password-stdin
  derive_tags "$evidence" "$cache_name" "$command" | tee "$evidence/lint-cache-keys-$cache_name.txt" > "$state"
  local tag seed_tag repository upstream_ref
  tag="$(read_state "$state" tag)"
  seed_tag="$(read_state "$state" seed_tag)"
  repository="$(read_state "$state" repository)"
  upstream_ref="$(cat "$evidence/upstream-ref")"
  local operations=(pull pull-partial pull pull-partial)
  local tags=("$tag" "$tag" "$seed_tag" "$seed_tag")
  local index result=3
  for index in 0 1 2 3; do
    set +e
    bash "$cache_root/ghcr-cargo-target-cache.sh" "${operations[$index]}" "$repository" "${tags[$index]}" \
      "$target_dir" "$upstream_ref" 2>&1 | tee -a "$evidence/lint-cache-restore-$cache_name.log"
    result=${PIPESTATUS[0]}
    set -e
    case "$result" in
      0) printf 'restored=%s\nexact_hit=%s\n' "${operations[$index]}-$index" "$([[ $index == 0 ]] && echo true || echo false)" >> "$state"; return 0 ;;
      3) ;;
      *) return "$result" ;;
    esac
  done
  printf 'No compiled state exists for %s; this lint starts cold.\n' "$cache_name"
  printf 'restored=none\nexact_hit=false\n' >> "$state"
}

save_state() {
  local evidence="$1" cache_name="$2" target_dir="$3"
  local state="$cache_root/$cache_name.state"
  if [[ ! -f "$state" ]]; then
    printf 'No restore state for %s; nothing to save.\n' "$cache_name"
    return 0
  fi
  local operation=push
  [[ "${LINT_OUTCOME:-failure}" == success ]] || operation=push-partial
  if [[ "$operation" == push && "$(read_state "$state" exact_hit)" == true ]]; then
    printf 'The verified exact state for %s was restored and the lint passed; nothing to upload.\n' "$cache_name"
    return 0
  fi
  export CODEX_SOURCE_CACHE_TOOL="$helper_tool" CARGO_CACHE_PARTS=1 CARGO_CACHE_STAGE=final
  bash "$cache_root/ghcr-cargo-target-cache.sh" "$operation" "$(read_state "$state" repository)" \
    "$(read_state "$state" tag)" "$target_dir" "$(cat "$evidence/upstream-ref")" "$(read_state "$state" seed_tag)" \
    2>&1 | tee "$evidence/lint-cache-save-$cache_name.log"
  return "${PIPESTATUS[0]}"
}

case "${1:-}" in
  preserve)
    [[ $# -ge 3 ]] || usage
    shift
    preserve "$@"
    ;;
  restore)
    [[ $# == 5 ]] || usage
    # The unreadable-state case is a cache fault, not a lint fault: run cold with an empty target directory.
    if ! bash "$0" _restore "${@:2}"; then
      printf '::warning::The compiled-cache restore for %s failed; this lint runs without it.\n' "$3"
      rm -rf -- "$4"
      mkdir -p "$4"
      rm -f -- "$cache_root/$3.state"
    fi
    ;;
  save)
    [[ $# == 4 ]] || usage
    if ! bash "$0" _save "${@:2}"; then
      printf '::warning::The compiled-cache save for %s failed; the lint result is unaffected.\n' "$3"
    fi
    ;;
  _restore) restore_state "${@:2}" ;;
  _save) save_state "${@:2}" ;;
  *) usage ;;
esac
