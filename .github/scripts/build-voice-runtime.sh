#!/usr/bin/env bash
# Builds or packages the native voice runtime and helper for one supported app target.
# Usage: matrix [app-target] | build <checkout> <app-target> <output> |
#        package <checkout> <app-target> <output> <expected-source-commit>
# matrix emits JSON; build/package leave voice-runtime-<app-target>.tar.gz in output.
# Unsupported targets, missing tools/files, invalid receipts or build failures exit nonzero.
# build requires the workflow's prepared native host and CODEX_UNLEASHED_TOOLING.
set -euo pipefail

# Emits the six native build configurations, optionally selecting one exact app target.
# Arguments: optional app target used by the release package.
# Output: JSON array containing target, runner, voice_target, prefix and helper.
# Errors: an unsupported nonempty app target exits 2 without output.
voice_matrix() {
  local focus="${1:-}"
  local matrix
  matrix='[
    {"target":"x86_64-unknown-linux-musl","runner":"ubuntu-24.04","voice_target":"x86_64-unknown-linux-gnu","prefix":"linux_x86_64","helper":"codex-voice-host"},
    {"target":"aarch64-unknown-linux-musl","runner":"ubuntu-24.04-arm","voice_target":"aarch64-unknown-linux-gnu","prefix":"linux_aarch64","helper":"codex-voice-host"},
    {"target":"x86_64-apple-darwin","runner":"macos-15-intel","voice_target":"x86_64-apple-darwin","prefix":"macos_x86_64","helper":"codex-voice-host"},
    {"target":"aarch64-apple-darwin","runner":"macos-15","voice_target":"aarch64-apple-darwin","prefix":"macos_aarch64","helper":"codex-voice-host"},
    {"target":"x86_64-pc-windows-msvc","runner":"windows-2022","voice_target":"x86_64-pc-windows-msvc","prefix":"windows_x86_64","helper":"codex-voice-host.exe"},
    {"target":"aarch64-pc-windows-msvc","runner":"windows-11-arm","voice_target":"aarch64-pc-windows-msvc","prefix":"windows_aarch64","helper":"codex-voice-host.exe"}
  ]'
  if [[ -n "$focus" ]]; then
    matrix="$(jq -c --arg target "$focus" 'map(select(.target == $target))' <<< "$matrix")"
    if [[ "$matrix" == '[]' ]]; then
      printf 'Unsupported app target: %s\n' "$focus" >&2
      return 2
    fi
  fi
  jq -c . <<< "$matrix"
}

# Stages, validates and seals build-produced files before creating a release archive.
# Arguments: upstream checkout; app target; output directory; expected upstream commit.
# Side effects: creates output directory and archive, removes its own temporary staging.
# Errors: target, source-commit, helper, source validator or archive failures exit nonzero.
package_voice() (
  local checkout="$1" target="$2" output="$3" expected_commit="$4"
  local configuration voice_target prefix helper source stage archive python
  [[ -n "$target" ]] || { echo 'App target must be nonempty' >&2; return 2; }
  configuration="$(voice_matrix "$target")"
  voice_target="$(jq -r '.[0].voice_target' <<< "$configuration")"
  prefix="$(jq -r '.[0].prefix' <<< "$configuration")"
  helper="$(jq -r '.[0].helper' <<< "$configuration")"
  checkout="$(CDPATH='' cd -- "$checkout" && pwd)"
  source="$checkout/bazel-bin/third_party/voice/native_runtime_$prefix"
  if ! jq -e --arg commit "$expected_commit" '.sourceCommit == $commit' "$source/runtime.json" >/dev/null; then
    printf 'Voice runtime sourceCommit must match upstream commit %s: %s\n' "$expected_commit" "$source/runtime.json" >&2
    return 1
  fi
  [[ -f "$checkout/bazel-bin/codex-rs/voice-host/$helper" ]] || {
    printf 'Voice helper is missing: %s\n' "$checkout/bazel-bin/codex-rs/voice-host/$helper" >&2
    return 1
  }

  mkdir -p "$output"
  output="$(CDPATH='' cd -- "$output" && pwd)"
  stage="$(mktemp -d "$output/.voice-stage-XXXXXX")"
  trap 'rm -rf -- "$stage"' EXIT
  archive="voice-runtime-$target.tar.gz"
  python=python3
  if [[ "$target" == *-pc-windows-msvc ]]; then
    python=python
  fi
  PYTHONDONTWRITEBYTECODE=1 "$python" "$checkout/third_party/voice/release_runtime.py" stage \
    --target "$voice_target" --source "$source" --output "$stage/runtime"
  cp "$checkout/bazel-bin/codex-rs/voice-host/$helper" "$stage/$helper"
  chmod 0755 "$stage/$helper"
  if [[ "$target" == *-pc-windows-msvc ]]; then
    PYTHONDONTWRITEBYTECODE=1 "$python" "$checkout/third_party/voice/windows_crt.py" \
      --root "$stage/runtime" --target "$voice_target" --helper "$stage/$helper"
  fi
  PYTHONDONTWRITEBYTECODE=1 "$python" "$checkout/third_party/voice/release_runtime.py" seal \
    --target "$voice_target" --output "$stage/runtime"
  jq -e --arg commit "$expected_commit" '.sourceCommit == $commit' "$stage/runtime/runtime.json" >/dev/null
  tar -C "$stage" -czf "$stage/$archive" runtime "$helper"
  mv "$stage/$archive" "$output/$archive"
)

# Builds only the matching native runtime/helper through the project Bazel scheduler.
# Arguments: prepared upstream checkout; app target; archive output directory.
# Environment: native Windows tool bindings supplied by setup-voice-windows.ps1;
#   BuildBuddy/cache/scheduler settings consumed by the existing run-bazel-ci wrapper.
# Side effects: creates Bazel outputs, reconciles Cargo workspace versions, packages files.
# Errors: setup, native build or packaging failures exit nonzero.
build_voice() (
  local checkout="$1" target="$2" output="$3"
  local project_root configuration voice_target prefix commit
  project_root="$(CDPATH='' cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd)"
  [[ -n "$target" ]] || { echo 'App target must be nonempty' >&2; return 2; }
  configuration="$(voice_matrix "$target")"
  voice_target="$(jq -r '.[0].voice_target' <<< "$configuration")"
  prefix="$(jq -r '.[0].prefix' <<< "$configuration")"
  mkdir -p "$output"
  output="$(CDPATH='' cd -- "$output" && pwd)"
  cd "$checkout"
  commit="$(git rev-parse HEAD)"
  export STABLE_GIT_COMMIT="$commit"
  (cd codex-rs && cargo update --workspace)
  local wrapper=(bash "$project_root/.github/scripts/run-bazel-ci.sh" --remote-download-toplevel --print-failed-action-summary)
  local options=(build --config=release "--build_metadata=COMMIT_SHA=$commit")
  local targets=("//third_party/voice:native_runtime_$prefix" //codex-rs/voice-host:codex-voice-host)
  case "$voice_target" in
    *-apple-darwin) options+=(--macos_minimum_os=14.0) ;;
    *-pc-windows-msvc)
      export VOICE_ARCH="${prefix#windows_}"
      options[0]="test"
      targets+=(@rules_rust//cargo/private/cargo_build_script_runner:test)
      wrapper+=(--windows-msvc-host-platform)
      options+=(
        --platforms=//:local_windows_msvc
        "--extra_toolchains=@local_config_cc//:cc-toolchain-x64_windows,@local_config_cc//:cc-toolchain-arm64_windows,//third_party/voice:windows_pkg_config_toolchain,//third_party/voice:windows_cmake_toolchain"
        --repo_env=BAZEL_DO_NOT_DETECT_CPP_TOOLCHAIN=0
        --repo_env=BAZEL_MSVC_RUNTIME_VISUAL_STUDIO_EULA=1
        "--inject_repository=voice_windows_tools=${VOICE_WINDOWS_BAZEL_REPOSITORY:?Run setup-voice-windows.ps1 first}"
        --//third_party/voice:windows_installed_tools=@voice_windows_tools//:tools
        "--action_env=SystemRoot=${VOICE_WINDOWS_SYSTEM_ROOT:?Windows SystemRoot required}"
        "--host_action_env=SystemRoot=$VOICE_WINDOWS_SYSTEM_ROOT"
        "--action_env=PROCESSOR_ARCHITECTURE=${VOICE_WINDOWS_HOST_ARCH:?Native Windows host architecture required}"
        "--host_action_env=PROCESSOR_ARCHITECTURE=$VOICE_WINDOWS_HOST_ARCH"
        "--output_groups=default,receipt,sdk"
        --workspace_status_command=./scripts/workspace-status.cmd
      )
      ;;
  esac
  "${wrapper[@]}" -- "${options[@]}" -- "${targets[@]}"
  package_voice "$PWD" "$target" "$output" "$commit"
)

case "${1:-}" in
  matrix)
    [[ $# -le 2 ]] || { echo 'Usage: build-voice-runtime.sh matrix [app-target]' >&2; exit 2; }
    voice_matrix "${2:-}"
    ;;
  build)
    [[ $# == 4 ]] || { echo 'Usage: build-voice-runtime.sh build <checkout> <app-target> <output>' >&2; exit 2; }
    build_voice "$2" "$3" "$4"
    ;;
  package)
    [[ $# == 5 ]] || { echo 'Usage: build-voice-runtime.sh package <checkout> <app-target> <output> <expected-source-commit>' >&2; exit 2; }
    package_voice "$2" "$3" "$4" "$5"
    ;;
  *) echo 'Usage: build-voice-runtime.sh <matrix|build|package> [arguments]' >&2; exit 2 ;;
esac
