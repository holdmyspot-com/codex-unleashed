# Deferred implicit skill index

- Issue: [openai/codex#34776](https://github.com/openai/codex/issues/34776)
- Applies to: upstream `openai/codex` main, commit `dddc3c0a79528c4de8d9a20d51dd3bd89b02a3e4`.
- Prerequisites: none.

## Intent

Startup and resume build the implicit skill index for every loaded skill outcome, canonicalizing each skill's document and scripts paths, even when no command ever triggers implicit skill detection. The patch builds the index on its first lookup and shares it between unchanged clones of the outcome. Changing the disabled skill paths starts a new index. Map keys stay `PathUri` values of the canonical host paths, so lookups match what they matched before. The index now reflects the filesystem at the first lookup instead of at load time, which `implicit_skill_index_resolves_aliases_on_first_use` checks on Unix.

## Verification

From the patched upstream checkout root:

```sh
cd codex-rs && cargo nextest run -p codex-skills-extension
just fmt-check
```
