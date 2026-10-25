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

Preparation always invalidates requested release binary outputs, including binaries whose libraries remain reusable. That is an observable implementation choice to measure, not evidence that all those rebuilds are necessary. The source-input helpers alone do not establish compiler compatibility or provider behavior; the additional review below traces their release-workflow callers and storage script.

## Cache identities and storage ownership

The fixed old-main `cache/ReleaseCacheKeys.java` derives download identity from the Rust target, compiled-target identity from the target plus compiler mode and fingerprint, and V8 identity from the target plus resolved crate version. Both Unix and Windows callers in `.github/workflows/build-release.yml` compute that fingerprint as SHA-256 of `rustc -Vv`. The target tag is `cargo-v2-<target>-<mode>-<fingerprint>`; the download key starts with `codex-release-downloads-v5-`. Release-qualified Actions prefixes provide retention association separately from those compatibility inputs.

The mode distinguishes ordinary compilation from an optional deterministic compiler experiment. Its old workflow sets `RUSTFLAGS` and `RUSTC_BOOTSTRAP` only for that experiment. Step 2 must preserve the verified ordinary baseline; copying the key helper does not authorize those compiler changes or copying old main's Rust 1.99.0 pin over the baseline's 1.99.1.

The release workflow gives GitHub Actions ownership of dependency downloads, explicitly disables target-directory and installed-binary caching there, and gives the optional GHCR path ownership of compiled Cargo target trees. Its GHCR restore reports an unusable cache and continues with an ordinary Cargo build. After restore, it invalidates the requested release executables and later records successful source inputs. These are concrete old-main behaviors, not evidence that restore succeeded or that every invalidated executable needs rebuilding.

The complete `.github/scripts/ghcr-cargo-target-cache.sh` review finds these additional contracts:

- Pull first requests the compiler-qualified tag, then falls back to a release-qualified legacy tag if that request fails. The legacy fallback does not carry the compiler fingerprint in its name; Cargo's actual compatibility decisions need verification before retaining this path.
- Pull downloads a compressed archive into a separate runner-temporary directory, extracts it into the target directory, then removes matching release executables and symbols. Push creates another compressed copy of the entire target tree before upload. Measure archive-plus-extraction peak storage on free runners, including the existing output tree; compressed size alone is insufficient.
- Push publishes a release-qualified alias before attaching the shared compiler tag. This lets pruning associate shared manifests with releases. The public archive's actual contents and consumer verification remain untested by this read-only review.
- Push calls GHCR pruning immediately. The script exposes no dry-run operation; prepare and verify a preview before using its deletion path during migration. No prune, upload or cache restore has been invoked here.

## Retention boundaries

`cache/CacheRetention.java` selects the two newest distinct stable tag strings using numeric version ordering, rejects an empty stable-release inventory, and classifies only its recognized managed cache families. `cache/CachePruner.java` adds explicitly protected stable references to that retained set. It validates the complete obsolete Actions-cache inventory before deleting sequentially, stops on a command failure, and offers dry-run reporting.

The maintained `.github/workflows/cache-cleanup.yml` passes the repository's pinned `.github/upstream-ref` as a protected reference. The GHCR script also preserves that reference and, during push, the release being built. It retains a package version if any release-qualified Cargo alias belongs to the protected set, because deleting a version deletes all its aliases; it also selects untagged versions for deletion. Verify these manifest-level decisions with shared aliases and the frozen baseline outside the newest two releases before enabling retention. This review does not authorize deleting any current cache or establish how concurrent writers behave.

## Checks required before reuse

The baseline exercises development, `ci-test` and release profiles. Validate cache storage, compatibility and cleanup for the consuming profile instead of copying the helper's release-only assumptions into all checks. Retain the selected toolchains, default features, warning policy, source identity and executable behavior.

Use the maintained Java command boundary when migration begins, with only the tooling needed by the consuming capability. Verify its relevant tests and bundled runtime on supported hosts; no tooling source is imported by this review.

Demonstrate cold and warm behavior, actual cache hits, a representative changed source rebuilding its consumers, and unchanged resulting source retaining reusable work. Verify that executable identity remains correct after reuse. Exercise interrupted-build recovery, missing snapshots, changed build inputs and cache-size reporting at the actual build boundary.

Use isolated cache fixtures for dependency-change cases; keep Codex dependency updates in step 7. Before retaining the lockfile exception, exercise dependency-resolution changes and generated workspace-version synchronization separately. The baseline already proves that its release tag requires exactly 166 workspace package-version synchronizations with unchanged external dependency records. That narrow result does not validate arbitrary lockfile mutations or prove the old exception defective.

Measure archive size, restore/save duration and peak disk use on free standard GitHub runners. The removed local Bazel disk cache was a separate redundant store; removing it did not demonstrate that a compiled Cargo archive fits or restores efficiently. Provider selection, public-cache contents and the approved retention policy still require their own verification.
