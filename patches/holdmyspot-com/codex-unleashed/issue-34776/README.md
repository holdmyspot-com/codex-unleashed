# Faster `/subagents` switching

- Issue: [openai/codex#34776](https://github.com/openai/codex/issues/34776)
- Applies to: upstream `openai/codex` `rust-v0.159.0`, commit `687a119f0fcaace47e1f1abcc77cec6c813fd6da`
- Related upstream work: [openai/codex#36948](https://github.com/openai/codex/pull/36948), [openai/codex#36950](https://github.com/openai/codex/pull/36950)

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

### Project-specific `config.toml` property

None.

## Reproduction

Resume a main session with several compaction windows, start a worker from
`/subagents`, then switch back to the main agent. The return switch should stay
responsive as older conversation windows accumulate.

## Regression test and TDD record

`switching_back_replays_only_latest_compacted_conversation` exercises the
`/subagents` return switch with fifty compaction windows, checks that switching
takes less than three seconds, and compares the result with a one-window
session. It also confirms that older history remains cached and that disabling
the feature restores full-history replay.

On the original `rust-v0.158.0` base, the test failed because the returned transcript
still contained `conversation-0` from before the latest compaction. With the
patch, the switch and resume regression tests passed; the switch test also
confirmed older history remained cached and ordinary session resumption still
replayed it.

```sh
CONTEXT_WINDOW_TOKENS=27_200 just test -p codex-tui -E 'test(switching_back_replays_only_latest_compacted_conversation)'
```

The focused patched run used:

```sh
just test -p codex-tui -E 'test(switching_back_replays_only_latest_compacted_conversation) | test(initial_resume_keeps_compacted_history_when_fast_switching_is_enabled)'
```

Results: 2 tests passed. They used upstream's Rust 1.95.0 pin because local
Rust 1.98 previously failed in unchanged `codex-chatgpt` code on the TUI test
target. Run the commands from `codex-rs/`.

## Formatting

`python3 ../scripts/format.py --check` passed from `codex-rs/` with local
Rust 1.98.0 and DotSlash 0.5.7.
