# Fatal rustc warnings in Codex crates

- Applies to: upstream `openai/codex` main, commit `dddc3c0a79528c4de8d9a20d51dd3bd89b02a3e4`, after patches 1–24.
- Prerequisites: none. The patch changes only the workspace lint table and `.bazelrc`.
- Patches, in queue order:
  1. `deny-workspace-warnings.patch`: denies rustc lint warnings and linker messages in every workspace crate.
- Fixture, not in the queue: `fixtures/unused-variable.patch` adds an unused variable to `codex-utils-elapsed`, for the red phase.

## deny-workspace-warnings.patch

## Intent

A rustc lint warning or a linker message in any Codex crate fails its build in release, test and Clippy builds alike. Dependencies are unaffected.

Upstream's `[workspace.lints]` sets no rustc levels (`rust = {}`), and all 169 workspace crates opt into it with `[lints] workspace = true`. The patch sets `warnings = "deny"` there. Cargo passes the level only to workspace members. It caps dependency lints with `--cap-lints`, so their warnings stay as they are.

Rust 1.95 reports linker output through the `linker_messages` lint, which is allowed by default, so the output is hidden and `warnings` does not reach it. The patch denies it explicitly. With it denied, `rustc +1.95.0 -C link-arg=-Wl,-z,bogusopt` fails with `error: linker stderr: rust-lld: unknown -z value: bogusopt`; without it the same link succeeds silently.

Bazel does not read Cargo's lint table, so the patch also adds `-Dwarnings` to `.bazelrc` through `extra_rustc_flag` and `extra_exec_rustc_flag`. It does not deny linker messages under Bazel: the hermetic LLVM toolchain's own link flags make clang++ report `argument unused during compilation: '--unwindlib=none' [-Wunused-command-line-argument]` on every link, starting with rules_rust's `process_wrapper` tool (Bazel test run `42537265904`). That is toolchain configuration outside Codex, reported separately, like the mingw-w64 CRT warnings in [warning-inventory.md](../../../../docs/warning-inventory.md).

Cargo's `build.warnings = "deny"` (`CARGO_BUILD_WARNINGS`) has the same scope without a source change, but it is stable only from Cargo 1.97, and upstream pins Rust 1.95.0.

### Feature configuration

#### Codex feature flag

None.

#### Project-specific `config.toml` property

None.

### Reproduction

At the base, a new unused variable in a workspace crate builds with a `warning: unused variable` line and exit status 0. `fixtures/unused-variable.patch` adds one to `codex-utils-elapsed`.

### Verification

From the patched upstream checkout root:

```sh
cd codex-rs && cargo build -p codex-utils-elapsed
git am fixtures/unused-variable.patch && cargo build -p codex-utils-elapsed
```

The first build passes. The second fails with `error: unused variable: `fatal_warning_fixture`` and the note ``-D unused-variables` implied by `-D warnings``; Cargo exits 101 (release run `42536065614`, x86_64 Linux). `python3 .github/scripts/verify_bazel_clippy_lints.py` and `python3 .github/scripts/verify_cargo_workspace_manifests.py` still pass.
