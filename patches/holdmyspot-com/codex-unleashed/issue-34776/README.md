# Avoid slow agent switching and resume

- Upstream issue: [openai/codex#34776](https://github.com/openai/codex/issues/34776)
- Applies to: upstream `openai/codex` `848b3845884e3aaf3359867047751dfff12dc448`
- Related upstream work: [openai/codex#36948](https://github.com/openai/codex/pull/36948), [openai/codex#36950](https://github.com/openai/codex/pull/36950)

## Intent

Make `/subagents` switch faster when the main agent has a long history spanning
many compactions.

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

The slow path can be exercised without a multi-day session: resume a populated
main session whose history contains repeated compaction windows, use
`/subagents` to switch to a child, then use `/subagents` again to switch back
to the main agent.
The regression fixture gives every window an approximately 272K-token payload.
Rebuilding the chat widget now replays only the conversation after the latest
compaction, so switching time does not grow with the number of older windows.

## Regression test

`switching_back_replays_only_latest_compacted_conversation` in
`codex-rs/tui/src/app/tests/session_lifecycle_requests.rs` exercises the
user-visible flow: it switches from the main thread to a worker and back while
the main thread contains fifty synthetic compaction windows, each with an
approximately 272K-token payload. It measures the return switch and verifies
that only the current conversation is rendered. It runs the flow with one and
fifty windows and requires the timings to remain comparable after the fix.

`replayed_compacted_history_contains_only_latest_conversation` in
`codex-rs/tui/src/chatwidget/tests/history_replay.rs` provides focused coverage
of the replay boundary with the same fifty-window scenario.

Run the tests from `codex-rs` with:

```text
cargo +stable test -p codex-tui switching_back_replays_only_latest_compacted_conversation --no-fail-fast
cargo +stable test -p codex-tui replayed_compacted_history_contains_only_latest_conversation --no-fail-fast
```

The end-to-end test enforces the three-second switch limit and checks that
timing remains comparable for one and fifty windows. The focused test verifies
that earlier conversation windows are not rendered.

## Validation

The change was verified with `git apply --check` on a clean checkout of the
upstream `main` revision.
