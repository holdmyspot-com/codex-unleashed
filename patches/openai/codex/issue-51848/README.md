# Consistent Windows static C runtime

## Intent

Windows Bazel binaries link their Rust and C++ dependencies against a consistent static Microsoft runtime.

## Reference and upstream base

- [Canonical reference](https://github.com/openai/codex/pull/51848).
- Base: rust-v0.162.1 (`092d3acd6bec3e3a14bdc7e7a2810ab628ab759d`).

## Scope

Bazel target and host C++ actions enable `static_link_msvcrt`; the Rust toolchain applies `-Ctarget-feature=+crt-static` to both MSVC targets. Native verification also uses the Windows SDK configuration in issue-53443; the source patches apply independently.

## Reproduction

Run the complete native MSVC Bazel test entry point on the declared upstream base with the issue-53443 SDK configuration. Rust and C++ dependencies select incompatible Microsoft runtime linkage, producing duplicate allocator symbols during linking.

## Verification

- From the patched checkout root, `just fmt-check` passes.
- On a native Windows runner, run the complete maintained Bazel CI test entry point using the issue-53443 SDK configuration; linking succeeds without duplicate CRT allocator symbols.

## Upstream status

This narrowly backports the static-runtime portion of upstream commit `745d86552113399ed90f2e4ee5d700f5c5c6f483`. Other upstream release optimizations remain outside this patch.
