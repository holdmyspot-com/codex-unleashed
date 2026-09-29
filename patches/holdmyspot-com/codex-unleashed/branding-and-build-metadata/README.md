# Codex Unleashed branding and build information

- Applies to: upstream `openai/codex` `rust-v0.159.1`, commit `8e68a98ef03cdde76d2e6800791ebdf1b3b95b24`

## Intent

Show the Codex Unleashed build number in `codex --version` and the session
header. Identify Codex Unleashed in the session header as an unofficial fork
of OpenAI Codex, with the complete project URL on its own line. The fork label
and URL align with the title, and the label has no trailing colon. The URL wraps
on narrow terminals. Let users run `codex --build-info` to see the upstream
version and the provider.

## Feature configuration

### Codex feature flag

None. Branding and build information are always available.

### Project-specific `config.toml` property

None. This patch adds no `config.toml` property or section.

## Reproduction

Run `codex --version` and `codex --build-info`, then start an interactive
session. The version includes `+<build number>`; build information identifies
OpenAI Codex as upstream and Codex Unleashed as provider; the session header
shows the build number and identifies the unofficial fork.
Source builds without a release build number show `dev` as the build number.

## Verification

The CLI tests check `--version` and `--build-info`. The TUI tests check the
session header, including the full URL at narrow widths and the raw
transcript. Run them from `codex-rs/` with a fixed build number as the test
input:

```sh
CODEX_UNLEASHED_BUILD_NUMBER=15 cargo test -p codex-cli --test build_info
CODEX_UNLEASHED_BUILD_NUMBER=15 cargo test -p codex-tui --lib session_header_
```

The checks expect the build number, provider, and fork identity in their
respective outputs. Run the CLI tests without
`CODEX_UNLEASHED_BUILD_NUMBER` to check the `+dev` fallback.

## Formatting

Run `cargo fmt --package codex-cli --package codex-tui -- --check` from
`codex-rs/`.
