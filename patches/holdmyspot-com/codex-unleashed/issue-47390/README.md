# Expanded diff previews in the full-screen transcript

- Issue: [openai/codex#47390](https://github.com/openai/codex/issues/47390)
- Applies to: upstream `openai/codex` `rust-v0.159.0`, commit `687a119f0fcaace47e1f1abcc77cec6c813fd6da`

## Intent

Show complete diffs for patch activities in the full-screen transcript,
including reopened sessions, when both the feature and project setting are
enabled. Keep other activities and patch-failure messages compact.

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

### Project-specific `config.toml` property

Set this property in the project's `config.toml`:

```toml
unleashed_expanded_diff_previews = true
```

The property defaults to `false`, independently of the enabled feature flag,
so previews remain compact by default. With the feature flag disabled, Codex
silently ignores this property: it has no effect and produces no unknown-key
warning.

## Reproduction

Render a patch activity containing at least 40 added lines in the owned
full-screen transcript. With either setting disabled, the compact preview
hides the later lines. Enable both settings and render the same activity: the
complete patch appears while long patch-failure diagnostics remain compact.

## Regression test and TDD record

`expanded_diff_previews_are_configurable_for_owned_transcript` renders a patch
activity and a patch-failure activity with 40 lines each. It verifies that the
feature flag defaults to enabled, the project property defaults to disabled,
and the preview is compact by default. It also verifies that the feature flag
alone does not expand previews, both settings together expand patch lines
without expanding failure diagnostics, and disabling the feature makes a
`true` project setting inert.

`config_toml_preserves_unleashed_expanded_diff_previews` initially failed
because `ConfigToml` discarded the new root-level setting. It passed after the
field was registered. `runtime_config_resolves_unleashed_expanded_diff_previews`
initially loaded an explicit `true` as `false`; it passed after the setting was
copied into runtime `Config`. The project-loader test
`project_unleashed_expanded_diff_previews_is_silent_when_feature_disabled`
verifies that a trusted project can set the property while the feature is off,
the value survives project-layer loading, and startup warnings remain empty.

The TUI regression was first run before behavior was gated on both settings. It
failed at the flag-only assertion because enabling the feature flag expanded the
patch without the project setting. The same test passed after adding the
two-setting gate. For the default-on change, the TUI command below ran on the
original `rust-v0.158.0` base with this patch applied but `default_enabled: false`; it
exited 101 at the new feature-default assertion. After changing the patch to
default the feature flag to `true`, the same command ran on the patched tree. It
passed (1 passed, 0 failed) and verified that the project property still
defaults to `false` and the default preview remains compact.

Run these focused tests from `codex-rs/` on the declared upstream base:

```sh
just test -p codex-config -E 'test(config_toml_preserves_unleashed_expanded_diff_previews)'
just test -p codex-core -E 'test(runtime_config_resolves_unleashed_expanded_diff_previews)'
just test -p codex-core -E 'test(project_unleashed_expanded_diff_previews_is_silent_when_feature_disabled)'
just test -p codex-tui -E 'test(expanded_diff_previews_are_configurable_for_owned_transcript)'
```

The schema test failed before registration because the serialized property was
`null` (exit status 101), then passed with local Rust 1.98. The runtime mapping
test failed before mapping because the explicit `true` resolved to `false`
(exit status 101), then passed with local Rust 1.98. The project-loader test
passed with local Rust 1.98 and found no startup warnings while the feature was
disabled. The TUI test failed before the behavior gate at the flag-only
assertion (exit status 101), then passed with the upstream-pinned Rust 1.95.
Local Rust 1.98 failed earlier in unchanged upstream `codex-chatgpt` code on the
same TUI target, so the pin was used for that check only.

The patched v0.158.0 tree was checked with this combined focused run:

```sh
just test -p codex-config -p codex-core -p codex-tui -E 'test(config_toml_preserves_unleashed_expanded_diff_previews) | test(runtime_config_resolves_unleashed_expanded_diff_previews) | test(project_unleashed_expanded_diff_previews_is_silent_when_feature_disabled) | test(expanded_diff_previews_are_configurable_for_owned_transcript)'
```

Results: all 4 focused tests passed.

On `rust-v0.159.0`, the focused
`expanded_diff_previews_are_configurable_for_owned_transcript` test passed with
Rust 1.95.0, and the full patch queue applied cleanly to the declared base.

## Formatting

`python3 ../scripts/format.py --check` passed from `codex-rs/` with local
Rust 1.98.0 and DotSlash 0.5.7.
