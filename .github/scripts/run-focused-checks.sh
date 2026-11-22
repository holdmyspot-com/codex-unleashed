#!/usr/bin/env bash
set -euo pipefail

run_test() {
    local test_name="$1"
    shift
    local output
    output=$(cargo test --locked "$@" "$test_name" -- --exact)
    printf '%s\n' "$output"
    grep -Fxq "test $test_name ... ok" <<< "$output"
}

run_test probe_distinguishes_untrusted_certificates_from_io_failures -p codex-api --test responses_websocket_probe
