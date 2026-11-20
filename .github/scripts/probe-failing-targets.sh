#!/usr/bin/env bash
# Experiment: build the Windows Bazel targets whose cases fail under load, then run only those cases alone, at
# Bazel's default concurrency and with two test jobs, so a failure under load can be told from a failure of the case.
# Usage: probe-failing-targets.sh EVIDENCE_DIR [WRAPPER_ARG...] -- BAZEL_TEST_ARG...
set -uo pipefail

evidence=$1
shift
wrapper_args=()
while [[ $# -gt 0 && "$1" != -- ]]; do
  wrapper_args+=("$1")
  shift
done
shift
test_args=("$@")

sharded=(
  //codex-rs/core:core-all-test
  //codex-rs/app-server:app-server-all-test
)
small=(
  //codex-rs/cli:cli-exec_server-test
  //codex-rs/core:core-mcp_trace_propagation-test
  //codex-rs/app-server:app-server-prompt_prefix-test
  //codex-rs/network-proxy:network-proxy-windows_stable_ingress-test
)
filters=(
  cached_mcp_startup_is_eager_for_root_and_lazy_for_subagents
  frozen_cloud_skills_with_named_executor_request_history
  skill_read_recovers_omitted_installation_and_reads_selected_environment
  request_permissions::
  mcp_server_status_list_reports_disconnected_stdio_transport
  remote_exec_server_gracefully_stops_when_parent_stdin_closes
  mcp_requests_preserve_trace_context_across_workers_and_continuations
  prompt_prefix_matches_release
  restricted_tokens_select_stable_routes_and_cleanup
)
filter_args=()
for filter in "${filters[@]}"; do
  filter_args+=("--test_arg=$filter")
done
commit=$(cat "$RUNNER_TEMP/baseline-windows-bazel-evidence/upstream-commit")

build_args=(build)
for arg in "${test_args[@]:1}"; do
  # Bazel's build command does not accept this test-summary option.
  if [[ "$arg" != --test_verbose_timeout_warnings ]]; then
    build_args+=("$arg")
  fi
done

run_phase() {
  local phase=$1
  shift
  local phase_args=() phase_targets=()
  while [[ $# -gt 0 && "$1" != -- ]]; do
    phase_args+=("$1")
    shift
  done
  shift
  phase_targets=("$@")
  printf 'phase=%s started=%s\n' "$phase" "$(date -u +%FT%TZ)" | tee -a "$evidence/probe-phases.log"
  .github/scripts/run-bazel-ci.sh "${wrapper_args[@]}" -- "${phase_args[@]}" --build_metadata="COMMIT_SHA=$commit" -- \
    "${phase_targets[@]}" 2>&1 | tee "$evidence/probe-$phase.log"
  printf 'phase=%s exit=%s finished=%s\n' "$phase" "${PIPESTATUS[0]}" "$(date -u +%FT%TZ)" | tee -a "$evidence/probe-phases.log"
  tar -chzf "$evidence/probe-$phase-testlogs.tgz" -C bazel-testlogs . 2>/dev/null || true
}

phase_args() {
  local phase=$1
  shift
  printf '%s\n' "${test_args[@]}" --cache_test_results=no --test_output=errors \
    "--build_event_json_file=$evidence/probe-$phase.bep.json" "${filter_args[@]}" "$@"
}

run_phase build "${build_args[@]}" -- "${sharded[@]}" "${small[@]}"

for phase in alone jobs2 default; do
  case $phase in
    alone) extra=(--local_test_jobs=1) ;;
    jobs2) extra=(--local_test_jobs=2) ;;
    default) extra=() ;;
  esac
  mapfile -t args < <(phase_args "$phase" "${extra[@]}")
  if [[ $phase == alone ]]; then
    # The small targets are not sharded, so repeat them; each shard of the others already repeats its cases.
    run_phase "$phase" "${args[@]}" -- "${sharded[@]}"
    mapfile -t args < <(phase_args "$phase-small" "${extra[@]}" --runs_per_test=5)
    run_phase "$phase-small" "${args[@]}" -- "${small[@]}"
  else
    run_phase "$phase" "${args[@]}" -- "${sharded[@]}" "${small[@]}"
  fi
done
