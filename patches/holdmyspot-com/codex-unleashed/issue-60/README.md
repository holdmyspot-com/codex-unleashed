# Current stable build toolchains

## Intent

Codex Unleashed builds with Rust/Cargo 1.99.0 and Bazel 9.3.0. Rendering tests
check the rendered cells and styled spans without depending on the standard
library's Unicode debug escaping. The build retains strict lint checks.

## Reference and base

- Issue: https://github.com/holdmyspot-com/codex-unleashed/issues/60
- Upstream base: `rust-v0.162.1`, commit
  `092d3acd6bec3e3a14bdc7e7a2810ab628ab759d`.
- Apply the release patch queue in repository filename order. The native MSVC
  runtime and environment patches follow this issue and preserve its toolchain
  selection. The LLVM 23 integration patch in issue #61 follows this issue
and the native warning-policy prerequisite.

## Scope

`stable-build-toolchains.patch` aligns Cargo, Bazel, Windows setup, and upstream
CI and release callers with the same stable Rust version. Bazel's
direct dependencies and lock data match Bazel 9.3.0. The separate nightly
toolchain for the argument-comment lint keeps its existing selection.

Fixed-size chunk iteration, atomic update calls, macro statement terminators,
and redundant expressions use forms accepted by the current compiler and
Clippy. `async-trait` 0.1.92 supplies its upstream fix for the generated
`double_must_use` diagnostic. Bubblewrap's socket-address initializers name the
initialized field explicitly.

The rules_rust linker adaptation removes the inert `--unwindlib=none` argument
from Rust links. Its `WORKAROUND` comment links
[hermetic-llvm #304](https://github.com/hermeticbuild/hermetic-llvm/issues/304).
Remove the adaptation when the upstream linker argument selection omits that
option for Rust links.

## Reproduction and verification

Use the maintained commands from the patched upstream checkout root:

```sh
just fmt-check
just test -p codex-tui --lib
just test -p codex-utils-path-uri -p codex-utils-stream-parser
just clippy --workspace --all-targets -- -D warnings
just test --workspace --features codex-v8-poc/sandbox
just bazel-lock-check
bazel test //tools/build-warnings/llvm-driver:all
just bazel-test
just build-for-release
```

The checks require the normal release fixtures and prepared native voice/V8
dependencies. The workspace sandbox feature matches the prepared sandboxed V8
archive and code-mode runtime. Run local Rust commands through this repository's
`tooling/bin/codex-tooling run-local-rust` helper with a separate output identity
for each checkout. Verify the emitted queue on a fresh checkout of the base;
an authoring checkout does not establish emitted-patch acceptance.

The LLVM driver tests expect a warning-free Windows GNU link with
`-nolibc` and require the MSVC linker to reject an intentionally triggered
warning when its fatal-warning option is enabled.

The halfwidth sound-mark test expects unchanged truncation, styled spans, and
rendered cells. Clippy expects no Rust warnings. Strict Bazel lock validation
expects no missing registry checksums or lock changes. Full tests and release
builds must pass on their required platforms.

## Upstream status

The selected release pins older stable toolchains.
[async-trait 0.1.92](https://github.com/dtolnay/async-trait/releases/tag/0.1.92)
contains the generated-code lint fix.
