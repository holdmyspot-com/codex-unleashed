# Ctrl+C during MCP startup

- Applies to: upstream `openai/codex` tag `rust-v0.167.0`, commit `086ccc833bc9cf01024ad2dd6af7dbd421b6bf9a`.
- Prerequisites: the rest of the queue up to and including `flaky-tests/startup-probe-da1-last.patch`.

## Intent

Make Ctrl+C behave the same whether or not the TUI's own tools server is still starting, and show users when their MCP servers are starting.

The TUI adds its own dynamic-tools MCP server, `codex_tui`, to every thread, and no configuration turns it off. Upstream treats any MCP startup as a running task. So Ctrl+C on an otherwise idle TUI interrupted the `codex_tui` startup instead of quitting, and the result depended on how fast that server started. Upstream also hid the status indicator while MCP servers started, so nothing showed why Ctrl+C did not quit.

With this patch:

- While only `codex_tui` is starting, the TUI is idle: Ctrl+C quits and no status indicator is shown.
- While a user-configured MCP server is starting, Ctrl+C interrupts the startup, as before, and the status indicator shows `Booting MCP server: <name>`. The header and the server counts leave out `codex_tui`.

This also makes the upstream end-to-end test `default_owned_screen_entry_paints_before_sync_ends_and_exit_clears_inline_draft` deterministic: it sent Ctrl+C while `codex_tui` could still be starting.

Remaining behavior: when a user server and `codex_tui` start together, Ctrl+C interrupts the startup, and the core cancels the startup of every server, including `codex_tui`. That cancellation happens in the core, not the TUI.

## Feature configuration

### Codex feature flag

None. This behavior is always on and has no Codex feature flag.

### Project-specific `config.toml` property

None. This patch adds no `config.toml` property or section.

## Reproduction

Without the patch, start the TUI and press Ctrl+C as soon as the composer appears. If `codex_tui` has not finished starting, the TUI shows `MCP startup interrupted. The following servers were not initialized: codex_tui` and keeps running.

With the patch, the same Ctrl+C quits. With a slow user-configured MCP server `alpha`, the status indicator shows `Booting MCP server: alpha` until `alpha` is ready, and Ctrl+C interrupts the startup.

## Verification

From the patched upstream checkout root:

```sh
cd codex-rs && cargo nextest run -p codex-tui -E 'test(tui_tools_server_startup_leaves_ctrl_c_to_quit) | test(ctrl_c_interrupts_user_mcp_server_startup) | test(mcp_startup_status_leaves_out_the_tui_tools_server) | test(mcp_startup_header_booting_snapshot) | test(default_owned_screen_entry_paints_before_sync_ends_and_exit_clears_inline_draft)'
just fmt-check
```

The checks expect Ctrl+C to quit while only `codex_tui` starts, Ctrl+C to interrupt while a user server starts, the status indicator to name only user servers, and the end-to-end Ctrl+C exit to pass.

Evidence on `holdmyspot-com/codex-unleashed`, Linux nextest, `codex-tui`, on the queue through `flaky-tests/startup-probe-da1-last.patch`:

- Without the fix, `43336705606` failed `tui_tools_server_startup_leaves_ctrl_c_to_quit`, `mcp_startup_status_leaves_out_the_tui_tools_server` and `mcp_startup_header_booting_snapshot`. `ctrl_c_interrupts_user_mcp_server_startup` passed, because it guards behavior upstream already has.
- Without the fix, the 30-iteration loaded run `43300544442` of `default_owned_screen_entry_paints_before_sync_ends_and_exit_clears_inline_draft` failed in iteration 5: the log shows `codex_tui` still `Starting` when Ctrl+C arrived, then `MCP startup interrupted`. With the fix, `43336783607` passed 30 of 30.
