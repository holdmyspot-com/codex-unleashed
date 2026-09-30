# Codex Unleashed configuration schema

- Applies to: upstream `openai/codex` `rust-v0.159.2`, commit `ff6aec96948b70d94983af2641a6b67c94faeff5`

## Intent

Help editors recognize the supported Codex Unleashed settings and their
allowed values.

## Feature configuration

### Codex feature flag

None. This patch adds no feature flag or feature behavior.

### Project-specific `config.toml` property

None. Each feature patch registers and documents its own properties:

- [Expanded diff previews README.md](../issue-47390/README.md)
- [Collapsed tool activity README.md](../collapsed-tool-activity/README.md)

## Dependencies and application order

Apply this patch last, after the shared configuration declaration and all
feature patches. The published schema describes the complete supported patch
queue. Its property order and formatting match upstream's schema generator.
Runtime behavior and configuration defaults remain owned by the feature
patches.

## Reproduction

Apply the complete patch queue and compare the published configuration schema
with the output of `codex-write-config-schema`.

## Verification

Run from `codex-rs/`:

```sh
cargo test -p codex-config-schema --test codex_unleashed
cargo test -p codex-core --lib config_schema_matches_fixture
```

Both checks expect the generated schema to match the published fixture,
including property order and formatting. The CLI check verifies that each
project setting has a boolean schema. Windows checks normalize CRLF line
endings to match upstream's fixture comparison.

## Formatting

Run from `codex-rs/`:

```sh
cargo fmt --package codex-config-schema -- --check
```

## Upstream status

This is generated metadata for downstream settings. Its base is
[upstream 0.159.2](https://github.com/openai/codex/releases/tag/rust-v0.159.2).
