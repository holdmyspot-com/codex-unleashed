# Codex Unleashed configuration schema

- Applies to: upstream `openai/codex` `rust-v0.161.0`, commit `979011409de0a60b52f179721948e65531d26144`

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
- [Background subagent completion README.md](../issue-35/README.md)

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

Run from the patched upstream checkout root:

```sh
just test -p codex-config-schema --test codex_unleashed
just test -p codex-core --lib -E 'test(config_schema_matches_fixture)'
```

Both checks expect the generated schema to match the published fixture,
including property order and formatting. The CLI check verifies that each
project setting has a boolean schema. Windows checks normalize CRLF line
endings to match upstream's fixture comparison.

## Formatting

Run from the patched upstream checkout root:

```sh
just fmt-check
```

## Upstream status

This is generated metadata for downstream settings. Its base is
[upstream 0.161.0](https://github.com/openai/codex/releases/tag/rust-v0.161.0).
