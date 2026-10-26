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
successful build results. Native fixture run
[`40371907954`](https://github.com/holdmyspot-com/codex-unleashed/actions/runs/40371907954)
passes on all six release hosts at `f550feafad644576c1d03bece323785a8850b30f`,
including compiled-archive reuse after a dependency-report update and restoration
of the updated report. Actual grouped Linux
[run `40371906652`](https://github.com/holdmyspot-com/codex-unleashed/actions/runs/40371906652)
then passes at the same revision in 8m 28s, including both native smokes,
source/dependency guards, source recording and verified private publication.
Restoration reads the preceding two-group manifest in 77 seconds. All five
executable invocations reuse Rust compilation outputs (9/1/1/1/1 seconds).
The first checkpoint converts the imported ownership to the new classification,
uploading 2,914,922,787 archive bytes in 115 seconds including guards. Subsequent
checkpoints reuse the compiled group, upload 2,393,167 / 2,393,161 / 2,393,982 /
2,393,625 metadata bytes, and take 20/18/18/19 seconds. The final verified save
reuses the compiled group and uploads 1,337,192 metadata bytes in 19 seconds,
publishing manifest
`sha256:f8846f63942738a59d0c079b7e3ddfb68ff91bcabce724a9b6af3b061fda69b0`.
Attempt 2 fails before cache access because the ORAS release download returns
HTTP 504 on all three setup attempts. Attempt 3 restores the exact newly saved
manifest and passes all build, smoke, guard and publication checks in 11m 36s.
It restores in 131 seconds and reuses all five compilations (14/2/1/1/1 seconds),
but still uploads 2,914,909,995 archive bytes at its first checkpoint. The earlier
conversion explanation therefore does not account for the cross-run change.
Later checkpoints reuse the compiled group and upload about 2.4 MB each in
25/17/18/17 seconds; the final save uploads 1,333,657 metadata bytes in 18 seconds.
Read-only comparison of the preceding verified archive and this first checkpoint
must identify what changed before any repair. Cross-run archive reuse, remaining
platforms, representative full Codex invalidation and the clean matrix remain
unmet gates.

Read-only run
[`40376256811`](https://github.com/holdmyspot-com/codex-unleashed/actions/runs/40376256811)
compares those two compiled archives and finds only the `bwrap` executable and
its hardlink with changed header timestamps. The build wrapper strips that file
on every invocation. The native release-stage fixture now supplies a fixed
nanosecond executable timestamp and asserts that the real strip operation
preserves it through the checkpoint. It fails with the previous command, then
passes with `strip --preserve-dates`; all four local release-stage tests and
ShellCheck pass. Native fixture run
[`40376947788`](https://github.com/holdmyspot-com/codex-unleashed/actions/runs/40376947788)
passes on all six hosts at `5e5f3407160dc337c04aa7e78b8a6d79c83390c3`.
This changes the downstream build wrapper only. Full Codex verification run
`40376998616` passes all five default-feature builds, both native smokes,
source/dependency guards and private publication. It saves six groups; the final
save reuses all five output archives and uploads 1,357,973 metadata bytes. The
CLI/app-server invocations take 38m 11s / 15m 41s; this population run does not
prove unchanged-input reuse across runners. Focused follow-up `40387733411`
combines that warm comparison with the changed-input check; its failure and
focused verifier repair are recorded below.

Several concurrent `gh run watch` processes using their default three-second
interval exhaust the caller's 5,000-request GitHub API allowance. Their HTTP 403
terminal results describe failed monitoring, not failed builds. Resume the same
run identities after the reset reported by the failed API response; leave hosted
jobs running. Use `gh run watch --interval 60 --exit-status` for ongoing checks.
The separate rate-limit endpoint returned a positive allowance while the failing
Actions endpoint reported zero; use the affected endpoint's response headers when
establishing whether that operation can resume.

Linux ARM64 run `40378411257` at
`d4676c21cd03aafab25af89efd355b41bf680a14` reaches CLI compilation but ends
with exit 143 in both attempts. Its retry restores completed checkpoints:
bwrap/proxy/code-mode-host invocations take 6/1/1 seconds, and each checkpoint
reuses all three output archives while uploading about 1.4 MB of metadata in
8/7/7 seconds. CLI compilation remains incomplete. Both attempts skip even the
`always()` evidence and source-guard steps; the surviving logs and check
annotations identify no signal sender, timeout or compiler error. The initiating
cause remains unknown. Do not count these attempts as successful builds or
repeat the same run without a new discriminating check.

The optional `trace_signals=true` diagnostic accepts only `cache=true` with
`target=aarch64-unknown-linux-musl`; selection rejects other combinations before
starting a build job. It runs the ordinary build wrapper under a signal-only
trace and streams SIGTERM delivery records, including sender information when
the kernel supplies it, into the job log. Syscall filtering reduces tracing
overhead. A local SIGTERM probe retains the sender and exit 143, and all four
release-stage fixtures pass under tracing. A host shutdown may still remove the
runner before a signal record survives; absence of a record cannot establish
the initiating cause. Ordinary runs keep tracing disabled. Run `40395599813`
uses this diagnostic at `ee711a9716fb4dfda3ab18f41256134b459dd5f4`;
job `121827730955` fails after the runner reports a shutdown signal and the build
exits 143. No SIGTERM sender record survives. Even the `always()` evidence and
guard steps are skipped. This narrows the observed boundary to runner shutdown,
but does not identify its requester or establish a compiler, memory or disk
failure. Keep ARM acceptance open; another identical retry would not distinguish
those causes.

The focused second attempt of `40395599813` enables GitHub runner and worker
diagnostic logging via `gh run rerun --job 121827730955 --debug`. The original
attempt is terminal before retrying; this preserves its source, selected ARM64
job and resumable checkpoints. The added observation is the runner diagnostic
archive, which was not enabled in the original attempt. Retrieve that archive
after completion and inspect shutdown/cancellation events before selecting a
build mitigation. See [GitHub's diagnostic logging contract](https://docs.github.com/en/actions/how-tos/monitor-workflows/enable-debug-logging).

That attempt is now terminal: job `121874632053` fails in 14m 32s, with the
release command returning 143 after 10m 41s. The attempt-2 log archive is
1,669,412 bytes, SHA-256
`286040acae7965e9e09c0255a523ffd649f4fc36c43005a61d31fec039b79cf8`.
It includes both runner and worker diagnostics for runner 2.338.0, source commit
`197ca027b90de199464dee3bb9ff198a1db60967`. The worker records process exit
143 followed by `Runner will be shutdown for UserCancelled`; no cancellation
channel message or signal-sender record survives. The listener's last recorded
lease renewal precedes shutdown by 16 seconds and remains valid beyond it.
This does not establish a job timeout, lease expiry, compiler error or human
cancellation. The [matching worker source](https://github.com/actions/runner/blob/197ca027b90de199464dee3bb9ff198a1db60967/src/Runner.Worker/Worker.cs)
uses the same shutdown label for its unloading handler, so the label alone
cannot identify the initiating actor. The next discriminating observation must
cover signal delivery to the runner processes, outside the traced build child.
No further identical retry is justified. The goal executor retains the archive
in session temp `arm-runner-debug-7v1SYj/logs.zip` until ARM cache acceptance
and owns its cleanup; the source, smoke and cache-save gates remain unrun.

Run [`40412484686`](https://github.com/holdmyspot-com/codex-unleashed/actions/runs/40412484686)
at `2701312d8cb1ea6d345b7e542da678305a209212` is a compilation-free ARM64
preflight for kernel signal observation. It installs bpftrace, starts an owned
observer and marker, waits for a recorded SIGCONT from the known Python sender,
then verifies a matching SIGTERM record and the marker's signal exit. Both
processes are reaped, and probe receipts/logs have one-day artifact retention.
The first hosted probe fails before attachment: bpftrace rejects `str(args->comm)`
because the tracepoint field is already a string array. Commit
`1d7046a34d4516c12c8ae0ea51ef025acf0f1586` passes the field directly; workflow
lint and Python syntax checks pass. The local negative check records sudo's
no-new-privileges rejection without hanging. This diagnostic is
downstream-only, with no upstream Codex counterpart. Its first manual dispatch
returned 404 because the workflow was not registered; the scoped main.new push
trigger starts it without modifying default main.

Focused retry
[`40412914539`](https://github.com/holdmyspot-com/codex-unleashed/actions/runs/40412914539)
at that correction passes in 15 seconds. Its log records Python sender PID 2643
delivering SIGCONT and SIGTERM to marker PID 2645, followed by the marker's
SIGCHLD and observer cleanup signals. Artifact `12693079656`,
`arm64-runner-signal-probe`, has API-reported SHA-256
`cb014ba6bfded9cc47c45ddfdbd673283d2ee0cf5114cfe81e36fdbd3b1d4acf`.
This verifies kernel signal-observation availability on the hosted ARM runner;
it does not identify the earlier shutdown sender or pass ARM cache acceptance.
The next diagnostic must keep this observer active across the actual ARM build
and stream records to the job log, including signals outside the build child.

The command-observation regression initially fails in run `40413278649` at
`bcc1342a3ea2c330484ce80fc432052839f9eeca`: the supplied command never runs,
and the wrapper returns zero instead of its requested exit 17. The corrected
wrapper at `a85255a594c8bc6912f4acefefbde23350943777` passes focused hosted run
[`40413519826`](https://github.com/holdmyspot-com/codex-unleashed/actions/runs/40413519826)
in 21 seconds. Its real-process fixture verifies command execution, SIGCONT
capture during each command, successful exit and propagation of exit 17.
Signal output is streamed through an owned tee process; routine SIGCHLD records
are excluded to avoid per-child-exit log growth during compilation. The observer,
marker and tee are reaped on ordinary completion. An abrupt host shutdown can
still prevent cleanup and terminal receipts.

The optional ARM build diagnostic now uses this verified wrapper instead of the
build-child-only strace invocation. It records process IDs, parent IDs and command
names before compilation, keeps the observer active across all build checkpoints,
and stores logs/receipts outside pristine source in the existing evidence
directory. Cache identity, source and default build commands are preserved.
Workflow lint reports only the previously documented unsupported `queue` key;
the new shell commands have no ShellCheck findings. Actual ARM build observation
and cache acceptance remain pending.

Run [`40413740004`](https://github.com/holdmyspot-com/codex-unleashed/actions/runs/40413740004)
at `2fd2eee04e49d0c3cdfb868ea64d1d3ed8646447` is now terminal: ARM job
`121890734990` fails after 17m 10s with exit 143. The streamed observer records
SIGKILL generation for rustc PID 55525 with `code=128`, in the current process
context of PID 1996 (`provjobd9019055`). Forty-four milliseconds later it records
systemd PID 1 generating SIGTERM for PID 1952 (`hosted-compute-`); the initial
process inventory makes Runner.Listener PID 2055 its child and Runner.Worker
PID 2070 the listener's child. Final smokes, guards and artifact collection are
skipped. The retained job log has SHA-256
`e96cbab4cfbb267e70dc8dc6f6c80058361a29c9f75a3367103e6c28c2ec231a`.
The goal executor retains it at session temp `arm-kernel-signal-EFWxgu/job.log`
until ARM acceptance and owns cleanup.

Linux defines [signal code 128 as SI_KERNEL](https://github.com/torvalds/linux/blob/master/include/uapi/asm-generic/siginfo.h).
The current-process label in the observer is execution context, not proof of a
userspace kill request. This is evidence of a kernel-generated SIGKILL followed
by runner-service termination, not proof that the provisioner intentionally
killed the compiler. An OOM cause remains a hypothesis: the next focused observer
must capture kernel victim-selection evidence, such as the
[OOM mark_victim tracepoint](https://github.com/torvalds/linux/blob/master/include/trace/events/oom.h),
and the SIGKILL kernel stack before selecting a mitigation. No identical retry
or source/dependency repair is justified by this result.

All four initial exact/compatible verified/partial lookups miss. Compared with
the earlier ARM diagnostic revision, the helper's Main and retention-preview
sources have changed; the helper-input digest participates in the compatible
seed. This run therefore does not demonstrate restoration of earlier ARM state.
It successfully saves new bwrap/proxy/code-mode-host partial checkpoints in
5/13/75 seconds after 7/63/181-second builds. The final checkpoint reports four
groups, two reused output groups and 610,207,768 uploaded archive bytes, under
seed `cargo-seed-v1-aarch64-unknown-linux-musl-b44a11594cfa3ea99bf3832284f0a3842228ad8298beb5b625104cd6cb366e3a`.
Resume these checkpoints with unchanged helper/compiler inputs in the next
diagnostic; verify the hit rather than assuming it. CLI compilation after that
checkpoint is not preserved by this failed run.

Windows x64 run `40373560817`, attempt 1 at
`0ed6d57794765591f46dee5825484e88e8236495`, passes all seven executable builds,
both native smokes, source/dependency guards, recording and private publication
in 1h 48m 32s. Its eight-group verified manifest is
`sha256:10365c192c13f042c650925551d61125aeb3b69266beb974928c697794366c90`.
Attempt 2, job `121808123754`, restores that exact manifest in 123 seconds and
passes the same checks in 11m 33s, with no Rust compilation entries. The seven
executable invocations take 28/3/4/2/3/3/18 seconds. Every checkpoint reuses all
seven output archives, uploads about 2.43 MB of metadata and takes 23–45 seconds.
The final save reuses all seven output archives and uploads 1,372,401 metadata
bytes in 42 seconds, publishing
`sha256:259fa4a06d9123f0d800a2d57be11c1a594cfa1802608c17c0c358e09c1a938f`.
This verifies Windows x64 grouped-cache reuse across runners. Other platform,
changed-input, clean-matrix and baseline-runtime gates remain incomplete.

Future baseline jobs use a concurrency group for their workflow and target, with
`cancel-in-progress: false` and `queue: max`. Different targets remain independent;
duplicate targets wait, with up to 100 pending jobs retained per group. Existing
runs use their original workflow definitions and do not acquire this guard.
[GitHub's concurrency documentation](https://docs.github.com/en/actions/how-tos/write-workflows/choose-when-workflows-run/control-workflow-concurrency)
supports these settings. The latest released actionlint, 1.7.12, rejects only the
new `queue` key. A disposable copy omitting that key passes the remaining
actionlint checks; this is partial lint evidence, not a hosted scheduling test.
Hosted run `40394539659` subsequently reports its Linux x86-64 job as `pending`
with no assigned runner while the same target in `40393398689` continues building.
This verifies duplicate-target waiting; queue-capacity limits are documented by
GitHub rather than exercised with redundant builds.

The optional `verify_invalidation=true` input selects a focused cached Linux
x86-64 check after the normal verified cache save. It changes only the CLI's
`CODEX_BUILD_COMMIT` environment input, requires Cargo to rebuild CLI artifacts
while retaining every non-CLI compilation, checks the running `codex doctor
--json` provenance, and requires the next unchanged invocation to reuse all
artifacts. Diagnostic outputs are never saved to shared cache references. The
doctor process may report unrelated environment failures; acceptance checks its
runtime provenance rather than claiming the full doctor report passes.
Hosted run `40387733411` rebuilds only the CLI after changing that input and its
retained doctor report contains the changed commit. The verifier then fails
because it expects arrays, whereas upstream's `cli/src/doctor.rs` defines maps
for both checks and details. The unchanged changed-input invocation is not
reached, so full invalidation acceptance remains unmet. The corrected real-Cargo
fixture reproduces that exact parser failure before the repair; both acceptance
and stale-provenance rejection then pass in 1.28 seconds. Commit `854e96c41e`
corrects the verifier without changing upstream. Run `40393398689`, job
`121820038862`, now passes the hosted gate at that revision: the original warm
invocation completes, the changed commit input rebuilds the CLI and appears in
the running doctor's provenance, and the next unchanged invocation rebuilds
nothing. The result records `non_cli_rebuilds: 0`, `changed_warm_rebuilds: 0`
and `status: passed`. The changed-input invocation takes about 21m 36s; this
verifies invalidation correctness, not the five-minute scheduling goal. Native
smokes, source/dependency guards and verified cache publication also pass. The
extra original-input rebuild is omitted to keep verification to the least work
needed for this gate. This is Linux x86-64 evidence; the other required platform
and clean-matrix gates remain open.

Windows ARM64 grouped population run `40373566212`, attempt 1 at
`0ed6d57794765591f46dee5825484e88e8236495`, passes all seven executable builds,
native smokes, source/dependency guards and verified private publication. All
restore candidates miss. Build invocations take 274/325/2,828/987/71/240/43 seconds
in executable order. The final save uses eight groups, reuses seven output
archives and uploads 1,344,011 metadata bytes in 64 seconds. Attempt 2, job
`121820069496`, passes the same-revision warm comparison in 24m 21s. It restores
that cold manifest, `sha256:6ac592bbee6fa80facec6133ab448b2c4e47cd91cb662ba43b46d1aef4800d82`,
in 177 seconds and emits zero Rust compilation records. The seven build
invocations take 82/4/7/4/3/6/5 seconds. Every checkpoint reuses seven output
archives and uploads about 2.4 MB of metadata in 90–96 seconds including guards.
Both native smokes, source/dependency guards, recording and verified private
publication pass. The final save reuses seven archives and uploads 1,333,319
metadata bytes in 90 seconds. These results verify compilation reuse; checkpoint
processing remains a substantial part of the job duration.

The first partial save previously searched only its partial reference, omitting
the verified manifest it had restored. A compatible restore also supplied no
manifest for archive reuse after an exact-key change. In run `40387733411`, the
verified compatible restore hits six groups, but the first partial save reuses
none and uploads 2,916,929,198 archive bytes. These are downstream transport
lookup gaps, with no upstream Codex counterpart. Two tiny real-ORAS regressions
reproduce archive uploads after a verified save and after an exact-key change.
The transport now searches both partial and verified manifests for the exact
reference and the compatible alias, reusing only matching group/input signatures.
Both regressions then upload zero archive bytes; existing publication-isolation
checks still pass. This repairs lookup without changing cache keys or promoting
partial results to verified status. Native fixture run `40394114661` passes on
all six release platforms at `c5ddca773af533ab234479a2530cc8ea56975e91`.
Hosted run `40394539659`, job `121824060994`, now verifies those savings for
Linux x86-64 at that revision. It restores the compatible six-group manifest
`sha256:16f77f7047b287e68211403c660841e93df62075a4de97b06c6a906de529e6da`
in 114 seconds, emits zero Rust compilation records, and passes all default-feature
builds, native smokes, source/dependency guards and private publication in
8m 34s. The five executable invocations take 9/1/1/1/1 seconds. Every partial
checkpoint reuses all five output archives and uploads only about 2.4 MB of
metadata, taking 31/22/23/21/21 seconds including guards. Final publication
reuses those five archives, uploads 1,338,648 metadata bytes and takes 20 seconds.
This closes the real Linux verified-to-partial/compatible archive-reuse gate;
other platforms and the required clean matrix retain their separate gates.

macOS ARM64 population run `40373552154`, attempt 1 at
`0ed6d57794765591f46dee5825484e88e8236495`, now passes all four default-feature
executable builds, both native smokes, source/dependency guards, recording and
private publication in 3h 12m 20s. Every restore candidate misses. Build stages
take 156/458/8,586/1,923 seconds; checkpoints take 17/41/102/50 seconds and each
new stage retains the preceding output archives. The final verified manifest,
`sha256:fb3c9dad00eb827f446cc0294c44d7f2df5eeaaa3fab1688fe57bb5ca0bcf292`,
has five groups and reuses all four output archives while uploading 1,589,666
metadata bytes. Attempt 2, job `121851931397`, passes the same-revision warm
comparison in 7m 11s with zero Rust compilation records. It restores that exact
cold manifest in 76 seconds. Build invocations take 21/6/6/6 seconds and partial
checkpoints take 39/38/36/37 seconds, reusing all four output archives and
uploading only about 2.76 MB of metadata each. Final publication reuses those
archives, uploads 1,597,730 metadata bytes and takes 36 seconds. Native smokes,
source/dependency guards and recording pass. This verifies macOS ARM64 grouped
reuse without restarting Intel macOS; other platform and clean-matrix gates
remain separate.

The macOS ARM64 cold primary report contained 1,059 packages and 1,420 compilation
units. These counts do not predict an exact archive count: a package can have
separate build-script, host and target outputs, and metadata also needs transfer.
Independent cache groups do not make their compilation independently runnable.
This change saves between executables; it does not publish a snapshot while Cargo
is still writing an executable's dependencies. Further dependency build stages
require evidence that their feature resolution preserves reuse. Single
compilations and links may still exceed five minutes; the scheduler checkpoint
must investigate those timings rather than infer a cache miss from duration.
