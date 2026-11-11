# Background-terminal transcript hint

- Issue: [holdmyspot-com/codex-unleashed#3](https://github.com/holdmyspot-com/codex-unleashed/issues/3)
- Applies to: upstream `openai/codex` tag `rust-v0.167.0`, commit `086ccc833bc9cf01024ad2dd6af7dbd421b6bf9a`.
- Prerequisites: none. It also applies after the rest of the queue.
- Related upstream requests: [openai/codex#13858](https://github.com/openai/codex/issues/13858), [openai/codex#14928](https://github.com/openai/codex/issues/14928), [openai/codex#16935](https://github.com/openai/codex/issues/16935)

## Intent

Show users how to view a background terminal's full transcript after it completes. While background terminals are running, `/ps` explains when the transcript will be available; completed terminal entries show the `Ctrl+T` shortcut.

## Feature configuration

### Codex feature flag

None. This behavior is always available and has no Codex feature flag.

### Project-specific `config.toml` property

None. This patch adds no `config.toml` property or section.

## Reproduction

While at least one background terminal is running, show this dimmed hint under the `/ps` heading:

```text
(transcript will be available after terminal completes)
```

After a background terminal completes, show this hint in its command history cell:

```text
(ctrl+t to view transcript)
```

An empty `/ps` result does not show the running-terminal hint.

## Verification

From the patched upstream checkout root:

```sh
cd codex-rs && cargo nextest run -p codex-tui -E 'test(ps_output_) | test(completed_background_terminal_includes_transcript_hint) | test(replayed_command_completion_preserves_tracking_and_transcript_hint) | test(adjacent_exploration_groups_across_reasoning_live_and_replayed) | test(unified_exec_unknown_end_with_active_exploring_cell_snapshot)'
just fmt-check
```

The checks expect the running hint only while a background terminal is active, no hint for an empty `/ps`, and the `Ctrl+T` hint on completed entries, including replayed ones and those next to exploration groups.
