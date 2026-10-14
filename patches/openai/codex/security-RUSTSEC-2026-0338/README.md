# QUIC dependency security updates

## Intent

QUIC connections use the patched quinn-proto dependency.

## Reference and upstream base

- [Canonical reference](https://rustsec.org/advisories/RUSTSEC-2026-0338.html).
- Base: rust-v0.162.1 (`092d3acd6bec3e3a14bdc7e7a2810ab628ab759d`).

## Scope

The lockfile selects quinn-proto 0.11.18 and its required rand_pcg dependency. This addresses RUSTSEC-2026-0338 through RUSTSEC-2026-0342 without suppressing advisories.

## Reproduction

Run `cargo deny check` from `codex-rs` on the declared upstream base. Its quinn-proto 0.11.15 dependency triggers RUSTSEC-2026-0338 through RUSTSEC-2026-0342.

## Verification

- From the patched checkout root, `just fmt-check` and `just bazel-lock-check` pass.
- From `codex-rs`, `cargo deny check` reports no advisory, license, ban, or source failures.

## Upstream status

The upstream stable base selects quinn-proto 0.11.15.
