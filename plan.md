# Restart plan

## Design Goals

- Complete the approved Codex restart on `main.new`, preserving its selected product scope and explicit deferrals.
- Establish checkpoint acceptance at the required execution boundaries, with failures and unrun gates visible.
- Advance independent coherent deliverables concurrently, preserving verified work and diagnosing only decisions that affect the remaining implementation.
- Make resumption possible from recorded inputs, ownership, evidence and the full reconciliation backlog.

## Resumption handoff

Handoff updated 2026-11-07T06:05Z. The goal `implement plan.md` is incomplete: CP1–3 passed, CP4 is current, CP5–7 are unrun and CP8 is deferred policy. Prior authorization covers continuation, parallel work and pushing to `main.new`; publishing a release needs a separate instruction. The earlier root, Linux-owner and Windows-module8 live-worker records (sessions `01a18dac…`, `01a197e5…`, `01a198d5…`) describe workers whose runs have long ended; their session directories stay on disk as evidence and must not be deleted, but they are not live assignments.

### Checkout

- Branch `main.new`. CP3 tag `checkpoint-3-cache-coverage-cold-build` is on `5b852ea3ea`.
- Local test command, from `tooling/cli`: `mvn -B -ntp -q -s <settings.xml> test`. Expect 0 failures and 0 errors; `SourceInputCargoTest` needs the local `rustc` and `cargo` on PATH (installed at `/home/gili/.cargo/bin`) and fails with 6 errors without them. Release-scale native Rust, Bazel and Go builds run only on remote runners.
- Tests live in the separate JPMS module `com.holdmyspot.codexunleashed.tooling.test`. The production module exports its tooling package only to the test module; no reflection and no fake executables for tools.

### Live operations at handoff

- The ARM Linux job-count worker (run `42325337412`, session directory `armjobs-4TfAmU`) finished and was shut down on 2026-11-07.
- Intel macOS rerun `42326488137` at `074bab3def` passed on 2026-11-07 with no quiet-build reports, so the watchdog's CPU check works on macOS.
- The earlier job-count workers (runs `42225639057`, `42264649730`, `42264654272`, `42264659111`, `42264664031`) were shut down and cancelled on 2026-11-06.

### Decision: the build scheduler is dropped (user, 2026-11-06)

The user asked whether the findings allow dropping the Java memory scheduler; they do, and the user directed its deletion with all related work. Findings that decided it:

- The release output must match upstream's unpatched build. That excludes turning LTO off, more `codegen-units` and a different linker, which are the options that change the build's critical path.
- Cargo's own job limit plus the runner's default swap prevents the memory failures that the scheduler's admission control was meant to prevent. Swap stays on (user, 2026-11-05).
- The build is CPU-bound: Windows runners showed at most about 4% idle CPU during the build, so admitting more work cannot use capacity that is not there.
- Supervising every compiler process cost time and caused hangs and crashes (Frida child gating on fork; recorded in the git history of this plan).

Removed: the scheduler, secrets and Bazel-backend Java packages and their tests, the gRPC/protobuf/OSHI dependencies, the scheduler, prerequisites and probe workflows, the release-scheduler scripts and Python probes, the memory-history cache steps, `docs/build-scheduler-investigation.md` and the scheduler-only evidence files. Release debug-session preparation moved to `.github/scripts/release-debug-session.sh`. Git history keeps the removed work.

### Build options for cold builds (CP3)

The remaining CP3 build-speed work chooses standard build options that shorten a cold release build without changing its output. Measurements so far, all cold for the measured crates:

| Target and experiment | Result |
| --- | --- |
| x64 Linux, 4 heavy libraries, 14,500 MB cgroup with swap | `-j2` 849 s, `-j3` 736 s, `-j4` killed for memory. The workflow now uses 3 jobs. |
| ARM Linux, relink of `codex` after touching `cli/src/main.rs` (run `42225639057`, `expA`) | `cargo build` 3,827 s. Through `cargo rustc` with `RUSTC_BOOTSTRAP=1`: `-Z time-passes` 3,640 s, `-Z threads=4` 2,009 s, `-C codegen-units=16` 2,033 s. |
| ARM Linux output hashes from the same runs | `cargo build`: `15454050…`; `-Z time-passes` and `-Z threads=4`: both `ce584d88…`; `codegen-units=16`: `b4788cc5…`. `-Z threads=4` matches the other `cargo rustc` run byte for byte; the difference from `cargo build` comes from the invocation, not from the thread count (inference: not isolated further). |
| Windows | At most about 4% idle CPU during the build. |
| Job counts, cold rebuild of `codex-tui`, `codex-exec`, `codex-app-server`, `codex-mcp` and `codex-core` (2026-11-06) | ARM macOS (3 CPUs, 7 GB): `-j2` 2,842 s, `-j3` 6,007 s. Intel macOS (4 CPUs): `-j3` 3,510 s, `-j4` 3,233 s. Windows x64 (4 CPUs): `-j3` 1,689 s, `-j4` 1,626 s. Windows ARM (4 CPUs): `-j3` 1,327 s, `-j4` 1,173 s. ARM Linux (run `42325337412`, 14,500 MB cgroup): `-j2` 982 s (peak 14.2 GB, no swap), `-j3` 719 s (1.1 GB swap, no kills), `-j4` failed after 3 memory kills. |

- `-Z threads` remains rejected by the user (2026-11-04: nightly-only through `RUSTC_BOOTSTRAP=1`, unstable and known to hang occasionally), even though it nearly halved the final ARM link.
- `codegen-units=16` changes the output, so it is excluded.
- Chosen job counts in [baseline-release-builds.yml](.github/workflows/baseline-release-builds.yml): x64 Linux 3, ARM Linux 3, ARM macOS 2. Windows x64, Windows ARM and Intel macOS keep Cargo's default of one job per CPU, which was fastest.

### Directive: split slow build steps without changing the output (user, 2026-11-04)

- If one compile or link step is slow, split it into concurrent steps that are joined at the end. Do this only when the output matches a one-shot build. The target is byte-identical binaries; any option that is not byte-identical needs the user's approval first.
- First establish that the current release build reproduces itself byte for byte: build the same binary twice from a clean target and compare hashes. Every later comparison depends on that baseline.
- Byte-identical options, which are allowed: reuse cached outputs, choose the job count, and use bigger runners. C/C++ build scripts already run with every jobserver slot.
- Options that are not byte-identical, and need approval: a different final linker (lld or mold), and more `codegen-units` than upstream's 4. Upstream's `codegen-units` and `lto` settings are a deliberate size and performance trade-off, so changing them counts as quality loss.

## Scope and authority

Rebuild Codex Unleashed from upstream main commit `dddc3c0a79528c4de8d9a20d51dd3bd89b02a3e4` on `main.new`. Preserve `main` and old-main commit `545479059ac86394049b6efd3bf4297b1ca18d17` for selective migration and final comparison. `.github/upstream-ref` and `.github/upstream-commit` record the selected upstream identity. Keep that source fixed through checkpoint 6 unless the owner changes it. The original orphan commit contained only this plan; that starting condition is not a restriction on the implemented repository.

This file owns the current checkpoint state, assignments and acceptance decisions. [restart-scope.md](docs/plans/restart-scope.md) is its normative appendix: retain all D1–D13 decisions, supporting changes, deferred concerns and individual old-main dispositions. [restart-history.md](docs/plans/restart-history.md) preserves the earlier plan and receipt links. Historical statuses and proposed next diagnostics are not current assignments. Do not silently relabel evidence from an earlier source as evidence from the current source.

The owner explicitly carries forward earlier passing release, native-smoke, cache, clean-build and helper results after the coordinated source/toolchain update. Treat their applicability as an owner-approved equivalence assumption unless contradictory evidence appears; do not claim those commands ran on the new snapshot or launch another cold matrix for reassurance. Continue failed or unrun work. New implementation changes still need their affected checks and required composed acceptance.

## Fixed constraints

- Build and publish the selected Codex CLI and app-server variants and only their required dependencies, helpers, resources and checks. No standalone SDK publication or unrelated whole-workspace builds. The six targets are Linux x86-64/ARM64 musl, macOS Intel/Apple Silicon, and Windows x86-64/ARM64 MSVC. Optional artifact producers need a demonstrated approved consumer.
- Keep upstream source and tests pristine through checkpoints 1–3. Keep warnings visible and nonfatal through checkpoint 5; checkpoint 6 introduces strict enforcement. Dependency-source backports remain deferred; stable library updates belong to checkpoint 7. D9 permits moving only a demonstrated toolchain prerequisite earlier.
- Implement production tooling in Java in `tooling/cli` and its bundled runtime, with required native OS bindings. Python is limited to probes and test fixtures. Migrate needed project-owned Python commands rather than treating their existing presence as an exemption.
- Acquire JsonMapper and other potentially shared services through caller-supplied Pouch scopes. The composition boundary owns disposal; consumers borrow services. Preserve parser configurations and native ownership lifetimes. Use Requirements assertions with TestNG discovery/data providers.
- Maintained tests own independent fixtures and run with method parallelism. Avoid every maintained `@BeforeClass` use. Keep portable behavior cross-platform; guard native acquisition on the actual host. Native matrix checks must confirm that each expected case executes on its designated platform, rather than accepting its skip elsewhere.
- Use free standard GitHub-hosted runners. Preserve suitable BuildBuddy/GitHub/GHCR integrations, authenticated private GHCR caches, three-stable-release cache retention and one-day Actions artifacts. Keep secrets out of archives and organization package visibility unchanged.
- Native Rust, Bazel and Go builds run remotely. Do not recreate local native outputs/caches or extract native binaries locally. Local Java compilation and portable checks remain available. Delete retained artifacts only after their last named consumer finishes, preserving rebuild speed and processes launched by this work; process inspection is limited to those processes.
- Pushing ongoing work to `main.new` is authorized. Publishing releases requires a separate instruction. npm publication and installation tests use local Verdaccio only. No public npm or restricted early-access package families, issue mutations or issue-closing automation; PR-closing automation stays deferred.
- Preserve the selected account/output-token usage direction and applicable HoldMySpot licensing, upstream rights and notices. Authentication, metering, billing enforcement, token definitions, pricing and detailed customer billing documentation remain deferred. No charges or payment operations are authorized. The appendix retains the exact license and Usage Terms obligations.

## Checkpoint state

| Checkpoint | Acceptance state | Authority / remaining outcome |
| --- | --- | --- |
| 1. Default upstream baseline | Passed, with explicit deferrals | [checkpoint-1-acceptance.json](docs/evidence/checkpoint-1-acceptance.json) and [checkpoint-1-clean-recheck.json](docs/evidence/checkpoint-1-clean-recheck.json); preserve the owner-approved equivalence and exceptions below. |
| 2. Cache and partial-progress reuse | Passed | [checkpoint-2-acceptance.json](docs/evidence/checkpoint-2-acceptance.json); successful cache reuse does not pass retention automation. |
| 3. Cache coverage and cold build time | Passed; tagged `checkpoint-3-cache-coverage-cold-build` at `5b852ea3ea` | The Java memory scheduler was dropped (user, 2026-11-06). Cold misses at or above five minutes remain and are recorded with their cause. |
| 4. Product patches | In progress: named deferred fixtures | Follow checkpoint 3; repair the named deferred fixtures first. The queue is in [patches/README.md](patches/README.md); see "CP4 progress" below. |
| 5. Warning causes | Unrun | Remove the causes in Codex and direct dependencies while warnings remain nonfatal. |
| 6. Fatal warnings | Unrun | Follow 5; demonstrate actual diagnostic enforcement. |
| 7. Latest stable dependencies | Unrun | Follow 6; resolve versions when work starts, with strict builds and audits. |
| 8. Unreleased dependency backports | Deferred policy; final disposition unrun | This labels the backport deferral and concerns reconciliation, not an eighth implementation authorization. The original implementation sequence contains seven changes. |

A checkpoint passes only when every required outcome has its scoped evidence or an explicit user exclusion. Record implementation commits, exact commands, toolchain/configuration, results, diagnostics and relevant timings. Then create a distinct descriptive annotated tag on its completing commit and record the tag/commit with its evidence. Apply the same completion rule to the approved supporting checklist items in the appendix. Intermediate fixes, source composition and component tests do not qualify for a completion tag.

## Checkpoint 3: cache coverage and cold build time

The build scheduler is dropped (see the handoff decision above), so CP3's remaining outcome is a release workflow whose cache reuse and cold build time are measured on all six targets, with standard build options chosen from measurements and the output unchanged from upstream.

| Required outcome | Gate state | Existing evidence and next action |
| --- | --- | --- |
| Java production cache/lock commands | Passed | `cp3-java-cache-helper-migration.json` was never committed; the release workflow used these commands on all six targets in `42342098021` (seed-compatible restore and save) and `42343598362` (exact hit) at `5b852ea3ea`. |
| Native metadata | Passed | The `cp3-*-native-metadata.json` files were never committed; Windows and macOS restored, reused and checkpointed outputs with exact hits in `42343598362` at `5b852ea3ea`, and every job passed its release checks. |
| Cold/warm per-step timing and cache coverage | Passed with recorded misses: cold `codex` and `app-server` steps exceed five minutes on every target | Cold six-target run `42307056656` at `96eade7c75` (every compiled-cache lookup missed on every target), build seconds per binary: x64 Linux (3 jobs) bwrap 11, proxy 98, code-mode-host 308, codex 2,970, app-server 1,165. ARM Linux (2 jobs) bwrap 10, proxy 126, code-mode-host 359, codex 3,564, app-server 1,337. ARM macOS (2 jobs) proxy 214, code-mode-host 555, codex 5,851, app-server 2,006. Windows x64 (default) proxy 257, code-mode-host 543, codex 5,185, app-server 2,045, sandbox-setup 130, sandbox-service 482, command-runner 78. Windows ARM (default) proxy 246, code-mode-host 361, codex 3,172, app-server 1,151, sandbox-setup 78, sandbox-service 294, command-runner 49. Checkpoints took 6 to 123 s each. Intel macOS failed there: the quiet-build watchdog stopped the build after 75 minutes without output while `rustc` was still compiling and linking `codex`; `074bab3def` makes the watchdog count the build's CPU use as progress. Its rerun `42326488137` restored the failed run's partial outputs through the seed key (the other lookups missed) and rebuilt codex in 7,402 s and app-server in 3,615 s, with proxy 82 s and code-mode-host 4 s reused. Earlier runs: Cold x64 `41857192315`: bwrap 25 s, code-mode-host 1120 s, codex 4898 s, app-server 1027 s. Warm x64 `41907442644` (exact hit): 9 to 18 s per binary plus a 23 to 27 s checkpoint, and about 11 minutes per job. Warm ARM `42096722165` (exact hit, digest index): 6 to 11 s per binary plus a 7 to 9 s checkpoint, an 87 s build step and a 446 s job; cold ARM `42086908981` (partial restore) took 3,002 s in its build step. Resumed x64 `41967115432`: about 0.9 s for each reused binary, then codex 4759 s and app-server 1334 s rebuilt. The cold `codex` and `app-server` steps exceed five minutes; on ARM, relinking `codex` alone took 3,827 s, and the output-equivalence rule excludes the options that shortened it. Warm six-target run `42343598362` at `5b852ea3ea` (exact hit on every target), largest build step and checkpoint in seconds: x64 Linux 9 and 8, ARM Linux 8 and 9, ARM macOS 24 and 15, Intel macOS 42 and 19, Windows x64 35 and 40, Windows ARM 76 and 51; every job ran between 05:38Z and 05:54Z. The preceding run `42342098021` restored every target through the seed-compatible key after the workflow change. Remaining misses and their cause: the cold `codex` (2,970 to 7,402 s) and `codex-app-server` (1,151 to 3,615 s) steps, dominated by the final thin-LTO compile and link that the output-equivalence rule keeps unchanged. |
| Cold-build options | Passed | See "Build options for cold builds" above. ARM Linux has run 3 jobs only in cached releases so far; its first cold release build is the next check of that choice. |
| Release on all six targets with useful outputs | Passed | Five targets in `42307056656` at `96eade7c75`, Intel macOS in `42326488137` at `074bab3def`, and all six in `42342098021` and `42343598362` at `5b852ea3ea`. |
| CP3 tag | Passed | Annotated tag `checkpoint-3-cache-coverage-cold-build` on `5b852ea3ea`, the commit both six-target runs verified. |

## Execution and verification

Choose the smallest coherent verifiable deliverable that preserves coupled decisions. A connected behavior slice, a shared implementation batch, and a focused investigation are all valid when their inputs, consuming outcome and acceptance are explicit. Schedule the connected acceptance check at its first runnable boundary, before more isolated verification unless a named prerequisite or unresolved failure requires it. Do not make the entire release wait for unrelated diagnostic or primitive reviews.

Before extending a probe, diagnostic harness or source investigation, name the unresolved implementation decision, how each plausible result changes the next action, and the required outcome it enables. Reuse evidence that already answers it. Once resolved, continue the dependent implementation. Parent review is required at the actual integration/handoff boundary; repeated approval of unchanged evidence between small steps is not an added prerequisite.

Run independent ready units at the highest bounded concurrency supported by actual capacity and isolation. Check workflow concurrency groups, locks, shared caches/trees and runner availability so intended parallelism actually overlaps. Build the conflict graph from both write sets and verification dependencies: disjoint files do not establish independence when one check consumes another unit's changing output. Concurrent write-capable delegates require isolated worktrees from an immutable accepted snapshot. Otherwise retain one exclusive writer in the shared tree; read-only work may overlap when it does not rely on changing inputs. Remove unnecessary serialization within existing authority and record unavoidable resource constraints. Each owner retains and reaps its children and reports terminal results. Give parallel units separate mutable scratch and evidence, and preserve others' source changes.

For behavior changes, establish a meaningful focused behavioral red result before implementation; setup/compilation failure is not that regression. If the contract or required test boundary is unresolved, resolve that prerequisite before marking dependent implementation ready rather than claiming a behavioral red result. Each producer owns quality closure: compile and run exact-owned-path Checkstyle/PMD preflight as soon as its paths exist, then pass its focused behavior gate and required source/Javadoc checks before handoff. Routine repairs inside the allowlist stay with that producer; report findings outside it without absorbing their paths. Record unrelated module-wide quality failures separately. Keep project warnings visible/nonfatal through CP5; an isolated diagnostic strict compile is not early global warning enforcement.

Delegates run focused gates; the root integrator rejects out-of-allowlist changes, integrates accepted results one at a time, runs combined affected checks, then runs the complete project verification once for the resulting stable composition. Preserve the dirty maintained tree through validated replay; do not merge or cherry-pick into it. Start a dependent consumer only from that accepted integrated snapshot. Reuse the existing full runtime acceptance when its determining inputs and contract remain valid; a new production composition requires its affected checks and complete verification before handoff. Native execution remains required for platform behavior. Broaden or repeat checks only for changed inputs, new failures, unresolved concerns or an explicit required gate.

Before the current unit finishes, prepare the next dependency frontier from accepted inputs: its write sets, verification dependencies, focused gates, static-analysis scope and consuming integration. Resolve genuine contract gaps together; do not add readiness reviews for already executable units. This lets eligible independent work start promptly without approving every primitive or launching a consumer before its producer is accepted.

For a failure, reproduce the affected operation and identify the owning cause, comparing the last passing case with the same determining inputs and isolating intervening changes when necessary. Check whether the corresponding upstream operation executes and passes at the same source/command/target/toolchain/prerequisites before attributing it to a test or environment; retain comparison evidence in [upstream-failure-comparison.md](docs/upstream-failure-comparison.md). A different revision, cached build, skipped fixture or pre-test failure is insufficient. Diagnose downstream-only wrappers at their own boundary. Preserve the narrow owner-approved comparison exceptions below.

For remote workflow work, apply the project rule [remote-runner-ssh.md](.cat/rules/include/common/remote-runner-ssh.md). Retain the original authenticated Upterm/SSH worker, exact run/job/session identity, workspace and valid partial outputs across focused fixes; do not select the newest run or restart merely because observation timed out. Inspect existing owner/terminal records before new work. Independent VMs need no per-target lock unless they share mutable state. Preserve provider smoke/post failures separately from native product results, including required partial-build/incremental-retry markers. When the worker expires, recover from authenticated retained artifacts/caches with an explicit successor identity.

Resume affected builds incrementally without an unconditional clean. Keep failed partial state separate from verified references, guard it by actual inputs and invalidate only necessary work. After required incremental success, perform the required clean verification for the changed scope with empty build outputs and no restored compiled/action outputs; dependency/tool downloads may stay cached. Preserve accepted earlier matrices under the owner's equivalence decision; this is not authority for another unchanged matrix. Generate reusable patches with `git format-patch`, document their exact bases/prerequisites and verify the emitted patches on a fresh base with only those prerequisites. Use maintained upstream check entrypoints and required options, including `just fmt-check` for formatting.

Retain the owner-selected cache contract documented in [release-build-checkpoints.md](docs/release-build-checkpoints.md): build the eight distinct required executables separately (four per macOS target, five per Linux target and seven per Windows target), saving guarded partial state between completed executables. Use one compressed output group per build step; assign new files to their first saving step, update changed files in their owner's archive and reuse unchanged earlier groups across steps and runners. Keep directory timestamps and cache bookkeeping in a separate metadata group, bootstrap imported ownership, isolate partial/verified and older-format references, and preserve private access and release-retention associations. Restore up front rather than on demand. Measure build time and checkpoint overhead separately. Additional library stages require verified feature compatibility before adoption. The linked document's cache implementation contract remains operative; its historical results do not pass a different runtime or release gate.

Report progress after each verified deliverable. State what its evidence proves, its limits and the next unmet gate. Make routine implementation choices within the authorized contract; ask only for a missing material product decision or authority. Never ask again whether to continue an active goal or use a user-input tool for agent communication.

## CP4 progress

Patches are generated with `git format-patch` against the pinned upstream commit; each README names its downstream prerequisites, and [patches/README.md](patches/README.md) lists the release order, which applies cleanly on a fresh base. `baseline-linux-tests.yml` and `baseline-windows-focused-runtime.yml` take a `patches` input; the Linux workflow's `red_restore` input reruns the selection with production files restored to the base and requires that run to fail.

| Named failure | Patch | Result |
| --- | --- | --- |
| Remote pipe capture recovery, remote TTY capture recovery, remote sandbox profile replay | [issue-47](patches/holdmyspot-com/codex-unleashed/issue-47/README.md): Bash replay passes `--norc` | Passed in run `42345599577` at `dfb3812998`: with `shell_snapshot.rs` withheld, 3 of 46 selected `codex-exec-server` tests fail (exit 100); patched, all 46 pass. |
| Blocked-descriptor replay | [issue-49](patches/holdmyspot-com/codex-unleashed/issue-49/README.md): the Linux test denies `/proc` in the caller's PID namespace and asserts the fallback | Passed in run `42345804006` at `e0fc404683`: all 5 concurrent-replay cases pass, including `blocked_descriptor_path`. Red is the unpatched run `40220417153`. |
| Windows `winget_package_starts_and_reselects_daemon` | [winget-daemon-fixture](patches/holdmyspot-com/codex-unleashed/winget-daemon-fixture/README.md): create the fixture's `CODEX_HOME` | Incomplete. Runs `42345542301` (x64) and `42345544509` (ARM64) get past `CODEX_HOME` and then fail at `daemon start`: the daemon cannot break away from a Windows job. The standard-user wrapper now launches the test child through Task Scheduler, whose job permits silent breakaway (runs `42375292919` and `42375294787` record limit flags `0x1000`), yet `daemon start` still reports `DetachedLaunchRestricted`. Cause: upstream's pinned nextest 0.9.103 assigns each test process to a job created without `JOB_OBJECT_LIMIT_BREAKAWAY_OK`. The child now runs the compiled libtest binary directly (`--exact`, `--nocapture`) from its package directory; runs `42377952399` and `42377954309` at `6303ee1826` verify it. |
| Shared `[codex_unleashed]` table; expanded diff previews ([openai/codex#47390](https://github.com/openai/codex/issues/47390)) | [base-configuration](patches/holdmyspot-com/codex-unleashed/base-configuration/README.md), then [issue-47390](patches/holdmyspot-com/codex-unleashed/issue-47390/README.md) | Passed in run `42375430632`: with the production files restored the selection fails to compile (exit 101); patched, all 10 selected `codex-config`, `codex-core` and `codex-tui` tests pass, including `config_schema_matches_fixture`. |
| Deferred implicit skill index ([openai/codex#34776](https://github.com/openai/codex/issues/34776)) | [issue-34776](patches/holdmyspot-com/codex-unleashed/issue-34776/README.md) | Pending run `42378058924`: the whole `codex-skills-extension` package, red with `host_outcome.rs` restored. |

## Baseline deferrals

These explicit owner exclusions apply through CP3; retain the original failed statuses, diagnostics and source guards. They do not cover unrelated failures or authorize suppression, diagnostic production workarounds or upstream source edits.

| Named failure | Disposition and later acceptance |
| --- | --- |
| Remote pipe capture recovery; remote TTY capture recovery; remote sandbox profile replay; blocked-descriptor replay | Repair established causes in CP4. The first three have Bash startup/peer-lookup diagnostic evidence; blocked-descriptor replay has the separate tmpfs `/dev/fd` mount failure. Verify ordinary focused behavior and cumulative checks without diagnostic errno injection. The owner accepts reported upstream passes with hidden assertion execution retained as a limitation; upstream guarded execution of blocked-descriptor replay remains unproven. |
| Windows `winget_package_starts_and_reselects_daemon` | CP4 repairs the missing fixture directory and tests unchanged daemon behavior under non-elevated accounts on x64/ARM64, including profile/account/daemon cleanup. Preserve [windows-native-runtime-results.json](docs/evidence/windows-native-runtime-results.json) and the unproven upstream account/assertion state. |
| `RUSTSEC-2026-0387` and `RUSTSEC-2026-0385` | Failed audits deferred to CP7, with unchanged locked-version diagnostics and exploitability unverified. Do not suppress or report passing. Exact dependency concerns remain in the scope appendix. |
| Manifest-convention checker and Python SDK formatter-command assertion | Supplemental repository concerns, not Codex release dependencies or CP1–3 gates; no repair is selected for CP4. Preserve them for final discussion. |

## Remaining sequence and supporting changes

- **CP3 completion:** every gate in the CP3 table above; cache misses at or above five minutes need established causes and recorded remaining target misses. A component pass alone cannot complete it.
- **CP4:** first fix the named shell and Windows fixture failures, then inventory and migrate still-needed product outcomes against the selected upstream pin one coherent change at a time. Preserve defaults/configuration contracts; separate build/dependency/warning changes from mixed patches. Focused product regressions, emitted-patch base/prerequisite checks, cumulative build and required upstream checks must pass. Warnings stay nonfatal.
- **CP5:** inventory warnings in Codex and direct dependencies, repair their actual causes using suitable APIs/configuration, and exercise the same emitting paths. Pass affected regressions and cumulative checks without those inventoried warnings. Report diagnostics outside scope separately; do not import deferred backports as warning cleanup.
- **CP6:** only after CP5, enable defined strict compiler/linker/check coverage for Codex and direct dependencies. Prove a clean cumulative pass, a controlled representative warning failure and its warning-free pass, plus upstream/product regressions. Retain original diagnostics; no silent exclusions or filters.
- **CP7:** resolve latest stable direct/transitive dependency versions when this step begins. Apply coherent supported updates with consistent manifests, locks, build metadata and API adaptations; document compatibility constraints rather than silently selecting older versions. Pass strict/platform/upstream/product checks and relevant audits, or explicitly retain unresolved findings. Recheck cold build timings and cache invalidation. No unreleased dependency-source backports.
- **CP8 / final concerns:** keep unreleased fixes deferred and reconcile their exact versions, current impact, canonical issues, stable availability and required later decision. Recording deferral is distinct from implementing a fix.

All supporting changes in [restart-scope.md](docs/plans/restart-scope.md) remain required at their stated prerequisites. D1/D8/D10/D13 may accompany their consuming checkpoints; package variants/installers (D3) and local npm consumers (D4) follow product integration; release identity/verification (D6) follows cumulative strict builds and packaging; discovery/manual releases (D5) follow release verification. D7 retains only demonstrated artifact consumers. D11 retains identity, licensing and applicable notices while preserving the separate billing milestone. Do not move a later milestone earlier solely because an old patch mixes its code with current work.

## Completion and reconciliation

Completion requires all seven implementation changes, the retained backport-deferral/reconciliation policy and every approved supporting change to have their required evidence. Account/billing implementation and billable-token definitions remain explicitly outside this restart's acceptance scope. Before a completing tag, publication or completion claim, compare the unchanged authorized scope with current gate states; failed, unrun or indirect evidence remains incomplete.

Compare the final branch with the fixed old-main commit, including changes outside `patches/`. The appendix retains all item-level dispositions and links to the 144 patch/mailbox and 648-file inventories. Record migrated commits, verified upstream equivalents or exact omitted/deferred leftovers; review mixed hunks separately. Path inventories and byte equality do not establish semantic acceptance. Reconcile the 18 saved uncommitted worktree change sets, original worktree commits and 33 archived independent checkouts using [worktree-recovery.md](docs/worktree-recovery.md); cleanup did not select those changes for migration. Keep unresolved security/compatibility findings explicit.

Retained artifacts and remote caches need explicit ownership, effective paths, last consumer and cleanup evidence. Do not recreate local native caches to satisfy the old historical storage prescription. Neither revising this plan nor producing an intermediate build completes the goal.
