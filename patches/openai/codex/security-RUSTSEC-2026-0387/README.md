# RUSTSEC-2026-0387: constant-time arithmetic

## Intent

Codex uses corrected constant-time arithmetic in both affected `crypto-bigint`
dependency branches. The current P-256 branch uses a compatible registry
update; the older branch required by `age` uses a reviewed source backport.

## Reference and base

- **Advisory**: https://rustsec.org/advisories/RUSTSEC-2026-0387.html
- **Upstream resolution**: https://github.com/RustCrypto/crypto-bigint/pull/1365
- **Upstream base**: `rust-v0.163.0`, commit
  `83086188ae0614bd882818ff1aa23f3194962012`.
- **Application order**: Apply the repository release queue in filename order.
  Within this directory, apply `compatible-constant-time-arithmetic.patch`
  before `vendored-legacy-constant-time-arithmetic.patch`.
- **Queue prerequisites**: These emitted patches use the dependency context
  established by issue #71 and the preceding security patches, including
  `security-RUSTSEC-2026-0385/update-ctutils.patch`. The issue #71 patch
  `patches/holdmyspot-com/codex-unleashed/issue-71/latest-stable-dependencies.patch`
  has SHA-256
  `ab9281acbb3936c32b8c56f2bf22086e6855d4610bceb884b4fc6a647e4610bf`.

The queue artifacts require that lockfile context. The underlying legacy
backport needs only upstream's normal dependencies and `ctutils 0.4.5`;
it does not require a third-party plugin. A clean-base standalone packaging
check covers that minimal prerequisite separately. The queue replay checks
cover the exact artifacts distributed here.

## Scope

`compatible-constant-time-arithmetic.patch` updates registry `crypto-bigint`
from `0.7.5` to `0.7.7` and `ctutils` from `0.4.4` to `0.4.5`, with matching
Cargo and Bazel registry metadata. RustSec identifies `crypto-bigint >=0.7.6`
as patched. This branch serves `p256 0.14.0` in `codex-user-verification`.

`vendored-legacy-constant-time-arithmetic.patch` retains the compatible chain
`codex-secrets -> age 0.12.1 -> p256 0.13.2 -> elliptic-curve 0.13.8 -> crypto-bigint 0.5.5`.
Cargo resolves its final package to the corrected local source under
`codex-rs/vendor/crypto-bigint-0.5.5`. The patch includes the original source
archive, the frozen library backport, and a guard that reconstructs and
compares every vendored file before checking the actual locked dependency
chain. Other `crypto-bigint` versions, when present, must retain unique
registry identities. No advisory exception is added.

The reviewed library patch has SHA-256
`daab4a04c34beea7f417fae1b348ada597221bed826c9fc0b6c0cdcef00d0002`.
The queue patch containing it has SHA-256
`ee43fab3a586b693c58029836b897a89279a6bb289a793d47cdf142cbf060658`.
The backport's constant-time analysis covers control flow and memory access;
it does not establish constant instruction latency on every processor.

## Verification

From the patched upstream checkout root, run the maintained checks below.
Local Rust commands use the project's
`tooling/bin/codex-tooling run-local-rust` helper with explicit project,
checkout, and task-owned cache arguments. `TMPDIR` names caller-owned
scratch storage under the project's build-cache directory.

First normalize Cargo's workspace lockfile versions to the patched manifests.
BuildBuddy's upstream preparation performs this step; a checkout prepared only
by applying patches needs it before the guard's locked metadata check.

```sh
(cd codex-rs && cargo metadata --format-version 1 > /dev/null)
just fmt-check
just bazel-lock-check
(
  metadata="$(mktemp "$TMPDIR/crypto-bigint-metadata-XXXXXXXX.json")"
  trap 'rm -f -- "$metadata"' EXIT
  cargo metadata --locked --format-version 1 --manifest-path codex-rs/Cargo.toml > "$metadata"
  python3 scripts/test_check_crypto_bigint_backport.py --checkout "$PWD" --metadata "$metadata"
)
bash scripts/check-crypto-bigint-backport.sh "$PWD"
cargo deny --manifest-path codex-rs/Cargo.toml check --deny unmatched-source \
  --deny license-not-encountered --deny unused-wrapper --deny advisory-not-detected
just test -p codex-secrets
```

The guard's 28 regression cases cover corrupted provenance, changed vendor
source, symlinks, invalid package identities, and broken dependency edges.
The source guard, strict advisory audit, formatting, and Bazel lock checks
must pass. The strict audit does not suppress RUSTSEC-2026-0387: the guard
establishes the corrected local source identity before the audit runs.
BuildBuddy's `cargo-deny` task invokes the guard before the strict audit.

When local test fixtures would inherit the enclosing project's `.git`
through `TMPDIR`, use Nextest's supported target runner to remove `TMPDIR`
only from the test processes, preserving compiler scratch and cache paths:

```sh
env 'CARGO_TARGET_X86_64_UNKNOWN_LINUX_GNU_RUNNER=/usr/bin/env -u TMPDIR' \
  just test -p codex-secrets
```

Bazel consumer compilation and native release-platform tests are separate
required release checks; formatting, lock consistency, and advisory checks
do not establish those outcomes.
