# Responsive history replay and `/subagents` switching

- Issue: [openai/codex#34776](https://github.com/openai/codex/issues/34776)
- Applies to: upstream `openai/codex` `rust-v0.159.1`, commit `8e68a98ef03cdde76d2e6800791ebdf1b3b95b24`

## Intent

Keep session resumption responsive with many writable roots and make `/subagents` switch back to the main agent quickly when its history spans many compactions. Initial resumption renders a bounded recent history page; scrolling back retrieves older pages without deleting messages. The feature flag controls the compaction window used when switching agents.

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
under `[features]` as shown above. With the default flag enabled, agent switching replays the latest compaction window. Disabling it restores full-history replay for switches. Initial history pagination and the plain-message permission-check optimization apply independently of this flag.

## Reproduction

Resume a main session with several compaction windows, start a worker from
`/subagents`, then switch back to the main agent. The return switch should stay
responsive as older conversation windows accumulate. Also resume a session containing ordinary assistant messages under a restricted filesystem policy with many writable roots. Initial replay stays responsive and scrolling back reveals older messages.

## Verification

`switching_back_replays_only_latest_compacted_conversation` exercises the
`/subagents` return switch with fifty compaction windows, checks that switching
takes less than three seconds, and compares the result with a one-window
session. It also confirms that older history remains cached and that disabling
the feature restores full-history replay.

Run the focused replay, pagination, visualization, and switching checks from `codex-rs/`:

```sh
just test -p codex-tui -E 'test(replay_plain_assistant_messages_with_many_writable_roots_stays_responsive) | test(beginning_navigation_holds_the_view_until_the_last_page_arrives) | test(inline_visualization) | test(switching_back_replays_only_latest_compacted_conversation) | test(initial_resume_keeps_compacted_history_when_fast_switching_is_enabled)'
```

The checks expect replay of 32 plain assistant messages with 64 writable roots to take less than three seconds and retain every message. Pagination initially excludes older answers and retrieves them when navigating to the beginning. Visualization checks retain trusted links and reject unsafe fragments. Switching remains responsive and ordinary resumption retains loaded compaction history.

Run the TUI suite from `codex-rs/`:

```sh
just test -p codex-tui
```

The declared release-tag base contains snapshots expecting `v0.0.0`, while
its manifests report `v0.159.1`. Full-suite checks in this environment contain
73 baseline snapshot failures; an untouched checkout reproduces the same
failure list. The focused checks and formatting pass independently.

## Implementation

Completed and replayed plain markdown skips visualization-context construction. Messages containing visualization directives or content references use the existing filesystem permission and fragment validation. Live streams construct their context before later chunks arrive. Plain transcript pages also skip context construction. No filesystem permission result is cached across messages or configuration changes.

Upstream owns bounded initial history hydration and older-page cursors in this base; this patch preserves that path and extends its navigation regression test.

## Formatting

Run `just fmt-check` from the checkout root. The command checks Rust, Just, Bazel/Starlark, and Python formatting.

## Upstream status

This downstream patch applies to the declared upstream base above. The canonical issue tracks slow agent switching; the resume optimization addresses the same synchronous history replay path.
