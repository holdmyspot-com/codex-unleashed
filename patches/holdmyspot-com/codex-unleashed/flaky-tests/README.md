# Flaky test fixes

- Applies to: the queue through `rama-0.4/rama-0.4.patch` (patch 35), which sits on upstream `openai/codex` main, commit `dddc3c0a79528c4de8d9a20d51dd3bd89b02a3e4`.
- Prerequisites: every earlier patch in the queue, through `rama-0.4/rama-0.4.patch`. The patches below apply in this order and do not depend on each other.
- Patches: `event-wait-timeout.patch`, `wine-pty-timeout.patch`, `rollout-trace.patch`, `daemon-telemetry-budgets.patch`, `otel-policy-export.patch`, `focus-palette.patch`.

## Intent

Every test here is upstream's and fails only when the runner is slow: a fixed time budget runs out, or the test acts before the program under test is ready. Each patch removes the time or ordering dependence instead of retrying, skipping or sleeping. A hang is still caught, by the test runner's own timeout.

## What changed

- `event-wait-timeout.patch`: `wait_for_event` in `core/tests/common/lib.rs` no longer has a per-event bound, and `wait_for_event_with_timeout` uses exactly the bound its caller passes. It used to raise every bound to at least 10 seconds and gave `wait_for_event` one second, so every event wait in the core tests had a fixed 10 second budget. A cold PowerShell start, a large image decode or a stalled runner exceeded it ("timeout waiting for event"). Every other caller passes 15 seconds or more, or a deadline-derived remainder; the two approval waits in the subagent test that passed 2 seconds now use `wait_for_event`.
- `wine-pty-timeout.patch`: `//bazel/rules/testing/wine:wine-test-support-unit-tests` gets the `moderate` Bazel timeout, and the PowerShell PTY test loses its inner 30 second deadline, so one budget governs the Wine prefix copy, the Wine boot and the PowerShell cold start.
- `rollout-trace.patch`: the four cases of `accepted_output_survives_success_and_stream_failure` are separate tests, so one slow `codex app-server` start is not multiplied by four. `TestAppServerBuilder::with_request_timeout` replaces the 10 second response wait for one server (the default is unchanged) and both rollout trace tests use 60 seconds. The tests use the mock server without an expectation and count its requests at the end, so a startup error is reported instead of wiremock's drop-time panic.
- `daemon-telemetry-budgets.patch`: `CODEX_DAEMON_TELEMETRY_BUDGET_MS` replaces both 2 second budgets in `cli/src/daemon_telemetry.rs` (configuration load and exporter flush). The default is still 2 seconds. The test sets 60 seconds.
- `otel-policy-export.patch`: `PolicyExporter::export` waits up to 10 seconds for the application network policy to be published instead of failing with "application network policy is unavailable", which dropped delta metrics exported before publication. It subscribes to policy changes before checking, so a publication in between is not missed. Other denials still fail at once. A timer thread bounds the wait, because SDK worker threads drive export futures outside a Tokio runtime. Unit tests start an export before the policy exists and publish while it waits.
- `focus-palette.patch`: the exit-clears-inline-draft test waits until it has answered the startup probe's cursor-position, keyboard and palette queries before it sends the exit key.

## Not fixed

- `compact` `local_compaction_retries_preserve_instructions_and_tool_metadata::test_case_1_metadata_enabled` and `turn_phase_trace::steer_joins_existing_turn_without_extending_sampling`: no cause confirmed (see the results below).
