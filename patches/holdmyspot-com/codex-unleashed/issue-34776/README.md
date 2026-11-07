# Responsive startup skill loading

- Issue: [openai/codex#34776](https://github.com/openai/codex/issues/34776)
- Each patch applies on its own to upstream `openai/codex` main, commit `dddc3c0a79528c4de8d9a20d51dd3bd89b02a3e4`.
- Prerequisites: none.

## Deferred implicit skill index

[defer-implicit-skill-index.patch](defer-implicit-skill-index.patch)

### Intent

Startup and resume build the implicit skill index for every loaded skill outcome, canonicalizing each skill's document and scripts paths, even when no command ever triggers implicit skill detection. The patch builds the index on its first lookup and shares it between unchanged clones of the outcome. Changing the disabled skill paths starts a new index. Map keys stay `PathUri` values of the canonical host paths, so lookups match what they matched before. The index now reflects the filesystem at the first lookup instead of at load time, which `implicit_skill_index_resolves_aliases_on_first_use` checks on Unix.

### Verification

From the patched upstream checkout root:

```sh
cd codex-rs && cargo nextest run -p codex-skills-extension
just fmt-check
```

## Single startup skills refresh

[avoid-duplicate-startup-skills-refresh.patch](avoid-duplicate-startup-skills-refresh.patch)

### Intent

The TUI starts a background skills refresh at startup, then the first session configuration for the same directory issues a second, blocking request for the same metadata. The patch records the startup refresh's directory so that first configuration reuses it. Later configurations, other directories and explicit user refreshes still request fresh metadata, and a failed background refresh still reaches the normal error display. Skill mentions work whether the metadata arrives before or after the session configuration.

### Verification

From the patched upstream checkout root:

```sh
cd codex-rs && cargo nextest run -p codex-tui -E 'test(startup_skills_refresh_)'
just fmt-check
```
