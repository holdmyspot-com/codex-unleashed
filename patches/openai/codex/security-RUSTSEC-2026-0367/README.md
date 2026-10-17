# ZIP dependency security update

## Intent

Plugin archive downloads use ZIP 9.0.4, which addresses three extraction
security advisories in the dependency.

## Reference and upstream base

- [RUSTSEC-2026-0367](https://rustsec.org/advisories/RUSTSEC-2026-0367.html).
- Related advisories: [RUSTSEC-2026-0368](https://rustsec.org/advisories/RUSTSEC-2026-0368.html)
  and [RUSTSEC-2026-0369](https://rustsec.org/advisories/RUSTSEC-2026-0369.html).
- Base: `rust-v0.163.0` (`83086188ae0614bd882818ff1aa23f3194962012`).

## Scope and prerequisites

The patch updates the direct ZIP dependency and the archive reader API,
retains its default compression formats, and refreshes Cargo and Bazel
resolution metadata. The replacement bzip2 backend has a crate-specific
license allowance for the redistribution terms of the bundled libbzip2 it
replaces. Advisory, license, ban, and source checks remain enabled.
The zstd-sys overlay retains the MSVC header search paths; zstd-sys handles
symbol visibility itself.

Apply this patch after the existing dependency and Bazel toolchain patches
in the full queue. Its lockfile includes their dependency overrides and
crate metadata. The plugin extractor's permission policy is unchanged;
the dependency update does not establish a separate audit of that policy.

## Reproduction

The base selects ZIP 2.4.2, which the maintained dependency audit rejects
for all three advisories. The fixed dependency passes the same audit.

## Verification

From a fresh patched checkout with the full queue applied:

```sh
cargo deny --manifest-path codex-rs/Cargo.toml --all-features check \
  --deny unmatched-source --deny license-not-encountered \
  --deny unused-wrapper --deny advisory-not-detected
just test -p codex-core-plugins
just fmt-check
just bazel-lock-check
```

The audit reports no advisory, license, ban, or source failures. Plugin
archive tests, formatting, and Bazel lock verification pass. Platform
builds remain separate verification gates. Local checks use the maintained
`run-local-rust` helper with checkout-specific compiler outputs.

## Upstream status

The declared stable base selects ZIP 2.4.2. Retain this patch until the
selected release covers all three advisories and the archive reader API.
