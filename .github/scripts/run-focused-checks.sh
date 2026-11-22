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

run_test http_client_adapter::tests::sse_body_stream_propagates_event_size_error -p codex-rmcp-client --lib
