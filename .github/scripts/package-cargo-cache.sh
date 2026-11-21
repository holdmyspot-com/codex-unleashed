#!/usr/bin/env bash
# Derive the exact Linux package cache key, restore into an empty Cargo target
# directory, or save dependencies after the package receipt and artifact upload.
set -euo pipefail

operation="${1:?Expected inputs, restore or save}"
metadata="$RUNNER_TEMP/manifest-patch-repo"
transport="$metadata/.github/scripts/ghcr-cargo-target-cache.sh"
test "$RUNNER_OS:$TARGET" = Linux:x86_64-unknown-linux-musl

if [[ "$operation" == inputs ]]; then
  inputs="$(mktemp -d "$RUNNER_TEMP/package-cache-inputs.XXXXXX")"
  trap 'rm -rf -- "$inputs"' EXIT
  git rev-parse HEAD 'HEAD^{tree}' > "$inputs/source"
  printf '%s\n' "$UPSTREAM_COMMIT" "$UPSTREAM_TAG" "$VERSION" "$CODEX_UNLEASHED_BUILD_NUMBER" >> "$inputs/source"
  test "$(git rev-parse HEAD)" = "$VOICE_BUILD_COMMIT"
  test -z "$(git status --porcelain --untracked-files=no)"
  read -r -a patch_paths <<< "$PATCHES"
  for patch in "${patch_paths[@]}"; do
    sha256sum "$RUNNER_TEMP/downstream-$patch"
  done > "$inputs/patches"
  sha256sum "$metadata/.github/workflows/upstream-auto-release.yml" \
    "$metadata/.github/scripts/package-cargo-cache.sh" > "$inputs/wrappers"
  sha256sum "$transport" > "$inputs/helper"
  operations=.github/scripts/codex-package-operations.sh
  openssl_dir="$X86_64_UNKNOWN_LINUX_MUSL_OPENSSL_DIR"
  native_root="$RUNNER_TEMP/codex-musl-tools-$TARGET"
  printf '%s\n' "$TARGET" "$RUNNER_OS" "$RUNNER_ARCH" "${ImageOS:?}" "${ImageVersion:?}" \
    "$GITHUB_WORKSPACE" "$CARGO_TARGET_DIR" "${SOURCE_DATE_EPOCH:?}" > "$inputs/compiler-context"
  {
    rustc -vV
    cargo --version
    zig version
    sha256sum "$(command -v zig)" "$(command -v gcc)" "$(command -v clang)" "$(command -v strip)" \
      "$CARGO_TARGET_X86_64_UNKNOWN_LINUX_MUSL_LINKER" "$CC" "$CXX"
  } > "$inputs/compiler-toolchain"
  dpkg-query --showformat='${binary:Package}=${Version}\n' --show | LC_ALL=C sort > "$inputs/compiler-packages"
  {
    bash "$operations" profile-env --target "$TARGET" --build-mode release
    bash "$operations" select-binaries --runner-os "$RUNNER_OS" --build-mode release --build-system cargo
    env | LC_ALL=C sort | grep -E '^(RUSTFLAGS|RUSTDOCFLAGS|RUSTC|RUSTC_WRAPPER|RUSTC_WORKSPACE_WRAPPER|RUSTUP_TOOLCHAIN|CARGO_INCREMENTAL|CARGO_BUILD_[A-Z_]+|CARGO_ENCODED_RUSTFLAGS|CARGO_PROFILE_RELEASE_[A-Z_]+|CARGO_TARGET_[A-Z0-9_]+_LINKER|CC(_[A-Za-z0-9_]+)?|CXX(_[A-Za-z0-9_]+)?|AR(_[A-Za-z0-9_]+)?|TARGET_CC|TARGET_CXX|CFLAGS(_[A-Za-z0-9_]+)?|CXXFLAGS(_[A-Za-z0-9_]+)?|CMAKE_(C_COMPILER|CXX_COMPILER|ARGS)|BORING_BSSL_SYSROOT[A-Za-z0-9_]*|([A-Z0-9_]+_)?OPENSSL_(DIR|NO_VENDOR|STATIC)|AWS_LC_SYS_NO_JITTER_ENTROPY[A-Za-z0-9_]*|PKG_CONFIG_[A-Za-z0-9_]+)='
  } > "$inputs/compiler-settings"
  sha256sum codex-rs/Cargo.lock codex-rs/rust-toolchain.toml > "$inputs/compiler-lockfiles"
  compiler_components=(compiler-context compiler-toolchain compiler-packages compiler-settings compiler-lockfiles)
  (cd "$inputs" && cat "${compiler_components[@]}") > "$inputs/compiler"
  sha256sum "$RUSTY_V8_ARCHIVE" "$RUSTY_V8_SRC_BINDING_PATH" > "$inputs/v8"
  find "$openssl_dir/include" "$openssl_dir/lib" "$native_root"/libcap-*/prefix \
    -type f -print0 | LC_ALL=C sort -z | xargs -0 -r sha256sum > "$inputs/musl"
  # The verified runtime lists the shipped libraries and plugins. Only those relative
  # paths, runtime.json, and the helper may appear in the diagnostic receipt.
  {
    printf '%s\n' codex-voice-host runtime/runtime.json
    if ! jq -er '.libraries[].path | "runtime/" + .' "$VOICE_RELEASE_DIR/runtime/runtime.json" 2>/dev/null; then
      printf 'Cannot read sealed voice runtime inventory\n' >&2
      exit 1
    fi
  } | LC_ALL=C sort > "$inputs/voice-expected"
  find "$VOICE_RELEASE_DIR" -type f -printf '%P\0' | LC_ALL=C sort -z > "$inputs/voice-paths"
  while IFS= read -r -d '' relative; do
    if ! [[ "$relative" =~ ^(codex-voice-host|runtime/runtime\.json|runtime/lib/(gstreamer-1\.0/)?lib[A-Za-z0-9_+.-]+\.so(\.[0-9]+)*)$ ]]; then
      printf 'Unexpected file layout in sealed voice release directory\n' >&2
      exit 1
    fi
    printf '%s\n' "$relative"
  done < "$inputs/voice-paths" > "$inputs/voice-observed"
  if ! cmp -s "$inputs/voice-expected" "$inputs/voice-observed"; then
    printf 'Sealed voice files do not match the runtime inventory\n' >&2
    exit 1
  fi
  (
    while IFS= read -r relative; do
      checksum="$(sha256sum -- "$VOICE_RELEASE_DIR/$relative")"
      printf '%s\n' "$checksum"
      printf '%s  voice/%s\n' "${checksum%% *}" "$relative" >> "$inputs/voice-files"
    done < "$inputs/voice-observed"
  ) > "$inputs/voice"
  # Preserve only the key and fixed-name component digests, never the raw inputs.
  (cd "$inputs" && sha256sum source patches wrappers helper compiler v8 musl voice) > "$inputs/components"
  cat "$inputs/v8" "$inputs/musl" "$inputs/voice" >> "$inputs/compiler"
  tag="$(bash "$CODEX_TOOLING" cargo-cache-keys "$TARGET-pkg" "$inputs/source" "$inputs/wrappers" \
    "$inputs/helper" "$inputs/compiler" "$inputs/patches" | sed -n 's/^tag=//p')"
  [[ "$tag" =~ ^cargo-v3-x86_64-unknown-linux-musl-pkg-[0-9a-f]{64}$ ]]
  {
    printf 'tag=%s\n' "$tag"
    cat "$inputs/components"
    (cd "$inputs" && sha256sum "${compiler_components[@]}")
    cat "$inputs/voice-files"
  } > "$inputs/key-receipt"
  mv -- "$inputs/key-receipt" "$RUNNER_TEMP/package-cargo-cache-key.txt"
  cat "$inputs/components"
  printf 'tag=%s\n' "$tag" >> "$GITHUB_OUTPUT"
  exit
fi

owner="${GITHUB_REPOSITORY_OWNER,,}"
package="$(cat "$metadata/.github/cargo-cache-package")"
repository="ghcr.io/$owner/$package"
started="$SECONDS"
status=unavailable
# The pilot uses only a full archive for the exact package tag.
export CARGO_CACHE_PARTS=0
stage=''
registry_config="$(mktemp -d "$RUNNER_TEMP/package-cache-auth.XXXXXX")"
# Remove the scoped registry credentials and any uninstalled restore directory.
# Side effects: Record the cache result and elapsed seconds in the job summary and
# emit the result as a workflow step output.
finish() {
  rm -rf -- "$registry_config"
  if [[ -n "$stage" ]]; then rm -rf -- "$stage"; fi
  printf "Package Cargo cache %s: %s (%ss, \`%s\`)\n" "$operation" "$status" "$((SECONDS-started))" "$CACHE_TAG" \
    >> "$GITHUB_STEP_SUMMARY"
  printf 'result=%s\n' "$status" >> "$GITHUB_OUTPUT"
}
trap finish EXIT
export CARGO_CACHE_REGISTRY_CONFIG="$registry_config/auth.json"
if ! printf '%s' "$GHCR_TOKEN" | oras login ghcr.io --username "$GITHUB_ACTOR" --password-stdin \
  --registry-config "$CARGO_CACHE_REGISTRY_CONFIG"; then
  printf '::warning::Package Cargo cache registry login failed\n'
  exit
fi
unset GHCR_TOKEN

case "$operation" in
  restore)
    stage="$(mktemp -d "${CARGO_TARGET_DIR}.restore.XXXXXX")"
    if bash "$transport" pull "$repository" "$CACHE_TAG" "$stage" "$UPSTREAM_TAG"; then
      # setup-ci created the target directory. Preserve it if another step has written into it.
      if [[ -d "$stage/$TARGET/release/build" || -d "$stage/$TARGET/release/deps" ]] &&
        rmdir -- "$CARGO_TARGET_DIR" && mv -T -- "$stage" "$CARGO_TARGET_DIR"; then
        status=hit
      fi
    else
      result="$?"
      if [[ "$result" == 3 ]]; then status=miss; fi
    fi
    ;;
  save)
    # Package assembly strips these binaries in place. Remove their Cargo hardlinks
    # as well so the next build relinks from restored dependencies and recreates symbols.
    read -r -a binaries < "$CODEX_PACKAGE_BUILD_RESULT/binaries"
    release_dir="$CARGO_TARGET_DIR/$TARGET/release"
    for binary in bwrap "${binaries[@]}"; do
      [[ "$binary" =~ ^[a-z0-9-]+$ ]]
      binary_path="$release_dir/$binary"
      test -f "$binary_path"
      find "$release_dir" -type f -samefile "$binary_path" ! -path "$binary_path" -delete
      rm -- "$binary_path"
    done
    if bash "$transport" push "$repository" "$CACHE_TAG" "$CARGO_TARGET_DIR" "$UPSTREAM_TAG"; then
      status=saved
    fi
    ;;
  *) printf 'Unknown package Cargo cache operation: %s\n' "$operation" >&2; exit 2 ;;
esac

if [[ "$status" == unavailable ]]; then
  printf '::warning::Package Cargo cache %s failed\n' "$operation"
fi
