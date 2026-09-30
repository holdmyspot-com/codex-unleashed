# Codex Unleashed configuration section

- Applies to: upstream `openai/codex` `rust-v0.159.2`, commit `ff6aec96948b70d94983af2641a6b67c94faeff5`

## Intent

Place project settings for Codex Unleashed features in one
`[codex_unleashed]` section. Undeclared settings retain Codex's normal
unknown-key warnings.

## Feature configuration

### Codex feature flag

None. This prerequisite adds no feature flag or feature behavior.

### Project-specific `config.toml` property

This prerequisite declares the `[codex_unleashed]` table. Each feature patch
registers its own properties, defaults, and feature controls:

- [Expanded diff previews README.md](../issue-47390/README.md)
- [Collapsed tool activity README.md](../collapsed-tool-activity/README.md)

## Dependencies and application order

Apply this patch before either feature patch. Each feature's runtime behavior
works without the other feature patch. Feature flags and runtime property
declarations belong to their respective feature patches. Apply the
[configuration schema README.md](../schema/README.md) patch after all runtime
patches to publish their combined schema.

## Reproduction

Use `[codex_unleashed]` in `config.toml` and supply the properties documented
by an installed feature patch. An undeclared property produces the normal
unknown-key warning.

## Verification

Run from `codex-rs/`:

```sh
cargo test -p codex-config --lib codex_unleashed_table_schema_rejects_undeclared_properties
cargo test -p codex-core --lib strict_config_rejects_unknown_codex_unleashed_settings
```

The first check expects the table to appear in the runtime configuration
schema with undeclared properties disallowed. The second check expects
strict mode to reject undeclared properties.

## Formatting

Run from `codex-rs/`:

```sh
cargo fmt --package codex-config --package codex-core -- --check
```

## Upstream status

This is a shared prerequisite for downstream settings. Its base is
[upstream 0.159.2](https://github.com/openai/codex/releases/tag/rust-v0.159.2).
