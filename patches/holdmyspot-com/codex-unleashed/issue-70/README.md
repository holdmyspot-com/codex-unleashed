# Readable command previews

- Issue: [holdmyspot-com/codex-unleashed#70](https://github.com/holdmyspot-com/codex-unleashed/issues/70)
- Applies to: upstream `openai/codex` tag `rust-v0.167.0`, commit `086ccc833bc9cf01024ad2dd6af7dbd421b6bf9a`.
- Prerequisites: the release queue through `issue-35/0003-defer-goal-continuation-to-live-children.patch`. Its rendering hunks share entry points with `collapsed-tool-activity`, and its schema entry sits next to the other `unleashed_*` features.
- Upstream status: absent upstream.

## Intent

Keep executable filenames and arguments visible when commands contain long paths. Collapsed previews shorten directory prefixes only when a path exceeds the available width, wrap arguments, emphasize executable names and keep directory context where space permits. Shell wrappers and multiline scripts stay inspectable, and invalid shell syntax displays literally.

Clicking a command or its output preview expands the full command and retained output. The collapsed section advertises this action; hovering an expanded section explains that clicking anywhere in it collapses it. Dragging selects text without toggling the section. The existing keyboard disclosure controls apply: F4 selects activities, Up/Down changes the selection, Right/Left expands or collapses, Enter toggles and Escape leaves activity selection. Hints use the configured key bindings, and expansion and focus persist through streaming, completion and resize.

Expanded commands have no path abbreviations. The command-block copy control copies the original protocol source, including quotes, redirections and newlines. Output stays subject to the existing retention and omission limits. This presentation does not change execution.

## Feature configuration

### Codex feature flag

`unleashed_readable_commands` is stable and enabled by default:

```sh
codex features enable unleashed_readable_commands
codex features disable unleashed_readable_commands
```

The equivalent configuration is:

```toml
[features]
unleashed_readable_commands = true
```

Disabling the flag keeps the existing command presentation and disclosure.

### Project-specific `config.toml` property

None.

## Reproduction

In a narrow terminal, display a command whose executable sits under a deeply nested plugin cache, with a quoted output path and several arguments. The executable basename and arguments stay visible; expanding the section shows the full command, and copying it yields the original text. Repeat with a heredoc or shell wrapper and with a command whose output exceeds the collapsed preview.

## Verification

From the patched upstream checkout root:

```sh
cd codex-rs && cargo nextest run -p codex-tui -p codex-features -p codex-core -E 'test(readable_command_preview) | test(unleashed_readable_commands) | test(unleashed_collapsed_tool_activity) | test(config_schema_matches_fixture)'
just fmt-check
```

The focused checks cover terminal width, Unicode and quoted paths, arguments, wrapper and script previews, exact copying, mouse hints and drag selection, keyboard remapping, streaming transitions, protocol replay, flag opt-out, grouped historical commands alongside collapsed tool activity, and the configuration schema entry.
