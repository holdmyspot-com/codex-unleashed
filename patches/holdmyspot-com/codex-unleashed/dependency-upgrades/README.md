# Dependency upgrades (CP7)

- Applies to: upstream `openai/codex` tag `rust-v0.171.0`, commit `ccf9be8cc458bd6b8336fe84d68d8893f2691a5e`.
- Prerequisites: every earlier patch in the queue, through `rust-toolchain/rust-1.99.patch` (patch 25).
- Patches, in order: `compatible-updates.patch`, `latest-releases.patch`, `serde-saphyr.patch`, `release-adaptations.patch`, `transitive-updates.patch`.

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

test-case 3.4 names each generated case `test_case_<n>_<name>`, where 3.3 named it `<name>`, keeping a leading underscore when the name began with one. The test IDs therefore change; the Java `compare-test-baseline` command matches IDs without that prefix, so baselines recorded on unpatched upstream still match.

The upgrade moves 148 direct dependencies, which forces 177 transitive packages to new versions, adds 24 and removes 14. None of the 177 can return to its old version: each reverted alone fails cargo's resolver with a "required by package" constraint from an upgraded package.

### Regenerated outputs

There is no separate regenerated-outputs patch. Each patch carries the files its own change regenerates, produced with upstream's generators on the queue up to that patch (`baseline-platform-lints.yml` with `generators="app-server-schema config-schema hooks-schema bazel-lock"`, runs `42934098328` to `42934201124` and `42935939271`). `latest-releases.patch` holds the app-server JSON schemas and TypeScript bindings, the Python SDK models and the `config.schema.json` changes that schemars 1 and ts-rs 12 cause, and `MODULE.bazel.lock` followed in whichever patch changed the lockfile. On `rust-v0.171.0` these generated files are stale: the rebase regenerated `Cargo.lock` with lockfile-only Cargo commands but none of the generated outputs, so `MODULE.bazel.lock` is stale from `rust-toolchain/rust-1.99.patch` onward, and the app-server schemas, TypeScript bindings, Python SDK models and the hooks model-request schema do not reflect `latest-releases.patch`; regenerate it once on the full queue with `baseline-platform-lints.yml` and `generators="bazel-lock"` and add the result as a patch.

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
- opentelemetry-otlp 0.33 retries failed exports by default (four attempts with backoff), where 0.31 sent each export once. On Windows a refused loopback connection takes about two seconds per attempt, so `metrics_exporters_respect_analytics_preferences` exceeded its telemetry shutdown budget (Windows run `43120032253`). Every OTLP exporter now disables retries through `otlp::export_retry_policy`, which keeps upstream's single attempt. The runtime OTLP HTTP client also returns the response instead of turning every error status into an error, so the exporter sees the real status.
- With tracing-opentelemetry 0.34, the noise-relay harness test layer reads a span's OpenTelemetry context inside a layer callback, where `tracing::dispatcher::get_default` returns the no-op dispatcher. The layer now keeps a weak handle to the dispatcher it is registered with and reads the span's context through it (`first_encrypted_request_frame_exposes_only_its_trace_context`, Linux run `43114171410`).
- reqwest 0.13 checks a custom CA bundle through the platform verifier with the bundle's roots added (`tls_certs_merge`). On Windows that verifier builds the chain by name. `production_catalog_uses_runtime_auth_and_preserves_provider_isolation` gave its test CA and leaf rcgen's default subject, so the leaf looked self-signed and failed with "The signature of the certificate cannot be verified" (Windows run `43120032253`; diagnostic run `43138143175` showed the same certificates pass the bundle-only webpki verifier). The test now gives the CA and the leaf distinct subjects.
- schemars 1 writes a single-value enum as `const` instead of a one-element `enum`, so `provider_error_schema_matches_wire_fields` matches each variant by its `const` discriminator.
- Two test servers, in app-server-transport and exec-server, wrote their response before the request arrived. hyper rejects a response on an idle connection, and reqwest 0.13 reports it as a send failure instead of the body timeout the tests check, so both now read the request first.
- The thread-sample tests accept schemars 1's web.run schema hashes.
- assert_cmd 2.2 panics when its deprecated `Command::cargo_bin` cannot find a binary; 2.1 returned an error. `codex_utils_cargo_bin::cargo_bin` callers use that error to fall back to another binary, so test suites run without a `CARGO_BIN_EXE_*` variable (Bazel, `cargo nextest -p codex-core`) panicked: Linux test run `42765976756` failed 2547 tests, and native Bazel test run `42759554553` failed three. The helper now looks for the binary next to the test executable itself, as assert_cmd did, and returns `NotFound`, so it no longer depends on assert_cmd.
- unicode-width stays at `=0.2.2` (see below).

### transitive-updates.patch

Adapts the Bazel build to the direct upgrades and changes nothing else. A transitive dependency moves only where an upgraded direct dependency requires it, so `Cargo.lock` stays as `release-adaptations.patch` leaves it. Two hunks are forced:
- cc is 1.11.1 (the direct upgrade in `compatible-updates.patch`), so the MXC annotation in `MODULE.bazel` names `@crates//:cc-1.11.1` (Bazel Clippy run `42737946018` failed on the old label).
- zbus 6.2.1 (the direct upgrade in `latest-releases.patch`) turns on the zruntime `event`, `broadcast` and `lock` features through its default features, but the Bazel crate resolver builds zruntime 0.2.2 without them, so a Linux-only `annotation_select` adds the features zbus's defaults request (native Bazel test run `42749909191`).

Owner decision 2026-11-22: a whole-lockfile `cargo update`, which an earlier version of this patch carried, is not part of the queue. Its security fixes (openssl 0.10.82, libz-sys 1.1.32, event-listener 5.4.2, quick-xml 0.41 through plist 1.10.3 and wayland-scanner 0.31.11) are not taken; "Audits" below lists what stays open. What followed from the sweep is dropped with it: the `-sys` version labels for GStreamer and the removed glib-sys, gobject-sys and gio-sys entries in `MODULE.bazel`, the `prost-types` label in `bazel/toolchains/prost/BUILD.bazel`, the `std` feature that `codex-utils-pty` requested from winapi (the locked serial2 0.2.33 still turns it on), the `deny.toml` exception changes, and the three `MODULE.bazel.lock` patches (`transitive-updates-bazel-lock-1.patch` to `-3.patch`), which the sweep made necessary. `stale-cargo-deny-entries.patch` removes the `deny.toml` entries that the direct upgrades made stale.

## Held back

| Dependency | Kept at | Constraint |
|---|---|---|
| unicode-width | 0.2.2 | 0.2.3 counts the halfwidth sound marks U+FF9E and U+FF9F as one cell, where 0.2.2 counted zero. ratatui-core 0.1.2, its latest release, adds one cell for each of them to make up for 0.2.2, so under 0.2.3 it counts them twice and "ｶﾞ" takes three cells instead of two (11 TUI test failures). vt100 0.18.1 requires `^0.2.2`. The pin in `Cargo.toml` names the reason; drop it when ratatui stops compensating. |
| rama-* (`codex-network-proxy`) | 0.3.0-alpha.4 | 0.4.0 removes `ProxyTarget`, `StreamForwardService` and `PeekStream` and redesigns the SOCKS5 server, URI, DNS, TLS configuration and extension APIs. The port is a separate work item after CP7 (owner decision). |
| libsqlite3-sys | 0.37 | sqlx-sqlite 0.9.0, the latest, requires `<0.38`. sqlx `main` lifts the limit (transact-rs/sqlx#4502) but has not released it (#4516). Overriding it would need a copy of sqlx-sqlite, which patches never contain, so RUSTSEC-2026-0459 stays open until a release allows 0.39. |
| sse-stream (`codex-rmcp-client`) | 0.2.6 | rmcp's streamable HTTP client takes sse-stream 0.2 types; every rmcp release through 3.9.0 requires `^0.2.4`. |
| tokio-tungstenite, tungstenite | 0.28, 0.27 | Patched to upstream's `openai-oss-forks` releases, which add proxy and deflate support. |
| keyring | 3.6 | keyring 4 moves the library to keyring-core and per-platform store crates. Its v1 shim drops the Linux keyutils layer of `linux-native-async-persistent` and has no mock credential, which about 50 test uses rely on. |
| secret-service | 4 | `keyring-store` downcasts keyring 3.6's errors to `secret_service::Error`; a second release would silently stop matching. Moves with keyring. |

## Audits

- `RUSTSEC-2026-0385` (`ctutils`): resolved by `ctutils 0.4.8`.
- A later audit against advisory-db `d9cb059f32` (2026-11-09) finds further open advisories in transitive dependencies; "CP8 reconciliation" in [restart-scope.md](../../../../docs/plans/restart-scope.md) lists them with their fixes.
- These stay open on the queue's lockfile: `hickory-proto 0.25.2` (RUSTSEC-2026-0118 and -0119, through rama 0.3; the rama 0.4 port fixes it), `quick-xml 0.39.4` through plist and wayland-scanner (RUSTSEC-2026-0194 and -0195, which `deny.toml` excepts), `libz-sys 1.1.29` (RUSTSEC-2026-0418, which `deny.toml` excepts), `libsqlite3-sys 0.37` (RUSTSEC-2026-0459; see Held back) and crypto-bigint below. The `deny.toml` acceptances for RUSTSEC-2026-0459 and chrono's RUSTSEC-2026-0543 are not in the queue, so `cargo deny check` reports both until the owner accepts them again.
- `RUSTSEC-2026-0387` (`crypto-bigint 0.5.5`): open. Its only path is `elliptic-curve 0.13` → `p256 0.13`, which `age 0.12.1` (scrypt recipients only, so no P-256 arithmetic runs) and `aws-config 1.13.0` with `credentials-login` (which signs AWS login proofs with P-256) reach at their latest releases. aws-config 1.13.1 uses `p256 0.14`, so only `age` remains, and through it no P-256 arithmetic runs. It stays open, not suppressed, until `age` moves to `p256 0.14`.

## Verification

The run IDs below ran on the queue before `transitive-updates.patch` was trimmed and before the `MODULE.bazel.lock` patches were removed; repeat them on the trimmed queue.

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

`sandbox_with_network_proxy_blocks_direct_loopback_access` timed out because the workflow's job-wide curl retry configuration retried the refused connection the test expects; the Linux test workflow no longer applies that configuration to the test steps, and focused run `42746049933` passes it.

`local_compaction_retries_preserve_instructions_and_tool_metadata::metadata_enabled` runs close to its 60-second limit on unpatched upstream as well and times out on loaded runners (34-patch run `42712983966`). See [upstream-failure-comparison.md](../../../../docs/upstream-failure-comparison.md).

## Rebase onto rust-v0.167.0

`latest-releases.patch`, `serde-saphyr.patch`, `transitive-updates.patch` and the regenerated-outputs patch (now folded into the patches above) needed conflict resolution; `compatible-updates.patch` applied after its lockfile was regenerated. Beyond the conflicts, the rebase needed these source changes: `latest-releases.patch` adds `register_with_interest` in `exec-server/src/server/missing_watch.rs` for tokio 1.54's `AsyncFd`, and `release-adaptations.patch` adapts tokio-util's `Bytes::from` and the OpenTelemetry data in the exec-server harness tests. `AsyncFd::new` is deprecated at this base, so the queue does not compile between `compatible-updates.patch` and `latest-releases.patch`.

## Rebase onto rust-v0.171.0

Every patch's `Cargo.lock` was regenerated with lockfile-only commands (`cargo update -p NAME --precise VERSION`), and `cargo update --workspace --locked` passes after each one. No native build has run.
- `compatible-updates.patch`: bumps that `rust-v0.171.0` already reaches are gone. image, moxcms and tiff stay where upstream has them, because upstream pins `image =0.25.9` in `utils/image` (openai/codex#61341).
- `latest-releases.patch`: rmcp stays at upstream's `=3.6.0`. reqwest 0.13's HTTP client uses the features `gzip`, `json`, `rustls`, `stream` and `zstd`. The Windows sandbox's random password is ported to rand 0.10's `SysRng`, and schemars 1 is ported in the new upstream code in `protocol` (`items` and `display.rs`), `features` (`feature_configs.rs`) and history notes (a `x-keep-null` marker with `remove_null_variants`). The patch no longer carries the generated schemas, bindings and SDK models.
- `serde-saphyr.patch`: upstream's lockfile already reaches newer serde-saphyr, granit-parser and annotate-snippets releases.
- `release-adaptations.patch`: the `web_search` `literature_search_and_open` and `thread_sample` `sample_inherits_parent_context` snapshots keep upstream's tool hashes, which need regenerating.
- `transitive-updates.patch`: upstream openai/codex#59219 builds zstd-sys from Bazel's `@zstd` and deleted `patches/zstd-sys_windows_msvc_include_dirs.patch`, so that hunk is gone. The zruntime annotation uses `annotation_select`, the `rules_rs` 0.2.3 API, as upstream openai/codex#60507 did.
