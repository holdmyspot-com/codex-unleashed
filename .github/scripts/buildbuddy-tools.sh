#!/usr/bin/env bash
# Installs the same user-space tools used by the GitHub CI jobs.
set -euo pipefail

if [[ ! -x "$CI_BUILD_ROOT/bin/just" ]]; then
  curl -fsSL --retry 3 https://github.com/casey/just/releases/download/1.51.0/just-1.51.0-x86_64-unknown-linux-musl.tar.gz \
    -o "$RUNNER_TEMP/just.tar.gz"
  printf '%s  %s\n' c8f085ca3e885723c341d06243fc291b5abfdc8bbe3b2c076b117de490387b59 \
    "$RUNNER_TEMP/just.tar.gz" | sha256sum --check --status
  tar -xzf "$RUNNER_TEMP/just.tar.gz" -C "$CI_BUILD_ROOT/bin" just
  rm -- "$RUNNER_TEMP/just.tar.gz"
fi
if [[ ! -x "$CI_BUILD_ROOT/bin/uv" ]]; then
  curl -fsSL --retry 3 https://github.com/astral-sh/uv/releases/download/0.11.3/uv-x86_64-unknown-linux-gnu.tar.gz \
    -o "$RUNNER_TEMP/uv.tar.gz"
  printf '%s  %s\n' c0f3236f146e55472663cfbcc9be3042a9f1092275bbe3fe2a56a6cbfd3da5ce \
    "$RUNNER_TEMP/uv.tar.gz" | sha256sum --check --status
  tar -xzf "$RUNNER_TEMP/uv.tar.gz" --strip-components=1 -C "$CI_BUILD_ROOT/bin"
  rm -- "$RUNNER_TEMP/uv.tar.gz"
fi
if [[ ! -x "$CI_BUILD_ROOT/bin/dotslash" ]]; then
  curl -fsSL --retry 3 https://github.com/facebook/dotslash/releases/download/v0.5.9/dotslash-linux-musl.x86_64.tar.gz \
    -o "$RUNNER_TEMP/dotslash.tar.gz"
  printf '%s  %s\n' 5cefa0f258e0a58ae53c7a9a5be3890574ddd33d57c66bc9c143cb411012d72a \
    "$RUNNER_TEMP/dotslash.tar.gz" | sha256sum --check --status
  tar -xzf "$RUNNER_TEMP/dotslash.tar.gz" -C "$CI_BUILD_ROOT/bin" dotslash
  rm -- "$RUNNER_TEMP/dotslash.tar.gz"
fi
sudo install "$CI_BUILD_ROOT/bin/dotslash" /usr/local/bin/dotslash
if [[ ! -x "$CI_BUILD_ROOT/pnpm/node_modules/.bin/pnpm" ]]; then
  npm install --prefix "$CI_BUILD_ROOT/pnpm" pnpm@10
fi
export PATH="$CI_BUILD_ROOT/pnpm/node_modules/.bin:$PATH"
export UV_PYTHON_INSTALL_DIR="$CI_BUILD_ROOT/python"
uv python install 3.12
if [[ ! -x "$CI_BUILD_ROOT/python-env/bin/python" ]]; then
  uv venv --python 3.12 "$CI_BUILD_ROOT/python-env"
fi
export PATH="$CI_BUILD_ROOT/python-env/bin:$PATH"
if [[ ! -f "$CI_BUILD_ROOT/python-packaging-ready" ]]; then
  uv pip install --python "$CI_BUILD_ROOT/python-env/bin/python" packaging
  touch "$CI_BUILD_ROOT/python-packaging-ready"
fi
