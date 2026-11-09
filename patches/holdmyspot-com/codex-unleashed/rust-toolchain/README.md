# Rust toolchain

- Applies to: upstream `openai/codex` main, commit `dddc3c0a79528c4de8d9a20d51dd3bd89b02a3e4`.
- Prerequisites: the `fatal-warnings` and `release-build-warnings` patches (queued after `workspace-rustdoc.patch`).
- Patches: `rust-1.99.patch`.

## rust-1.99.patch

### Intent

Codex builds with Rust 1.99.1, the latest stable release, so macOS links pass under the workspace's fatal `linker_messages` lint (patch 25).

Upstream pins Rust 1.95.0. On macOS, ld prints `ld: warning: ignoring duplicate libraries: '-lSystem'`, because both `std` and `block2` link libSystem (`block2` reaches `codex-code-mode-host` through `os_info` and `objc2-foundation`). Rust 1.95 reports that line through `linker_messages`, so patch 25 turns it into a link error. Rust 1.96 and later file it, along with ld's "was built for newer 'macOS' version" notes, under the allow-by-default `linker_info` lint (`compiler/rustc_codegen_ssa/src/back/link.rs`; tracking issue [rust-lang/rust#136096](https://github.com/rust-lang/rust/issues/136096)). Rust upstream treats the message as informational, so the fix is the toolchain, not a linker flag that hides it.

Toolchain upgrades are otherwise deferred (D9 in [restart-scope.md](../../../../docs/plans/restart-scope.md)); this is the demonstrated prerequisite that D9 allows to move earlier. The owner chose the latest stable release over the minimum, 1.96.

The patch pins 1.99.1 in:
- `codex-rs/rust-toolchain.toml`;
- the Bazel toolchain in `MODULE.bazel`, with `MODULE.bazel.lock` from upstream's generator (`bazel-lock`, platform lint workflow run `42559227280`);
- `codex-rs/scripts/setup-windows.ps1`;
- every workflow's `dtolnay/rust-toolchain` action (branch `1.99.1`, commit `d124f86f28ff42855784ff3319f48ac182c248f8`), `toolchain:` input, `cargo +1.95.0` call and cargo-deny `rust-version`.

Since Rust 1.97, `linker_messages` warns by default but ignores `-D warnings`, so the workspace comment now gives that as the reason for denying it explicitly.

Rust 1.99 also adds lints that fail the workspace under patch 25 (platform lint run `42556216850`). The patch adapts each call site:
- `Atomic*::fetch_update` is deprecated and renamed `try_update`, with the same arguments and result (four sites).
- Clippy's `chunks_exact_to_as_chunks`: three byte-pair loops use `as_chunks::<2>()`. Each already checks for an even length, so the remainder is empty.
- Clippy's `map_or_identity`: `map_or(0, identity)` becomes `unwrap_or(0)`.
- Clippy's `useless_borrows_in_formatting`: 21 `assert!` messages in the OTLP loopback tests drop a redundant `&` (lint run `42558019944`).
- Clippy's `question_mark`: one `if let … else { return None }` in `shell-command/src/bash.rs` becomes `?`.
- Clippy's `needless_borrows_for_generic_args`: `and_then(&normalize_temp_path)` in `exec-server-protocol` passes the function by value.
- rustc's `semicolon_in_expressions_from_non_local_macros`: three TUI functions that end in eyre's `bail!` end it with `;`, since the macro expands to a `return` statement (lint run `42559229185`).
- Clippy's `double_must_use` fires on tonic's generated `ThreadConfigLoader` trait, because async-trait 0.1.89 adds `#[must_use]` to the boxed future it returns. async-trait 0.1.92 no longer adds it, so the lockfile moves that transitive dependency, and only that one, to 0.1.92.

### Reproduction

At patch 27, the macOS release builds fail while linking `codex-code-mode-host`: `error: linker stderr: ld: ignoring duplicate libraries: '-lSystem'` (macOS release runs `42550713786` and `42550711373`).

### Verification

```sh
cd codex-rs && cargo build --release --target aarch64-apple-darwin
```

The macOS builds link without linker diagnostics, and the platform lints pass with Rust 1.99.1.
