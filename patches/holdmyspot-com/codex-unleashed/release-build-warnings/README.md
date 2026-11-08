# Release-build warnings from debug-only test hooks

- Applies to: upstream `openai/codex` main, commit `dddc3c0a79528c4de8d9a20d51dd3bd89b02a3e4`.
- Prerequisites: none. It also applies after the rest of the queue.

## Intent

A release build of Codex compiles without warnings in `codex-app-server` and `codex-cloud-tasks`. Debug builds keep every test hook unchanged.

Release builds compile out the debug-only test hooks. At the pinned base this leaves the following unused in release builds:

- the `PluginStartupTasks` import and the two managed-config environment-variable constants in `app-server/src/main.rs`;
- the `HttpClientFactory` and `OutboundProxyPolicy` imports in `cloud-tasks/src/lib.rs`;
- the `mut` on `loader_overrides` in `app-server/src/lib.rs`.

The patch gates those items on `debug_assertions` too. It also returns the overridden loader settings by value, so the parameter no longer needs `mut`.

## Feature configuration

### Codex feature flag

None.

### Project-specific `config.toml` property

None.

## Reproduction

`cargo build --release -p codex-app-server -p codex-cloud-tasks` at the base reports six warnings on every release target: two unused imports in `codex-cloud-tasks`, and in `codex-app-server` an unused import, two unused constants and an unneeded `mut` (release run `42427338720`).

## Verification

From the patched upstream checkout root:

```sh
cd codex-rs && cargo build --release -p codex-app-server -p codex-cloud-tasks
cargo nextest run -p codex-app-server -p codex-cloud-tasks
just fmt-check
```

The release build reports no warnings from either package. The debug test suites still use the gated hooks.
