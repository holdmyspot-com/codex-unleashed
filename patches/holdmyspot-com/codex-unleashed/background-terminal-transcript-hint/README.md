# Background-terminal transcript hint

- Issue: [holdmyspot-com/codex-unleashed#3](https://github.com/holdmyspot-com/codex-unleashed/issues/3)
- Applies to: upstream `openai/codex` `rust-v0.159.0` at commit `687a119f0fcaace47e1f1abcc77cec6c813fd6da`
- Related upstream requests: [openai/codex#13858](https://github.com/openai/codex/issues/13858), [openai/codex#14928](https://github.com/openai/codex/issues/14928), [openai/codex#16935](https://github.com/openai/codex/issues/16935)

## Intent

Show users how to view a background terminal's full transcript after it completes. While background terminals are running, `/ps` explains when the transcript will be available; completed terminal entries show the `Ctrl+T` shortcut.

## Feature configuration

### Codex feature flag

None. This behavior is always available and has no Codex feature flag.

### Project-specific `config.toml` property

None.

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

## Regression test and TDD record

The `ps_output_explains_when_running_transcript_is_available` test checks the running-state hint, and `ps_output_empty_snapshot` checks that it is omitted when no terminal is running. `completed_background_terminal_includes_transcript_hint` checks the completed terminal cell, while `replayed_command_completion_preserves_tracking_and_transcript_hint` checks replayed command history.

On the original `rust-v0.158.0` base, these focused tests failed at the expected assertions because the corresponding hints were absent:

```sh
just test -p codex-tui -E 'test(ps_output_explains_when_running_transcript_is_available) | test(completed_background_terminal_includes_transcript_hint) | test(replayed_command_completion_preserves_tracking_and_transcript_hint)'
```

With the patch applied, the focused suites passed:

```sh
INSTA_UPDATE=always just test -p codex-tui -E 'test(ps_output_) | test(transcript_hint)'
```

Results: 12 tests passed. Run the commands from `codex-rs/` with upstream's pinned Rust 1.95.0 toolchain.

## Formatting

`python3 ../scripts/format.py --check` passed from `codex-rs/` with local
Rust 1.98.0 and DotSlash 0.5.7.
