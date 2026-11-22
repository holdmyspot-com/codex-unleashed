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

ignored=$(cargo test --locked -p codex-api --test responses_websocket_probe -- --list --ignored)
printf '%s\n' "$ignored"
grep -Fxq 'probe_preserves_evidence_and_redacts_wire_secrets: test' <<< "$ignored"

run_test probe_reports_normal_loopback_handshake_and_close -p codex-api --test responses_websocket_probe
run_test probe_reports_empty_loopback_close -p codex-api --test responses_websocket_probe
run_test probe_reports_upgrade_when_loopback_peer_stays_open -p codex-api --test responses_websocket_probe
run_test probe_reports_ordinary_loopback_http_rejection -p codex-api --test responses_websocket_probe
run_test probe_preserves_upgrade_evidence_when_loopback_peer_disconnects -p codex-api --test responses_websocket_probe
run_test probe_distinguishes_untrusted_certificates_from_io_failures -p codex-api --test responses_websocket_probe
