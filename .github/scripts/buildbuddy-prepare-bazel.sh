#!/usr/bin/env bash
# Sources a verified native Bazel client without the provider's shared-output-base wrapper.
set -euo pipefail

bazel_version="$(cat "$upstream/.bazelversion")"
if [[ ! "$bazel_version" =~ ^[0-9]+[.][0-9]+[.][0-9]+$ ]]; then
  printf 'Unsupported upstream Bazel release: %s\n' "$bazel_version" >&2
  exit 2
fi
export BUILDBUDDY_BAZEL_BINARY="$CI_BUILD_ROOT/bazel-client/$bazel_version/bazel"
if [[ ! -x "$BUILDBUDDY_BAZEL_BINARY" ]]; then
  (
    mkdir -p "$(dirname "$BUILDBUDDY_BAZEL_BINARY")"
    download_directory="$(mktemp -d "$(dirname "$BUILDBUDDY_BAZEL_BINARY")/.download-XXXXXX")"
    trap 'rm -rf -- "$download_directory"' EXIT
    binary_name="bazel-$bazel_version-linux-x86_64"
    base_url="https://releases.bazel.build/$bazel_version/release"
    curl -fsSL --retry 3 "$base_url/$binary_name" -o "$download_directory/$binary_name"
    curl -fsSL --retry 3 "$base_url/$binary_name.sha256" -o "$download_directory/$binary_name.sha256"
    (cd "$download_directory" && sha256sum --check "$binary_name.sha256")
    chmod +x "$download_directory/$binary_name"
    mv -- "$download_directory/$binary_name" "$BUILDBUDDY_BAZEL_BINARY"
  )
fi
printf 'startup --max_idle_secs=0\ncommon --build_metadata=PARENT_INVOCATION_ID=%s\ncommon --build_metadata=PARENT_RUN_ID=%s\ncommon --build_metadata=DISABLE_COMMIT_STATUS_REPORTING=true\n' \
  "${BUILDBUDDY_INVOCATION_ID:?}" "${BUILDBUDDY_RUN_ID:?}" > "$CI_BUILD_ROOT/product.bazelrc"
ln -sfn "$project_root/.github/scripts/run-buildbuddy-bazel.sh" "$CI_BUILD_ROOT/bin/bazel"
