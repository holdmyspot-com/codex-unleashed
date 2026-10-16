# Release build warnings

- Issue: https://github.com/openai/codex/issues/29872
- Applies to: `openai/codex` `rust-v0.163.0`, commit
  `83086188ae0614bd882818ff1aa23f3194962012`

## Intent

Compile release libraries, binaries, and tests without unused debug-only
imports, bindings, constants, or helpers. Debug configuration overrides,
analytics capture tests, and the cloud-task mock backend remain available in
debug builds across platforms.

## Scope and reproduction

With debug assertions disabled, the app-server library has an unnecessary
mutable `loader_overrides` binding, and the cloud-task and analytics tests have
unused `HttpClientFactory` and `OutboundProxyPolicy` imports. The app-server
binary also has an unused `PluginStartupTasks` import and two unused managed
configuration constants. The CLI update test has unused imports and a helper
because its test runs only with debug assertions enabled.

The patch applies the same debug condition to those declarations as their
consumers and makes the app-server binding mutable only in debug builds.
The focused `just check-release-warnings` regression gate covers libraries,
binaries, and test targets. The patch adds no lint suppressions. GitHub Actions
deprecations, rustfmt diagnostics, and dependency warnings remain outside this
source patch's scope.

## Dependencies and application order

Apply `release-build-warnings.patch` directly to the declared upstream base.
It has no patch prerequisites and introduces no feature flag or configuration
property.

## Verification

Run from the patched checkout root:

```sh
just fmt-check
just check-release-warnings
just clippy --all-targets -p codex-core -p codex-app-server -p codex-cloud-tasks -p codex-analytics -p codex-cli -- --no-deps -D unused_imports -D unused_mut -D dead_code
```

Formatting and both release and debug checks must pass. The release gate denies
unused imports, mutable bindings, and dead code in the five packages' targets
without linting unrelated workspace dependencies. The debug check compiles
their debug-only paths.

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
this patch when a stable upstream release includes its fixes.
