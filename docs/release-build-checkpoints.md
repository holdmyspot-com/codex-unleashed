# Release build checkpoints

The release builder runs each required executable separately, preserving default
Cargo features, release settings, the target and its existing output directory.
It builds the responses proxy and code-mode host before the CLI and app-server.
Linux first builds and hashes bubblewrap; Windows also builds its three helpers.
There are four executables on macOS, five on Linux and seven on Windows: eight
distinct executables across the release matrix. Code-mode host is built once.

When caching is enabled, each completed executable triggers source and dependency
identity checks followed by a private GHCR partial-cache save. Compilation or
checkpoint failure stops further stages. The normal final smoke checks, source
and dependency checks, and source-input recording still precede verified saves.
A completed intermediate checkpoint does not mark the build successful.

## Independently transferable output groups

`CARGO_CACHE_PARTS=1` makes the cache transport compress each build step's outputs
together with Zstandard. A checkpoint passes its executable name through
`CARGO_CACHE_STAGE`. Newly encountered executable, dependency, fingerprint and
build-script files join that step's group. Existing files retain their first
owner; changing one updates that owner's archive. Hardlinked files stay together.
This groups the outputs without treating a package name as proof of compiler,
feature, target or profile compatibility.

Directories, Cargo timings, input-freshness snapshots and the ownership record
share a metadata group. Adding a file changes directory timestamps, so keeping
directories separate avoids repacking an earlier large output group solely for
that change. The cache carries `.codex-cache-groups.json`, whose paths are
relative to the Cargo target directory and use `/` separators. A restored cache
retains that ownership on another runner. Imported caches without ownership use
one `bootstrap` output group; new outputs discovered during the final or failed
save use `final`. Ordinary cold runs therefore have one group per populated build
step plus metadata, rather than one group per Cargo unit.

A manifest references the current groups. Each group records a hash of its
relative paths, file contents, modes, sizes, modification timestamps and symlink
targets. A subsequent checkpoint references unchanged groups directly, without
compressing or uploading them again. Final publication also considers the latest
partial checkpoint so it can reuse outputs uploaded between steps. Only new or
changed groups require archive uploads. Removing a group removes it from the next
manifest. Every snapshot is
self-contained: restoring it does not require replaying earlier manifests.
Archives are uploaded before publishing the new manifest, so an interrupted save
leaves the preceding published checkpoint available. Restoration overlays the
snapshot onto the selected output directory; Cargo and the freshness helper
remain responsible for validating and invalidating outputs.

The workflow restores every group before the first executable build, downloading
and extracting one group at a time. Checkpoints record complete output snapshots,
not the groups required by each executable. Restoration remains up front; the
owner has chosen to retain this behavior rather than add on-demand restoration.

The transport writes distinct `-parts-v2` reference suffixes, including separate
partial references and compatible aliases. It reads the earlier `-parts-v1`
Cargo-unit format and then the legacy single archive when the corresponding new
reference is absent. Existing outputs can therefore be regrouped without a cold
build when an exact or compatible old reference exists. Old clients retain their
own namespaces. All formats retain the existing
`cargo-release-<identity>-<upstream-release>` association for retention. Cache
visibility and authentication requirements stay private.

## Verification and performance

Run `.github/workflows/release-checkpoint-tests.yml` for native archive and build
orchestration checks without compiling Codex. The real-ORAS local layout tests
exercise archive restoration, timestamp and hardlink retention, independent
step-sized groups, reuse of earlier steps, zero archive uploads for unchanged
checkpoints and final publication, relocation, older-format reads, partial/verified
isolation, compatible aliases and transport errors. The release-stage tests
exercise ordering, failure propagation and a real offline Cargo workspace with
shared dependency features. That fixture verifies that changing the original
bundle commands into executable stages reuses its previously built outputs.

These fixtures do not prove full Codex reuse or GHCR transfer performance. Run
the release workflow with caching to measure those boundaries. Its evidence
includes each executable's Cargo log, Cargo timing reports, build/checkpoint
durations, group counts, uploaded archive bytes and reused group counts. Complete
the required clean matrix separately before accepting the plan's cache gate.

The earlier Cargo-unit grouping's native fixture [run `40332295510`](https://github.com/holdmyspot-com/codex-unleashed/actions/runs/40332295510)
passes on all six release hosts at `f9745689f29b7e3f81c3fa643824d8ac5ac45337`.
Windows verification resolves Git Bash and GNU tar explicitly at the native
Python launch boundary. The [failure comparison](upstream-failure-comparison.md)
retains the failed launcher results and passing counterfactuals. Full Codex
multipart build, restoration and transfer measurements remain pending.

The build-step grouping regression first observes 37 archives for one fixture
step with 32 dependencies, then passes with two archives: outputs and metadata.
A second step adds one archive and retains the earlier output blob. Local
real-ORAS checks pass final-save reuse with both older verified and older partial
state, cache relocation, imported bootstrap ownership, legacy and earlier-parts
reads, removed files, hardlinks and exact timestamps. Native fixture
[run `40360535128`](https://github.com/holdmyspot-com/codex-unleashed/actions/runs/40360535128)
passes on all six release hosts at `32cb038e1e4ee6835cddc9247bea2f265a24a31e`.
Actual Codex transfer measurements remain a separate gate.

Actual Linux x86-64 [run `40332032199`, attempt 1](https://github.com/holdmyspot-com/codex-unleashed/actions/runs/40332032199/attempts/1)
ends with exit 143 and the runner's explicit shutdown-signal diagnostic during
the CLI build. Final smokes, guards and artifact collection do not run. The
surviving job log records these completed partial checkpoints:

| Executable | Build seconds | Checkpoint seconds | Snapshot groups | Reused groups | Uploaded archive bytes |
| --- | ---: | ---: | ---: | ---: | ---: |
| bwrap | 10 | 12 | 35 | 0 | 13,316,512 |
| codex-responses-api-proxy | 84 | 79 | 293 | 27 | 248,743,957 |
| codex-code-mode-host | 263 | 159 | 795 | 285 | 619,250,083 |

This establishes intermediate publication and group reuse, not a successful
Codex build or restored checkpoint acceptance. The host reports shutdown but
does not identify its initiating cause. A focused same-revision retry checks
restoration of the published partial state; it does not recover work compiled
after the last completed checkpoint.

Attempt 2 restores the 795-group partial snapshot, completes every required
executable and reaches the final verified-cache save. CLI and app-server builds
take 2,362 and 952 seconds; their partial checkpoints take 334 and 40 seconds.
The final save uploads a 1,511-layer manifest at
`sha256:29d8f96e8048c534145aae540d3c3f1d9b23daf1db44e097813bf7fdfd36d6ea`,
then fails when `oras tag` immediately tries to resolve its release tag and GHCR
returns `not found`. The failure handler saves partial state successfully.
This run fails; it does not establish a verified successful cache.

Read-only hosted [inspection `40365166154`](https://github.com/holdmyspot-com/codex-unleashed/actions/runs/40365166154)
subsequently fetches that exact manifest by both release tag and digest. Package
metadata confirms the original publication and partial aliases. The registry's
reason for the earlier lookup failure remains unknown. Publication now passes
the release, exact and compatible tags to ORAS together, using its local
manifest rather than looking up a newly written tag. A local regression supplies
the observed tag-lookup failure and verifies successful publication, alias
resolution and restoration for both grouped and legacy archives. Native fixture
[run `40365691558`](https://github.com/holdmyspot-com/codex-unleashed/actions/runs/40365691558)
passes all six release hosts at `d50b36a7a185e8b7a04554229797a1c8fa8afb9b`.
These fixtures use local OCI layouts. Actual Linux
[run `40365751436`](https://github.com/holdmyspot-com/codex-unleashed/actions/runs/40365751436)
passes at the same revision, including compilation, both native smokes,
source/dependency guards, source recording and private GHCR publication. It
restores the preceding 1,511-part snapshot in 710 seconds and reports zero
compilation entries for all five executable invocations (9/1/1/1/2 seconds).
The job takes 25m 06s and saves a verified two-group manifest at
`sha256:7e41c97400e303c08e9be0462802b25054f442232e37b63c82de84a18cb794c8`.
Artifact `12672905434`, SHA-256
`62a021ad9e5aca2bff3cd189449caa12c657716da5c415bfc472d6283a3512b4`,
retains the checked logs and inputs.

Transfer reuse is incomplete: every executable checkpoint changes the imported
bootstrap group's fingerprint and uploads roughly 2.9 GB again. Checkpoints
take 109/91/88/88/88 seconds. The final save reuses that group and uploads only
947,001 metadata bytes in 21 seconds. Hosted read-only comparison
`40370295206` confirms distinct bootstrap fingerprints and archive digests
between executable checkpoints, eliminating an unchanged-group lookup miss as
the explanation. Read-only header comparison `40370722901` finds only the
`codex-responses-api-proxy.d` timestamp changing between the first two output
archives. That refresh alone invalidates the entire imported group while Cargo
reuses compiled outputs. Header comparison does not establish equality of all file
contents. The transport now assigns `.d` dependency reports to metadata and
continues saving their contents and timestamps. A real-ORAS regression first
fails because a report update changes the compiled archive's digest, then passes
with that archive reused and the updated report restored. Preserve these
successful build results while verifying this correction on native hosts and
the actual grouped build; grouped warm comparison, remaining platforms and the
clean matrix remain unmet gates.

The macOS ARM64 cold primary report contained 1,059 packages and 1,420 compilation
units. These counts do not predict an exact archive count: a package can have
separate build-script, host and target outputs, and metadata also needs transfer.
Independent cache groups do not make their compilation independently runnable.
This change saves between executables; it does not publish a snapshot while Cargo
is still writing an executable's dependencies. Further dependency build stages
require evidence that their feature resolution preserves reuse. Single
compilations and links may still exceed five minutes; the scheduler checkpoint
must investigate those timings rather than infer a cache miss from duration.
