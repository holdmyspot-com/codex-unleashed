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

## Background skill warmup on resume

[background-session-skill-warmup.patch](background-session-skill-warmup.patch)

### Intent

Session startup, resume and fork wait for plugin and skill discovery before the conversation accepts input. The patch moves that discovery into a background task the session owns, so a restored conversation is available while it runs. The first execution context waits for the warmup before its normal discovery, and history restoration no longer scans skills. Cancelling a waiting request leaves the warmup for the next one, a failed warmup still lets execution discover skills, and dropping the session aborts unfinished work. Plugin setup that hooks and MCP need stays synchronous.

A cold client resume registers skill watches after the resume response and its restored-state notifications, then invalidates cached metadata and sends a refresh notification so changes made before the watches were ready reach the client. Only the active listener generation can install those watches; a closed or replaced listener discards them. New sessions and forks keep registering watches before their responses.

### Verification

From the patched upstream checkout root:

```sh
cd codex-rs && cargo nextest run -p codex-core -p codex-app-server -E 'test(resume_warmup_tests) | test(late_skill_watches_) | test(cold_resume_refreshes_skill_metadata_after_watches_are_ready) | test(production_turn_warns_and_omits_unreadable_host_skill)'
just fmt-check
```
