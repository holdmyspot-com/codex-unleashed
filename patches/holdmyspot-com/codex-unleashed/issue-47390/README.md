# Expanded diff previews in the full-screen transcript

- Upstream issue: [openai/codex#47390](https://github.com/openai/codex/issues/47390)
- Applies to: upstream `openai/codex` `rust-v0.157.1`, commit `36650394c5b38c2990ccf2a3457165ca3e9d9726`

## Intent

Add the `unleashed_expanded_diff_previews` feature and its project-specific
configuration property. Patch activities in the rich, owned full-screen
transcript show their complete diff only when both settings are enabled. Other
activity types and patch-failure diagnostics keep their compact presentation.
This preserves rich transcript rendering and applies to live and restored patch
history.

## Configuration

Enable or disable the Codex feature flag with:

```text
codex features enable unleashed_expanded_diff_previews
codex features disable unleashed_expanded_diff_previews
```

Set the project-specific property in that project's `config.toml`:

```toml
unleashed_expanded_diff_previews = true
```

The property defaults to `false`. With the feature flag disabled, the property
is silently ignored: it has no effect and produces no unknown-key warning.

## Reproduction

Render a patch activity containing at least 40 added lines in the owned
full-screen transcript. With either setting disabled, the compact preview
hides the later lines. Enable both settings and render the same activity: the
complete patch appears while long patch-failure diagnostics remain compact.

## Regression test and TDD record

`expanded_diff_previews_are_configurable_for_owned_transcript` renders a patch
activity and a patch-failure activity with 40 lines each. It verifies that the
default is compact, the feature flag alone does not expand previews, both
settings together expand patch lines without expanding failure diagnostics, and
disabling the feature makes a `true` project setting inert.

`config_toml_preserves_unleashed_expanded_diff_previews` initially failed
because `ConfigToml` discarded the new root-level setting. It passed after the
field was registered. `runtime_config_resolves_unleashed_expanded_diff_previews`
initially loaded an explicit `true` as `false`; it passed after the setting was
copied into runtime `Config`. The project-loader test
`project_unleashed_expanded_diff_previews_is_silent_when_feature_disabled`
verifies that a trusted project can set the property while the feature is off,
the value survives project-layer loading, and startup warnings remain empty.

The TUI regression was run before behavior was gated on both settings. It failed
at the flag-only assertion because enabling the feature flag expanded the patch
without the project setting. The same test passed after adding the two-setting
gate.

Run from `codex-rs`:

```text
RUSTUP_HOME=/home/gili/.cache/codex-unleashed-rustup RUST_MIN_STACK=8388608 cargo +1.95.0 test -p codex-config config_toml_preserves_unleashed_expanded_diff_previews --lib
RUST_MIN_STACK=8388608 cargo +stable test -p codex-config config_toml_preserves_unleashed_expanded_diff_previews --lib
RUST_MIN_STACK=8388608 cargo +stable test -p codex-core runtime_config_resolves_unleashed_expanded_diff_previews --lib
RUST_MIN_STACK=8388608 cargo +stable test -p codex-core project_unleashed_expanded_diff_previews_is_silent_when_feature_disabled --lib
RUSTUP_HOME=/home/gili/.cache/codex-unleashed-rustup RUST_MIN_STACK=8388608 cargo +1.95.0 test -p codex-tui expanded_diff_previews_are_configurable_for_owned_transcript --lib
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

## Formatting

The changed Rust files pass `rustfmt --edition 2024 --check` with local Rust
1.98 rustfmt. It requested wrapping changes only on lines introduced by this
patch; those lines were formatted with the local version.
