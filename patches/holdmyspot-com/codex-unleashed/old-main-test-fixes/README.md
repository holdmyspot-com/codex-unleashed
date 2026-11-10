# Old-main test fixes (T9)

- Applies to: the queue through `flaky-tests/focus-palette.patch` (patch 39), which sits on upstream `openai/codex` main, commit `dddc3c0a79528c4de8d9a20d51dd3bd89b02a3e4`.
- Prerequisites: every earlier patch in the queue. The patches below apply in the order listed. `conpty-lifecycle-input-fixture.patch` needs `windows-capture-reader-drain.patch` and `legacy-powershell-environment.patch`, and `legacy-powershell-environment.patch` needs `windows-capture-reader-drain.patch`; the others do not depend on each other.
- Patches: the 16 files listed under "What changed", in that order.

## Intent

These fixes come from the old `main`'s unmigrated test-fix candidates (T9 in [archived-worktree-refs.md](../../../../docs/reconciliation/archived-worktree-refs.md)). The owner chose to re-test each one on 2026-11-10 rather than carry them over unproven. A patch is kept only when its test fails with the fix removed and passes with it, on the platform the test targets. Most are test fixtures that select the right local or remote environment, working directory or console state; four are product fixes (`windows-capture-reader-drain`, `windows-mouse-capture-initialization`, `close-remote-client-owner`, `host-hook-cwd`).

All runs are on `holdmyspot-com/codex-unleashed`, from scratch branches `t9-windows` and `t9-linux`. A "red" phase restores the tests to their state before the fix and must fail; the "green" phase applies the patch and must pass. Remote-environment runs used the Docker remote test environment, the setup that `baseline-linux-tests.yml` now always provides.

## What changed

Windows (x64 unless noted):
- `windows-capture-reader-drain.patch`: legacy Windows capture finishes after the root process exits, even when a surviving descendant still holds the output pipe. Without it, `legacy_capture_emits_output_and_preserves_descendant_after_normal_exit` fails after about 45 s. Red/green run `42837979896`.
- `legacy-powershell-environment.patch`: the legacy PowerShell sandbox fixtures get their own temp directory and executor environment (`SystemRoot`, `TEMP`) and write markers inside writable directories. Without it, `legacy_capture_cancellation_terminates_descendants_without_timeout` fails after 31 s. Run `42840897950`.
- `conpty-lifecycle-input-fixture.patch`: the descendant lifecycle fixture keeps ConPTY input open. With input closed, `legacy_tty_job_terminates_and_preserves_descendants` fails with "Terminate descendant did not start". Run `42840911796`, stacked on the two patches above. Unstacked run `42837997853` failed in both phases because the environment fix was missing.
- `windows-shell-policy-system-root.patch`: the installed Windows shell policy fixture keeps `SystemRoot`. On ARM64 only, `exec_command_uses_installed_environment_shell_policy_with_explicit_overrides` fails with PowerShell `GetSaferPolicy` Win32Exception 126. ARM64 run `42838009679`; on x64 the red phase passes (run `42838003635`).
- `conpty-interrupt-readiness-fixture.patch`: the ConPTY interrupt fixture isolates Ctrl+C state and waits for PowerShell to resume instead of a fixed delay. Without it, `conpty_ctrl_c_interrupts_powershell_foreground_child` times out after about 11 s. Run `42838014729`.
- `windows-mouse-capture-initialization.patch`: native mouse-capture cleanup is armed only after Crossterm has saved the console mode. Without it, releasing input during the scroll write fails with "Initial console modes not set". Run `42838035115`.

Linux:
- `close-remote-client-owner.patch`: dropping the owning remote app-server client closes the peer websocket even while a request handle is retained. Without it, `dropping_remote_client_closes_peer_with_retained_request_handle` fails ("peer should observe client closure: Elapsed"). Run `42841247651`.
- `host-hook-cwd.patch`: host hooks run in the host turn's working directory when only a remote environment is selected. Without it, `hook_cwd_stays_on_host_when_only_a_remote_environment_is_selected` sees `core` instead of `core/host-workspace`. Run `42842159760`.
- `ui-plugin-startup-fixtures.patch`: the generic TUI test configuration disables `Feature::Plugins`, so fixtures do not start plugin marketplace sync. The new regression `generic_fixture_keeps_plugin_startup_opt_in` fails without it. Run `42838827152`.
- `local-stdio-mcp-fixtures.patch`: the MCP elicitation and verification fixtures register the local runtime capabilities their stdio servers need. Without it, `guardian_mcp_elicitation`, `mcp_subagent_elicitation` and `mcp_user_verification` fail when a remote environment is configured. Run `42839938136`.
- `remote-shell-fixtures.patch`: the shell fixtures in `deprecation_notice` and `remote_env` match the local and remote capabilities. Without it, `deprecation_notice` and the `remote_env` exec_command and request_permissions tests fail. Run `42839938136`. Three other `remote_env` tests failed in both phases there, for reasons this patch does not touch.
- `trusted-plugin-executor-fixture.patch`: the trusted plugin scripts are placed on the selected executor. Without it, `command_execution_notifications_include_trusted_plugin_id` fails. Run `42839989982`.
- `interrupt-host-cwd-fixtures.patch`: the interrupt-hook fixtures keep the host working directory independent of tool execution. Without it, four `interrupt_hooks` tests fail with a remote environment. Run `42839962999`.
- `host-loopback-mcp-fixtures.patch`: host MCP fixtures stay reachable when the remote executor is primary. Without it, `hooks::local_bundled_cleanup_hook_runs_without_saved_trust` fails. Run `42839962999`. The `hooks_executor` cases it also changes failed in both phases there, because the runner lacked the MCP test servers, so that half is not yet proven.
- `feedback-thread-cwd-fixture.patch`: the feedback processor test compares the working directory with the selected thread's workspace. Without it, `doctor_uses_loaded_feedback_thread_cwd` fails. Run `42839989982`.
- `guardian-local-capability-fixtures.patch`: Guardian fixtures register local execution alongside the remote environment. Without it, the `guardian_review` session-reuse case and `guardian_review_cancellation` case 1 fail. Run `42839962999`. Other Guardian cases failed in both phases there, because the runner lacked the MCP test servers, so they are not yet proven.

## Not kept

- Omitted because the red phase passed: Windows 0075 (run `42837985060`) and 0076 (3 of 3); Linux startup-directory (`42838845325`), TLS fallback (`42841271803`), sandbox-temp (`42838883065`) and secrets-root (Bazel `42838901026`, `42838918953`).
- Upstream-equivalent: 53897, 55693 and 0079, fixed upstream only at `rust-v0.167.0`. Obsolete: 0077.
- Inconclusive: Windows 0092, because the helper binaries were not built in its run, and Linux 0090, which needs a managed-requirements file with `fast_mode=false`. Rerun both now that the test workflows build their helpers.

## Verification

- All 55 patches of the queue apply with `git am` onto `dddc3c0a79`.
- Rerun `host-loopback-mcp-fixtures.patch` and `guardian-local-capability-fixtures.patch` red/green with the MCP test servers built, then rerun the full queue's checks.
