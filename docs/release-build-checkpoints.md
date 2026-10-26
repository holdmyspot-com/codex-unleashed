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

`CARGO_CACHE_PARTS=1` makes the cache transport compress Cargo output groups
separately with Zstandard. Dependency files, fingerprints and build-script
outputs are grouped by their Cargo output path and unit hash. Executables and
other metadata have separate groups; hardlinked files stay in the same archive.
This preserves Cargo's outputs without interpreting a package name as sufficient
proof of compiler, feature, target or profile compatibility.

A manifest references the current groups. Each group records a hash of its
relative paths, file contents, modes, sizes, modification timestamps and symlink
targets. A subsequent checkpoint references unchanged groups directly, without
compressing or uploading them again. Only new or changed groups require archive
uploads. Removing a group removes it from the next manifest. Every snapshot is
self-contained: restoring it does not require replaying earlier manifests.
Archives are uploaded before publishing the new manifest, so an interrupted save
leaves the preceding published checkpoint available. Restoration overlays the
snapshot onto the selected output directory; Cargo and the freshness helper
remain responsible for validating and invalidating outputs.

The transport uses distinct `-parts-v1` reference suffixes, including separate
partial references and compatible aliases. Old clients continue using their
existing single-archive references. New clients can read those legacy references
when a multipart reference is absent. Both formats retain the existing
`cargo-release-<identity>-<upstream-release>` association for retention. Cache
visibility and authentication requirements stay private.

## Verification and performance

Run `.github/workflows/release-checkpoint-tests.yml` for native archive and build
orchestration checks without compiling Codex. The real-ORAS local layout tests
exercise archive restoration, timestamp and hardlink retention, independent
groups, zero archive uploads for an unchanged checkpoint, partial/verified
isolation, compatible aliases and transport errors. The release-stage tests
exercise ordering, failure propagation and a real offline Cargo workspace with
shared dependency features. That fixture verifies that changing the original
bundle commands into executable stages reuses its previously built outputs.

These fixtures do not prove full Codex reuse or GHCR transfer performance. Run
the release workflow with caching to measure those boundaries. Its evidence
includes each executable's Cargo log, Cargo timing reports, build/checkpoint
durations, group counts, uploaded archive bytes and reused group counts. Complete
the required clean matrix separately before accepting the plan's cache gate.

The macOS ARM64 cold primary report contained 1,059 packages and 1,420 compilation
units. These counts do not predict an exact archive count: a package can have
separate build-script, host and target outputs, and metadata also needs transfer.
Independent cache groups do not make their compilation independently runnable.
This change saves between executables; it does not publish a snapshot while Cargo
is still writing an executable's dependencies. Further dependency build stages
require evidence that their feature resolution preserves reuse. Single
compilations and links may still exceed five minutes; the scheduler checkpoint
must investigate those timings rather than infer a cache miss from duration.
