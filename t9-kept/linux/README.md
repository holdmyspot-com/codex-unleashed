# T9 Linux phase 2 kept patches (red/green in CI, repo holdmyspot-com/codex-unleashed, ref t9-linux)
Run IDs are GitHub Actions run IDs. Red = tests restored to pre-fix, fails; green = with patch, passes.
Remote-env runs used the scratch-branch remote_env input (Docker remote test env, confirmed set up in log).

- close-remote-client-owner: closes the peer websocket when the owner drops with a retained request handle (event_tx.closed() arm).
  Failure: dropping_remote_client_closes_peer_with_retained_request_handle, "peer should observe client closure: Elapsed". red 42841247651 (fails), green same run (passes).
- host-hook-cwd: hook cwd uses the host turn config cwd when only a remote environment is selected.
  Failure: hook_cwd_stays_on_host_when_only_a_remote_environment_is_selected, cwd == core instead of core/host-workspace. red/green 42842159760 (verified as test+visibility commit, then fix commit; this file is the squash).
- ui-plugin-startup-fixtures (0089): test_config disables Feature::Plugins. Red fails only the new regression generic_fixture_keeps_plugin_startup_opt_in; green 1082 pass. Run 42838827152.
- 0083 MCP local stdio fixtures: guardian_mcp_elicitation, mcp_subagent_elicitation, mcp_user_verification fail red, pass green. Run 42839938136 (remote_env).
- 0084 remote shell fixtures: deprecation_notice and remote_env exec_command/request_permissions fail red, pass green. Run 42839938136. Three other remote_env tests still fail green (unrelated wiremock/yield failures).
- 0085 trusted plugin executor fixture: turn_start command_execution_notifications_include_trusted_plugin_id fails red, passes green. Run 42839989982.
- 0088 feedback thread cwd: feedback_processor doctor_uses_loaded_feedback_thread_cwd fails red, passes green. Run 42839989982.
- 0086 interrupt-hook host cwd fixtures: four interrupt_hooks tests fail red, pass green. Run 42839962999 (remote_env).
- 0087 host loopback MCP fixtures: hooks::local_bundled_cleanup_hook_runs_without_saved_trust fails red, passes green. hooks_executor tests fail in both phases (node_repl/MCP missing on runner), so that half is unproven. Run 42839962999.
- 0094 guardian local capability fixtures: guardian_review session reuse and guardian_review_cancellation test_case_1 fail red, pass green; other guardian cases fail in both (node_repl missing). Run 42839962999.
