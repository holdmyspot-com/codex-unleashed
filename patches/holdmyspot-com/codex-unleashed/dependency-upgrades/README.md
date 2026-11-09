# Dependency upgrades (CP7)

- Applies to: upstream `openai/codex` main, commit `dddc3c0a79528c4de8d9a20d51dd3bd89b02a3e4`.
- Prerequisites: every earlier patch in the queue, through `rust-toolchain/rust-1.99.patch` (patch 28).
- Patches, in order: `compatible-updates.patch`, `latest-releases.patch`, `regenerated-outputs.patch`, `serde-saphyr.patch`, `release-adaptations.patch`, `transitive-updates.patch`.

## Intent

Codex builds against the latest stable release of each direct dependency. A transitive dependency moves only where an upgraded direct dependency requires it; the rest of the lockfile stays as upstream left it (owner decisions in [plan.md](../../../../plan.md), "CP7 progress"). Each dependency that stays older is listed below with the constraint that holds it.

## Patches

### compatible-updates.patch

Moves each direct dependency to its newest semver-compatible release with `cargo update -p <package>` per direct package, which changes 360 lockfile entries where a whole-lockfile `cargo update` changes 614. It also updates the tonic annotation version and regenerates `MODULE.bazel.lock`.

### latest-releases.patch

Raises the direct requirements to their latest stable major releases under Rust 1.99.1 and adapts Codex to them:
- rand 0.10, digest 0.11, hmac 0.13, p256 0.14, reqwest 0.13, tokio 1.54 (`AsyncFd::register`), ctor 1, vt100 0.18, tree-sitter 0.27, whoami 2, base64 0.23, zbus 6, ed25519-dalek 3 (which now needs the `alloc` feature), webrtc and rtc 0.21, landlock, sentry 0.49 and opentelemetry_sdk 0.33;
- serde_yaml, deprecated, is replaced with serde_norway, and the RUSTSEC-2024-0449 exception is removed; `serde-saphyr.patch` then replaces serde_norway;
- schemars 0.8 → 1 and ts-rs 11 → 12 (owner decision).

  These migrations change generated schema and TypeScript output, not wire data. schemars keeps draft-07 output and the published 0.8 names for generic types, single-value enums and schema shapes that generated clients depend on. schemars 1 identifies schemas by type, so the hosted image-generation and web-search items get names apart from the extension's types. ts-rs 12's map types drop the optional `?` for non-enum keys.

The upgrade moves 148 direct dependencies, which forces 177 transitive packages to new versions, adds 24 and removes 14. None of the 177 can return to its old version: each reverted alone fails cargo's resolver with a "required by package" constraint from an upgraded package.

### regenerated-outputs.patch

Regenerates what the upgrades change, each with upstream's own generator: the app-server JSON schemas and TypeScript bindings, the Python SDK models and `MODULE.bazel.lock`.

The web.run tool schema changes only in form under schemars 1: `minimum` is written `0` instead of `0.0`, and `required` lists fields in declaration order instead of alphabetical order (schema dump runs `42673538018` against `42673542514`). `release-adaptations.patch` accepts the new thread-sample hashes.

### serde-saphyr.patch

serde_norway 0.9.42 carries the same nine `dangerous_implicit_autorefs` errors as serde_yaml 0.9.34. serde_yaml_ng keeps the same libyaml binding and noyalib is pre-1.0, so the YAML readers in skills, core-plugins and external-agent-migration move to [serde-saphyr](https://github.com/bourumir-wyngs/serde-saphyr) 2.1.1. It is pure Rust and forbids unsafe code. One helper in `codex-skills` keeps serde_yaml's YAML 1.2 rules: only `true` and `false` are booleans, and numbers written like `0755` or `1_000` stay strings. The four new transitive crates are MIT or Apache-2.0.

### release-adaptations.patch

Fixes what the new releases change at run time or in test expectations:
- rtc 0.21's interceptor API in the capture worker test, and tokio-util 0.7.20's `Bytes` requirement in the plugin process fixture.
- jsonwebtoken 11 needs an explicit crypto backend, so it uses `aws_lc_rs`, which the workspace already builds.
- pulldown-cmark 0.13 renders task lists differently, so the task-list snapshot is updated. Its two empty items end in a space, which `git am` reports as whitespace errors; the renderer writes them.
- opentelemetry-proto 0.33 writes 64-bit integers as JSON strings, so the OTLP loopback and worktree tests read `asInt` as a string.
- opentelemetry_sdk 0.33 logs its own export errors as events. The log database drops events whose names end in `ExportError`, so a failing export does not write rows that it would then export again.
- opentelemetry-otlp 0.33 retries failed exports, but only for HTTP 429, 502, 503 and 504, and always for network failures. The runtime OTLP HTTP client turned every error status into an error, which the exporter counts as a network failure, so a 500 was sent four times. The client now returns the response and leaves the status to the exporter.
- Two test servers, in app-server-transport and exec-server, wrote their response before the request arrived. hyper rejects a response on an idle connection, and reqwest 0.13 reports it as a send failure instead of the body timeout the tests check, so both now read the request first.
- The thread-sample tests accept schemars 1's web.run schema hashes.
- unicode-width stays at `=0.2.2` (see below).

### transitive-updates.patch

Runs `cargo update`, so every transitive dependency moves to the highest release its dependents allow (owner decision, 2026-11-09). This takes the security fixes in openssl 0.10.82, libz-sys 1.1.32, event-listener 5.4.2, quick-xml (through plist 1.10.3 and wayland-scanner 0.31.11) and aws-config 1.13.1, which moves to p256 0.14. rama-error, rama-macros and rama-utils stay at `0.3.0-alpha.4`: cargo lets `^0.3.0-alpha.4` select the 0.3.0 releases, but rama-error 0.3.0 removes `OpaqueError`, which rama-core 0.3.0-alpha.4 imports (platform lint run `42712058990`). The rama 0.4 port replaces all of them. `MODULE.bazel.lock` is regenerated to match.

## Held back

| Dependency | Kept at | Constraint |
|---|---|---|
| unicode-width | 0.2.2 | 0.2.3 counts the halfwidth sound marks U+FF9E and U+FF9F as one cell, where 0.2.2 counted zero. ratatui-core 0.1.2, its latest release, adds one cell for each of them to make up for 0.2.2, so under 0.2.3 it counts them twice and "ｶﾞ" takes three cells instead of two (11 TUI test failures). vt100 0.18.1 requires `^0.2.2`. The pin in `Cargo.toml` names the reason; drop it when ratatui stops compensating. |
| rama-* (`codex-network-proxy`) | 0.3.0-alpha.4 | 0.4.0 removes `ProxyTarget`, `StreamForwardService` and `PeekStream` and redesigns the SOCKS5 server, URI, DNS, TLS configuration and extension APIs. The port is a separate work item after CP7 (owner decision). |
| sse-stream (`codex-rmcp-client`) | 0.2.6 | rmcp's streamable HTTP client takes sse-stream 0.2 types; every rmcp release through 3.9.0 requires `^0.2.4`. |
| tokio-tungstenite, tungstenite | 0.28, 0.27 | Patched to upstream's `openai-oss-forks` releases, which add proxy and deflate support. |
| libsqlite3-sys | 0.37 | sqlx-sqlite 0.9.0, the latest, requires `<0.38`. |
| keyring | 3.6 | keyring 4 moves the library to keyring-core and per-platform store crates. Its v1 shim drops the Linux keyutils layer of `linux-native-async-persistent` and has no mock credential, which about 50 test uses rely on. |
| secret-service | 4 | `keyring-store` downcasts keyring 3.6's errors to `secret_service::Error`; a second release would silently stop matching. Moves with keyring. |

## Audits

- `RUSTSEC-2026-0385` (`ctutils`): resolved by `ctutils 0.4.8`.
- A later audit against advisory-db `d9cb059f32` (2026-11-09) finds further open advisories in transitive dependencies; "CP8 reconciliation" in [restart-scope.md](../../../../docs/plans/restart-scope.md) lists them with their fixes.
- After `transitive-updates.patch`, three crates stay open: `hickory-proto 0.25.2` (RUSTSEC-2026-0118 and -0119, through rama 0.3; the rama 0.4 port fixes it), `libsqlite3-sys 0.37` (RUSTSEC-2026-0459; sqlx-sqlite 0.9.0 requires `<0.38`) and crypto-bigint below.
- `RUSTSEC-2026-0387` (`crypto-bigint 0.5.5`): open. Its only path is `elliptic-curve 0.13` → `p256 0.13`, which `age 0.12.1` (scrypt recipients only, so no P-256 arithmetic runs) and `aws-config 1.13.0` with `credentials-login` (which signs AWS login proofs with P-256) reach at their latest releases. aws-config 1.13.1 moves to `p256 0.14`, so after `transitive-updates.patch` only `age` remains, and through it no P-256 arithmetic runs. It stays open, not suppressed, until `age` moves to `p256 0.14`.

## Verification

```sh
cd codex-rs
cargo metadata --locked --format-version 1 > /dev/null
cargo nextest run --workspace
cargo clippy --workspace --tests -- -D warnings
```

The platform, macOS and Windows lint workflows and the Linux test workflow run on the full queue. On the Linux test run before the last fixes (`42692670763`, 23,184 tests), the only failures that remained beyond those fixes also fail on unpatched upstream:
- `host_roots::tests::repo_ancestry_without_project_marker_does_not_walk_parents`;
- `root_metadata_symlinks_cannot_reopen_approved_denials`;
- `sandbox_blocks_curl`;
- `sandbox_feature_matches_linked_v8`.
