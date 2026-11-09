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
- Clippy's `needless_borrows_for_generic_args`: `and_then(&normalize_temp_path)` in `exec-server-protocol` passes the function by value, and the skills executor passes its copyable `read_error` closure to `map_err` by value (lint run `42570112285`).
- rustc's `semicolon_in_expressions_from_non_local_macros`: three TUI functions that end in eyre's `bail!` end it with `;`, since the macro expands to a `return` statement (lint run `42559229185`).
- Clippy's `result_large_err`: codex-mcp's `emit_update` returned the 632-byte `SendError<Event>`, which carries the rejected event, so the error is boxed (lint run `42565453466`).
- rustc's `unused_imports`: an agent-identity test module re-imported `base64::Engine`, which `use super::*` already brings in (lint run `42565453466`).
- Clippy's `drain_collect`: the realtime handoff stream's final chunk takes its buffered strings with `std::mem::take` instead of draining them into new ones (lint run `42616091080`).
- Clippy's `redundant_field_names` fires inside thiserror 2.0.18's expansion of `#[from] source` in `core/src/exec_policy.rs`. thiserror 2.0.21 no longer triggers it (the CP7 queue's lint run `42616087745`, which has 2.0.21, does not report it), so the lockfile moves thiserror and thiserror-impl, and only those, to 2.0.21.
- Clippy's `double_must_use` fires on tonic's generated `ThreadConfigLoader` trait, because async-trait 0.1.89 adds `#[must_use]` to the boxed future it returns. async-trait 0.1.92 no longer adds it, so the lockfile moves that transitive dependency, and only that one, to 0.1.92.

### The `__eh_frame` notice on macOS

With Rust 1.99.1 the release `codex` binary links, but ld prints `ld: __eh_frame section too large (max 16MB) to encode dwarf unwind offsets in compact unwind table, performance of exception handling might be affected`, which `linker_messages` turns into an error (macOS ARM release run `42563009608`).

Compact unwind stores one 32-bit entry per function in `__unwind_info`. Functions that need DWARF unwind information point into `__eh_frame` through a 24-bit offset, so entries past 16 MB can't be encoded, and the unwinder finds those functions by searching `__eh_frame` instead. Unwinding stays correct; only a panic that unwinds through those functions is slower. The alternative, `-Wl,-no_compact_unwind`, drops the compact table for every function, and the owner chose to keep it.

`linker_messages` has no per-message setting, so the patch links both Apple targets through `codex-rs/scripts/apple-linker.sh` (`[target.*-apple-darwin] linker` in `codex-rs/.cargo/config.toml`). The script runs `cc`, rustc's default linker, removes only that exact line from its stderr, and passes every other line and the exit status through, so every other linker message still fails the build. Bazel builds leave `linker_messages` allowed (see the `fatal-warnings` README) and don't use the script.

### Reproduction

At patch 27, the macOS release builds fail while linking `codex-code-mode-host`: `error: linker stderr: ld: ignoring duplicate libraries: '-lSystem'` (macOS release runs `42550713786` and `42550711373`).

### Verification

```sh
cd codex-rs && cargo build --release --target aarch64-apple-darwin
```

The macOS builds link without linker diagnostics other than the `__eh_frame` notice that `apple-linker.sh` removes, and the platform lints pass with Rust 1.99.1.

On the ARM macOS release runner, `.github/scripts/macos-unwind-report.py` (the release workflow's unwind report step) records the `__eh_frame` and `__unwind_info` sizes, the crates that fill `__eh_frame`, and how many FDEs start past 16 MB. It also walks the running `codex --version` stack with `_Unwind_Backtrace`, the unwinder that panics and Rust backtraces use, and fails unless the walk reaches the end of the stack through `main`.
