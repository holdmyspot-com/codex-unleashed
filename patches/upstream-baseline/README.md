# Stable upstream baseline repairs

These patches repair confirmed baseline failures before downstream caching, scheduling, product changes, strict warnings, or dependency upgrades. Apply them in filename order to upstream `rust-v0.164.0`, commit `6aaac694803004a63c6f393d153c96d22521a6b9`.

Generated patches retain Git's space-prefixed context lines, including empty source lines. The scoped `.gitattributes` entry keeps their LF bytes stable and excludes end-of-line spaces from the wrapper-file whitespace check. Check the applied source diff with Git's normal whitespace checks rather than editing the generated patch's context encoding. This attribute does not change compiler or linter warning policy.

## Remove the stale code-mode feature exception

`0001-remove-stale-code-mode-feature-exception.patch` has no patch prerequisites. The release's `codex-rs/code-mode/Cargo.toml` no longer declares `[features]`, but `.github/scripts/verify_cargo_workspace_manifests.py` still lists it in `MANIFEST_FEATURE_EXCEPTIONS`. The checker correctly rejects unused exceptions and exits 1 on the unmodified tag.

The patch removes only that obsolete exception. It retains the checker and its stale-exception enforcement, changes no product source or dependency version, and leaves the remaining V8 proof-of-concept exception intact.

Validation runs the existing checker from the checkout root:

```sh
PYTHONDONTWRITEBYTECODE=1 python3 .github/scripts/verify_cargo_workspace_manifests.py
```

The command failed on the exact clean base and passed after the repair. The emitted `git format-patch` was separately applied with `git am` to a second fresh checkout of that base, where the checker also passed. [Build baseline](../../docs/build-baseline.md) records the retained execution evidence and remaining gates. A passing metadata check does not establish build or release-target success.

The same stale-exception diagnostic appears in [upstream's repository-check job](https://github.com/openai/codex/actions/runs/39710355092/job/119599956729) on the stable release's parent. The release commit changes only the workspace Cargo version; this check failure is distinct from its successful release builds.

## Format every discovered Rust source

`0002-format-all-repository-rust-sources.patch` has no patch prerequisites. The root formatter still invokes `cargo fmt`, while its existing regression requires repository-wide Rust discovery, including Bazel-only files, names containing spaces, and removal of stale deleted entries. [Upstream's SDK job](https://github.com/openai/codex/actions/runs/39710355092/job/119599956750) fails at the same assertion as the clean local tag.

The patch discovers existing Git-tracked and unignored Rust paths, deduplicates and sorts them, and invokes rustfmt with the existing repository configuration. It divides the argument list according to [Windows' 32,767 UTF-16-unit process limit](https://learn.microsoft.com/en-us/windows/win32/api/processthreadsapi/nf-processthreadsapi-createprocessw), including quoting and the terminating null. Python's standard subprocess serializer supplies the quoting. Empty discovery produces no command. A command that cannot fit a single source fails with the source path and corrective guidance.

The original regression fails on a clean tag and passes after repair. Additional regressions establish that astral Unicode paths are counted correctly, all discovered files occur exactly once across batches, and empty discovery does not invoke rustfmt. The first size-test fixture was too small; it was corrected to prove that its unbatched command crosses the UTF-16 limit before verifying the repair. The corrected regressions fail without batching and pass with it.

Validation passed all seven formatter regressions, Ruff lint/format checks, and the complete Python SDK suite (271 passed, 38 skipped) in isolation. The real repository Rust format check passed across seven commands; their maximum serialized length was 32,765 UTF-16 units including the terminator. Existing nonfatal rustfmt configuration warnings remain visible in that direct check. The emitted `git format-patch` was applied with `git am` to another clean tag checkout, where the seven regressions passed. Both verification worktrees were removed after the patch was applied to the active baseline. Cumulative SDK and formatting checks are recorded separately in [build-baseline.md](../../docs/build-baseline.md); these portable command-construction checks do not establish a native Windows build.

## Preserve the nested sandbox fixture's resources and caller identity

`0003-preserve-nested-sandbox-fixture-resources.patch` copies the helper's associated bundled bubblewrap into its minimal root alongside the helper. It also removes the outer fixture's unnecessary forced UID/GID 0. The permission policy, denied-path assertions, and runtime sandbox implementation remain unchanged. Cargo test setup must build the ordinary `bwrap` executable alongside `codex-linux-sandbox`.

A three-variant diagnostic on a standard free GitHub Linux runner reproduces the missing-executable panic, then a nested UID-map failure after supplying the executable, then success after retaining the caller's default identity. Run 40172691443 passes this comparison. The repaired original regression passes locally with successful output captured and no skip diagnostic.

## Control the negative project-marker fixture

`0004-isolate-absent-project-marker-fixture.patch` gives the negative ancestry test a unique configured marker whose absence the fixture controls. An ambient `/tmp/.git` otherwise changes the default-marker lookup and invalidates the test's assumption that no parent repository exists. Production discovery behavior and the other default-marker tests remain unchanged.

The original binary fails with ordinary temporary-directory ancestry and passes with the same fixture data reached outside that lexical ancestry. With the repair, all skills-extension tests and the root-metadata regression pass together: 214 tests. An initial candidate incorrectly inserted a missing TOML key through indexing; it was corrected to table insertion before validation. Evidence is retained in `fixture-repairs-regressions.*`. Both emitted patches apply with `git am` after the earlier repairs on a fresh stable checkout, and the resulting files match the tested source exactly. These focused results do not establish full-suite or native Bazel success. The temporary patch-generation and verification worktrees were removed after verification.

## Respect V8 dependency feature unification

`0005-accept-unified-v8-sandbox-features.patch` corrects the proof-of-concept test's assumption that its own optional forwarding feature must equal the shared dependency's resolved feature. Cargo metadata on the clean stable workspace shows no local proof-of-concept feature, while `codex-code-mode-runtime` enables `v8/v8_enable_sandbox`. [Cargo's documented feature unification](https://doc.rust-lang.org/cargo/reference/features.html#feature-unification) combines these requests on the shared V8 dependency.

The test now verifies the supported guarantee: requesting the proof-of-concept's sandbox feature requires a sandbox-enabled linked V8 library. Omitting that request does not disable another workspace consumer's feature. The test still calls the linked-library capability probe; no default feature, archive, dependency, or runtime behavior changes.

The isolated original workspace run passes five tests and fails the equality assertion with linked sandbox `true` and local feature `false`. After repair, all six proof-of-concept tests pass both with the default workspace feature selection and with its sandbox feature explicitly enabled. The emitted patch applies through `git am` after patches 0001–0004 on a fresh stable base, and the resulting source matches the tested file exactly. Evidence: `v8-feature-unification-{red,green}.*`, `v8-feature-explicit-sandbox-green.*`, and `v8-feature-patch-verified.receipt`. Temporary generation and verification worktrees were removed. The unchanged full native Bazel selection still retains upstream's proof-of-concept exclusion; the full Cargo test gate remains pending.
