#!/usr/bin/env bash
# Print the effective native Apple toolchain identities for a package cache key.
# Call after selecting Xcode and MACOSX_DEPLOYMENT_TARGET, before restoring any cache.
set -euo pipefail
export LC_ALL=C

case "$RUNNER_OS:$RUNNER_ARCH:$TARGET" in
  macOS:ARM64:aarch64-apple-darwin | macOS:X64:x86_64-apple-darwin) ;;
  *) printf 'Unsupported native macOS package target: %s/%s/%s\n' "$RUNNER_OS" "$RUNNER_ARCH" "$TARGET" >&2; exit 2 ;;
esac

# xcrun resolves the developer directory and toolchain selected through these
# environment variables. An alternate SDK override needs its own probe.
developer_dir="$(xcode-select -p)"
xcode_version="$(xcodebuild -version)"
sdk_path="$(xcrun --sdk macosx --show-sdk-path)"
sdk_version="$(xcrun --sdk macosx --show-sdk-version)"
clang_path="$(xcrun --sdk macosx --find clang)"
linker_path="$(xcrun --sdk macosx --find ld)"
clang_version="$(xcrun --sdk macosx clang --version)"
test -n "$developer_dir" && test -n "$xcode_version" && test -n "$sdk_path" && test -n "$sdk_version" &&
  test -n "$clang_version" && test -x "$clang_path" && test -x "$linker_path" || exit 1

if [[ -n "${SDKROOT-}" && "$SDKROOT" != "$sdk_path" ]]; then
  printf 'SDKROOT does not match the macOS SDK selected by xcrun\n' >&2
  exit 1
fi
clang_digest="$(shasum -a 256 < "$clang_path")"
linker_digest="$(shasum -a 256 < "$linker_path")"
printf '%s=%q\n' \
  target "$TARGET" runner_os "$RUNNER_OS" runner_arch "$RUNNER_ARCH" \
  developer_dir "$developer_dir" xcode_version "$xcode_version" \
  sdk_path "$sdk_path" sdk_version "$sdk_version" \
  clang_path "$clang_path" clang_version "$clang_version" clang_sha256 "${clang_digest%% *}" \
  linker_path "$linker_path" linker_sha256 "${linker_digest%% *}" \
  deployment_target "${MACOSX_DEPLOYMENT_TARGET:?Missing macOS deployment target}" \
  developer_dir_override "${DEVELOPER_DIR-}" sdkroot_override "${SDKROOT-}" toolchains_override "${TOOLCHAINS-}"
