# Codex Unleashed branding and build information

- Applies to: upstream `openai/codex` `rust-v0.159.2`, commit `ff6aec96948b70d94983af2641a6b67c94faeff5`

## Intent

Show the Codex Unleashed build number in `codex --version` and the session
header. Identify Codex Unleashed in the session header as an unofficial fork
of OpenAI Codex, with the complete project URL on its own line. The fork label
and URL align with the title, and the label has no trailing colon. The `/status`
header keeps the OpenAI Codex title and also displays the fork label and project
URL. The URL wraps on narrow terminals in both headers. Let users run
`codex --build-info` to see the upstream version and the provider.

## Feature configuration

### Codex feature flag

None. Branding and build information are always available.

### Project-specific `config.toml` property

None. This patch adds no `config.toml` property or section.

## Reproduction

Run `codex --version` and `codex --build-info`, then start an interactive
session and run `/status`. The version includes `+<build number>`; build
information identifies OpenAI Codex as upstream and Codex Unleashed as provider; the session header
shows the build number and identifies the unofficial fork. The `/status` header
shows the same fork label and complete project URL beneath its title.
Source builds without a release build number show `dev` as the build number.

## Verification

The CLI tests check `--version` and `--build-info`. The TUI tests check the
session header and `/status` header, including the full URL at narrow widths
and the raw transcript. Run them from `codex-rs/` with a fixed build number as
the test input:

```sh
export RUST_MIN_STACK=16777216
CODEX_UNLEASHED_BUILD_NUMBER=15 cargo test -p codex-cli --test build_info
CODEX_UNLEASHED_BUILD_NUMBER=15 cargo test -p codex-tui --lib session_header_
CODEX_UNLEASHED_BUILD_NUMBER=15 cargo test -p codex-tui --lib status::tests::
```

The checks expect the build number, provider, and fork identity in their
respective outputs. Run the CLI tests without
`CODEX_UNLEASHED_BUILD_NUMBER` to check the `+dev` fallback.

## Formatting

Run `cargo fmt --package codex-cli --package codex-tui -- --check` from
`codex-rs/`.
