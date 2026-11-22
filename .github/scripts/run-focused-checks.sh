#!/usr/bin/env bash
set -euo pipefail

run_test() {
    local package="$1"
    local test_binary="$2"
    local test_name="$3"
    local output
    output=$(cargo test --locked -p "$package" --test "$test_binary" "$test_name" -- --exact)
    printf '%s\n' "$output"
    if ! grep -Fq "test $test_name ... ok" <<< "$output"; then
        printf 'Expected test did not pass: %s\n' "$test_name" >&2
        return 1
    fi
}

run_test codex-api responses_websocket_probe probe_reports_normal_loopback_handshake_and_close
run_test codex-rmcp-client mcp_2026_discovery ordinary_http_discovery_returns_advertised_peer_info
run_test codex-api responses_websocket_probe probe_reports_empty_loopback_close
run_test codex-api responses_websocket_probe probe_reports_upgrade_when_loopback_peer_stays_open
run_test codex-api responses_websocket_probe probe_reports_ordinary_loopback_http_rejection
run_test codex-api responses_websocket_probe probe_preserves_upgrade_evidence_when_loopback_peer_disconnects
run_test codex-rmcp-client mcp_2026_discovery ordinary_modern_http_discovery_stops_at_same_origin_redirect
run_test codex-rmcp-client mcp_2026_discovery ordinary_modern_http_discovery_accepts_json_at_message_limit
run_test codex-rmcp-client mcp_2026_discovery ordinary_modern_http_tool_catalog_accepts_json_at_message_limit
