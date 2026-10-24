#!/usr/bin/env bash
set -euo pipefail
evidence="$RUNNER_TEMP/windows-tui-proxy-phases"
overlay=.github/diagnostics/windows-mouse-initialization-red.patch
mkdir -p "$evidence"
{
  printf 'project_sha='; git rev-parse HEAD
  printf 'upstream_base='; git -C upstream rev-parse HEAD
  printf 'runner_os=%s runner_arch=%s image_os=%s image_version=%s\n' "$RUNNER_OS" "$RUNNER_ARCH" "${ImageOS:-unknown}" "${ImageVersion:-unknown}"
  printf 'runner_name=%s run_id=%s run_attempt=%s\n' "$RUNNER_NAME" "$GITHUB_RUN_ID" "$GITHUB_RUN_ATTEMPT"
} > "$evidence/provenance.txt"
find patches -type f -name '*.patch' -print0 | sort -z | xargs -0 sha256sum > "$evidence/queue-sha256.txt"
printf '%s  %s\n' a1c3b0b7b63b9b17d667a4e93462ff08a22dca233527b54c01a5bf3c2322934a "$overlay" | sha256sum --check > "$evidence/overlay-sha256.txt"
git -C upstream diff --binary > "$evidence/pre-overlay.diff"
sha256sum upstream/codex-rs/tui/src/tui/alternate_screen_windows_tests.rs > "$evidence/test-before-sha256.txt"
git -C upstream apply --check "../$overlay"
git -C upstream apply "../$overlay"
sha256sum upstream/codex-rs/tui/src/tui/alternate_screen_windows_tests.rs > "$evidence/test-after-sha256.txt"
git -C upstream diff --binary > "$evidence/emitted-source.diff"
cd upstream
status=0
"${GITHUB_WORKSPACE}/.github/scripts/run-bazel-ci.sh" \
  --print-failed-action-summary --print-failed-test-logs --windows-msvc-host-platform \
  -- test --config=ci-windows-msvc --platforms=//:windows_x86_64_msvc \
  --repo_env=BAZEL_MSVC_RUNTIME_VISUAL_STUDIO_EULA=1 \
  --test_verbose_timeout_warnings "--build_metadata=COMMIT_SHA=${GITHUB_SHA}" \
  --nocache_test_results --flaky_test_attempts=1 --test_output=all \
  --test_sharding_strategy=disabled --test_arg=--nocapture --test_arg=--exact \
  --test_arg=tui::alternate_screen::windows_tests::mouse_capture_restores_console_mode_and_encoding \
  -- //codex-rs/tui:tui-unit-tests \
  2>&1 | tee "$evidence/mouse-console.log" || status=$?
printf '%s\n' "$status" > "$evidence/mouse-console.status"
classification=unclassified_failure
if [[ "$status" == 0 ]]; then
  classification=unexpected_green
elif grep -Fq 'releasing input during capture setup should not restore an uninitialized mode' "$evidence/mouse-console.log" \
  && grep -Fq 'Initial console modes not set' "$evidence/mouse-console.log" \
  && grep -Fq 'test tui::alternate_screen::windows_tests::mouse_capture_restores_console_mode_and_encoding ... FAILED' "$evidence/mouse-console.log"; then
  classification=expected_native_red
fi
printf '%s\n' "$classification" > "$evidence/classification.txt"
printf 'Native mouse regression classification: %s; test exit: %s\n' "$classification" "$status"
exit "$status"
