#!/usr/bin/env bash
# Sources the toolchain environment and verifies the tooling runtime before reuse.
set -euo pipefail

project_root="$PWD"
export CI_BUILD_ROOT="$HOME/.cache/codex-unleashed-ci"
mkdir -p "$CI_BUILD_ROOT"
export RUNNER_TEMP="$CI_BUILD_ROOT/tmp"
export TMPDIR="$RUNNER_TEMP"
export CARGO_HOME="$CI_BUILD_ROOT/cargo"
export RUSTUP_HOME="$CI_BUILD_ROOT/rustup"
export UV_CACHE_DIR="$CI_BUILD_ROOT/uv-cache"
export XDG_CACHE_HOME="$CI_BUILD_ROOT/xdg"
export CARGO_NET_GIT_FETCH_WITH_CLI=true
export PATH="$CI_BUILD_ROOT/bin:$CARGO_HOME/bin:$PATH"
mkdir -p "$RUNNER_TEMP" "$CI_BUILD_ROOT/bin"

if [[ ! -f "$CI_BUILD_ROOT/system-packages-v1" ]]; then
  sudo apt-get update
  sudo env DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends \
    jq unzip xz-utils build-essential pkg-config libcap-dev ca-certificates
  if [[ -f /proc/sys/kernel/unprivileged_userns_clone ]]; then
    sudo sysctl -w kernel.unprivileged_userns_clone=1
  fi
  unshare --user --map-root-user true
  touch "$CI_BUILD_ROOT/system-packages-v1"
fi

if [[ ! -f "$CI_BUILD_ROOT/jdk-27/.complete" ]]; then
  curl -fsSL --retry 3 \
    'https://github.com/adoptium/temurin27-binaries/releases/download/jdk-27%2B35/OpenJDK27U-jdk_x64_linux_hotspot_27_35.tar.gz' \
    -o "$RUNNER_TEMP/jdk.tar.gz"
  printf '%s  %s\n' 1cf69a4848ffb728b3b260dfd45206a51566ab571a02a30092271d4c580bccbc \
    "$RUNNER_TEMP/jdk.tar.gz" | sha256sum --check --status
  mkdir -p "$CI_BUILD_ROOT/jdk-27"
  tar -xzf "$RUNNER_TEMP/jdk.tar.gz" --strip-components=1 -C "$CI_BUILD_ROOT/jdk-27"
  touch "$CI_BUILD_ROOT/jdk-27/.complete"
  rm -- "$RUNNER_TEMP/jdk.tar.gz"
fi
export JAVA_HOME="$CI_BUILD_ROOT/jdk-27"
export PATH="$JAVA_HOME/bin:$PATH"

if [[ ! -f "$CI_BUILD_ROOT/node-22/.complete" ]]; then
  curl -fsSL --retry 3 https://nodejs.org/dist/v22.23.3/node-v22.23.3-linux-x64.tar.xz \
    -o "$RUNNER_TEMP/node.tar.xz"
  printf '%s  %s\n' df450af89261115ef9f9e3830c3eeb2cc9213b63c720b1af623cb5dcbe2e02de \
    "$RUNNER_TEMP/node.tar.xz" | sha256sum --check --status
  mkdir -p "$CI_BUILD_ROOT/node-22"
  tar -xJf "$RUNNER_TEMP/node.tar.xz" --strip-components=1 -C "$CI_BUILD_ROOT/node-22"
  touch "$CI_BUILD_ROOT/node-22/.complete"
  rm -- "$RUNNER_TEMP/node.tar.xz"
fi
export PATH="$CI_BUILD_ROOT/node-22/bin:$PATH"

if [[ ! -x "$CARGO_HOME/bin/rustup" ]]; then
  curl -fsSL --retry 3 https://static.rust-lang.org/rustup/dist/x86_64-unknown-linux-gnu/rustup-init \
    -o "$RUNNER_TEMP/rustup-init"
  curl -fsSL --retry 3 https://static.rust-lang.org/rustup/dist/x86_64-unknown-linux-gnu/rustup-init.sha256 \
    -o "$RUNNER_TEMP/rustup-init.sha256"
  (cd "$RUNNER_TEMP" && sha256sum --check rustup-init.sha256)
  chmod +x "$RUNNER_TEMP/rustup-init"
  "$RUNNER_TEMP/rustup-init" -y --no-modify-path --profile minimal --default-toolchain stable
  rm -- "$RUNNER_TEMP/rustup-init" "$RUNNER_TEMP/rustup-init.sha256"
fi

# The checkout is cleaned between invocations; persistent targets live outside it.
mkdir -p .cat/work/temp/build-caches "$CI_BUILD_ROOT/maven"
ln -s "$CI_BUILD_ROOT/maven" .cat/work/temp/build-caches/maven
export CODEX_UNLEASHED_TOOLING="$project_root/tooling/bin/codex-tooling"
tooling_identity="$(
  { git ls-files -z tooling .github/scripts patches | sort -z | xargs -0 sha256sum
    java --version
    uname -sm
  } | sha256sum | cut -d ' ' -f 1
)"
tooling_marker="$CI_BUILD_ROOT/tooling-verified"
if [[ ! -f "$tooling_marker" ]] || [[ "$(cat "$tooling_marker")" != "$tooling_identity" ]]; then
  rm -f -- "$tooling_marker"
  tooling/mvnw -q verify </dev/null
  printf '%s\n' "$tooling_identity" > "$tooling_marker"
  printf 'Tooling runtime: verified cache miss.\n'
else
  printf 'Tooling runtime: verified cache hit.\n'
fi
probe="$("$CODEX_UNLEASHED_TOOLING" next-release-build-number rust-v0.0.0 </dev/null)"
if [[ "$probe" != 1 ]]; then
  printf 'Tooling launcher probe returned %s; expected 1.\n' "$probe" >&2
  exit 1
fi
