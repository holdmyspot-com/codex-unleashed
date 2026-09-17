# Background-terminal transcript hint

- Issue: [holdmyspot-com/codex-unleashed#3](https://github.com/holdmyspot-com/codex-unleashed/issues/3)
- Applies to: upstream `openai/codex` `rust-v0.155.0`
- Related upstream requests: [openai/codex#13858](https://github.com/openai/codex/issues/13858), [openai/codex#14928](https://github.com/openai/codex/issues/14928), [openai/codex#16935](https://github.com/openai/codex/issues/16935)

## Intent

Tell users when the complete command and output for a background terminal become available, and how to inspect them with `Ctrl+T`.

## Change

While a terminal is running, add this dimmed hint below the `Background terminals` heading:

```text
(transcript will be available after terminal completes)
```

After the background terminal completes, add this hint to its command history cell:

```text
(ctrl + t to view transcript)
```

## Regression test

The `ps_output_explains_how_to_view_full_transcript` history-cell test checks the running-state availability message. The `completed_background_terminal_includes_transcript_hint` exec-cell test checks the post-completion message. The existing `/ps` rendering snapshots are updated for the running-state hint.

Run the focused test with:

```text
RUSTUP_TOOLCHAIN=stable cargo test -p codex-tui ps_output_ --lib
```

Validation completed successfully: 8 focused tests passed.

## Upstream status

This is a Codex Unleashed patch for issue #3. Remove it when equivalent upstream guidance ships.
