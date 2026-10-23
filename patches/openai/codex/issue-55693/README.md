# Hermetic macOS debug-symbol generation

## Intent

macOS Bazel binaries generate debug symbols with the pinned LLVM toolchain's
`dsymutil`, rather than relying on a tool installed on the host.

## Reference and base

- Upstream change: https://github.com/openai/codex/pull/55693
- Base: `rust-v0.163.0`, `83086188ae0614bd882818ff1aa23f3194962012`.
- No prerequisite patches or plugins are required.

## Scope

The wrapper receives `LLVM_DSYMUTIL` from the C toolchain. macOS Rust binary
actions declare the wrapper as a compile input and put it on their command
search path. Other platforms keep their existing environment and inputs.

Our release artifacts use Cargo. Upstream's additional Bazel release-staging
flags do not apply to that workflow; this patch changes Bazel compilation only.

## Verification

Run `just fmt-check` in the patched checkout. On macOS, use the maintained Bazel
CI command to build `//codex-rs/cli:codex`, and verify its generated dSYM with
`dwarfdump --verify`. The build must use the declared LLVM toolchain. A Linux
wrapper invocation can check argument forwarding and missing-variable failure,
but does not establish that the macOS build or dSYM is correct.

## Upstream status

This backports the compiler-input changes from upstream commit
`6fb1c3903d42e3cc7da2c89e3a045fe0ce137af4`. Reassess it when upgrading the base.
