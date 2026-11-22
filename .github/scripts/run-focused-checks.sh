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

run_test http_client_adapter::tests::collect_body_rejects_one_byte_beyond_the_limit -p codex-rmcp-client --lib
run_test doctor::tests::websocket_probe_json_preserves_http_status_without_response_values -p codex-cli --bin codex
