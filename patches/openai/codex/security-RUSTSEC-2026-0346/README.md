# Rustls dependency security update

## Intent

TLS connections use Rustls 0.23.46, which addresses four security advisories.

## Reference and upstream base

- [Canonical reference](https://rustsec.org/advisories/RUSTSEC-2026-0346.html).
- Related advisories: [RUSTSEC-2026-0347](https://rustsec.org/advisories/RUSTSEC-2026-0347.html), [RUSTSEC-2026-0348](https://rustsec.org/advisories/RUSTSEC-2026-0348.html), and [RUSTSEC-2026-0349](https://rustsec.org/advisories/RUSTSEC-2026-0349.html).
- Base: `rust-v0.163.0` (`83086188ae0614bd882818ff1aa23f3194962012`).

## Scope

The patch updates the Rustls Cargo lockfile version, published checksum and
corresponding Bazel crate fact together. It leaves advisory checks enabled.
Apply the issue #60 toolchain and issue #61 Bazel module prerequisites first.
Verification of the complete queue also requires the security patches for
unrelated dependencies.

## Reproduction

The base selects Rustls 0.23.45. Its maintained Cargo deny workflow reports all
four advisories; each advisory requires Rustls 0.23.46 or newer.

## Verification

From the patched checkout, with the remaining queue applied:

```sh
cargo deny --log-level warn --manifest-path codex-rs/Cargo.toml --all-features check
just fmt-check
just bazel-lock-check
```

Cargo deny reports no advisory, license, ban, or source failures. Existing
duplicate-dependency and stale-policy warnings remain visible and require their
own policy repairs. The formatting and Bazel lock checks pass. Platform builds
and runtime tests remain separate acceptance gates.

Local verification runs through the project's `run-local-rust` helper with
checkout-specific outputs and dependency caches under `.cat/work/temp/build-caches`.

## Upstream status

The declared stable base selects Rustls 0.23.45. The queue retains this lockfile
update until its selected upstream base uses a version covering all four advisories.
