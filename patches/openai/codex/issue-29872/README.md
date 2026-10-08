# Release build warnings

- Issue: https://github.com/openai/codex/issues/29872
- Applies to: `openai/codex` `rust-v0.161.0`, commit
  `979011409de0a60b52f179721948e65531d26144`

## Intent

Remove four Rust compiler warnings from release builds while preserving debug
configuration overrides and the cloud-task mock backend. The change applies
across platforms and does not alter runtime behavior.

## Scope and reproduction

Release builds report an unused `ToolCallSource` import in `codex-core`, an
unnecessary mutable `loader_overrides` parameter in `codex-app-server`, and
unused `HttpClientFactory` and `OutboundProxyPolicy` imports in
`codex-cloud-tasks`. The cloud imports and app-server mutation are used only
with debug assertions enabled. The core import has no uses.

The patch removes the unused core import, enables the cloud imports only with
debug assertions, and makes the app-server binding mutable only with debug
assertions. It adds a focused `just check-release-warnings` regression gate.
It does not suppress warnings. GitHub Actions deprecations, stable rustfmt's
unstable-option diagnostics, and dependency or unrelated Clippy warnings are
outside this source patch's scope.

## Dependencies and application order

Apply this patch directly to the declared upstream base. It has no patch
prerequisites and introduces no feature flag or configuration property.

## Verification

Run from the patched checkout root:

```sh
just fmt-check
just check-release-warnings
cd codex-rs
cargo clippy --lib -p codex-core -p codex-app-server -p codex-cloud-tasks -- --no-deps -D unused_imports -D unused_mut
```

Formatting and both release and debug checks must pass. The release gate denies
unused imports and mutable bindings in the three affected libraries without
linting unrelated workspace dependencies. The debug check compiles their
debug-only paths.

The existing app-server override test checks the debug behavior:

```sh
cd codex-rs
cargo test -p codex-app-server --lib debug_test_user_config_file_overrides_loader_path
```

The test must pass with debug assertions enabled. Native macOS, Windows, and
Linux release builds establish platform validation; a local Linux check alone
does not establish that gate.

## Upstream status

The issue remains open and the declared release contains the warnings. Remove
this patch when a stable upstream release includes the fixes.
