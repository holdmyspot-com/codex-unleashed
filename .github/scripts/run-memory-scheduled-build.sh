#!/usr/bin/env bash
# Runs a build whose rustc invocations the tooling's compiler scheduler pauses, kills, and reruns to keep the
# build within memory.
#
# On Linux, the build runs in a transient systemd scope that cannot swap and whose out-of-memory kills end only the victim, so
# that if the scheduler reacts too late, the kernel kills one compiler, preferring the newest, instead of stalling
# the runner in swap or ending the whole build.
set -euo pipefail

if [[ "$#" -eq 0 ]]; then
  echo 'Usage: run-memory-scheduled-build.sh <command> [args...]' >&2
  exit 2
fi

scheduled=("${CODEX_UNLEASHED_TOOLING:?CODEX_UNLEASHED_TOOLING is required}" schedule-compilers -- "$@")
if [[ "${RUNNER_OS:-$(uname -s)}" != Linux ]]; then
  exec "${scheduled[@]}"
fi
if ! command -v systemd-run > /dev/null || ! sudo -n true 2> /dev/null; then
  echo 'Running without a memory scope: systemd-run or passwordless sudo is unavailable' >&2
  exec "${scheduled[@]}"
fi

# The scope keeps the caller's environment, working directory, and standard streams, and runs as the caller.
exec sudo -n --preserve-env systemd-run --scope --quiet --collect \
  --uid="$(id -u)" --gid="$(id -g)" \
  --property=MemorySwapMax=0 \
  --property=OOMPolicy=continue \
  -- env "PATH=$PATH" HOME="$HOME" "${scheduled[@]}"
