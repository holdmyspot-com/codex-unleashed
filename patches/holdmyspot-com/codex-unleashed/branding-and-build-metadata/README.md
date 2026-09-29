# Codex Unleashed branding and build information

- Applies to: upstream `openai/codex` `rust-v0.158.0`, commit `064c6b8c737f5b41d171fdda80bd9ef10ad06eb3`
- Restores the branding patch set removed in commit `d162acc7fc954212bfb56dc0d9e4d5727e7fd6bf`

## Intent

Show the Codex Unleashed build number in `codex --version` and the session
header. Let users run `codex --build-info` to see the upstream version and the
provider. Show the provider link in the session header.

## Feature configuration

Branding and build information are always available. There is no feature flag
or `config.toml` property. Source builds without a release build number show
`dev` as the build number.

## Reproduction

Run `codex --version` and `codex --build-info`, then start an interactive
session. The version includes `+<build number>`; build information identifies
OpenAI Codex as upstream and Codex Unleashed as provider; the session header
shows the build number and provider link.

## Regression test and TDD record

On the declared upstream base, `build_info_reports_upstream_and_provider`
failed because `--build-info` was unknown. Before restoring the remaining
branding behavior, `version_identifies_codex_unleashed_build` failed because
`--version` printed only `codex-cli 0.158.0`, and
`session_header_identifies_codex_unleashed_build` failed because the header
omitted `+15`. With this patch series applied, both CLI tests and the TUI test
passed. The session-header test checks both the full card and raw transcript.

The restored provider line made six existing session-header snapshot tests
fail. `fix-branding-test-snapshots.patch` updates their expected output; the
focused `session_header_` run then passed 13 tests. The historical
`fix-cli-tests-for-build-info.patch` is folded into the main patch so CLI tests
compile after the first patch is applied.

Run these focused checks from `codex-rs/`:

```sh
CODEX_UNLEASHED_BUILD_NUMBER=15 cargo test -p codex-cli --test build_info
CODEX_UNLEASHED_BUILD_NUMBER=15 cargo test -p codex-tui --lib session_header_
```

The two CLI tests also passed without `CODEX_UNLEASHED_BUILD_NUMBER`, checking
the `+dev` fallback. Local Rust 1.98 failed while compiling unchanged upstream
`codex-chatgpt` because compiler queries overflowed the depth limit; the red
and green runs used upstream-pinned Rust 1.95 after that failure. A full TUI
library run stopped at a stack overflow in
`analytics::plan::tests::plan_gate_prevents_requests_and_unavailable_plan_does_not_block_other_reports`;
the focused branding checks passed.

## Formatting

`cargo fmt --package codex-cli --package codex-tui -- --check` passed with
Rust 1.95.
