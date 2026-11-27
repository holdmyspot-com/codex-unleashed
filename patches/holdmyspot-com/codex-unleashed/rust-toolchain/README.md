# Rust toolchain

- Applies to: upstream `openai/codex` tag `rust-v0.172.0`, commit `3d86e43b075adeba2193ec5c22ac92f55afd82e6`.
- Prerequisites: the `fatal-warnings` and `release-build-warnings` patches (queued after `workspace-rustdoc.patch`).
- Patches: `rust-1.99.patch`.

## rust-1.99.patch

### Intent

Codex builds with Rust 1.99.1, the latest stable release, so macOS links pass under the workspace's fatal `linker_messages` lint (patch 22).

On macOS, ld prints `ld: warning: ignoring duplicate libraries: '-lSystem'`, because both `std` and `block2` link libSystem (`block2` reaches `codex-code-mode-host` through `os_info` and `objc2-foundation`). Rust 1.95 reported that line through `linker_messages`, so patch 22 turned it into a link error. Rust 1.96 and later file it, along with ld's "was built for newer 'macOS' version" notes, under the allow-by-default `linker_info` lint (`compiler/rustc_codegen_ssa/src/back/link.rs`; tracking issue [rust-lang/rust#136096](https://github.com/rust-lang/rust/issues/136096)).

Upstream moved to Rust 1.99.1 in [openai/codex#61205](https://github.com/openai/codex/pull/61205), which is in `rust-v0.171.0`: `rust-toolchain.toml`, the Bazel toolchain, `setup-windows.ps1`, every workflow's toolchain action and the Rust 1.99 lint fixes at the call sites. The halfwidth sound mark snapshot is upstream's as well. The patch keeps what upstream lacks, because upstream does not deny warnings:
- the `apple-linker.sh` wrapper for the `__eh_frame` notice (below);
- the `linker_messages` comment: since Rust 1.97 the lint warns by default but ignores `-D warnings`, so the comment gives that as the reason for denying it explicitly;
- two lockfile moves. Clippy's `redundant_field_names` fires inside thiserror 2.0.18's expansion of `#[from] source` in `core/src/exec_policy.rs`, and thiserror 2.0.21 no longer triggers it (lint run `42616087745`), so thiserror and thiserror-impl, and only those, move to 2.0.21. Clippy's `double_must_use` fires on tonic's generated `ThreadConfigLoader` trait, because async-trait 0.1.89 adds `#[must_use]` to the boxed future it returns; async-trait 0.1.92 no longer adds it, so that transitive dependency, and only that one, moves to 0.1.92.

### The `__eh_frame` notice on macOS

With Rust 1.99.1 the release `codex` binary links, but ld prints `ld: warning: __eh_frame section too large (max 16MB) to encode dwarf unwind offsets in compact unwind table, performance of exception handling might be affected`, which `linker_messages` turns into an error (macOS ARM release run `42563009608`). rustc's error shows it as `ld: __eh_frame …` because rustc removes `: warning: ` from linker output; the wrapper's first version matched that displayed form and missed the real line (release run `42619462435`).

Compact unwind stores one 32-bit entry per function in `__unwind_info`. Functions that need DWARF unwind information point into `__eh_frame` through a 24-bit offset, so entries past 16 MB can't be encoded, and the unwinder finds those functions by searching `__eh_frame` instead. Unwinding stays correct; only a panic that unwinds through those functions is slower. The alternative, `-Wl,-no_compact_unwind`, drops the compact table for every function, and the owner chose to keep it.

`linker_messages` has no per-message setting, so the patch links both Apple targets through `codex-rs/scripts/apple-linker.sh` (`[target.*-apple-darwin] linker` in `codex-rs/.cargo/config.toml`). The script runs `cc`, rustc's default linker, removes only that exact line from its stderr, and passes every other line and the exit status through, so every other linker message still fails the build. Bazel builds leave `linker_messages` allowed (see the `fatal-warnings` README) and don't use the script.

### Reproduction

Before this patch, on Rust 1.95.0, the macOS release builds failed while linking `codex-code-mode-host`: `error: linker stderr: ld: ignoring duplicate libraries: '-lSystem'` (macOS release runs `42550713786` and `42550711373`).

### Verification

```sh
cd codex-rs && cargo build --release --target aarch64-apple-darwin
```

The macOS builds link without linker diagnostics other than the `__eh_frame` notice that `apple-linker.sh` removes, and the platform lints pass with Rust 1.99.1.

On the ARM macOS release runner, the `macos-unwind-report` tooling command (the release workflow's unwind report step) records the `__eh_frame` and `__unwind_info` sizes, the crates that fill `__eh_frame`, and how many FDEs start past 16 MB. It also walks the running `codex --version` stack with `_Unwind_Backtrace`, the unwinder that panics and Rust backtraces use, and fails unless the walk reaches the end of the stack, either through `main` or at `_pthread_start`, since codex runs its command line on a spawned thread.

ARM macOS release run `42665564052` (CP7 queue) measured:
- `__eh_frame` is 22,837,340 bytes (21.8 MiB) and `__unwind_info` 3,648,672 bytes (3.5 MiB).
- 76,912 of the 286,376 FDEs start past 16 MB.
- `core` fills 39.9% of `__eh_frame`, followed by `tokio` (6.9%), `serde_json` (6.0%), `alloc` (5.8%) and `serde_path_to_error` (5.0%); monomorphized generics, not one crate, make the section large.
- The `codex --version` walk reached the end of the stack (`_URC_END_OF_STACK`) through 11 frames, 9 of them in the binary. Two of those are functions whose FDE starts past 16 MB, which the unwinder finds by searching `__eh_frame`. So unwinding works through the functions that compact unwind can't encode.

That run's report step failed only because the script then required `main`, and this walk ends at `_pthread_start`.
