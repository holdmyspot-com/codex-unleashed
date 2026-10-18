#!/usr/bin/env bash
# Cross-compiles the Windows GNU test targets and stages the native callback probe.
set -euo pipefail

if [[ "$BUILDBUDDY_CI_TARGET" != x86_64-pc-windows-gnullvm ]]; then
  printf 'Unsupported Windows cross-build target: %s\n' "$BUILDBUDDY_CI_TARGET" >&2
  exit 2
fi

(
  work_root="$(mktemp -d "$RUNNER_TEMP/windows-cross-XXXXXX")"
  trap 'rm -rf -- "$work_root"' EXIT
  cd upstream
  bazel_test_query='tests(//...) except tests(//third_party/v8:all) except attr(tags, "manual", tests(//...))'
  "$project_root/.github/scripts/run-bazel-query-ci.sh" --output=label -- "$bazel_test_query" \
    | LC_ALL=C sort > "$work_root/targets"
  "$CODEX_UNLEASHED_TOOLING" select-windows-bazel-targets --shard 1 --shard-count 1 \
    --durations "$project_root/.github/scripts/windows_bazel_test_durations.tsv" \
    < "$work_root/targets" > "$work_root/selected"
  mapfile -t selected_targets < "$work_root/selected"
  if [[ ${#selected_targets[@]} == 0 ]]; then
    printf 'Windows cross-build selected no targets.\n' >&2
    exit 1
  fi
  "$project_root/.github/scripts/run-bazel-ci.sh" --print-failed-action-summary --windows-cross-compile -- \
    build --skip_incompatible_explicit_targets "--build_metadata=COMMIT_SHA=$GITHUB_SHA" -- "${selected_targets[@]}"

  "$project_root/.github/scripts/run-bazel-ci.sh" --print-failed-action-summary \
    --windows-cross-compile --remote-download-toplevel -- build -- @v8//:codex_windows_stack_assembly_test
  query_output="$("$project_root/.github/scripts/run-bazel-ci.sh" --windows-cross-compile -- \
    cquery --output=files -- @v8//:codex_windows_stack_assembly_test)"
  mapfile -t binaries < <(printf '%s\n' "$query_output" | grep -E '^(/|bazel-out/)')
  if [[ ${#binaries[@]} != 1 ]]; then
    printf 'Expected one Windows callback executable; found %s.\n' "${#binaries[@]}" >&2
    exit 1
  fi
  mkdir -p "${BUILDBUDDY_ARTIFACTS_DIRECTORY:?}/windows-cross"
  install -m 755 "${binaries[0]}" "$BUILDBUDDY_ARTIFACTS_DIRECTORY/windows-cross/stack-assembly.exe"
)
