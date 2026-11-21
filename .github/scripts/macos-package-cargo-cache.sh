#!/usr/bin/env bash
# Identify and transfer the exact native macOS package dependency cache.
set -euo pipefail

operation="${1:?Expected inputs, restore or save}"
case "$RUNNER_OS:$RUNNER_ARCH:$TARGET" in
  macOS:ARM64:aarch64-apple-darwin | macOS:X64:x86_64-apple-darwin) ;;
  *) printf 'Native macOS package runner required\n' >&2; exit 2 ;;
esac
metadata="$RUNNER_TEMP/manifest-patch-repo"
scripts="$metadata/.github/scripts"
transport="$scripts/ghcr-cargo-target-cache.sh"
tree="$scripts/macos-package-cache-tree.sh"
tree_source="$scripts/MacosPackageCacheTree.java"
test "$CARGO_TARGET_DIR" = "${CI_BUILD_ROOT:?}/cargo-target"

if [[ "$operation" == inputs ]]; then
  inputs="$(mktemp -d "$RUNNER_TEMP/macos-package-cache-inputs.XXXXXX")"
  trap 'rm -rf -- "$inputs"' EXIT
  git rev-parse HEAD 'HEAD^{tree}' > "$inputs/source"
  printf '%s\n' "$UPSTREAM_COMMIT" "$UPSTREAM_TAG" "$VERSION" "$CODEX_UNLEASHED_BUILD_NUMBER" >> "$inputs/source"
  test "$(git rev-parse HEAD)" = "$VOICE_BUILD_COMMIT"
  test -z "$(git status --porcelain --untracked-files=no)"
  read -r -a patch_paths <<< "$PATCHES"
  for patch in "${patch_paths[@]}"; do
    shasum -a 256 "$RUNNER_TEMP/downstream-$patch"
  done > "$inputs/patches"
  shasum -a 256 "$metadata/.github/workflows/upstream-auto-release.yml" \
    "$scripts/macos-package-cargo-cache.sh" "$tree" "$tree_source" "$scripts/macos-package-toolchain-inputs.sh" > "$inputs/wrappers"
  shasum -a 256 "$transport" > "$inputs/helper"
  operations=.github/scripts/codex-package-operations.sh
  printf '%s\n' "$TARGET" "$RUNNER_OS" "$RUNNER_ARCH" "${ImageOS:?}" "${ImageVersion:?}" \
    "$GITHUB_WORKSPACE" "$CARGO_TARGET_DIR" "${SOURCE_DATE_EPOCH:?}" > "$inputs/context"
  bash "$scripts/macos-package-toolchain-inputs.sh" > "$inputs/apple"
  {
    rustc -vV
    cargo --version
    for tool in clang clang++ ld ar cmake pkg-config perl make dsymutil strip; do
      if executable="$(command -v "$tool")"; then
        shasum -a 256 "$executable"
      else
        printf 'missing %s\n' "$tool"
      fi
    done
    cargo_home="${CARGO_HOME:-$HOME/.cargo}"
    printf 'cargo_home=%s\n' "$cargo_home"
    for config in "$cargo_home/config" "$cargo_home/config.toml"; do
      if [[ -f "$config" ]]; then shasum -a 256 "$config"; fi
    done
  } > "$inputs/tools"
  {
    bash "$operations" profile-env --target "$TARGET" --build-mode release
    bash "$operations" select-binaries --runner-os "$RUNNER_OS" --build-mode release --build-system cargo
    env | LC_ALL=C sort | grep -E '^(RUSTFLAGS|RUSTDOCFLAGS|RUSTC|RUSTC_WRAPPER|RUSTC_WORKSPACE_WRAPPER|RUSTUP_TOOLCHAIN|CARGO_INCREMENTAL|CARGO_BUILD_[A-Z_]+|CARGO_ENCODED_RUSTFLAGS|CARGO_PROFILE_RELEASE_[A-Z_]+|CARGO_TARGET_[A-Z0-9_]+_LINKER|CC(_[A-Za-z0-9_]+)?|CXX(_[A-Za-z0-9_]+)?|AR(_[A-Za-z0-9_]+)?|TARGET_CC|TARGET_CXX|CFLAGS(_[A-Za-z0-9_]+)?|CXXFLAGS(_[A-Za-z0-9_]+)?|CMAKE_[A-Za-z0-9_]+|BORING_BSSL_SYSROOT[A-Za-z0-9_]*|([A-Z0-9_]+_)?OPENSSL_(DIR|NO_VENDOR|STATIC)|AWS_LC_SYS_NO_JITTER_ENTROPY[A-Za-z0-9_]*|PKG_CONFIG_[A-Za-z0-9_]+)='
  } > "$inputs/settings"
  shasum -a 256 codex-rs/Cargo.lock codex-rs/rust-toolchain.toml > "$inputs/lockfiles"
  shasum -a 256 "$RUSTY_V8_ARCHIVE" "$RUSTY_V8_SRC_BINDING_PATH" > "$inputs/v8"
  bash "$tree" voice-inventory "$VOICE_RELEASE_DIR" > "$inputs/voice"
  compiler_components=(context apple tools settings lockfiles v8)
  # On Intel, Cargo builds only the application executables. The separately
  # built voice payload enters the archive step after compilation. Retain its
  # digest as release evidence outside the compiled dependency key.
  if [[ "$TARGET" != x86_64-apple-darwin ]]; then
    compiler_components+=(voice)
  fi
  (cd "$inputs" && cat "${compiler_components[@]}") > "$inputs/compiler"
  tag="$(bash "$CODEX_TOOLING" cargo-cache-keys "$TARGET-pkg" "$inputs/source" "$inputs/wrappers" \
    "$inputs/helper" "$inputs/compiler" "$inputs/patches" | sed -n 's/^tag=//p')"
  [[ "$tag" =~ ^cargo-v3-$TARGET-pkg-[0-9a-f]{64}$ ]]
  {
    printf 'tag=%s\n' "$tag"
    (cd "$inputs" && shasum -a 256 source patches wrappers helper context apple tools settings lockfiles v8 voice)
  } > "$RUNNER_TEMP/package-cargo-cache-key.txt"
  printf 'tag=%s\n' "$tag" >> "$GITHUB_OUTPUT"
  exit
fi

[[ "$operation" == restore || "$operation" == save ]]
[[ "$CACHE_TAG" =~ ^cargo-v3-$TARGET-pkg-[0-9a-f]{64}$ ]]
# The shared transport prefers GNU tar when gtar is available, otherwise it uses
# the system tar. Check that the selected implementation can read and write zstd.
command -v sha256sum >/dev/null
archive_tar=tar
if command -v gtar >/dev/null; then archive_tar=gtar; fi
owner="$(printf '%s' "$GITHUB_REPOSITORY_OWNER" | tr '[:upper:]' '[:lower:]')"
repository="ghcr.io/$owner/$(cat "$metadata/.github/cargo-cache-package")"
started="$SECONDS"
status=unavailable
export CARGO_CACHE_PARTS=0
stage=''
registry_config="$(mktemp -d "$RUNNER_TEMP/macos-package-cache-auth.XXXXXX")"
finish() {
  rm -rf -- "$registry_config"
  if [[ -n "$stage" ]]; then rm -rf -- "$stage"; fi
  printf "Package Cargo cache %s: %s (%ss, \`%s\`)\n" "$operation" "$status" "$((SECONDS-started))" "$CACHE_TAG" \
    >> "$GITHUB_STEP_SUMMARY"
  printf 'result=%s\n' "$status" >> "$GITHUB_OUTPUT"
}
trap finish EXIT
mkdir "$registry_config/empty"
"$archive_tar" --format=pax --zstd -cf - -C "$registry_config/empty" . |
  "$archive_tar" --zstd -tf - >/dev/null
export CARGO_CACHE_REGISTRY_CONFIG="$registry_config/auth.json"
printf '%s' "$GHCR_TOKEN" | oras login ghcr.io --username "$GITHUB_ACTOR" --password-stdin \
  --registry-config "$CARGO_CACHE_REGISTRY_CONFIG"
unset GHCR_TOKEN

if [[ "$operation" == restore ]]; then
  stage="$(mktemp -d "${CARGO_TARGET_DIR}.restore.XXXXXX")"
  if bash "$transport" pull "$repository" "$CACHE_TAG" "$stage" "$UPSTREAM_TAG"; then
    bash "$tree" install "$stage" "$CARGO_TARGET_DIR" "$TARGET"
    status=hit
  else
    result="$?"
    if [[ "$result" == 3 ]]; then status=miss; fi
  fi
else
  bash "$tree" prune "$CARGO_TARGET_DIR" "$TARGET"
  if bash "$transport" push "$repository" "$CACHE_TAG" "$CARGO_TARGET_DIR" "$UPSTREAM_TAG"; then
    status=saved
  fi
fi
if [[ "$status" == unavailable ]]; then
  printf '::warning::Package Cargo cache %s failed\n' "$operation"
fi
