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

run_test http_client_adapter::tests::modern_tool_catalog_applies_configured_response_cap -p codex-rmcp-client --lib
run_test legacy_oauth_discovery_follows_same_origin_metadata_redirects -p codex-rmcp-client --test mcp_2026_oauth_discovery
