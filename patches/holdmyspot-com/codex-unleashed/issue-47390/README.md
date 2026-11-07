# Expanded diff previews in the fullscreen transcript

- Issue: [openai/codex#47390](https://github.com/openai/codex/issues/47390)
- Applies to: upstream `openai/codex` main, commit `dddc3c0a79528c4de8d9a20d51dd3bd89b02a3e4`.
- Prerequisite: [base-configuration/codex-unleashed-configuration.patch](../base-configuration/codex-unleashed-configuration.patch).

## Intent

The rich fullscreen transcript collapses each patch to a summary that the reader must expand by hand. With `codex_unleashed.expanded_diff_previews = true` and the `unleashed_expanded_diff_previews` feature enabled, patch cells show their full diff instead. The setting defaults to `false` and the feature to enabled, so both must allow it. Failed-apply diagnostics keep their existing disclosure, and detailed and raw modes are unchanged. `thread-manager-sample` builds a complete `Config`, so it sets the new field to `false`.

## Verification

From the patched upstream checkout root:

```sh
cd codex-rs && cargo nextest run -p codex-config -p codex-core -p codex-tui \
  -E 'test(/codex_unleashed|expanded_diff_previews|config_schema_matches_fixture|transcript_view::layout/)'
just fmt-check
```
