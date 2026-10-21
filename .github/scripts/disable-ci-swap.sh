#!/usr/bin/env bash
# Disables swap on the Linux CI execution host and refuses to continue if any remains active.
set -euo pipefail

sudo -n swapoff --all
active_swap="$(swapon --show=NAME --noheadings)"
if [[ -n "$active_swap" ]]; then
  printf 'ERROR: Linux CI requires RAM-only operation; swap remains active:\n%s\n' "$active_swap" >&2
  exit 1
fi
printf 'RAM-only CI: no active swap.\n'
