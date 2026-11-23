# Build warnings in Codex sources and build configuration

- Applies to: upstream `openai/codex` tag `rust-v0.171.0`, commit `ccf9be8cc458bd6b8336fe84d68d8893f2691a5e`.
- Prerequisites: none for patches 1 to 5, each of which applies alone and after the rest of the queue. Patch 6 requires the `dependency-upgrades` patches, whose lockfile changes make its entries stale.
- Patches, in queue order:
  1. `debug-only-release-items.patch`: release-build warnings from debug-only test hooks.
  2. `bubblewrap-netlink-initializers.patch`: `-Wmissing-field-initializers` in the vendored bubblewrap.
  3. `nextest-local-profile.patch`: nextest's unknown `profile.local.inherits` key.
  4. `rmcp-client-doc-links.patch`: rustdoc's bare-URL warnings in `codex-rmcp-client` (queued after `fatal-warnings`).
  5. `workspace-rustdoc.patch`: rustdoc's warnings in the other 22 workspace crates (queued after 4).
  6. `stale-cargo-deny-entries.patch`: cargo-deny's warnings about `deny.toml` entries that match no crate (queued after the dependency upgrades; see its section for the advisories and why it is needed).

The inventory these come from, with the warnings left open and why, is [warning-inventory.md](../../../../docs/warning-inventory.md).

## debug-only-release-items.patch

## Intent

A release build of Codex compiles without warnings in `codex-app-server`. Debug builds keep every test hook unchanged.

Release builds compile out the debug-only test hooks. At the pinned base this leaves the following unused in release builds:

- the `PluginStartupTasks` import and the two managed-config environment-variable constants in `app-server/src/main.rs`;
- the `mut` on `loader_overrides` in `app-server/src/lib.rs`.

The patch gates those items on `debug_assertions` too. It also returns the overridden loader settings by value, so the parameter no longer needs `mut`.

### Feature configuration

#### Codex feature flag

None.

#### Project-specific `config.toml` property

None.

### Reproduction

`cargo build --release -p codex-app-server -p codex-cloud-tasks` at the base reports six warnings on every release target: two unused imports in `codex-cloud-tasks`, and in `codex-app-server` an unused import, two unused constants and an unneeded `mut` (release run `42427338720`).

### Verification

From the patched upstream checkout root:

```sh
cd codex-rs && cargo build --release -p codex-app-server -p codex-cloud-tasks
cargo nextest run -p codex-app-server -p codex-cloud-tasks
just fmt-check
```

The release build reports no warnings from either package. The debug test suites still use the gated hooks.

## bubblewrap-netlink-initializers.patch

The `cc` crate builds the vendored bubblewrap with `-Wall -Wextra`. `vendor/bubblewrap/network.c` initializes `struct sockaddr_nl` as `{ AF_NETLINK, 0 }`, which names two of its four fields, so GCC reports `nl_pid` as missing at lines 53 and 142 (Linux nextest run `42410922258`). Upstream bubblewrap v0.13.1 has the same code. The patch names the one field it sets, `{ .nl_family = AF_NETLINK }`; C still zeroes the remaining fields, so the addresses are unchanged.

Verification: a Linux build of `codex-bwrap` reports no compiler warnings, and `cargo nextest run -p codex-linux-sandbox` passes.

## nextest-local-profile.patch

`.config/nextest.toml` declares `[profile.local]` with `inherits = "default"`. The `inherits` key arrived in nextest 0.9.115, so upstream's pinned 0.9.103 warns that it is an unknown key. Every custom profile already inherits from `default`, as the [nextest configuration docs](https://nexte.st/docs/configuration/) state, so the patch removes the key and keeps the profile that `just test` selects.

Verification: `NEXTEST_PROFILE=local cargo nextest run ...` with nextest 0.9.103 prints no configuration warning.

## rmcp-client-doc-links.patch

`cargo doc --no-deps` reports eight `rustdoc::bare_urls` warnings in `codex-rmcp-client`: plain URLs in the doc comments of `oauth.rs`, `oauth_callback.rs` and `rmcp_client.rs`, which rustdoc renders as text, not links. Under `fatal-warnings/deny-workspace-warnings.patch` (patch 22) they fail documentation with `error: this URL is not a hyperlink` (platform lint run `42537177622`). The patch wraps each URL in angle brackets, rustdoc's autolink form.

Verification: `RUSTDOCFLAGS=-Dwarnings cargo doc --workspace --no-deps` reports nothing for `codex-rmcp-client`.

## workspace-rustdoc.patch

With `--keep-going`, `RUSTDOCFLAGS=-Dwarnings cargo doc --workspace --no-deps` reports 68 more errors in 22 crates (platform lint run `42539814186`). The causes are:
- bare URLs (`rustdoc::bare_urls`);
- literal brackets such as `[UNSTABLE]` and `argv[1]`, read as intra-doc links (`rustdoc::broken_intra_doc_links`);
- placeholders such as `<u16>`, read as unclosed HTML tags (`rustdoc::invalid_html_tags`);
- intra-doc links to items the crate cannot name.

The patch edits only doc comments. URLs become autolinks, literal brackets are escaped, placeholders become code spans, and unresolvable links become code spans.

`codex-cli` reported 12 more of these errors once the run reached it (lint run `42543225907`). clap turns those doc comments into `--help` text, and its default formatting prints a Markdown escape literally. So the bracketed help moves into explicit `about`/`help` attributes with the text clap derived from the comment: the same words without the trailing period, and no long help. `--help` output is unchanged except that the metrics URL in `app-server --help` is now written as `<https://…>`.

The app-server protocol, `config.schema.json` and the Python SDK copy these doc comments into generated files, so the patch includes their output from upstream's own generators (the platform lint workflow's `generators` input, run `42541908776`; the experimental precomputed exports, which the generator rewrites only with `--experimental`, come from run `42553580839`). Those descriptions now carry the Markdown escape `\[UNSTABLE\]`, which renders as `[UNSTABLE]`. No code parses the marker.

Verification: `RUSTDOCFLAGS=-Dwarnings cargo doc --workspace --no-deps --keep-going` passes, and the schema fixture tests pass unchanged.

## stale-cargo-deny-entries.patch

### What it does

The patch deletes seven entries from `codex-rs/deny.toml` that match nothing in the queue's `Cargo.lock`: three advisory exceptions, one allowed license, two ban wrappers and one allowed Git source. It changes no build input, and every check that still matches a crate is unchanged.

### Why it is needed

`deny.toml` excepts an advisory only for the crate versions that carry it, and each exception's reason names its removal condition. When no crate in the lockfile matches an exception, cargo-deny reports `warning[advisory-not-detected]: advisory was not encountered`; an unmatched license, ban wrapper or Git source gets the corresponding not-encountered warning. Cargo-deny does not fail on these warnings by default, but this queue treats a release-build warning as a defect (see [warning-inventory.md](../../../../docs/warning-inventory.md)), and a stale exception would silently excuse the advisory if a vulnerable version returned. The direct upgrades in `dependency-upgrades` make the entries stale, so the patch comes after them. Repository check run `43009570386` on the earlier queue reported the warnings.

| Entry | Why it matches nothing |
| --- | --- |
| Advisory exception `RUSTSEC-2026-0391` (`zstd`: a borrowed context carries data from an abandoned stream into the next stream) | The advisory covers `zstd` before 0.14.3 and 0.13.2 to 0.13.3; the lockfile holds 0.13.4 and 0.14.3 |
| Advisory exception `RUSTSEC-2026-0398` (`zstd-safe`: `Seekable::decompress` sets a `Vec` length beyond its capacity) | The lockfile holds `zstd-safe` 7.4.1 and 8.2.0, both patched |
| Advisory exception `RUSTSEC-2026-0403` (`zstd-safe`: uninitialized memory exposed when streaming decompression of a legacy frame fails) | The lockfile holds `zstd-safe` 7.4.1 and 8.2.0, both patched |
| Allowed license `OpenSSL` | No package in `cargo metadata` declares an OpenSSL term; `aws-lc-sys`, its only listed user, does not |
| `reqwest` wrapper `sentry` | `sentry` 0.49.5 no longer depends on `reqwest` directly |
| `reqwest` wrapper `webrtc-sys-build` and allowed Git source `juberti-oai/rust-sdks` | Already absent from the base lockfile |

`rust-v0.171.0` already removed the exceptions for `RUSTSEC-2026-0410` (`zstd-sys`) and `RUSTSEC-2026-0418` (`libz-sys`). The `async-trait` wrapper for `hickory-proto` stays, because `rama-0.4.patch` is left out of this queue and rama 0.3 keeps `hickory-proto` 0.25.2, which depends on `async-trait`.

Verification: derived from the lockfile, the advisory database and each crate's published license; `cargo deny check` has not run on this queue yet. After applying the queue, run it in `codex-rs`; it must report none of the seven warnings.

## Rebase onto rust-v0.171.0

- `bazel-module-versions.patch` is dropped: `rust-v0.171.0` requires `platforms` 1.1.0 and `rules_cc` 0.2.25.
- `debug-only-release-items.patch` no longer changes `codex-cloud-tasks`: upstream's route-aware HTTP client pool removed the two imports that release builds left unused.
- `workspace-rustdoc.patch` no longer carries the precomputed app-server exports (`app-server-exports-stable.json.zst` and `-experimental.json.zst`); they are stale and need upstream's generator.
- `stale-cargo-deny-entries.patch`: see the table above.
