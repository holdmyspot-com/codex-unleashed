# Flaky test fixes

- Applies to: the queue through `rama-0.4/rama-0.4.patch` (patch 35), which sits on upstream `openai/codex` main, commit `dddc3c0a79528c4de8d9a20d51dd3bd89b02a3e4`.
- Prerequisites: every earlier patch in the queue, through `rama-0.4/rama-0.4.patch`. The patches below apply in this order and do not depend on each other.
- Patches: `wine-pty-timeout.patch`, `rollout-trace.patch`, `otel-policy-export.patch`, `focus-palette.patch`.

## Intent

Every test here is upstream's and fails only when the runner is slow: a fixed time budget runs out, or the test acts before the program under test is ready. Each patch removes the time or ordering dependence instead of retrying, skipping or sleeping. A hang is still caught, by the test runner's own timeout.

## What changed

- `wine-pty-timeout.patch`: `//bazel/rules/testing/wine:wine-test-support-unit-tests` gets the `moderate` Bazel timeout, and the PowerShell PTY test loses its inner 30 second deadline, so one budget governs the Wine prefix copy, the Wine boot and the PowerShell cold start.
- `rollout-trace.patch`: the four cases of `accepted_output_survives_success_and_stream_failure` are separate tests, so one slow `codex app-server` start is not multiplied by four. `TestAppServerBuilder::with_request_timeout` replaces the 10 second response wait for one server (the default is unchanged) and both rollout trace tests use 60 seconds. The tests use the mock server without an expectation and count its requests at the end, so a startup error is reported instead of wiremock's drop-time panic.
- `otel-policy-export.patch`: `PolicyExporter::export` waits up to 10 seconds for the application network policy to be published instead of failing with "application network policy is unavailable", which dropped delta metrics exported before publication. It subscribes to policy changes before checking, so a publication in between is not missed. Other denials still fail at once. A timer thread bounds the wait, because SDK worker threads drive export futures outside a Tokio runtime. Unit tests start an export before the policy exists and publish while it waits.
- `focus-palette.patch`: the exit-clears-inline-draft test waits until it has answered the startup probe's cursor-position, keyboard and palette queries before it sends the exit key.

## Not fixed

- `compact` `local_compaction_retries_preserve_instructions_and_tool_metadata::test_case_1_metadata_enabled`: CPU bound, not a race. Under a loaded runner it ran 48 s (case 2) and hit nextest's 60 s limit (case 1) in 10 of 10 iterations (run `42845848861`). About 2 MiB of metadata should not take that long even in an unoptimized binary, so the likely cause is quadratic product work in history recording or `append_executed_tool_calls`. It needs a profile before a fix; no patch is included.
- `turn_phase_trace::steer_joins_existing_turn_without_extending_sampling` (`interrupt_enabled`, `reply_enabled`): 0 failures in 10 loaded iterations (run `42845848861`), each about 0.1 s. The single failure (`observe_turn_timing` returned `None`) matches `invalidate_critical_path`, which `core/src/tasks/mod.rs` calls when an interrupted task does not finish within the 100 ms `GRACEFULL_INTERRUPTION_TIMEOUT_MS`. That explains `interrupt_enabled`, not `reply_enabled`, so no patch is included.
- `focus-palette.patch` removes the test's own race. The application's startup probe (`tui/src/terminal_probe.rs`, 250 ms) can still miss a late reply under extreme load, and the exit path then skips its DA1 query.
- Core event waits (`wait_for_event`, `wait_for_event_with_timeout` in `core/tests/common/lib.rs`) and `cli` `daemon_telemetry`: fixes were drafted (`event-wait-timeout.patch`, `daemon-telemetry-budgets.patch`) and dropped on 2026-11-10, because no loaded run failed before them (`stdio_image_responses_resize_large_image`: before `42853762462`, 0 of 20; daemon telemetry: before `42844736948`, 0 of 20), so they had no red phase. The event-wait draft also changed the timeout of every core event wait, and the telemetry draft added a CLI environment variable used only by a test. If either test fails again, the fix should be limited to the failing test.

## Verification

All runs are on `holdmyspot-com/codex-unleashed`. Stress runs use `stress_count=20` with one CPU-burning `yes` process per core (`baseline-linux-tests.yml`, `baseline-bazel-tests.yml`), nextest `--retries 0` or Bazel `--flaky_test_attempts=1`. "Before" applies the first 35 patches, "after" all 41, including the two dropped ones.

- The 39 patches apply with `git am` onto `dddc3c0a79`.
- Linux nextest, `codex-core codex-cli codex-app-server codex-tui`, nine test filters: before `42844736948`, after `42844742955`.
  - `auth_storage_originator::concurrent_connections_export_their_own_storage_originator`: 6 timeouts in 20 iterations before, 0 after.
  - `focus_palette::default_owned_screen_entry_paints_before_sync_ends_and_exit_clears_inline_draft`: 1 failure (a 30 s probe wait) before, 0 after.
  - The `accepted_output_survives` tests did not fail before or after.
  - `stdio_image_responses_resize_large_image` failed 20 of 20 in both runs because that run did not build `test_stdio_server`. Reruns with `codex-rmcp-client` added: before `42853762462`, after `42853788049`, 0 failures of 20 each.
- Fix 5 red phase `42844805122`: with `network_policy.rs` withheld, `export_started_before_publication_waits_and_then_exports` failed 20 of 20 and the run then passed with the fix.
- Bazel `//bazel/rules/testing/wine:wine-test-support-unit-tests`, 20 runs each: before `42850502873`, TIMEOUT in 20 of 20 at 60.4 s; after `42850528725`, passed.
- Rows not patched: focused run `42845848861`.
- Repository checks `42844896018`, platform lints `42848707830` and argument-comment lint `42844909597` passed. Bazel Clippy `42848713820`: the Linux lanes passed. The macOS lane failed on a 500 from a `rules_platform` download and the Windows gnullvm lane on a dangling `opusic-sys` symlink; neither involves these patches.
