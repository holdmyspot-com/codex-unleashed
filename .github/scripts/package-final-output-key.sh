#!/usr/bin/env bash
# Stage Zig at a stable path or derive the exact Linux package executable cache key.
set -euo pipefail

operation="${1:?Expected stage-zig or inputs}"
metadata="$RUNNER_TEMP/manifest-patch-repo"
test "$RUNNER_OS:$TARGET" = Linux:x86_64-unknown-linux-musl

stable_zig="$RUNNER_TEMP/package-cache-zig"
if [[ "$operation" == stage-zig ]]; then
  zig="$(command -v zig)"
  test ! -e "$stable_zig"
  # setup-zig extracts to a fresh UUID directory. Keep its install intact for
  # action cleanup and give the keyed musl wrappers a stable toolchain path.
  cp -a -- "$(dirname -- "$zig")" "$stable_zig"
  test "$("$stable_zig/zig" version)" = 0.14.0
  test -f "$stable_zig/lib/std/std.zig"
  printf 'CODEX_PACKAGE_CACHE_ZIG=%s\n' "$stable_zig/zig" >> "$GITHUB_ENV"
  printf '%s\n' "$stable_zig" >> "$GITHUB_PATH"
  exit
fi

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
    "$metadata/.github/scripts/package-final-output-key.sh" > "$inputs/wrappers"
  sha256sum "$metadata/.github/scripts/package-final-output-cache.sh" \
    "$metadata/tooling/cli/src/main/java/com/holdmyspot/codexunleashed/tooling/PackageFinalOutputBundle.java" > "$inputs/helper"
  operations=.github/scripts/codex-package-operations.sh
  openssl_dir="$X86_64_UNKNOWN_LINUX_MUSL_OPENSSL_DIR"
  native_root="$RUNNER_TEMP/codex-musl-tools-$TARGET"
  printf '%s\n' "$TARGET" "$RUNNER_OS" "$RUNNER_ARCH" "${ImageOS:?}" "${ImageVersion:?}" \
    "$GITHUB_WORKSPACE" "$CARGO_TARGET_DIR" "${SOURCE_DATE_EPOCH:?}" > "$inputs/compiler-context"
  test "${CODEX_PACKAGE_CACHE_ZIG:?}" = "$stable_zig/zig"
  test "$(command -v zig)" = "$CODEX_PACKAGE_CACHE_ZIG"
  # The compiler uses Zig headers and libraries as well as the executable.
  # A symlink must resolve within the staged distribution to keep it keyed.
  find "$stable_zig" -type l -print0 | LC_ALL=C sort -z > "$inputs/zig-links"
  {
    rustc -vV
    cargo --version
    zig version
    sha256sum "$(command -v gcc)" "$(command -v clang)" "$(command -v strip)" \
      "$CARGO_TARGET_X86_64_UNKNOWN_LINUX_MUSL_LINKER" "$CC" "$CXX"
    find "$stable_zig" -type f -print0 | LC_ALL=C sort -z | xargs -0 -r sha256sum
    while IFS= read -r -d '' link; do
      resolved="$(realpath -e -- "$link")"
      [[ "$resolved" == "$stable_zig/"* ]]
      printf '%s -> %s\n' "$link" "$(readlink -- "$link")"
    done < "$inputs/zig-links"
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
  mv -- "$inputs/key-receipt" "$RUNNER_TEMP/package-final-output-key.txt"
  cat "$inputs/components"
  printf 'tag=%s\n' "$tag" >> "$GITHUB_OUTPUT"
  exit
fi
printf 'Expected stage-zig or inputs.\n' >&2
exit 2
