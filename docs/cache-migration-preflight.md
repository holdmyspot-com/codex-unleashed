# Cache migration preflight

The owner now authorizes step 2 to overlap with step 1 for build paths already verified to work, using old main’s GHCR compiled-output and Actions dependency-download approach. This document retains the preflight review and verified transport results; complete cache acceptance still requires warm reuse and invalidation evidence. It does not authorize upstream source/test patches. Initially the eligible release builds are Linux x86-64/ARM64 musl and Windows ARM64 MSVC.

The current baseline is upstream `rust-v0.164.0`, commit `6aaac694803004a63c6f393d153c96d22521a6b9`, with Rust 1.99.1. The reviewed old-main commit is `545479059ac86394049b6efd3bf4297b1ca18d17`. Its `docs/BUILD_CACHE.md` describes Actions caches for downloads, public GHCR Cargo target trees, successful source snapshots, and two-stable-release retention. The owner has changed the new branch's policy to private GHCR caches with authenticated workflow access; anonymous access is not an acceptance requirement, and organization visibility settings remain unchanged. The new branch's approved scope remains in [plan.md](../plan.md); current baseline evidence remains in [build-baseline.md](build-baseline.md).

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

## Current private-registry retention inventory

The read-only [cache-retention-inventory.yml](../.github/workflows/cache-retention-inventory.yml) workflow collects paginated upstream
release metadata and private package-version metadata with `packages: read`.
It has no deletion permission or command. Run `40396840902` at
`b11b37a2e8326938b950178efd3935584366670c` first receives HTTP 504 while listing
upstream releases, before package enumeration. Its unchanged read-only retry
passes in 1m 22s. The local token separately lacks `read:packages`; that local
permission failure is not evidence that the workflow token cannot read packages.

Successful artifact `12685459536` has SHA-256
`ce023de1ca19814aafac75e08003cfb674922ce22fe05c4f4fe9fca83ef600fa`, verified
before analysis. It contains 1,245 release records and 124 package versions.
Applying the fixed old-main selection rules retains `rust-v0.164.0` and
`rust-v0.163.0`, with the pinned baseline included explicitly. All 49 tagged
versions have a retained release association, including all ten tagged
`-parts-v2-partial` versions. The 75 obsolete candidates are all untagged.
No package version is deleted. This verifies the current tag association against
old main's policy; it does not establish safe concurrent deletion or complete
the retention-tool migration.

Integrated preview run `40401691282` at `4120107dabe19dd3864f145dd57b0b39cda50879`
fails in both attempts while listing upstream releases, before package collection
or helper compilation. Each reports GitHub HTTP 504 and retains only the three
pre-package files. A local request for the same 100-release REST page reproduces
the timeout. The supported 20-release page succeeds, and its complete pagination
returns all 1,245 unique tags with every retained metadata field identical to
the verified earlier inventory. The workflow now uses that smaller page size;
hosted integration remains unverified. GitHub's internal timeout cause is unknown.
Old main's smaller GraphQL field query was also evaluated: it returns only
1,000 releases, omitting 245 captured tags, so it is not substituted for the
complete inventory.

Hosted run `40404430412`, job `121858399002`, at
`ea688c75dceab7d725de25d5027686582a66a9d1` passes the integrated read-only
workflow in 2m 13s. It captures 1,245 distinct releases and 137 private package
versions, passes all 37 helper tests and runtime assembly, and previews 52
retained versions, 85 obsolete candidates and zero unmanaged versions. The
selected stable releases are `rust-v0.164.0` and `rust-v0.163.0`. Artifact
`12690685477` has verified SHA-256
`036f29eb2014e06e8d49ed1613aa16467fc87f7c2a5150e7fc9ec3cd82e6f5d3`;
its three disjoint classification lists cover every captured package ID.
Nothing is deleted. This closes hosted preview integration, not automatic
retention or concurrent-writer safety. The executor retains the verified archive
in session directory `retention-hosted-LqCLCD` through retention checkpoint
verification and owns cleanup after its last consumer.

The run also reports an unexpected `toolchain` input to the pinned Rust action.
That action's own definition fixes the toolchain to 1.99.1 and declares only
target/component inputs; its log confirms rustc 1.99.1 actually ran. The redundant
unsupported input is removed, preserving that immutable action and version.
Run `40404968404`, job `121860246662`, at
`1f4dc30343160fe88c2442029085b1c0d1643fa7` confirms the correction in 2m 15s:
zero check annotations, actual rustc 1.99.1, all 37 tests passing, runtime
assembly passing and the same 52/85/0 preview classification. No deletion runs.

The goal executor retains the verified archive and analysis in session directory
`retention-verified-nIxInb` through retention checkpoint verification and owns their
cleanup after the last verification consumer. Hosted artifacts retain the one-day policy.

## Read-only retention preview command

The bundled tooling CLI now provides `preview-ghcr-retention`. It accepts one
JSON object on standard input with `releases`, `package_versions` and
`protected_upstream_refs` arrays. Release and package records use the fields
collected by the inventory workflow above. Join those captured inputs with:

```sh
jq -n \
  --slurpfile releases upstream-releases.jsonl \
  --slurpfile versions package-versions.jsonl \
  --rawfile pinned protected-upstream-ref \
  '{releases: $releases, package_versions: $versions,
    protected_upstream_refs: [$pinned | rtrimstr("\n")]}' \
  | codex-tooling preview-ghcr-retention
```

The command reuses old main's numeric stable-version ordering and manifest-level
Cargo alias policy, using the existing Jackson dependency for JSON. It excludes
drafts and prereleases, selects the newest two distinct stable tags and adds
explicit protected stable references. Any matching Cargo alias protects the
whole package version. Untagged versions and other Cargo versions are reported
as obsolete candidates; unrelated tagged versions are unmanaged. All fields and
unique positive integer version IDs are validated before any preview is emitted.
No stable release, missing tag arrays, duplicate IDs and malformed JSON fail
without printing a candidate list. IDs retain inventory order.

The maintained dispatcher regression first returns status 2 for the absent
command, then passes shared-alias, partial-alias, pinned-baseline and numeric
ordering cases after implementation. Its invalid-inventory cases verify refusal
without partial output. All 37 tooling tests and bundled runtime assembly pass
with JDK 27 and Rust 1.99.1 using the documented `-Pruntime verify` command in
[source-cache-migration.md](source-cache-migration.md). The packaged launcher also
runs with an empty `PATH` and no `JAVA_HOME` against the hash-verified private
inventory above: it retains all 49 tagged versions and reports exactly the same
75 untagged candidates, with zero unmanaged versions and no diagnostics.

This command has no registry client or deletion operation. Its `dry_run: true`
result is a point-in-time policy preview, not permission to delete candidates
while writers can publish new aliases. Automatic pruning and the concurrent
writer/deletion gate remain unimplemented on `main.new`. Old main's existing
cleanup remains active, as documented below. The goal executor retains the preview
inputs, recreation proof and receipt in session directory
`retention-preview-Oe7oyA` through retention checkpoint acceptance and owns their
cleanup then; the previously captured inventory remains available alongside it.

## Interaction with old main's active cleanup

Retention must preserve the pinned restart baseline, but the default branch's
active cleanup protects its own `rust-v0.163.0` pin and the newest two stable
releases, so it selects a restart-only `rust-v0.164.0` manifest for deletion once
two newer stable releases exist. D8 remains open until the actual cleanup
consumers preserve the restart pin or operate on separate owned cache packages,
with verification covering both branches and concurrent publication.

The default branch is still `main`. Its scheduled cleanup run
[`40313104630`](https://github.com/holdmyspot-com/codex-unleashed/actions/runs/40313104630)
passes at `f2830d912540ebe647d1e11b20c3121c47ccbbb0`. The workflow and pruning
script fetched at that exact commit target the same
`ghcr.io/holdmyspot-com/cargo-cache` package used by the restart. The script's
SHA-256 is `5b109eee567af3a63abda6ba079a4503e2fef788b514078100a3db3704f05e07`.
The latest stable release is currently 0.164.0, so this observation does not
establish that any current restart cache has been deleted.

A local, compilation-free check executes the actual pruning script's AWK
classifier against synthetic package-version and retained-release inputs:

| Retained releases | Manifest aliases | Deletion candidate |
| --- | --- | --- |
| 0.164.0, 0.163.0 | Restart alias ending in 0.164.0 | None |
| 0.166.0, 0.165.0, 0.163.0 | Restart alias ending in 0.164.0 | Version 42 |
| 0.166.0, 0.165.0, 0.163.0 | Old-main alias ending in 0.163.0 | None |
| 0.166.0, 0.165.0, 0.163.0 | Both 0.164.0 and 0.166.0 aliases | None |
| 0.166.0, 0.165.0, 0.163.0, 0.164.0 | Restart alias ending in 0.164.0 | None |

All five expected classifications match. These are classifier results, not
registry deletion tests; no registry state is changed. The fixture uses tags
`cargo-release-example-rust-v<version>` and package version ID 42. Its owner
removes the temporary receipt after committing this evidence. Grouped-cache
archives are blobs listed directly in the retained manifest's layers, rather
than independently prunable package versions; protecting that manifest is the
relevant retention boundary.

The same old-main cleanup also invokes `prune-actions-caches`. Its inspected
`CacheRetention` classifier recognizes stable-release associations and selected
legacy prefixes. The restart's Cargo download prefix is
`upstream-<source-commit>-downloads-v1`, so source comparison places those keys
outside those managed families. This is classifier analysis, not a hosted
deletion test. Associating restart dependency-download caches with the approved
two-stable-release retention policy remains part of D8; moving compiled caches
to another GHCR package does not implement that Actions retention policy.

## Private package isolation transfer proof

The focused [cache-package-transfer.yml](../.github/workflows/cache-package-transfer.yml)
workflow verifies the existing ORAS copy operation before moving build caches.
Run [`40501668304`](https://github.com/holdmyspot-com/codex-unleashed/actions/runs/40501668304)
at `e95e2aff946422c24a100aa6394341b820fd15e6` passes transfer, consumption
with a fresh job token, and owned-fixture cleanup. Both package metadata responses
report private visibility; anonymous resolution is rejected with authentication
required. The source and both destination aliases resolve to
`sha256:3f55c33c555f406046915bcdb5a479ebb04051f1568290afff73014f955ab727`.
The copy reports a server-side mount of the payload blob, and the independent
consumer pulls byte-identical content. This verifies tiny-artifact transfer and
workflow access to `ghcr.io/holdmyspot-com/cargo-cache-main-new`; real compiled
cache migration and subsequent restore remain unrun.

The first run, `40463370931`, already verifies the private copy and server-side
mount, then fails because the runner lacks ripgrep. Its cleanup deletes source
version `1452472689`, but GHCR rejects deleting the destination package's last
tagged version. The correction uses available grep and preserves a last tagged
bootstrap rather than deleting the package and its access setup. The successful
run deletes only its owned source/destination versions `1454098082` and
`1454098221`, after checking tags and the fixture ownership annotation.
The migration executor owns the earlier bootstrap, identified by tag
`cache-transfer-fixture-40463370931-1` and digest
`sha256:302a00900e339ef6403d59366942ab756c5ac5aaf0ccbc9fc4e4f0678d1fd4d5`,
and must remove it after real cache versions populate the destination.

Next copy the verified restart manifests and their aliases by immutable digest,
verify destination identities, then switch the build and retention consumers to
the isolated package. Keep the original caches available until actual restoration
passes; do not recompile merely to populate the new package. Old main remains
unchanged. Automatic retention and concurrent publication/deletion acceptance
remain open.

Migration run [`40502368002`](https://github.com/holdmyspot-com/codex-unleashed/actions/runs/40502368002)
at `ca5d6d6ac6a0e2be30af52def835b3c4eabff655` passes in 51 seconds.
It copies eight current compatible-cache manifests across all six targets and
verifies all 24 destination aliases against their source digests. Its
copy logs report 41 server-side blob mounts, seven existing blobs and zero
archive download/upload operations.
The request is [cache-package-migration.tsv](../.github/cache-package-migration.tsv);
the workflow also captures current compatible aliases before copying. No source
cache is deleted and no compilation runs. The build and read-only retention
inventory now obtain the isolated package name from
[cargo-cache-package](../.github/cargo-cache-package). Native restoration from
that package remains unverified.

Earlier migration `40502021459` stops before copying because a historical
verified manifest has lost its compatible alias to a newer save. Follow-up
`40502205292` copies the current macOS ARM64 manifest, then stops on an absent
historical manifest. The captured inventory contains neither the recorded Linux
ARM64 cold digest `03063c5540d04908edc5b5e3bc0ef37b2e6ec6d19a765e21574e8886406939d2`,
macOS ARM64 cold digest `fb3c9dad00eb827f446cc0294c44d7f2df5eeaaa3fab1688fe57bb5ca0bcf292`,
nor Windows ARM64 cold digest `6ac592bbee6fa80facec6133ab448b2c4e47cd91cb662ba43b46d1aef4800d82`.
Their removal mechanism is unobserved. Current compatible snapshots replace
these historical request entries; migration acceptance does not imply the absent
historical manifests can still be restored.

Isolated inventory run `40502660726` at
`4325b567859884b936eb0aea44ad4a390cd3dc61` passes the complete read-only
workflow in 2m 19s. Its preview retains all eight compiled-cache versions,
reports zero obsolete candidates and classifies the one bootstrap as unmanaged.
Follow-up migration/cleanup `40503122313` at
`613f90ee2caa0cdc081355659055be8bbec25f17` passes in 39 seconds. It verifies
the bootstrap's exact version ID `1452472811`, digest and complete alias set,
deletes only that version and confirms its absence from the resulting inventory.
This completes the bootstrap cleanup handoff; no real build cache is deleted.

For cache-only acceptance, dispatch the release workflow with `cache=true` and
`require_cache_hit=true`. The latter stops before compilation when every
compiled-cache lookup misses; its default remains false for cold population and
ordinary baseline builds. Expected-negative run `40508191624` at
`25697e6ad0c610fc569ddcf72ade15acc16aa923` rejects `require_cache_hit=true`
with `cache=false` during target selection and skips the build job. This checks
input validation only; miss rejection and successful hit-required compilation
still need their own execution evidence.

Focused Linux run [`40502660994`](https://github.com/holdmyspot-com/codex-unleashed/actions/runs/40502660994)
at `4325b567859884b936eb0aea44ad4a390cd3dc61` passes in 1h 26m 22s,
including both native version smokes, source/dependency guards and verified
private publication. All four initial lookups (exact/compatible, verified/partial)
report misses. Its compatible identity is
`cargo-seed-v1-x86_64-unknown-linux-musl-e6b99579981ac5ee52eb93f0af9d8d4395c568678cdf8d93fc8fe3f8d9cc8137`;
none of the three migrated x86-64 compatible aliases has that identity.
This is new population, not acceptance of restoration from a migrated cache.
The input receipt records runner image `20261026.354.1`; explaining each
difference from the migrated producers requires their matching input receipts.
No cache-compatibility check is relaxed to force a hit.

The five build logs contain 1,184 `Compiling` entries after removing terminal
color codes, summed across invocations rather than counted as distinct packages.
The CLI and app-server stages take 3,074 and 1,223 seconds. The final manifest is
`sha256:a835e91c5986dc744973dc09f1239d3337f15445a6f33baa3c44b9a4f7f8a23d`;
the final save reuses five checkpoint groups, uploads 1,392,131 archive bytes
and takes 18 seconds. Earlier checkpoint transfers are separate from that final
save. Downloaded artifact `12738697965` matches its API-reported digest
`sha256:ada7b5ff5c78b0d6ff242d41040b611cb838b184966213372fbb0ab13d13d05d`.
The root executor retains its archive and extracted evidence under the session's
`isolated-cache-acceptance-EUtyoV` until isolated-package acceptance is
recorded, then removes that owned directory. Follow-up `40508366323` requires
a cache hit before compilation; it is the next restore check, not another
unconditional cold population run.

## Checks required before reuse

The baseline exercises development, `ci-test` and release profiles. Validate cache storage, compatibility and cleanup for the consuming profile instead of copying the helper's release-only assumptions into all checks. Retain the selected toolchains, default features, warning policy, source identity and executable behavior.

Use the maintained Java command boundary when migrating source-input inventory, invalidation or retention helpers, with only the tooling needed by the consuming capability. The first transport migration retains old main’s Bash/ORAS archive boundary and lets Cargo decide which restored units rebuild. Java source-input helpers are now migrated as recorded in [source-cache-migration.md](source-cache-migration.md); automatic pruning remains disabled. Verify the relevant Java tests and bundled runtime on supported hosts before enabling those helpers.

Demonstrate cold and warm behavior, actual cache hits, a representative changed source rebuilding its consumers, and unchanged resulting source retaining reusable work. Verify that executable identity remains correct after reuse. Exercise interrupted-build recovery, missing snapshots, changed build inputs and cache-size reporting at the actual build boundary.

Use isolated cache fixtures for dependency-change cases; keep Codex dependency updates in step 7. Before retaining the lockfile exception, exercise dependency-resolution changes and generated workspace-version synchronization separately. The baseline already proves that its release tag requires exactly 166 workspace package-version synchronizations with unchanged external dependency records. That narrow result does not validate arbitrary lockfile mutations or prove the old exception defective.

Measure archive size, restore/save duration and peak disk use on free standard GitHub runners. The removed local Bazel disk cache was a separate redundant store; removing it did not demonstrate that a compiled Cargo archive fits or restores efficiently. Provider selection, cache contents, authenticated access and the approved retention policy require their own verification.

## Initial transport migration

The release workflow now offers `cache=true` for the three verified targets. Leaving it false preserves the six-target uncached baseline. The matrix records eligibility from build and native version-smoke evidence, not test-suite acceptance. Source and final source guards remain mandatory.

Compiled target trees use the old-main Bash/ORAS compressed-tar interface in `ghcr.io/holdmyspot-com/cargo-cache`; Cargo downloads use the old-main `Swatinem/rust-cache` approach with target and installed-binary caching disabled. The first `cargo-v3` identity hashes the exact source commit, Rust compiler identity, target, runner image, effective selected build flags, lockfile, toolchain declaration and cache/build wrappers. No compiler-only or legacy fallback is used. Restores invalidate top-level release executable aliases after extraction, retaining dependency hard links, and ordinary Cargo invocations and both native version smokes run again. Cargo still decides which units rebuild; source-timestamp inventory restoration is not implemented yet.

Each transfer records its reference, hit/miss, digest on a hit, elapsed seconds, archive/target sizes and filesystem capacity. A missing manifest permits a cold build; other lookup errors and all transfer/extraction errors fail visibly. Only successful compilation, both smokes and the pristine source guard permit upload. Release-qualified aliases retain compatibility with the approved retention association. Automatic pruning remains disabled until the retention preview is migrated and verified.

A real ORAS 1.3.1 local OCI-layout fixture verifies archive round-trip bytes, dependency hard links after executable alias invalidation, a changed-input cache miss and propagation of a simulated registry transport failure. A fractional-mtime regression reproduces the default archive's loss from `1700000000122999906` to `1700000000000000000` nanoseconds. Selecting GNU tar PAX format makes that same public push/pull regression pass with exact timestamp preservation. The test uses Node's filesystem API to create and compare the timestamps. ShellCheck, actionlint and whitespace checks pass. This tests transport behavior only; it does not establish hosted extraction of the new format or repair fresh-checkout input timestamps. Those gates still require hosted evidence.

## Freshness probe and Windows transport evidence

A no-dependency Cargo fixture using Rust 1.99.1 reports `fresh=false` on its cold build, `fresh=true` for an unchanged second build, and `fresh=false` after only the source modification timestamp advances. Source bytes stay identical. This demonstrates a timestamp-related reuse risk at Cargo's fixture boundary; it does not prove the behavior of a hosted Codex warm run. Retain the hosted cold/warm evidence before choosing the required source-input helper migration. No source timestamps are altered in the Codex checkout by this probe. Evidence is retained in the session's `cache-migration-40B3Cg/cargo-freshness-probe/verified.json`.

Focused Windows ARM run `40212093326` verifies the checksum-pinned ORAS Windows amd64 executable and native real-OCI transport fixture. Its later restore fails our tag validation because filename escaping adds a backslash to the displayed checksum. The stdin-hash correction is published at `c6b7572c8ed8ad9ead5a0ef438c6ab574813eb43`; retry `40212476702` subsequently passes the cold build and authenticated upload, as recorded below. Native fixture success and GHCR login alone do not establish registry writes, compiled-output reuse or capacity under full builds.

The first Linux cold-cache jobs in `40211546824` each pass the native archive fixture and see a registry cache miss, then receive runner shutdown signals during compilation (exit 143). Final guards, smokes and saves are skipped. The observed logs establish interrupted execution, not its underlying host cause or a cache-size failure. Targeted cold retries are `40213326832` (Linux x86-64) and `40213329117` (Linux ARM64), from restart `257a54b6ee6762a44861d7bcde886db6e8e80c31`. The ARM retry terminates after 59 minutes with GitHub reporting that the hosted runner lost communication. Its build step fails; final source guard, smokes, artifact upload and GHCR save are skipped. The job-log API returns 404, and the available workflow-log archive contains only the selector job. No build artifact survives. This establishes a lost runner, not a compiler, OOM, disk-full or cache-size cause. The x86-64 retry passes its cold build, as recorded below; the two runs must not be conflated. A source comparison against successful pristine release commit `1e74872eb42d3870ccf985c80bcc4f94e49710e1` verifies unchanged ARM runner label, upstream identity, build environment, prerequisites and serial default-feature Cargo commands. The added precompile operations are download caches, ORAS transport checks, GHCR login, key derivation and optional exact-key restore; cache save runs only after compilation and guards. This bounds the configuration differences but supplies no runtime resource measurement for the lost runner.

ARM attempt 2 retains runner diagnostics and fails after 12 min 50 s of compilation with exit 143 and shutdown classification `UserCancelled`. The initiating actor remains unknown after the available audit checks; no compiler, OOM, disk or source failure is established. Smokes, final guard and save are skipped, so this is not an accepted ARM cold cache build. See the [baseline investigation](build-baseline.md) and retained `linux-arm-cold-debug-1-verified.json`. The successful Linux x86-64 and Windows ARM cold saves below establish authenticated writes. Warm compiled-unit reuse remains unverified.

## Verified private-cache cold builds

Both successful cold runs retain the exact upstream commit, Rust 1.99.1, an empty initial source diff, default-feature release builds, CLI and app-server version smokes at 0.164.0, a passing final source/test guard, and a successful authenticated GHCR save. Both exact-key lookups are misses before compilation. These results establish cold population and measured capacity on those particular free hosted runners; they do not establish warm reuse or other platforms' capacity.

| Target and run | Exact cache key | Saved manifest digest | Target / compressed archive (KiB) | Free with archive (KiB) | Save time |
| --- | --- | --- | --- | --- | --- |
| Linux x86-64, [40213326832 attempt 1](https://github.com/holdmyspot-com/codex-unleashed/actions/runs/40213326832/attempts/1) | `3f27eaac5f9b4473c09454a68370dcced910d8c3c47f2dc04932adda8aa1eebd` | `sha256:6c1e806a22382e0f6e6cf33a8df95580404ef1dfd081c16d149c11bb9ea948a9` | 12,090,952 / 2,926,932 | 70,729,720 | 72 s |
| Windows ARM, [40212476702 attempt 1](https://github.com/holdmyspot-com/codex-unleashed/actions/runs/40212476702/attempts/1) | `38833935f6a751c28ab00796b6a25f4e51e96f07b8bd6f5aa5e466e4b6fc713c` | `sha256:944bfb1a561248a8f18bf1dca2ff961093607465881c16977e24584de5d56b3d` | 12,263,257 / 2,994,764 | 112,381,696 | 265 s |

Linux primary/app-server builds take 45 min 11 s / 15 min 05 s; its bwrap build takes 21.15 s. Windows ARM primary/app-server/helper builds take 56 min 54 s / 17 min 47 s / 5 min 48 s. These are cold durations, before the scheduler milestone.

The existing GHCR package is private. Anonymous lookup returns unauthorized, which is consistent with the approved policy and is not a failed cache gate. Attempt 2 of each successful run is an unchanged-workflow warm check, retaining the original workflow hash in the key. Acceptance requires an authenticated digest hit, measured Cargo reuse, both native smokes, source guards and transfer/capacity evidence. A changed runner image must be accounted for rather than counted as a same-input cache hit. Both warm attempts restore their original cold digests and produce working outputs, but recompile workspace packages; unchanged-work reuse remains unmet. Automatic retention deletion remains disabled.

### Linux x86-64 warm observation

[Run `40213326832` attempt 2](https://github.com/holdmyspot-com/codex-unleashed/actions/runs/40213326832/attempts/2), job `121245514027`, succeeds with both native 0.164.0 version smokes and pristine source guards. Its compiled-cache input file is byte-identical to attempt 1, and authenticated restore hits the original digest `sha256:6c1e806a22382e0f6e6cf33a8df95580404ef1dfd081c16d149c11bb9ea948a9` for key `cargo-v3-x86_64-unknown-linux-musl-3f27eaac5f9b4473c09454a68370dcced910d8c3c47f2dc04932adda8aa1eebd`.

Restore takes 106 seconds and leaves 71,726,476 KiB free. Despite that exact hit, Cargo recompiles bwrap in 16.72 seconds, the primary bundle in 38m 36s and app-server in 15m 58s. The primary log emits compile records for 147 workspace packages and V8 152.2.0; the separate app-server invocation emits one workspace compile record. Other dependencies emit no compile records. This supports partial reuse, not acceptable unchanged-workspace reuse. The warm build logs themselves contain no fingerprint dirty reasons. The corrected diagnostic below subsequently identifies the stale source and V8 input timestamps at the original cold-cache boundary.

The resulting target tree is 12,093,796 KiB; the archive is 2,926,996 KiB, leaving 70,525,484 KiB free. Save takes 85 seconds and produces digest `sha256:14d2fe9c6323d7fbd3a1eaa035321aa33a9c40e325a115df3adcc7db700bfb64`. The mutable tag now names that digest; a diagnostic comparing the original cold state must explicitly retain the original digest above. The subsequent fingerprint diagnostic below supplies the required dirty-reason evidence before input freshness restoration is changed. Cache transport and output behavior pass on this run; unchanged-work reuse and representative invalidation remain unmet.

A separate manual `diagnose-cargo-fingerprints.yml` workflow observes the original cold digest without saving compiled outputs or changing the production cache key. It retrieves the cold run's restart metadata, requires its exact compiled-cache identity (including runner image and flags), reproduces archive extraction and executable-alias removal, and records `cargo::core::compiler::fingerprint=info` for bwrap and the primary build. The primary command is deliberately bounded to 120 seconds; exit 124 records an incomplete observation, not a successful build. Its source guard and one-day evidence upload remain required. This diagnostic has no upstream workflow counterpart: it investigates our added compiled-output caching rather than an upstream source/test failure. Actionlint and whitespace validation pass; hosted fingerprint evidence is pending.

Fingerprint run `40224834342` verifies the original compiled-input identity, immutable digest restore and source guard, and records primary exit 124 as intended. It reproduces workspace compilation but emits no fingerprint messages: the old FAQ filter `cargo::core::compiler::fingerprint=info` does not match Cargo 1.99.1's current module. A same-toolchain fixture isolates the filter difference; `cargo::compiler::fingerprint=info` reports the stale source and reference mtimes, while the old filter remains silent. The diagnostic now uses the [current fingerprint module](https://doc.rust-lang.org/stable/nightly-rustc/cargo/compiler/fingerprint/index.html) and requires fingerprint messages from both observed commands.

### Confirmed Linux input-freshness causes

Corrected run `40225613962`, job `121266202699`, restores the original cold digest with the original compiled-input identity and passes initial/final source guards. Its bounded primary command exits 124; this remains an incomplete observation, not a completed build. Cargo emits 163 dirty entries: 160 `FsStatusOutdated`, two dependency-information changes and one `PrecalculatedComponentsChanged`. Direct stale inputs include 37 workspace files and the downloaded V8 binding file under `RUNNER_TEMP/rusty_v8`. For example, `utils/absolute-path/src/lib.rs` is newer than its cached fingerprint; CLI build-script discovery also sees a newer `tests/update.rs` timestamp. V8's regenerated binding timestamp independently invalidates V8 and its consumers.

The cache restores compiled outputs but does not restore the timestamps of byte-identical checkout or downloaded inputs. Those observed timestamp differences trigger dependent rebuilds. Old main's content-checked source timestamp inventory is suitable for checkout inputs, but its Git inventory omits this external V8 file and its former workflow prepares timestamps before V8 setup. Migration must include the actual binding input after verified materialization, preserve exact timestamps, and prove that changed content still rebuilds. Executable-alias removal may separately require executable work; it does not explain the observed library-input dirtiness. No upstream source/test defect is asserted by this downstream-only cache diagnostic.

### Windows ARM warm observation

[Run `40212476702` attempt 2](https://github.com/holdmyspot-com/codex-unleashed/actions/runs/40212476702/attempts/2), job `121248376529`, restores original cold digest `sha256:944bfb1a561248a8f18bf1dca2ff961093607465881c16977e24584de5d56b3d`. Its compiled-input file is byte-identical to attempt 1. Both native 0.164.0 version smokes and pristine source guards pass. Restore takes 171 seconds and leaves 115,701,520 KiB free. Primary/app-server/helper builds take 46m 32s / 17m 17s / 5m 37s, with 148 / one / two compile records. This is partial reuse, not accepted unchanged-workspace reuse; Windows-specific fingerprint reasons have not been observed.

The target tree is 12,264,009 KiB and compressed archive 2,994,836 KiB, leaving 109,882,424 KiB free. Save takes 248 seconds and updates the tag to `sha256:299a7e59fc17b55cac48d8bae53b6e88c1fdf49ed92a03289648aeb5df47c404`. Preserve the original immutable digest for comparisons. The Linux diagnosis does not establish the exact Windows cause.
