#!/usr/bin/env bash
set -euo pipefail

# Fits a release build into a hosted Linux runner's memory: adds a 12 GB swap file, limits Cargo to three concurrent
# jobs through GITHUB_ENV, and starts a background sampler that writes memory use every 20 seconds to
# $RUNNER_TEMP/memory-samples.log, so an out-of-memory run leaves evidence for a later step to print.
#
# Upstream builds its packages on a larger repository-owned runner (`<repo>-linux-arm64`) with Cargo's default job
# count and no swap. The hosted ubuntu-24.04-arm runner has 4 CPUs and 16 GB; four concurrent rustc and thin-LTO
# processes exhausted its memory and the runner was lost mid-build ("shutdown signal", exit 143) in package dry run
# 42860267858 and smoke run 42940948378, both while compiling codex-exec. Three jobs without swap were lost the same way
# in release dry run 43295198971. The release-build workflow measured the same limit on this runner type: three Cargo
# jobs finish fastest, four run out of memory. The release profile (LTO, codegen units, optimization level) is
# unchanged. Swap absorbs the link-time peaks.
#
# usage: fit-linux-build-memory.sh
# Exit status: 0 on success; 2 when not run on a Linux runner; otherwise the failing tool's.
if [[ $# != 0 || "${RUNNER_OS:-}" != Linux ]]; then
  printf 'usage: %s (on a Linux GitHub Actions runner)\n' "$0" >&2
  exit 2
fi
free -h
swapon --show || true
df -h / /mnt 2>/dev/null || df -h /
swapfile=/mnt/codex-build-swap
if [[ ! -d /mnt ]] || [[ "$(df --output=avail -BG /mnt | tail -1 | tr -dc 0-9)" -lt 20 ]]; then
  swapfile=/codex-build-swap
fi
sudo fallocate -l 12G "$swapfile"
sudo chmod 600 "$swapfile"
sudo mkswap "$swapfile"
sudo swapon "$swapfile"
free -h
echo 'CARGO_BUILD_JOBS=3' >> "$GITHUB_ENV"
nohup setsid bash -c 'while true; do date -u +%H:%M:%S; free -m | sed -n "1,3p"; ps -eo rss,comm --sort=-rss | sed -n "1,4p"; sleep 20; done' \
  > "$RUNNER_TEMP/memory-samples.log" 2>&1 < /dev/null &
