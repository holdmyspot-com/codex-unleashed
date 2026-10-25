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

## Stabilize the TUI unit-fixture version

`0006-stabilize-tui-unit-fixture-version.patch` gives unit tests the development version `0.0.0` used by their existing snapshots, including its rendering geometry. Non-test builds continue to embed `env!("CARGO_PKG_VERSION")`. The update prompt uses the same version definition instead of reading a second compile-time value, preserving its production value while allowing the fixture to apply consistently. Snapshot files and runtime version-selection behavior are unchanged.

Three representative assertions first fail with the release version `0.164.0` and pass with the fixed unit fixture. The complete 5,844-test TUI comparison uses identical commands, tracked TUI source outside the candidate, and the upstream 8 MiB thread-stack setting. Before the candidate: 5,794 passed and 50 failed; afterward: 5,831 passed and 13 failed, with eight skips in each run. Exactly 37 failures are fixed, matching every TUI failure in the native baseline; no newly failing tests are introduced. The remaining 13 also fail without the candidate and require separate environment or fixture diagnosis. This is not a passing full TUI gate.

An earlier local launcher omitted `RUST_MIN_STACK=8388608`, causing unrelated stack overflows; that run was interrupted and excluded from the comparison. The same overflowing test passes with the required stack input. Four remaining terminal-cursor failures also pass when the local `NO_COLOR` setting is absent; WSL key hints still differ because the kernel independently identifies WSL. Neither host behavior is changed by the patch.

The emitted patch applies through `git am` after patches 0001–0005 on a fresh stable base, and both resulting files match the tested candidate exactly. Rust formatting checks pass. Evidence: `tui-release-version-red.*`, `tui-release-version-fixture-probe.*`, `tui-release-version-full-before.*`, `tui-release-version-full-probe-2.*`, `tui-release-version-full-comparison.json`, and `tui-version-patch-verified.receipt`. Temporary generation and verification worktrees were removed. Native full-suite verification remains required; release executable smoke checks independently verify version `0.164.0`.

## Use development package metadata in daemon fixtures

`0007-use-development-package-fixture-version.patch` sets only the packaged-daemon fixture's version to `0.0.0`. A stable version enables automatic updates, contradicting the existing bootstrap assertion that this development package has updates disabled. Runtime package versioning and updater behavior remain unchanged.

Native probe 40177231648 reproduces the original bootstrap failure, then passes all four package tests with this single-line change. Its source-restoration receipt verifies the original bytes afterward. The emitted patch applies to a fresh stable checkout through `git am`, and its result matches the tested candidate exactly. Evidence: `package-fixture-patch-verified.json` and `native-failure-probe-artifact`. The probe workflow itself exits unsuccessfully because its capacity-monitor cleanup fails; phase receipts independently establish the four passing tests. The full cumulative test gate remains pending.

## Propagate generated compiler metadata to integration tests

`0008-propagate-integration-test-compiler-environment.patch` passes the library's `rustc_env_files` to all three integration-test target forms in `defs.bzl`. The bundled sandbox helper enforces its generated bubblewrap digest, but integration tests previously lacked that metadata and incorrectly selected a development-only system-binary fixture. Production digest verification and the existing release-fixture guard remain unchanged.

A new focused regression asserts that Bazel supplies the bundled digest to the sandbox integration-test binary. With `--test_arg=bazel_tests_receive_bundled_bwrap_digest`, it fails before the propagation repair and passes afterward: one selected test, 69 filtered out in each run. An earlier `--test_filter` attempt ran the entire Rust binary and is not focused evidence. The emitted patch applies to a fresh stable checkout and both resulting files match the tested candidate exactly. Evidence: `sandbox-compiler-env-{red-focused,green-focused}.*` and `sandbox-compiler-env-patch-verified.json`. Full native Bazel verification and the separate helper-path failure remain unmet gates.


## Resolve the sandbox fixture's executable before granting access

`0009-resolve-sandbox-helper-fixture-path.patch` resolves `CARGO_BIN_EXE_codex-linux-sandbox` through runfiles links before deriving the fixture's readable helper directory. Under Bazel, the minimal fixture originally grants access to the symlink's parent while the launched executable resides outside it. The sandbox correctly reports that the executable is unavailable. This adapts old-main issue 68 to the pinned stable source and preserves the permission profile, denied-egress assertions, compiler metadata and production sandbox behavior.

The existing bridge-routing regression fails with the missing executable before the candidate and passes afterward: one selected test and 69 filtered out in each run, using the same Bazel command and normal test-owned temporary directories. The test observes the proxied response and denied direct egress; no assertion changes. Rustfmt and applied-source whitespace checks pass. All nine baseline patches and both policy patches apply through `git am` on a fresh stable checkout; the fixture and policy bytes match the active candidate exactly. Both owned worktrees are removed. Evidence: `sandbox-helper-path-{red,candidate}.*` and `sandbox-helper-path-patch-verified.receipt`. Full native Bazel acceptance remains separate.
