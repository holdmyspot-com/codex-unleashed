# Shared Codex Unleashed configuration table

- Applies to: upstream `openai/codex` tag `rust-v0.172.0`, commit `3d86e43b075adeba2193ec5c22ac92f55afd82e6`. No downstream prerequisites.

## Intent

Codex Unleashed features need project settings. Each feature owns its typed fields in one optional `codex_unleashed` table instead of adding top-level `config.toml` keys. The table starts empty and rejects undeclared settings, like the rest of `config.toml`. The published `core/config.schema.json` includes the table, because upstream's `config_schema_matches_fixture` test compares the fixture with the generated schema.

## Verification

From the patched upstream checkout root:

```sh
cd codex-rs && cargo nextest run -p codex-config -p codex-core -E 'test(/codex_unleashed|config_schema_matches_fixture/)'
just fmt-check
```
