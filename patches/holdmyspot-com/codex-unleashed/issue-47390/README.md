# Expanded diff previews in the full-screen transcript

- Issue: [openai/codex#47390](https://github.com/openai/codex/issues/47390)
- Applies to: upstream `openai/codex` `rust-v0.161.0`, commit `979011409de0a60b52f179721948e65531d26144`

## Intent

Show complete diffs for patch activities in the full-screen transcript,
including reopened sessions, when both the feature and project setting are
enabled. Keep other activities and patch-failure messages compact.

## Dependencies and application order

Apply the [shared configuration patch README.md](../base-configuration/README.md)
before this patch. This feature does not require other feature patches.

## Feature configuration

### Codex feature flag

The feature flag is stable and enabled by default:

```text
unleashed_expanded_diff_previews  stable  true
```

Enable or disable the Codex feature flag with:

```text
codex features enable unleashed_expanded_diff_previews
codex features disable unleashed_expanded_diff_previews
```

If setting the flag in `config.toml` directly, put it under `[features]`:

```toml
[features]
unleashed_expanded_diff_previews = true
```

### Project-specific `config.toml` property

Set this property under `[codex_unleashed]` in the project's
`.codex/config.toml`:

```toml
[codex_unleashed]
expanded_diff_previews = true
```

The `[features]` flag and `[codex_unleashed]` project setting are separate.
To set both explicitly in one file, use:

```toml
[codex_unleashed]
expanded_diff_previews = true

[features]
unleashed_expanded_diff_previews = true
```

The property defaults to `false`, independently of the enabled feature flag,
so previews remain compact by default. With the feature flag disabled, Codex
silently ignores this property: it has no effect and produces no unknown-key
warning. Other keys in `[codex_unleashed]` produce the normal unknown-key
warning unless a feature declares them.

## Reproduction

Render a patch activity containing at least 40 added lines in the owned
full-screen transcript. With either setting disabled, the compact preview
hides the later lines. Enable both settings and render the same activity: the
complete patch appears while long patch-failure diagnostics remain compact.

## Verification

Run the focused configuration checks from the patched upstream checkout root:

```sh
just test -p codex-config --lib -E 'test(config_toml_preserves_codex_unleashed_expanded_diff_previews)'
just test -p codex-core --lib -E 'test(codex_unleashed_expanded_diff_previews) | test(strict_config_rejects_unknown_codex_unleashed_settings)'
just test -p codex-thread-manager-sample
```

The checks expect the setting to survive TOML parsing, resolve to the requested
runtime value, load from a trusted project without a warning when the feature
is disabled, and reject unknown keys in strict mode.
The runtime and project checks cover absent, `true`, and `false` values.
The sample check verifies that its direct configuration initialization builds
with the project's default setting.

On the complete patch queue, run `just test -p codex-core --lib -E
'test(config_schema_matches_fixture)'` to verify the shared published schema.

Run the focused TUI check from the patched upstream checkout root:

```sh
just test -p codex-tui -E 'test(expanded_diff_previews_are_configurable_for_owned_transcript)'
```

It expects patch diffs to expand only when both settings are enabled. It also
expects patch-failure diagnostics to stay compact.

## Formatting

Run `just fmt-check` from the patched upstream checkout root. All upstream
formatter groups must pass.
