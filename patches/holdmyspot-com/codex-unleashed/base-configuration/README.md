# Codex Unleashed configuration section

- Applies to: upstream `openai/codex` `rust-v0.161.0`, commit `979011409de0a60b52f179721948e65531d26144`

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

Property keys use snake_case. Codex-Unleashed-owned fixed-vocabulary string
values use lowercase kebab-case, with hyphens between words. Boolean values
retain TOML's `true` and `false` spellings. Upstream Codex values and free-form
content retain their existing spellings.

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
just test -p codex-config --lib -E 'test(codex_unleashed_table_schema_rejects_undeclared_properties)'
just test -p codex-core --lib -E 'test(strict_config_rejects_unknown_codex_unleashed_settings)'
```

The first check expects the table to appear in the runtime configuration
schema with undeclared properties disallowed. The second check expects
strict mode to reject undeclared properties.

## Formatting

Run from the patched checkout root:

```sh
just fmt-check
```

## Upstream status

This is a shared prerequisite for downstream settings. Its base is
[upstream 0.161.0](https://github.com/openai/codex/releases/tag/rust-v0.161.0).
