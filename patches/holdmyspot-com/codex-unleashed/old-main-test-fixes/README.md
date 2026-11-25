# Old-main test fixes (T9)

- Applies to: the queue through `flaky-tests/focus-palette.patch` (patch 34), which sits on upstream `openai/codex` tag `rust-v0.171.0`, commit `ccf9be8cc458bd6b8336fe84d68d8893f2691a5e`.
- Prerequisites: every earlier patch in the queue. The patches below apply in the order listed. `legacy-powershell-environment.patch` needs `windows-capture-reader-drain.patch`; the others do not depend on each other.
- Patches: the 14 files listed under "What changed", in that order. Three more patches, `windows-test-executable-extensions.patch`, `gov-cloud-quit-closed-pty.patch` and `windows-profile-unload-polling.patch`, apply later in the queue (entries 105 to 107, after `rmcp-stdio/close-after-oversized-line.patch`) and are listed under "Owner-approved additions (2026-11-25)".

## Intent

These fixes come from the old `main`'s unmigrated test-fix candidates (T9 in [archived-worktree-refs.md](../../../../docs/reconciliation/archived-worktree-refs.md)). The owner chose to re-test each one on 2026-11-10 rather than carry them over unproven. A patch is kept only when its test fails with the fix removed and passes with it, on the platform the test targets. Most are test fixtures that select the right local or remote environment, working directory or console state; four are product fixes (`windows-capture-reader-drain`, `windows-mouse-capture-initialization`, `close-remote-client-owner`, `host-hook-cwd`).

All runs are on `holdmyspot-com/codex-unleashed`, from scratch branches `t9-windows` and `t9-linux`. A "red" phase restores the tests to their state before the fix and must fail; the "green" phase applies the patch and must pass. Remote-environment runs used the Docker remote test environment, the setup that `baseline-linux-tests.yml` now always provides.

## What changed

Windows (x64 unless noted):
- `windows-capture-reader-drain.patch`: legacy Windows capture finishes after the root process exits, even when a surviving descendant still holds the output pipe. Without it, `legacy_capture_emits_output_and_preserves_descendant_after_normal_exit` fails after about 45 s. Red/green run `42837979896`.
- `legacy-powershell-environment.patch`: the legacy PowerShell sandbox fixtures get their own temp directory and executor environment (`SystemRoot`, `TEMP`) and write markers inside writable directories. Without it, `legacy_capture_cancellation_terminates_descendants_without_timeout` fails after 31 s. Run `42840897950`.
- `windows-shell-policy-system-root.patch`: the installed Windows shell policy fixture keeps `SystemRoot`. On ARM64 only, `exec_command_uses_installed_environment_shell_policy_with_explicit_overrides` fails with PowerShell `GetSaferPolicy` Win32Exception 126. ARM64 run `42838009679`; on x64 the red phase passes (run `42838003635`).
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

- All 55 patches of the queue applied with `git am` onto `dddc3c0a79`; the rebased queue (53 patches after issue-62 was dropped) applies onto `rust-v0.167.0` (see "Rebase onto rust-v0.167.0").
- Rerun `host-loopback-mcp-fixtures.patch` and `guardian-local-capability-fixtures.patch` red/green with the MCP test servers built, then rerun the full queue's checks.

## Rebase onto rust-v0.167.0

Eighteen of the twenty patches apply with `git am -3` unchanged. `interrupt-host-cwd-fixtures.patch` conflicted in `codex-rs/core/tests/common/test_codex.rs`: upstream added `with_apps_tools_cache` where the patch adds `with_separate_host_cwd`, and the merged file keeps both. None of the patches is upstream at `rust-v0.167.0`: each reverse-applies nowhere and applies to a non-empty change. Upstream already contains the old-main patches 53897, 55693 and 0079, which stay omitted, and 0077 is obsolete.

## Rebase onto rust-v0.171.0

- `conpty-lifecycle-input-fixture.patch` is dropped: upstream #60872 made the same fix.
- `conpty-interrupt-readiness-fixture.patch` is dropped: upstream #59369 rewrote the fixture around a Python child that resets its own Ctrl+C disposition and waits for `READY` and then the returning `PS ` prompt, so the patch's prompt detection has nothing to key on.
- `windows-capture-reader-drain.patch`: upstream replaced the PowerShell capture test with a native re-exec fixture. The patch moves its checks into that fixture's capture role: stdout and stderr longer than one read buffer, matched with `contains` because libtest also writes to them, and no timeout. The capture role keeps its pipes inheritable so that the descendant holds them.
- `legacy-powershell-environment.patch`: upstream runs the normal-capture and ConPTY lifecycle tests as native fixtures, so the patch keeps `powershell_env` only for the three remaining PowerShell fixtures and no longer adds `publish_pid_command`.
- `local-stdio-mcp-fixtures.patch`: the Guardian elicitation fixture keeps upstream's `GuardianNestedApprovalContext` switch and registers its servers with upstream's `set_host_stdio_servers`.
- `host-loopback-mcp-fixtures.patch`: the hook fixture uses upstream's `configure_hook_mcp` helper with `DEFAULT_MCP_SERVER_ENVIRONMENT_ID` instead of inline JSON.
- The red/green runs above are on older bases. No native build or test has run on the rebased queue.

## Owner-approved additions (2026-11-25)

The owner approved migrating three more old-main test fixes in the per-item review of 2026-11-25. Each fails in the recorded Windows or macOS Bazel baseline at `rust-v0.167.0` (`docs/test-baselines/086ccc833b.../*.json`; no `rust-v0.171.0` baseline exists). None has run on a native runner at `rust-v0.171.0`.

- Applies to: the queue through `rmcp-stdio/close-after-oversized-line.patch` (entry 104), on upstream `openai/codex` tag `rust-v0.171.0`, commit `ccf9be8cc458bd6b8336fe84d68d8893f2691a5e`. The patches apply in the order listed and do not depend on each other.
- `windows-test-executable-extensions.patch`: forwards `PATHEXT` into Bazel's Windows test environment (`.bazelrc`) and adds a diagnostic message to the `git.environment` assertion of `doctor_path_safety`. The baseline fails `cli-doctor_path_safety-test` on Windows Bazel (gnullvm shard 4, msvc shard 1). Verify by re-running that target on Windows Bazel (MSVC and gnullvm).
- `gov-cloud-quit-closed-pty.patch`: `gov_cloud_startup_persists_only_acknowledgment` accepts a quit whose repeated input reaches a PTY that already closed (EIO), only after an earlier input succeeded and the child exits successfully within the original deadline. Other write errors and unsuccessful exits still fail. The baseline fails this test on macOS x86 Bazel. Verify by re-running `tui` `gov_cloud_guidance_tests` on Intel macOS Bazel.
- `windows-profile-unload-polling.patch`: the profile fixture keeps polling while the profile hive is marked deleted (`ERROR_KEY_DELETED`) instead of failing; the deadline and every other error still fail. The `use` lines are re-based on upstream's imports. The baseline fails `profile_cleanup_preserves_account_until_profile_can_be_deleted` on Windows Bazel. Verify by re-running that test on Windows Bazel.
