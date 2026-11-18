# Explicit failures and unknown model metadata

These patches apply after queue entry 75 on upstream `rust-v0.167.0`
(`086ccc833bc9cf01024ad2dd6af7dbd421b6bf9a`). Apply them in the order listed
in the [patch queue](../../../README.md).

- `custom_ca.patch` rejects invalid configured certificate trust before making
  requests, so a broken trust configuration produces an error.
- `explicit_shell_selection.patch` rejects an unavailable explicitly selected
  shell before execution.
- `synthetic_model_metadata.patch` leaves unknown model context limits and default
  reasoning effort unspecified and disables unsupported reasoning summaries.
  Explicit user overrides remain available.

The certificate, shell, and model patches passed their focused remote tests,
formatting check, and Clippy with warnings treated as errors. Verification of the
combined queue remains pending.

Run these checks from `codex-rs` on a native verification runner after applying
the patches:

```sh
cargo fmt --all -- --check
cargo clippy -p codex-http-client -p codex-login -p codex-tui \
  -p codex-ollama -p codex-api -p codex-exec-server -p codex-cli \
  -p codex-core -p codex-core-plugins -p codex-models-manager \
  --all-targets -- -D warnings
cargo test -p codex-models-manager --lib model_info::tests:: -- --nocapture
cargo test -p codex-models-manager --lib manager::tests:: -- --nocapture
```

The certificate patch also requires the HTTP client builder, route-aware pool,
login client, and OSS-selection regressions. The shell patch requires the unified
execution handler regressions and its explicit-shell integration case. The
combined Clippy command above covers all three patches; its combined result is
still pending even though each patch's scoped command passed separately.

The separate `structured_review.patch` preserves invalid reviewer text, reports
missing or malformed findings as an error, and forwards delegate errors without
adding a duplicate error. It passed the native CLI builds, 14 focused review
cases, two focused execution cases, three additional focused cases, formatting,
and `cargo clippy -p codex-core -p codex-exec --all-targets`. That Clippy gate
did not use `-D warnings`. It applies cleanly after entries 75–77; combined
runtime and release acceptance remain pending.

The separate `masking_startup.patch` rejects unsupported explicitly enabled
command masking before startup. Its native Linux checks passed two core cases,
six execution-server cases, formatting, and the scoped Clippy gate.

The separate `unknown_server_version.patch` displays an unknown connected-server
version when no version is available. Its native Linux checks passed five
notice cases and eleven remote-connection status cases. The final formatted
candidate passed the eleven status cases, formatting, and
`cargo clippy -p codex-tui --all-targets` without `-D warnings`.

Both patches passed mailbox parsing with their source diffs preserved exactly.
They occupy queue entries 79 and 80, respectively. Cumulative patch application,
combined runtime checks, and release acceptance remain pending.
