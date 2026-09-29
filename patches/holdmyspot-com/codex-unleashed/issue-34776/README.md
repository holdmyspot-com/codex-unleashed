# Faster `/subagents` switching

- Issue: [openai/codex#34776](https://github.com/openai/codex/issues/34776)
- Applies to: upstream `openai/codex` `rust-v0.159.1`, commit `8e68a98ef03cdde76d2e6800791ebdf1b3b95b24`

## Intent

Make `/subagents` switch back to the main agent quickly when its history spans many compactions. The feature can be disabled to restore full-history replay.

## Feature configuration

### Codex feature flag

The feature flag is stable and enabled by default:

```text
unleashed_agent_fast_switching  stable  true
```

Enable or disable the Codex feature flag with:

```text
codex features enable unleashed_agent_fast_switching
codex features disable unleashed_agent_fast_switching
```

If setting the flag in `config.toml` directly, put it under `[features]`:

```toml
[features]
unleashed_agent_fast_switching = true
```

### Project-specific `config.toml` property

None. This patch adds no project-specific property. Its feature flag belongs
under `[features]` as shown above.

## Reproduction

Resume a main session with several compaction windows, start a worker from
`/subagents`, then switch back to the main agent. The return switch should stay
responsive as older conversation windows accumulate.

## Verification

`switching_back_replays_only_latest_compacted_conversation` exercises the
`/subagents` return switch with fifty compaction windows, checks that switching
takes less than three seconds, and compares the result with a one-window
session. It also confirms that older history remains cached and that disabling
the feature restores full-history replay.

Run the focused switch and resume checks from `codex-rs/`:

```sh
just test -p codex-tui -E 'test(switching_back_replays_only_latest_compacted_conversation) | test(initial_resume_keeps_compacted_history_when_fast_switching_is_enabled)'
```

The checks expect a responsive return switch and retained older history for
ordinary session resumption.

## Formatting

Run `python3 ../scripts/format.py --check` from `codex-rs/`.
