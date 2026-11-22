#!/usr/bin/env bash
set -euo pipefail

run_test() {
    local package="$1"
    local test_binary="$2"
    local test_name="$3"
    local test_target=(--test "$test_binary")
    if [[ "$test_binary" == --lib ]]; then
        test_target=(--lib)
    fi
    local output
    output=$(cargo test --locked -p "$package" "${test_target[@]}" "$test_name" -- --exact)
    printf '%s\n' "$output"
    if ! grep -Fq "test $test_name ... ok" <<< "$output"; then
        printf 'Expected test did not pass: %s\n' "$test_name" >&2
        return 1
    fi
}

run_test codex-rmcp-client --lib oauth_client_registration::tests::issuer_bound_oauth_metadata_requires_an_explicit_issuer
run_test codex-rmcp-client --lib oauth_client_registration::tests::issuerless_oauth_metadata_requires_one_endpoint_origin
