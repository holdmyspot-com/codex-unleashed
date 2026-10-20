# WebPKI distrusted emSign intermediates

## Intent

Certificate verification rejects paths through the distrusted emSign
intermediates, whose private keys are publicly known.

## Reference and upstream base

- [Canonical reference](https://rustsec.org/advisories/RUSTSEC-2026-0382.html).
- Related advisories: [RUSTSEC-2026-0381](https://rustsec.org/advisories/RUSTSEC-2026-0381.html)
  and [RUSTSEC-2026-0383](https://rustsec.org/advisories/RUSTSEC-2026-0383.html).
- Base: `rust-v0.163.0` (`83086188ae0614bd882818ff1aa23f3194962012`).

## Scope

`update-webpki.patch` selects rustls-webpki 0.103.16, which rejects paths
containing the distrusted intermediate keys, and webpki-roots and
webpki-root-certs 1.0.10, which drop the `emSign ECC Root CA - G3` root. It
updates the Cargo lockfile versions and checksums and the matching Bazel crate
facts together. No workspace manifest names these crates directly, so the
patch applies to the clean upstream base and to the full queue alike. Advisory
checks stay enabled.

## Reproduction

The base selects rustls-webpki 0.103.15 and the 1.0.5 root bundles. The
maintained cargo-deny workflow reports all three advisories.

## Verification

From the patched checkout, with the remaining queue applied:

```sh
cargo deny --manifest-path codex-rs/Cargo.toml check \
  --deny unmatched-source --deny license-not-encountered \
  --deny unused-wrapper --deny advisory-not-detected
just bazel-lock-check
```

The audit reports no advisory, license, ban, or source failures. The existing
duplicate-version warnings remain visible.

## Upstream status

Remove this patch once the selected upstream base locks rustls-webpki 0.103.16
and the 1.0.10 root bundles or newer.
