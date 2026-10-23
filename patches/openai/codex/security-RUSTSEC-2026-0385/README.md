# Constant-time ctutils operations

## Intent

Cryptographic operations use the ctutils release that fixes non-constant-time
mask generation and `u128` comparisons.

## Reference and upstream base

- [RUSTSEC-2026-0385](https://rustsec.org/advisories/RUSTSEC-2026-0385).
- [Upstream fix](https://github.com/RustCrypto/utils/pull/1569).
- Base: `rust-v0.163.0` (`83086188ae0614bd882818ff1aa23f3194962012`).

## Scope

`update-ctutils.patch` selects ctutils 0.4.4 and its registry checksum in
`codex-rs/Cargo.lock`. The matching Bazel crate fact includes the release's
optional subtle dependency requirement. The patch applies independently to
the declared base and integrates with the full queue. Advisory checks remain
enabled.

## Reproduction

The base and the queue without this patch lock ctutils 0.4.2. On the full
queue, the maintained cargo-deny check reports RUSTSEC-2026-0385 for that
version. Removing this patch reproduces that failure; applying it clears the
advisory without disabling the check.

## Verification

From the patched checkout, with the complete queue applied:

```sh
cargo deny --manifest-path codex-rs/Cargo.toml check \
  --deny unmatched-source --deny license-not-encountered \
  --deny unused-wrapper --deny advisory-not-detected
just bazel-lock-check
```

Use the checkout's `.bazelversion` for the Bazel lock check. The audit reports
no advisory, license, ban, or source failures, and the Bazel lock check exits
successfully. Existing dependency warnings remain visible.

For standalone verification, apply the patch to the declared base and run the
same audit. RUSTSEC-2026-0385 is absent; unrelated base advisories can still
fail the overall audit. In this disposable verification checkout, set
`workspace.package.version` in `codex-rs/Cargo.toml` to `0.0.0` to match the
base's workspace lock entries, then run `just bazel-lock-check` with the base's
Bazel 9.2.0 pin. This version alignment is test-fixture preparation, outside
the security patch; the full queue supplies its own consistent package
versions and uses Bazel 9.3.1.

## Upstream status

Remove this patch when the selected upstream base locks ctutils 0.4.4 or a
newer fixed release.
