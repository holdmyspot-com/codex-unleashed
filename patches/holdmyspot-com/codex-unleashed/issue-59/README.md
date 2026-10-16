# Update the yanked spin dependency

## Intent

Dependency verification uses the available spin 0.9.9 release instead of the yanked 0.9.8 release. Existing runtime interfaces and audit checks remain enabled.

## Reference and upstream base

- [Canonical issue](https://github.com/holdmyspot-com/codex-unleashed/issues/59).
- Base: `rust-v0.163.0`, commit `83086188ae0614bd882818ff1aa23f3194962012`.
- [Official registry metadata](https://index.crates.io/sp/in/spin).

## Scope and dependencies

The Cargo lockfile selects spin 0.9.9 and its published checksum. The Bazel extension lockfile uses the matching crate facts. Registry dependency requirements, feature definitions and the minimum Rust version are unchanged. The patch requires no other downstream patch.

## Reproduction

From `codex-rs` on the declared upstream base, `cargo deny check advisories -D yanked` rejects spin 0.9.8 with `error[yanked]`.

## Verification

- From the patched checkout root, `just fmt-check` and `just bazel-lock-check` pass.
- From `codex-rs`, `cargo deny check advisories -D yanked` and `cargo deny check` pass without a yanked-crate finding.
- From the patched checkout root, `just test -p codex-tui --lib`, `just test -p codex-core --lib`, and `just test -p codex-core --test all` pass.

## Upstream status

The declared stable base and inspected upstream development commit `6cc7d9cc5c702c0ca133f7ea48432166e0b880be` select spin 0.9.8.
