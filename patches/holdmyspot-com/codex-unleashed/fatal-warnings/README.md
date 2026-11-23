# Fatal rustc warnings in Codex crates

- Applies to: upstream `openai/codex` tag `rust-v0.171.0`, commit `ccf9be8cc458bd6b8336fe84d68d8893f2691a5e`, after patches 1–21.
- Prerequisites: none. The patch changes the workspace lint table, `.bazelrc`, the x86-64 musl C and C++ flags in `.github/scripts/install-musl-build-tools.sh`, and removes three unused imports from `codex-rs/app-server/tests/suite/v2/sleep.rs`.
- Patches, in queue order:
  1. `deny-workspace-warnings.patch`: denies rustc lint warnings and linker messages in every workspace crate.
- Fixture, not in the queue: `fixtures/unused-variable.patch` adds an unused variable to `codex-utils-elapsed`, for the red phase.

## deny-workspace-warnings.patch

## Intent

A rustc lint warning or a linker message in any Codex crate fails its build in release, test and Clippy builds alike. Dependencies are unaffected.

Upstream's `[workspace.lints]` sets no rustc levels (`rust = {}`), and all 169 workspace crates opt into it with `[lints] workspace = true`. The patch sets `warnings = "deny"` there. Cargo passes the level only to workspace members. It caps dependency lints with `--cap-lints`, so their warnings stay as they are.

Rust 1.95 reports linker output through the `linker_messages` lint, which is allowed by default, so the output is hidden and `warnings` does not reach it. The patch denies it explicitly. With it denied, `rustc +1.95.0 -C link-arg=-Wl,-z,bogusopt` fails with `error: linker stderr: rust-lld: unknown -z value: bogusopt`; without it the same link succeeds silently.

Bazel does not read Cargo's lint table, so the patch also adds `-Dwarnings` to `.bazelrc` through `extra_rustc_flag` and `extra_exec_rustc_flag`. It does not deny linker messages under Bazel: the hermetic LLVM toolchain's own link flags make clang++ report `argument unused during compilation: '--unwindlib=none' [-Wunused-command-line-argument]` on every link, starting with rules_rust's `process_wrapper` tool (Bazel test run `42537265904`). That is toolchain configuration outside Codex, reported separately, like the mingw-w64 CRT warnings in [warning-inventory.md](../../../../docs/warning-inventory.md).

The x86-64 musl release link passes `--icf=safe` to lld. For each C object that `zig cc` built, lld warns `--icf=safe conservatively ignores SHT_LLVM_ADDRSIG [index N] with sh_link=0 (likely created using objcopy or ld -r)`, because the object's `.llvm_addrsig` section is not linked to a symbol table. The denied `linker_messages` lint makes those warnings fail the link of `codex-bwrap`, the first binary the release job links. The build runs no `objcopy` or `ld -r` step: `codex-bwrap` compiles four vendored sources with the `cc` crate under `zigcc`. `install-musl-build-tools.sh` therefore adds `-fno-addrsig` to `CFLAGS` and `CXXFLAGS` when `CODEX_MUSL_RELEASE_LINK_FLAGS=1`, so every C and C++ object in that link carries no `.llvm_addrsig` section. lld treats all symbols of such an object as address-significant, with no warning, which is how it already treated the objects with the unlinked section. Rust objects keep their tables, so `--icf=safe` still folds identical Rust code. The link flags are unchanged and no linker output is hidden.

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
