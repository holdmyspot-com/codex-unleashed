#!/usr/bin/env bash
set -euo pipefail

run_test() {
    local package="$1"
    local test_name="$2"
    local output
    output=$(cargo test --locked -p "$package" --lib "$test_name" -- --exact)
    printf '%s\n' "$output"
    if ! grep -Fq "test $test_name ... ok" <<< "$output"; then
        printf 'Expected test did not pass: %s\n' "$test_name" >&2
        return 1
    fi
}

run_test codex-api endpoint::responses_websocket_probe::tests::probe_debug_preserves_status_and_hides_synthetic_values
run_test codex-api endpoint::responses_websocket_probe::tests::probe_failure_preserves_status_and_hides_synthetic_values
run_test codex-api endpoint::responses_websocket::probe_header_tests::probe_retains_only_tracking_headers
run_test codex-api endpoint::responses_websocket::probe_header_tests::probe_close_evidence_preserves_code_and_hides_reason_in_debug
run_test codex-rmcp-client http_client_redirect::origin_policy_tests::redirect_policy_requires_the_original_scheme_host_and_port
run_test codex-rmcp-client http_client_redirect::tests::redirect_wrapper_controls_same_origin_following
run_test codex-rmcp-client http_client_redirect::tests::redirect_wrapper_rejects_origin_change_before_following
