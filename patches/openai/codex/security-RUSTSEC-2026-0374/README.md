# basic-toml advisory exception

## Intent

The dependency audit accepts the unmaintained basic-toml crate until its
only consumer, i18n-config, releases the replacement that removes it.

## Reference and upstream base

- [RUSTSEC-2026-0374](https://rustsec.org/advisories/RUSTSEC-2026-0374.html).
- Fix in the consumer: [kellpossible/cargo-i18n#176](https://github.com/kellpossible/cargo-i18n/pull/176),
  merged but not yet released.
- Base: `rust-v0.163.0` (`83086188ae0614bd882818ff1aa23f3194962012`).

## Scope and prerequisites

basic-toml reaches `codex-secrets` through `age`, `i18n-embed-fl` and
`i18n-config`. Their latest releases still require it, so no dependency
update removes it. The patch adds one `WORKAROUND` exception to
`codex-rs/deny.toml` and `codex-rs/.cargo/audit.toml`. Advisory, license,
ban, and source checks remain enabled for every other crate.

Apply this patch after the existing advisory patches in the full queue;
it extends the same exception lists.

## Reproduction

The base lockfile selects basic-toml 0.1.10, which the maintained
dependency audit rejects as unmaintained.

## Verification

From a fresh patched checkout with the full queue applied:

```sh
cargo deny --manifest-path codex-rs/Cargo.toml --all-features check \
  --deny unmatched-source --deny license-not-encountered \
  --deny unused-wrapper --deny advisory-not-detected
```

The audit reports no advisory, license, ban, or source failures.

## Upstream status

Remove both exceptions once an `i18n-config` release includes
cargo-i18n#176 and the lockfile selects it.
