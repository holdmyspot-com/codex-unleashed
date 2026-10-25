# Cache migration preflight

Step 2 remains gated on the passing step-1 baseline. This document records a read-only review of old main for later migration; it does not enable caching or establish that any old implementation is correct for the new build.

The current baseline is upstream `rust-v0.164.0`, commit `6aaac694803004a63c6f393d153c96d22521a6b9`, with Rust 1.99.1. The reviewed old-main commit is `545479059ac86394049b6efd3bf4297b1ca18d17`. Its `docs/BUILD_CACHE.md` describes Actions caches for downloads, public GHCR Cargo target trees, successful source snapshots, and two-stable-release retention. The new branch's approved scope remains in [plan.md](../plan.md); current baseline evidence remains in [build-baseline.md](build-baseline.md).

## Implementation facts

The following files are under `tooling/cli/src/main/java/com/holdmyspot/codexunleashed/tooling/` at the fixed old-main commit:

- `SourceInputCache.java` coordinates preparation and successful recording. Missing successful snapshots trigger Cargo cleanup of workspace packages while retaining external dependencies. It checks requested binary ownership through Cargo metadata. Its cleanup command uses `--release`, and its binary-output path ends in `release`.
- `SourceInputInventory.java` hashes tracked and nonignored untracked regular files from the Git root, with a filesystem fallback. It excludes the compiled target and selected cache directories and records file and ancestor timestamps.
- `SourceInputChanges.java` compares current, successful and interrupted inventories. It restores unchanged timestamps and marks changed inputs and their ancestors for Cargo. Filesystem paths must remain confined to the checkout.
- `SourceInputSnapshots.java` atomically replaces successful snapshots after checking prepared inputs. Its recording comparison ignores every `Cargo.lock` basename, rather than distinguishing workspace-version synchronization from dependency changes.
- `CargoCacheManifest.java` inventories target, registry archive and Git-cache file sizes. It does not decide compiler compatibility or source invalidation.

Preparation always invalidates requested release binary outputs, including binaries whose libraries remain reusable. That is an observable implementation choice to measure, not evidence that all those rebuilds are necessary. The compiler-compatibility key, archive transfer and retention implementations have not been reviewed by this preflight.

## Checks required before reuse

The baseline exercises development, `ci-test` and release profiles. Validate cache storage, compatibility and cleanup for the consuming profile instead of copying the helper's release-only assumptions into all checks. Retain the selected toolchains, default features, warning policy, source identity and executable behavior.

Use the maintained Java command boundary when migration begins, with only the tooling needed by the consuming capability. Verify its relevant tests and bundled runtime on supported hosts; no tooling source is imported by this review.

Demonstrate cold and warm behavior, actual cache hits, a representative changed source rebuilding its consumers, and unchanged resulting source retaining reusable work. Verify that executable identity remains correct after reuse. Exercise interrupted-build recovery, missing snapshots, changed build inputs and cache-size reporting at the actual build boundary.

Use isolated cache fixtures for dependency-change cases; keep Codex dependency updates in step 7. Before retaining the lockfile exception, exercise dependency-resolution changes and generated workspace-version synchronization separately. The baseline already proves that its release tag requires exactly 166 workspace package-version synchronizations with unchanged external dependency records. That narrow result does not validate arbitrary lockfile mutations or prove the old exception defective.

Measure archive size, restore/save duration and peak disk use on free standard GitHub runners. The removed local Bazel disk cache was a separate redundant store; removing it did not demonstrate that a compiled Cargo archive fits or restores efficiently. Provider selection, public-cache contents and the approved retention policy still require their own verification.
