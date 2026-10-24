# RUSTSEC-2026-0387: compatible constant-time arithmetic update

## Intent

Codex uses the fixed compatible `crypto-bigint` release for its upgraded
P-256 dependency. The older `crypto-bigint` dependency through `age` remains
an unresolved security blocker.

## Reference and base

- **Advisory**: https://rustsec.org/advisories/RUSTSEC-2026-0387.html
- **Upstream resolution**: https://github.com/RustCrypto/crypto-bigint/pull/1365
- **Upstream base**: `rust-v0.163.0`, commit
  `83086188ae0614bd882818ff1aa23f3194962012`.
- **Patch**: `compatible-constant-time-arithmetic.patch`.
- **Prerequisites**: Apply the release patch queue in repository filename
  order through
  `patches/holdmyspot-com/codex-unleashed/issue-71/latest-stable-dependencies.patch`,
  including that patch's earlier queue prerequisites. Its expected SHA-256 is
  `61240207b38e24fde33696459f2fbaef813014560911855dc33410a94df7a4eb`.
  Also apply `security-RUSTSEC-2026-0385/update-ctutils.patch`, which establishes
  the `ctutils 0.4.4` lock entries that this patch updates.
- **Application order**: This security patch follows issue #71. The existing
  security patches for RUSTSEC-2024-0449, RUSTSEC-2026-0346, and
  RUSTSEC-2026-0367 retain their positions before this advisory directory.
  The RUSTSEC-2026-0385 patch also precedes this directory.

The patch requires the dependency queue context and does not apply to the
clean upstream base by itself. The focused verification below covers the
complete current release queue and a fresh checkout of the declared base
with that queue context and the emitted patch applied.

## Scope and remaining blocker

`codex-rs/Cargo.lock` resolves `crypto-bigint` from `0.7.5` to `0.7.7` and
`ctutils` from `0.4.4` to its required minimum `0.4.5`. The three existing
qualified dependency references and registry checksums follow those versions.
`MODULE.bazel.lock` contains the matching published registry metadata.
There are no dependency-manifest changes or advisory suppressions.

RustSec identifies `crypto-bigint >=0.7.6` as patched. The `0.7.7` release
provides a compatible fix for the branch used by `p256 0.14.0` in
`codex-user-verification`.

The separate chain
`codex-secrets -> age 0.12.1 -> p256 0.13.2 -> elliptic-curve 0.13.8 -> crypto-bigint 0.5.5`
is unchanged. `age 0.12.1` is the latest stable age release and requires
`p256 ^0.13`; its elliptic-curve dependency requires `crypto-bigint ^0.5`.
The fixed `0.7` release does not satisfy that requirement. This patch does
not resolve the advisory for the `0.5.5` branch, and the complete advisory
check continues to fail for that package. A separate supported dependency
migration or verified cryptographic backport is necessary before that gate
can pass.

## Focused verification

From the patched upstream checkout root, use these commands. Local Rust
commands run through the project's `tooling/bin/codex-tooling run-local-rust`
helper with explicit project, checkout, and task-owned cache arguments.

```sh
cargo metadata --manifest-path codex-rs/Cargo.toml --locked --no-deps --format-version 1
just bazel-lock-check
cargo deny --manifest-path codex-rs/Cargo.toml check --deny unmatched-source \
  --deny license-not-encountered --deny unused-wrapper --deny advisory-not-detected
```

- **Cargo metadata**: Passes on the candidate and emitted-patch replay.
- **Bazel lock consistency**: Passes on the candidate and emitted-patch replay.
- **Strict cargo-deny**: Exits with status `1`; the sole error is
  RUSTSEC-2026-0387 for the unchanged `crypto-bigint 0.5.5`. Bans, licenses,
  and sources checks pass. The `0.7.7` package has no vulnerability error.
- **Change scope**: Only two lockfiles change. The `crypto-bigint 0.5.5`
  package and all other locked package versions remain unchanged apart from
  the explicitly listed `crypto-bigint` and `ctutils` upgrades.
- **Emitted patch**: Applies to the fresh base-plus-queue checkout and
  reproduces the candidate lockfiles byte for byte.
- **Broader gates**: Builds, runtime tests, and final release-queue acceptance
  remain outside these focused checks.
