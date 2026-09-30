# Collapsed tool activity

- Applies to: upstream `openai/codex` `rust-v0.159.2`, commit `ff6aec96948b70d94983af2641a6b67c94faeff5`

## Intent

Group adjacent file reads and shell commands into a compact activity section.
During execution, show their counts, elapsed time, and a shortened preview of
the running command. Finished activity shows a short summary. Failed commands
remain identified, and the complete commands and retained output remain
available when expanded.

In the full-screen chat view, hovering over a collapsed section changes its
text from dark gray to light gray. Clicking the section expands it with a
light gray background. Clicking anywhere in that background collapses it.
Turn completion collapses the turn's sections, which can be reopened.

Visible assistant messages separate activity sections. User-entered shell
commands and other tool types retain their existing presentation. Mouse
interaction requires a terminal that supports mouse reporting and the
full-screen chat view. Terminal scrollback in inline mode does not support
these interactions; Ctrl+T provides the complete transcript.

## Dependencies and application order

Apply the [shared configuration patch README.md](../base-configuration/README.md)
before this patch. This feature does not require other feature patches.

## Feature configuration

### Codex feature flag

The feature flag is stable and enabled by default:

```text
unleashed_collapsed_tool_activity  stable  true
```

Enable or disable the Codex feature flag with:

```text
codex features enable unleashed_collapsed_tool_activity
codex features disable unleashed_collapsed_tool_activity
```

If setting the flag in `config.toml` directly, put it under `[features]`:

```toml
[features]
unleashed_collapsed_tool_activity = true
```

### Project-specific `config.toml` property

Set the property under `[codex_unleashed]` in `~/.codex/config.toml` or the
project's `.codex/config.toml`:

```toml
[codex_unleashed]
collapsed_tool_activity = true
```

The property defaults to `false`, independently of the enabled feature flag.
Activity retains upstream's presentation until this property is `true`.
Grouping requires both the property and the feature flag. With the feature
flag disabled, the property has no effect and produces no unknown-key warning.
Undeclared fields in `[codex_unleashed]` retain the normal unknown-key warning.

Full-screen mode uses the upstream setting under `[tui]`:

```toml
[tui]
fullscreen_transcript = true
```

## Reproduction

Enable `collapsed_tool_activity` under `[codex_unleashed]`. Ask Codex to read
a file, run several short shell commands, and run a longer command that
produces output. Hover over and click its activity summary.
Click an output row or its background to collapse it. Expand the section while
the turn runs, then verify that turn completion collapses it and that clicking
the completed summary reopens its retained output.

## Verification

Run the focused checks from `codex-rs/`:

```sh
export RUST_MIN_STACK=16777216
cargo test -p codex-features --lib unleashed_collapsed_tool_activity
cargo test -p codex-core --lib project_codex_unleashed_collapsed_tool_activity_is_silent_when_feature_disabled
cargo test -p codex-core --lib strict_config_rejects_unknown_codex_unleashed_settings
cargo test -p codex-config-schema --test codex_unleashed
cargo test -p codex-tui --lib unleashed_collapsed_tool_activity
```

The checks cover the default off state, explicit project opt-in, disabled
feature flags, normal unknown-key handling, live shell notifications, elapsed-time
updates, narrow failure summaries, mouse hover and clicks, turn completion,
resumed history, and retained output. Each check expects all selected tests
to pass. The schema check runs on the complete patch queue and verifies that the
published configuration schema matches the generated schema.

## Formatting

Run from `codex-rs/`:

```sh
cargo fmt --package codex-features --package codex-config --package codex-core --package codex-tui -- --check
```

## Upstream status

This is a downstream presentation feature. The patch uses upstream's
full-screen transcript and tool-disclosure controls. Its base is
[upstream 0.159.2](https://github.com/openai/codex/releases/tag/rust-v0.159.2).
