# Upstream failure comparison

The restart must establish whether each failure in our environment also occurs upstream before classifying it as an environment or test problem. The source baseline is `rust-v0.164.0`, commit `6aaac694803004a63c6f393d153c96d22521a6b9`. Its parent `342ed69a174c206c1de52a59491b131e0753cfa5` differs in workspace package version; parent results are comparison evidence with that limitation, not exact-release test outcomes.

A direct comparison must identify the source, command, target, toolchain, prerequisites, execution environment and whether the relevant fixture actually executed. Keep the observed local mechanism separate from the upstream outcome. A reported cached test pass without evidence that its guarded assertions executed, a fixture capability return, or an earlier setup failure leaves functional comparability unknown. Unknown is an unmet comparison gate. The owner-approved four-test repair deferral preserves failed status through steps 1–3; it does not resolve this gate.

The owner subsequently accepts the retained upstream per-case passes for the
four deferred shell failures and the empty-workspace-roots case with the hidden
assertion-execution limitation. That explicit disposition closes those five
upstream outcome comparisons without establishing functional comparability.
Historical unknown labels below describe the evidence; they no longer require
fresh upstream execution for checkpoint 1. The four downstream failures remain
failed, with repairs deferred to step 4. Other runtime gates remain open.

This is the working index of current baseline comparisons, not a completed audit of every historical diagnostic attempt. Add each newly observed failure before pursuing its repair. Historical runs with discarded patches retain their original source inputs in [build-baseline.md](build-baseline.md); they cannot establish defects in the pristine baseline. Downstream-only wrapper failures need an explicit no-counterpart determination and their own counterfactual evidence.

## Selected main snapshot

At selected commit `dddc3c0a79528c4de8d9a20d51dd3bd89b02a3e4`, upstream postmerge run
[`40529958039`](https://github.com/openai/codex/actions/runs/40529958039) fails before
its nextest suites execute. Linux GNU archive job
[`122279734875`](https://github.com/openai/codex/actions/runs/40529958039/job/122279734875)
runs `cargo test --no-run --profile ci-test --target x86_64-unknown-linux-gnu`
with Rust 1.95.0; `glib-sys 0.22.8` cannot find `glib-2.0.pc` through pkg-config
(`glib-2.0 >= 2.56`). This establishes an upstream native-prerequisite failure,
not a failing test assertion. It does not compare the historical shell assertions.
The same run's Linux x86-64 musl release lint/build job `122279734911` succeeds.
Our release-profile CLI check therefore uses the already selected musl release
boundary rather than expanding to the failing full-workspace GNU archive.
Run [`40534698649`](https://github.com/holdmyspot-com/codex-unleashed/actions/runs/40534698649)
at restart commit `1f803e33fa8cdd9b24faa0f059ba7fb305b24692` passes that check:
16 library tests and 314 binary tests pass, one binary test is ignored and none
fail. Release compilation, native smokes and source/dependency guards also pass.
This resolves the previously unrun CLI unit check without reproducing upstream's
unrelated full-workspace prerequisite failure. The historical shell execution
comparisons below have the owner's explicit disposition: accept the reported
upstream passes while retaining unknown assertion execution and the four
downstream failures.

### Native macOS CLI comparisons in progress

Pristine ARM64 run `40729663557` executes the remaining CLI targets on its
retained worker with `ci-test`, Rust 1.95.0 and nextest 0.9.103. Four previously
passing cases are excluded. The result is 546 passed, nine failed, two timed
out and five skipped; skipped entries require separate filter/ignore accounting.
Keep the failed run and subsequent focused diagnostics as distinct results.

Upstream's separate blocking Bazel run `40529953490`, job `122279717700`,
reports all eleven affected cases passing. Its checkout is PR merge commit
`ef71db84dec3ce45750d6ad9d1204d898c4c64f0`; that commit and selected main have
the identical complete Git tree `16b1c50831e79726bca339e0da0b51c682944959`.
Seven SHA-256-verified target logs retain each named `ok` result. Their execution
strategy is `darwin-sandbox`, and their job summaries are not marked cached.
[The comparison evidence](evidence/upstream-macos-cli-comparison.json) embeds
the logs and target responses. This establishes upstream reported passes with
source identity; Bazel and Cargo nextest remain different execution environments.
The separate postmerge run `40529958039` still supplies no nextest comparison:
archive job `122279734132` fails before the macOS runtime shards execute.

| Cases | Controlled observation | Remaining comparison |
| --- | --- | --- |
| `managed_identity_survives_locale_and_timezone_changes`, `cloud_list_only_allows_trusted_credential_destinations`, `mcp_list_reports_invalid_keybinding_reason`, `remote_start_rejects_add_dir_before_connecting`, `remote_start_rejects_writable_root_overrides_before_connecting`, `add_rejects_blank_client_credentials_before_saving`, `mcp_login::tests::callback_line_is_bounded_and_does_not_expose_input_in_errors` | Failure output appends library stack traces to strings/snapshots expecting plain errors. Setting only `RUST_LIB_BACKTRACE=0`, while retaining `RUST_BACKTRACE=1`, makes all seven pass without source/test edits. | All seven are reported passing in upstream's native Bazel run. Its strict test environment does not explicitly set either backtrace variable; the unsuccessful nextest workflow instead sets `RUST_BACKTRACE=1`. Future focused Cargo checks disable library error backtraces while preserving panic backtraces. Retain the failed and controlled results; final source/dependency guards remain required. |
| `agents_defers_launch_directory_trust_until_starting_a_task` | Our long owned `TMPDIR` wraps the workspace path across terminal lines, preventing the existing snapshot replacement. Changing only `TMPDIR` to a short owned runner-temp directory makes the unchanged case pass in 11.331 seconds. | Upstream preserves its runner-provided `TMPDIR`. Our storage action introduced the long value; it now places owned storage directly below `RUNNER_TEMP`, preserving cleanup. |
| `agents_checks_trust_when_starting_after_dismissing_overview` | The same short-path diagnostic passes. The original failure remains retained; a passing repeat alone does not establish its cause. | Upstream reports this case passing. Inspect the original failure before attributing it to path length. |
| `explicit_restart_applies_feature_overrides_and_preserves_saved_settings`, `daemon_worktree_start_and_fork_bind_owner_before_turn` | Both time out in the broad run. With only the short `TMPDIR` change, the first reaches a snapshot failure at line 201; the second still times out. | Upstream reports both passing. Inspect the new snapshot diff and the actual stalled process boundary. Neither timeout is accepted or repaired. |

The local storage-action probe passes runner-temp ownership, exported paths,
post cleanup and cleanup after setup failure. Existing native workers remain
running their original checkout; this environment change applies to future
workers. Broader app-server testing continues incrementally on the retained
ARM64 worker. No upstream source or test repair is introduced.

### Shell-failure source applicability

Direct Git blob comparisons between historical baseline
`6aaac694803004a63c6f393d153c96d22521a6b9` and selected snapshot
`dddc3c0a79528c4de8d9a20d51dd3bd89b02a3e4` establish the following scope.
Retrieve each commit's recursive tree with
`gh api repos/openai/codex/git/trees/<commit>?recursive=1`; compare the named
entries' blob identities, then retrieve differing blobs to inspect their diff.
The comparison uses complete tree entries rather than the compare API's bounded
changed-file list.

| Path under `codex-rs/` | Comparison |
| --- | --- |
| `exec-server/src/shell_snapshot.rs` | Identical blob `55a831c7cb07cba9cb7b5ab6da84833c89e7eb16`. |
| `exec-server/src/shell_snapshot_process.rs` | Identical blob `095444fa2cfbe8bce03e44894c29826420e382fa`. |
| `exec-server/src/shell_snapshot_file.rs` | Identical blob `08ff6df9714771ad970d34852ad37b360f4b8eef`. |
| `shell-command/src/shell_snapshot.rs` | Identical blob `726be2c6f6dcfcb80c860351c0c40bc59807d02b`. |
| `exec-server/tests/exec_process/shell_snapshot.rs` | Identical blob `7d022116b128d2f6f768930e7a5a4f204aa70f14`. |
| `linux-sandbox/src/launcher.rs` | Identical blob `b4455ff210c3c5bd26d4f648debc9d3befc6614d`. |
| `exec-server/tests/exec_process.rs` | Only change: an unrelated child API-key assertion now requires the prefix `sk-proj-masked` instead of `sk-proj-`. The deferred shell cases are unchanged. |
| `linux-sandbox/src/bwrap.rs` | Only change: unreadable-glob search-root inspection now propagates filesystem errors other than absent/non-directory roots. The observed descriptor-mount path is unchanged. |

This source comparison supports carrying the historical shell diagnoses forward
for investigation; it does not establish runtime equivalence across changed
dependencies or toolchains. The four failures retain their approved step-4 repair
deferral and failed historical results. Upstream guarded execution remains
unknown, and no new test pass is claimed. The owner accepts the reported
upstream passes with that limitation; investigate any changed runtime outcome
at its actual owning boundary.

## Pristine source and runtime failures

| Failure in our environment | Observed local evidence | Upstream evidence and limitation | Comparison status / next required evidence |
| --- | --- | --- | --- |
| Remote pipe capture recovery | Run `40217047017`: ordinary and observational execution fail; the unchanged case executes and passes under the focused null-stdin peer-lookup errno diagnostic. | The upstream per-case log reports this case `ok`; successful-test output is hidden and the target is remotely cached. See the retained evidence below. | **Owner accepts the reported upstream pass with guarded execution unknown.** Comparison disposed of; downstream failure retained, with repair deferred to step 4. |
| Remote TTY capture recovery | Run `40220070685`: ordinary/null-only phases fail; stdin-only diagnostic passes with exactly two injected TTY peer lookups. | The upstream per-case log reports this case `ok`, with the same capture and remote-cache limitations. | **Owner accepts the reported upstream pass with guarded execution unknown.** Comparison disposed of; downstream failure retained, with repair deferred to step 4. |
| Remote sandbox profile replay | Run `40215033696`: ordinary/traced phases fail; focused null-stdin errno diagnostic passes. | The upstream per-case log reports this case `ok`, with the same capture and remote-cache limitations. The separate postmerge archive job fails before tests. | **Owner accepts the reported upstream pass with guarded execution unknown.** Comparison disposed of; downstream failure retained, with repair deferred to step 4. |
| Blocked-descriptor replay | Run `40220417153`: both ordinary attempts fail. Retained trace shows a dangling `/newroot/dev/fd` symlink, failed tmpfs mount (`ENOENT`), bwrap exit 1, then eight-byte exec-server writes returning `EPIPE` and fatal `SIGPIPE`. The trace step remains alive after both test attempts and reaches the job limit; artifacts and source guards are retained. Null-only phase is skipped. | The upstream per-case log reports this case `ok`, with the same capture and remote-cache limitations. | **Owner accepts the reported upstream pass with guarded execution unknown.** Comparison disposed of; downstream failure retained, with repair deferred to step 4. Establish the pipe reader lifecycle/signal-disposition policy before repair. |
| Local blocked-descriptor fixture setup | A focused pristine Cargo invocation stops before replay assertions: `/tmp/codex-daemon-1000` already exists with owner 1000 and mode 0000. The unchanged `codex-uds` prerequisite requires a user-owned directory with mode 0700 and deliberately ignores `TMPDIR`. Incremental compilation takes 2.83 seconds; the case fails in 0.10 seconds. | The upstream case is reported `ok`, but its directory state and guarded execution remain unknown. | **Different local setup failure isolated.** An outer bwrap with ephemeral `/tmp` and a usable `/dev` leaves the host directory untouched and reaches the same 25-second transport recovery timeout. The observational trace reproduces `/newroot/dev/fd` tmpfs mount `ENOENT` and exec-server `SIGPIPE`. Pristine source is restored clean. Receipts: `blocked-isolated-dev-tmp-owner.json` and `blocked-isolated-dev-tmp-traced-owner.json`. Upstream guarded execution remains unknown. |
| Empty-workspace-roots remote process timeout | Pristine serial Bazel run `40207065002` reports the separate timeout. A focused local Cargo run passes in 0.17 seconds without a bwrap skip message; receipt `empty-root-incremental-owner.json` retains source/dependency guards and 32.70-second incremental compilation. Focused hosted Bazel run `40302440271` then executes exactly this case, with 71 filtered out, and fails with `deadline has elapsed` after 2.12 seconds. No skip message is emitted; the final source guard passes and before/after diffs match. Artifact `12641248361`, SHA-256 `c0c0caf641af254522fbd1113d1d640868f75ebd7eacfcf0cf78809f29c3a087`, is checked in `test-filter-proof-0NBXQJ/hosted-verified.json`. | The upstream per-case log reports this case `ok`, with the same capture and remote-cache limitations; per-case duration and assertion execution remain unknown. | **Missing bundled helper isolated in the hosted child; upstream guarded execution unknown.** Broader-suite execution is not required to reproduce the timeout. Run `40305433807` repeats the ordinary timeout in 2.21 seconds, with identical source diffs. Its trace phase never launches the test because the downstream prefix cannot resolve the logical executable path; the empty trace provides no evidence about the timeout. Corrected focused run `40308835332` now captures the actual case: ordinary/traced failures take 2.11/3.31 seconds, with source guards passing and an 8.514-second incremental trace invocation. The zero-environment sandbox child loses PATH and Bazel runfile discovery, finds no sibling helper, and panics at launcher.rs:60; the test backtrace locates the event-receive deadline at exec_process.rs:1535. The upstream guarded-execution comparison remains unknown. The bundled-helper counterfactual below preserves these failed results. |
| Workspace-manifest validation | Retained clean-source check exits 1 because `code-mode/Cargo.toml` has no feature table while the checker retains its stale exception. Earlier checker repair is discarded. | Parent repo-check job `119599956729` runs the same script and reports the exact stale `MANIFEST_FEATURE_EXCEPTIONS` failure. Checker and affected manifest are byte-identical to the pinned release. Retained upstream log SHA-256: `0a31c873316e50fe9f22eab288e3abbc279298e64728694ee5acfcddc80634dd`. | **Same check fails upstream.** This is a stale checker exception, not a demonstrated build-environment difference. Supplemental repository check outside release-build dependencies; retain failed status for later review without making it a baseline gate or scheduling a repair. |
| Python SDK root formatter assertion | Exact-tag reproduction fails `test_root_format_driver_covers_all_formatter_groups`; the formatter emits a Cargo command while the assertion expects repository-wide rustfmt file discovery. Earlier formatter repair is discarded. | Parent SDK job `119599956750` reports the same assertion and command mismatch: one failed, 268 passed, 38 skipped. Relevant source is identical to the pinned release. | **Same assertion fails upstream.** Supplemental repository check outside release-build dependencies; retain failed status for later review without making it a baseline gate or scheduling a repair. |
| Codespell identifiers | Clean-source maintained scan exits 65 on `WRONLY` and two LaTeX `oint` occurrences; narrower changed-file passes do not establish a whole-source pass. | Parent Codespell job `119599957110` fails on the exact same three locations: `exec-server/src/no_follow/unix.rs:124`, `tui/src/markdown_render/math_tests.rs:47`, `tui/src/markdown_render/math/render.rs:397`. Retained upstream log SHA-256: `f71bac052b7630ed6a2d1ff039312834aedbddf81fc94454c5859984b202b6b5`. | **Same diagnostics fail upstream.** Retain the findings under the nonfatal baseline policy; no suppression or identifier change is introduced. |
| TypeScript SDK non-Git fixture and MCP conformance timeout | Historical suite reports 47 passed/two failed. Removing inherited `TMPDIR` fixes the focused non-Git case; later Node 22/normal-temp full suite passes, without isolating timeout resource contention from other changed inputs. | Parent SDK job `119599956750` reports all 49 TypeScript tests passing with its Bazel CLI, including conformance. Our historical check uses a Cargo CLI; new pristine SDK runtime acceptance against the matched CLI/helper pair is unrun. | **Upstream tests pass; local fixture-temp difference isolated, timeout cause unresolved.** Preserve command/binary identity limits; do not patch tests. |
| Default development Cargo build misses GStreamer metadata | First clean local build cannot discover `gstreamer-1.0 >= 1.28`; installing native prerequisites makes the unchanged build pass. | Upstream target-specific release success excludes these voice components and is not an equivalent default-workspace check. The matching upstream development-build result has not been established. | **Upstream outcome unknown.** Compare full-workspace command and native prerequisite inputs; retained local counterfactual establishes a missing prerequisite only. |
| Windows native GNU Opus header-output validation | Run `40198569205` retains unchanged source and five dangling installed-header links under `opusic-sys-0.7.5`; link invalidation cause remains unestablished. | Pinned upstream maintains Windows gnullvm target checks, but trusted builds execute on Linux RBE while tests execute on Windows. Its no-secret fallback, separate native Windows test job and release matrix use MSVC. No maintained native Windows GNU compilation check is present. A retained parent gnullvm shard reaches 22,184 actions and fails an unrelated mxc-sandbox test, with no Opus diagnostic; it has no Opus-action attestation and is not exact-release evidence. | **No maintained counterpart for native Windows GNU compilation.** Bazel Clippy on native Windows gnullvm fails the same way, on a dangling `include/opus/opus_types.h` link, with only the first queue patch (`42746469138`, opusic-sys 0.7.5) and with all 35 (`42749913658`, opusic-sys 0.7.7), so the queue does not cause it. The execution boundary differs; reproduce upstream's maintained build boundary before attributing this to Opus. The local symlink invalidation cause remains unknown; do not patch dependency source. |
| Dependency audit findings `RUSTSEC-2026-0387` and `RUSTSEC-2026-0385` | Unchanged locked versions fail the retained audit at RustSec database revision `21d909e4c8bd4e14c7aea58bf980c881a0053ab1`. | Parent audit job `119599956903` uses cargo-deny 0.19.0 and reports `advisories ok, bans ok, licenses ok, sources ok` on 2026-10-22 UTC. Its lockfile and deny configuration are byte-identical to the pinned release. Both named advisory IDs were added on 2026-10-23, after that check; retained database `21d909e4` is dated 2026-10-24. Upstream's database commit is unrecorded. | **Historical upstream audit passes; matching newer-database outcome unknown.** The advisory timing difference is established; historical success does not dismiss the retained findings. Retain exact inputs and compare the same database boundary. Stable dependency repairs remain deferred to step 7. |

### Empty-environment bundled-helper counterfactual

The matching-helper staging option also accepts complete-target execution in single-target mode; syscall tracing still requires the exact empty-workspace-roots case. Run `40521582667` at `041ef71c5b8dcc9bdf1664b32e46bc68fe88ad63` demonstrates that dispatching an empty string does not select the complete target: the hosted step receives the default `shell_snapshot` value. It runs 31 cases in 66.65 seconds, with 27 passed, the same four owner-deferred failures and 41 filtered out; that result does not cover the complete target. The matching helper is staged, compilation succeeds, and source guards pass. Artifact `12742004695` matches SHA-256 `59fe71fcb0c88b3892990b60cd4d8d06f655905b2d8d47a4ec8d360e740130c8`; its comparison arguments confirm the received default and its before/after source diffs match. The workflow now uses explicit `*` to omit the name argument; named filters retain their original behavior. A Bash argument-construction probe first forwards that value literally, then passes both complete-target and named-filter cases after the correction. This verifies command construction; the corrected hosted result follows.

Corrected [run `40524534249`](https://github.com/holdmyspot-com/codex-unleashed/actions/runs/40524534249), job `122260351388`, at `9ae96de1159c5227c7718a6fd67d7b907a3e1306` receives the explicit `*` input and runs all 72 integration cases: **57 passed, four failed, 11 ignored, zero filtered out**, in 48.56 seconds. The failing-name list contains exactly the four owner-deferred shell cases; the ordinary empty-workspace-roots case passes within this complete target. The matching helper is staged before the test invocation. Compilation succeeds, and the before/after source diffs are identical, containing only the verified workspace-version lock synchronization. The initial source diff is empty. The job takes 15m 32s and remains failed with exit 3; this result neither passes the four failed cases nor closes the upstream guarded-execution comparisons or the other platform/runtime gates. Artifact `12745145077` matches its API SHA-256 `cf37f366bac49322a7dbcd40c1a68df782a00a07b0af8f30553c20924db1e772`. The retained argument list has only `--test_arg=--nocapture` and `--test_output=all`, proving that no test-name filter is forwarded. The local receipt parser initially assumes an extra blank line before the failure names; inspection of the retained output corrects that parser, and verification then confirms all four exact names, source identities, arguments and the passing empty-root case without rerunning compilation. The executor removes the owned `exec-server-cumulative-cLstkP` artifact directory after recording these results.

The hosted trace above identifies a missing helper after the sandbox child clears its environment. A local copy of the unchanged, hash-verified Bazel test binary reproduces the failure under an isolated `/tmp`. Adding the matching Bazel-built helper beside that binary makes the unchanged case pass without a guard return; tracing confirms bundled bubblewrap executes and constructs the restricted filesystem. No source or test is edited. The matching helper SHA-256 is `f285b84fab4f269728ba5250166068c18c14d6f68184fa4a623816f0419bdab7`; the test-binary SHA-256 is `cdf7adc50bca638523ae40bd8f1775b56bffbbd6fdf84bd0f278398ac9eec628`. The extracted workflow staging block passes the same local check. Hosted comparison `40311676830` now confirms the same mechanism: the ordinary case still fails in 2.11 seconds, then the helper-staged traced case passes in 0.38 seconds, with source diffs identical. The second Bazel invocation takes 3.936 seconds, reports four action-cache hits and reuses the compiled target. Its 496,457-byte trace confirms bundled bubblewrap launches without a missing-helper panic or digest rejection. Artifact `12645826393`, SHA-256 `b6339b348700b2458ae1e1d234491860866efedaae901adf71450cc61535a29a`, and `test-filter-proof-0NBXQJ/hosted-bundle-verified.json` retain the checked evidence. The workflow remains failed because the ordinary failure is preserved.

A pass alone does not establish the intended sandbox behavior: using the different Cargo helper also reports a pass after digest verification rejects it. With the matching helper, the trace instead reaches bundled sandbox construction, then the GNU inner helper returns `ENOENT` under the intentionally empty filesystem view. This does not establish that `/bin/cat` ran or directly attempted the excluded-file read. Preserve these distinct outcomes and the open upstream guarded-execution comparison. Local evidence is retained in `empty-root-bundle-UL94lJ/verified.json`; copied executable and companion links were removed after the checks.

An exact-source review of `exec-server/tests/exec_process.rs` at the pinned
commit confirms the maintained case requires stdout without `excluded`, a
nonzero exit and a closed process. It supplies an empty environment and clears
workspace roots; it does not assert that `/bin/cat` executes or require a
particular stderr diagnostic. The helper-staged pass therefore satisfies the
case's actual assertions. Requiring a successful inner-helper exec would be
an additional guarantee, not a prerequisite stated by this test. Keep the
ordinary missing-helper failure and unknown upstream guard execution separate;
neither is resolved by this source review.

The separate untraced local comparison in
`empty-root-ordinary-UrPEcu/verified.json` uses the same hash-verified Bazel test
and matching helper: the missing-sibling case times out in 2.12 seconds, while
adding only the sibling helper passes in 0.22 seconds. Neither takes the bwrap
capability guard. Temporary executable copies and fixtures are removed; the
goal executor retains the logs until baseline acceptance and owns cleanup.
The focused workflow now accepts `bundle_empty_workspace_bwrap=true` without
tracing: it builds the declared test/helper outputs, stages the matching helper
and runs the ordinary test incrementally against those outputs. With tracing
selected, the earlier original-versus-diagnostic comparison remains available.
Actionlint and whitespace checks pass. Ordinary hosted run
[`40405970958`](https://github.com/holdmyspot-com/codex-unleashed/actions/runs/40405970958),
job `121863693353`, passes at restart commit
`a938f85a5ea87afa1f7d88442967e23ad3fbf3cb`: one test passes in 0.21 seconds,
zero fail and 71 are filtered out. The prepared test invocation takes 5.571
seconds and reuses four actions without compilation. Before/after source diffs
are identical; upstream source and tests are unchanged. Artifact `12691269190`
has SHA-256
`9a8d65e05db9325c0223163dd1d64d0f666f203945fa00d6467a777d855786bc`;
its receipt records `exit_code=0` and the matching helper digest above.
The goal executor retains the downloaded artifact beside the local comparison
receipt until baseline acceptance and owns its cleanup. This accepts the
focused environment repair; upstream guard execution remains unknown.

The complete already-built local exec-server integration target then runs with
that same matching helper, serially and without tracing or recompilation. It
finishes in 64.88 seconds: 57 reported passes, the four owner-deferred failures,
11 ignored and zero filtered out. The empty-root case passes. The four failures
are exactly blocked-descriptor replay, remote pipe recovery, remote TTY recovery
and remote sandbox-profile replay. Four zsh cases among the reported passes
return early because `/bin/zsh` is unavailable; do not count those bodies as
executed. There are no bwrap guard returns. Source diffs before/after match.
Receipt and complete log are retained in
`exec-server-pristine-Z6MR7A/{verified.json,tests.log}` through baseline
acceptance, with cleanup owned by the goal executor; executable copies and test
fixtures are already removed. This is local target evidence, not a complete
cross-platform runtime gate or an upstream execution comparison.

Focused hosted run
[`40409819108`](https://github.com/holdmyspot-com/codex-unleashed/actions/runs/40409819108)
at `339adefd75eaa921b77eecac10e3517338bab733` supplies `/bin/zsh` through the
Linux host's package installation and selects the unchanged `zsh` cases.
Job `121877279177` passes all four selected cases in 11.14 seconds, with zero
failures/ignored cases and 68 filtered out. Its host reports zsh 5.9. No zsh or
bubblewrap prerequisite guard returns appear in the captured test output.
Artifact `12693646354` has SHA-256
`115316d2de2d44d88749b4c5990597dcba66666e82143f8289f30c3c99411330`;
downloaded verification confirms exact source/restart identities, equal
before/after source diffs and `exit_code=0`. This accepts the focused Linux
environment setup and four test bodies; it does not establish upstream
execution or the complete cross-platform runtime gate. The goal executor
retains session temp `zsh-baseline-JKR5CN/artifact.zip` through baseline
acceptance and owns cleanup. Workflow static checks also pass.

### Retrieved upstream case log

Upstream parent Linux job `119599956802`, run `39710355092`, references [BuildBuddy invocation `81a0b14c-a642-4af8-b877-1a3d56db58c7`](https://openai.buildbuddy.io/invocation/81a0b14c-a642-4af8-b877-1a3d56db58c7). Its remotely cached `//codex-rs/exec-server:exec-server-exec_process-test` result retains a 6,663-byte `test.log`, SHA-256 `6d22b56ce262b55f61ace18b2c71427d22fab3189192e9bc8f9cf12864c858cc`. It reports 61 passed, zero failed and 11 ignored in 25.01 seconds. All five cases above are individually reported `ok`; this is stronger evidence than the previously available target summary.

The recorded invocation supplies only `--test-threads=1`, without `--nocapture`, `--no-capture`, `--show-output` or `RUST_TEST_NOCAPTURE`. Libtest hides successful-case output, including the capability guards' `eprintln!` messages. The 7,099-byte `test.xml`, SHA-256 `16b29cfbb585415c652d3193adb977423497f1eb6668113a6c6b9ea7504e9010`, embeds the aggregate log rather than per-case guard evidence. The relevant test/guard source is identical between this parent and the pinned release, but the cached execution's originating host, time and source are not established. These are reported upstream passes; they do not yet prove that the sandbox assertions executed upstream.

The action declares a bundled bwrap runfile and `CARGO_BIN_EXE_bwrap`, but the fixture guard checks trusted system `PATH` bwrap independently. Bundled availability alone cannot establish that this guard passed. Actual upstream system-bwrap discovery and namespace capability remain unknown. The next comparison must expose the guard path from an authoritative upstream execution; reproducing only the container image does not establish the historical executor's filesystem or kernel policy.

The retained extraction includes every platform property for these test actions:
`Arch=amd64`, `OSFamily=Linux` and
`docker://docker.io/mbolin491/codex-bazel@sha256:0a8e56bfaa3b2e5279db0d3bb2d62b44ba5e5d63a37d97eb8790f49da570af70`.
Neither the inspected upstream wrapper/RC nor the consuming job log records an
`x-buildbuddy-platform.*` override or runner recycling. This narrows the
consuming invocation's configuration; it does not identify the original cached
execution's request. BuildBuddy's [execution-property documentation](https://www.buildbuddy.io/docs/rbe-platforms/#setting-properties-via-remote-headers)
states that request headers can override platform properties without storing
those values in the remote cache. Consequently, the cached platform and a
local run of its named image cannot by themselves prove the original action's
filesystem or whether its guard returned early. Do not repeat the image-only
comparison as a way to close this gate. Fresh upstream output exposing guard
execution, or an explicit owner disposition of this comparison, is required.

The owner provides that disposition: accept the reported upstream per-case
passes with the limitation that successful-case output cannot prove sandbox
assertion execution. No fresh upstream execution is required to dispose of
these five comparisons. This accepts the reported upstream outcome only; it
does not pass the four downstream failures, authorize source repairs before
step 4, or close other runtime checks.

The two supplemental manifest/Python formatter checks above are not prerequisites of the Codex release build. The pinned `rust-release.yml` starts on release-tag pushes; its compilation job depends on `tag-check`. It does not invoke those two checks. Their failures therefore coexist with a successful release build; no passing broader-CI claim follows from the release.

Blocked-descriptor trace evidence is retained under `pristine-shell-blocked-io-1-artifacts`, with receipt `pristine-shell-blocked-io-1-verified.json`. The compiled archive SHA-256 is `0f4e4eba992fa1f04004329c5581306b3b63912d5986e7b7b433cd65d8c519a6`; trace SHA-256 is `af38bf4a28513c5b9c56ba8113a330bd09dac683addd7b1fb5b21e0a89e198af`. The inventory selects exactly one nonignored case. Trace lines 924032–924168 show bwrap creating `/newroot/dev/fd -> /proc/self/fd`, failing target lookup and the tmpfs mount, printing its mount error and exiting. Lines 940398–940420 show the initiating writes to descriptors 19 and 24 returning `EPIPE` before `SIGPIPE` terminates exec-server. These observations identify failed operations, but do not establish the reader-close cause, signal policy or upstream environment equivalence. The overall run is cancelled; neither the traced failure nor skipped null-only phase is a successful acceptance check.

A local counterfactual installs the normal Rust startup policy, `SIGPIPE=SIG_IGN`, only in the ctor-dispatched test server: the trace confirms installation after exec and no fatal SIGPIPE, but the unchanged test still fails its output assertion after 4.44 seconds while the descriptor-denial mounts still fail. The prior connection-reset/recovery timeout is therefore distinct from the mount failure; retain the diagnostic under `sigpipe-counterfactual-BSFNsB` and investigate that remaining operation before proposing a source repair, with source and dependency guards passing after exact lockfile restoration.

A minimal `--dev /dev --perms 000 --tmpfs /dev/fd` comparison fails with the same missing-target mount error under both system bubblewrap 0.9.0 and the pinned source's freshly Cargo-built vendored bubblewrap 0.11.2, so merely selecting the newer helper does not resolve this operation. The vendored helper builds incrementally in 4.56 seconds with nonfatal warnings, passing source and dependency guards; `descriptor-mask-minimal.json` records both commands and binary hashes, while upstream's actual guarded test execution remains unresolved.

A fresh-directory control now changes only the minimal tmpfs destination from `/dev/fd` to `/dev/fd-control`; both exact, hash-verified bubblewrap binaries succeed. A focused vendored-binary trace observes `pivot_root("/tmp", "oldroot")`, creation of `/newroot/dev/fd` as an absolute `/proc/self/fd` symlink, a following stat returning `ENOENT`, mkdir returning `EEXIST`, and the tmpfs mount returning `ENOENT`. The source review explains the topology: mount setup occurs inside the staging root before the final pivot, so that absolute symlink resolves outside the constructed new root. The directory-creation helper accepts the existing dangling symlink without confirming its resolved target. This isolates destination topology from generic mode-000 tmpfs capability and strengthens the local mount diagnosis; it does not prove replay correctness, upstream guarded execution or the separate signal-policy repair. No source/test change is made. Receipts and trace: `descriptor-directory-control-KrKf2x/{verified,trace-receipt}.json` and `vendored-symlink.trace`. The owner approves deferring the blocked-descriptor repair to step 4. The case retains failed status; upstream guarded execution and the separate signal-policy investigation remain open. No source repair is authorized in steps 1–3.

The three peer-lookup diagnostics establish an observed Bash/seccomp mechanism in our environment. They do not establish that upstream encounters the same denied syscall or that upstream's tests exercise the same sandbox path.

## Interrupted builds

| Failure | Verified outcome | Upstream comparison still required |
| --- | --- | --- |
| Clean Linux x86-64 runner loses communication | Run `40434705434`, job `121960532451`, fails after 1h 15m 17s with GitHub's lost-runner-communication annotation. Final smokes, guards and evidence upload do not finish. Its job-log API returns HTTP 404, so no compiler terminal result or resource samples are available. | Exact-source upstream release `39894017941` succeeds for primary Cargo job `120252106123`, target `x86_64-unknown-linux-musl`, on `codex-linux-x64-xl`; our host is the free standard Ubuntu 24.04 runner. That success does not identify our runner's interruption cause. The existing signal/resource observer is extended to uncached x86-64 execution, with native preflight required before a diagnostic clean retry; source, release commands and concurrency are unchanged. |
| Initial macOS and Windows x86-64 release jobs reach our 120-minute limit | GitHub annotations identify our configured job timeout; partial compilation is not platform acceptance. The limit was removed. Windows x86-64 retry `40213213142` succeeds in 2h 8m 8s with source guards and both native smokes; macOS ARM retry `40213209183` also passes both native smokes and source guards; macOS Intel remains pending. | Exact-release upstream `rust-release` run `39894017941` succeeds. Retain the target/job/command match rather than inferring test success from release success. |
| Intel clean verification cannot retrieve the crates.io index | Run `40438957248` attempt 1, job `121974418865`, fails before any Rust compilation: Cargo cannot transfer the `aw/s-/aws-config` index entry, exhausting its retries with zero transferred bytes. Artifact upload separately fails with `ENOTFOUND`. The source guard passes. | Exact-release upstream run `39894017941`, job `120252151708`, succeeds on target `x86_64-apple-darwin` with Rust 1.99.1: its log updates the crates.io index, downloads `aws-config v1.8.12`, compiles it and completes the release build. Its host is macOS ARM64 on `macos-15-xlarge`, whereas our failing host is native Intel on a free standard runner. Upstream does not exhibit this dependency-acquisition failure. The failed runner's network/DNS cause remains unknown; unchanged attempt 2 retries on a fresh runner without source or configuration changes. |
| Linux ARM cache jobs lose their runner or receive shutdown | Attempt 1 of `40213329117` loses communication without build logs. Attempt 2 retains `UserCancelled` shutdown classification and exit 143; its initiating actor remains unknown after available audit checks. Guards, smokes and cache save do not execute. | Exact-release upstream run `39894017941` passes both ARM Cargo release jobs: primary `120252106552` and app-server `120252106352`. Their logs verify the pinned source, Rust 1.99.1, target and actual successful Cargo invocations. Both use `codex-linux-arm64` hosts with 16 logical CPUs and 62.7 GiB RAM. This establishes upstream build success and a resource difference from our free standard runner; the initiating actor in our interrupted runs remains unknown. |

The upstream ARM logs check out
`6aaac694803004a63c6f393d153c96d22521a6b9` and install Rust 1.99.1
(`58cf8f9c3`). Both install the maintained musl prerequisites and build bwrap
before the consuming binaries. The primary invocation is
`cargo build --target aarch64-unknown-linux-musl --release --timings --bin codex --bin codex-code-mode-host --bin codex-responses-api-proxy`;
the app-server invocation selects `codex-app-server` and
`codex-code-mode-host` with the same target/profile flags. Their Cargo completion
records report 15m 18s and 9m 28s respectively. Our executable checkpoints split
that same required binary set into separate invocations. Upstream's successful
release compilation does not establish that any sandbox test executed.

The public job-log API returns both complete logs. Their SHA-256 values are
`05e8dce2fc8d9f93f989a9a6e6eee64ce7340509c436fc1e506c2b37a76de450`
(primary) and
`18ba92f38c9bea1f742023f30c2da1c72533b20ce6bcd518c65e0b2154dbabaa`
(app-server). The root executor retains the raw logs under the session's
`upstream-arm-comparison-vXKDSQ` directory until the failure-comparison gate is
recorded, then removes that owned directory. The runner shutdown cause remains
open; no source, dependency or build-concurrency change follows from this
comparison.

The Intel acquisition comparison uses the public job-log API because
`gh run view --log` returns empty output for that upstream job. Retained
log SHA-256 values are
`48e9effb7b3474c56155d95bc2c73be588d083084b2739fc4954d43780d1b606`
(our failed job) and
`032dba24cfcc287298ab60fd5bc3c47023f31083a8844839dfdd3b48dd433884`
(upstream primary Cargo job). The root executor owns their temporary copies
under `clean-matrix-NPAgpQ` until the clean-matrix outcome is recorded,
then removes that directory. The failed attempt and its skipped native smokes
remain failed/unrun evidence; upstream success does not pass our clean gate.
Our unchanged Intel attempt 2, job `121978110058`, subsequently passes clean
compilation, both native smokes and source/dependency guards in 3h 24m 39s.
This accepts the retry without establishing the first runner's network/DNS
cause. Its artifact and log hashes are retained in
[release-build-checkpoints.md](release-build-checkpoints.md#clean-verification-after-grouped-cache-acceptance).

## Downstream setup and wrapper failures

Resolved wrapper incidents retain counterfactual evidence in [build-baseline.md](build-baseline.md) and [cache-migration-preflight.md](cache-migration-preflight.md).

| Failure | Upstream counterpart and evidence | Status |
| --- | --- | --- |
| x86-64 observer preflight expects an ARM syscall | Downstream-only run `40442706479`, job `121986557420`, captures the real self-kill stack through `__x64_sys_kill+` and records command exit `-9`, but our diagnostic test asserts `__arm64_sys_kill+`. The native ARM64 job passes. This observer has no upstream Codex counterpart. | The failure is our architecture assumption in the test after broadening its host matrix. Commit `824c844eaa` selects the expected syscall entry from the host architecture while retaining signal, stack and exit-status assertions. Native run `40443064027` passes on x86-64/ARM64 in 30/33 seconds, including all real-command signal/exit/resource checks. Diagnostic clean release run `40443272471` then starts on that tested revision; its build result remains pending. |
| Private retention inventory times out before helper execution | Both attempts of downstream-only run `40401691282` at `37b957732f` fail in the first `gh api --paginate repos/openai/codex/releases?per_page=100` call with HTTP 504; package collection and helper compilation are unrun. A local request for the same page also fails. This private-cache inventory has no upstream Codex build counterpart. | Smaller 20-release REST pages return all 1,245 unique tags locally, matching every captured metadata record. Hosted run `40404430412` then passes collection, 37 helper tests, runtime assembly and private-inventory preview in 2m 13s. The server's internal timeout cause remains unknown. Old main's GraphQL query returns only 1,000 releases and is rejected as an incomplete replacement. |
| Retention workflow passes an unsupported Rust setup input | Hosted run `40404430412` warns that `toolchain` is unexpected. The exact pinned action declares only targets/target/components and fixes its own toolchain to 1.99.1; the executed log confirms rustc 1.99.1. This is downstream workflow configuration, with no upstream Codex test counterpart. | Commit `ae6f9924bb` removes the redundant input while preserving the action and actual toolchain. Run `40404968404`, job `121860246662`, passes in 2m 15s with zero annotations, rustc 1.99.1, all 37 tests, runtime assembly and the same preview classification. |
| Run-list monitor misses an existing pushed workflow | The ad hoc downstream monitor filters by abbreviated commit `37b957732f` and receives `[]` until its observation deadline. A paired local query with full commit `37b957732f4bd39b1425f8dba94b856847010b76` returns run `40401691282`. Upstream Codex has no counterpart to this goal executor's monitor. | Monitoring now uses the authoritative run ID and obtains the actual failed job result. The monitor timeout did not establish that the hosted workflow was absent or still running. Use full SHAs when a commit filter is needed. |
| Cache invalidation verifier rejects the actual doctor JSON | Linux x86-64 run `40387733411` rebuilds only the pristine CLI after changing `CODEX_BUILD_COMMIT`, and its retained doctor report contains that commit. Our verifier then raises `TypeError` because it expects arrays. Upstream's `cli/src/doctor.rs` defines check/detail maps; upstream has no counterpart to this downstream invalidation verifier. | Correcting the real-Cargo fixture to match the actual report reproduces the exact error before repair. Commit `4d3cd41e47` reads the maps; acceptance and stale-provenance rejection pass in 1.28 seconds. Hosted rerun `40393398689`, job `121820038862`, passes: changed CLI provenance is observed, no non-CLI compilation rebuilds, and the next unchanged invocation rebuilds nothing. This establishes the Linux x86-64 invalidation gate only. |
| Partial checkpoints re-upload unchanged restored archives | Run `40387733411` restores six groups from the verified compatible reference, but its first partial save reuses none and uploads 2,916,929,198 archive bytes. The downstream transport searches only the partial exact reference. Pristine upstream has no corresponding GHCR checkpoint operation. | Two real-ORAS regressions reproduce missing verified-reference and compatible-reference lookups. Commit `e1c66a7fcb` searches both states for both references, retaining signature checks and publication isolation. Both regressions upload zero archive bytes; native fixture run `40394114661` passes on all six platforms. Hosted Codex run `40394539659` passes in 8m 34s with zero Rust compilation records. Each partial checkpoint reuses all five output archives and uploads only metadata; the final save does the same. Other platform acceptance remains separate. |
| Verified cache alias lookup after successful publication | Linux release run `40332032199` attempt 2 completes compilation, native smokes, source/dependency guards and source recording. Its downstream-only GHCR save pushes manifest `sha256:29d8f96e8048c534145aae540d3c3f1d9b23daf1db44e097813bf7fdfd36d6ea`, then `oras tag` immediately receives `not found` for that release tag. Pristine upstream has no corresponding GHCR Cargo-target publication. Read-only hosted inspection `40365166154` subsequently fetches the exact manifest by both tag and digest and confirms its package metadata. | The failed boundary is the immediate tag lookup; GHCR's internal reason remains unknown. Commit `fc756e3a8c` supplies all aliases to ORAS's local-manifest publication instead. A focused regression supplies the observed lookup failure and passes grouped/legacy alias and restoration checks. Hosted publication and full grouped Codex verification remain required. |
| Windows multipart fixture launches the installed ORAS instead of its local-layout wrapper | Run `40329087323` passes Linux/macOS and fails Windows when native Python sends the local-layout reference to registry parsing. This fixture and multipart transport were introduced downstream in `e3d4793cf0`; the pristine upstream release has no counterpart. | Explicit Bash launcher selection in `e03f8c5195` reaches fixture publication on Windows in `40330347559`. Local legacy and multipart checks pass. |
| Windows multipart archive restoration fails | Run `40330347559` publishes five groups, then extraction reports an unknown Zstandard frame descriptor. Diagnostic `40331335517` establishes that Python's bare `tar` launches Windows bsdtar 3.8.9, while the resolved PATH executable is Git GNU tar 1.35. The former produces a 10,240-byte archive; the latter produces 186 bytes for the same input and options. Both start with Zstandard magic; archive size alone does not establish missing compression. This is the downstream-only multipart transport. | Resolve the full executable path at the CLI boundary in `ef3040ce05`. Run `40331765306` passes the complete multipart transport checks on both Windows hosts, including restoration, nanosecond timestamps, links and independent groups. The separate stage-test failure below does not invalidate that transport result. |
| Windows release-stage fixture launches WSL Bash | After archive checks pass in `40331765306`, the new Python stage fixture fails before the release script starts: native `bash` launches WSL and reports no installed distributions. Upstream has no counterpart to this downstream orchestration fixture. | Resolve Git Bash from PATH and pass portable script paths in `2cbbd79c01`; all four local tests pass. Native rerun `40332295510` passes both archive and stage checks on all six hosts. |
| Focused Bazel test selection | The retained execution record for `40207065002` passes `shell_snapshot` through `TESTBRIDGE_TEST_ONLY`, without a test-name argument. The hash-verified pristine Rust test binary ignores that environment selector and lists all 72 cases; a positional argument selects the requested case. This is our diagnostic selection, not an upstream source defect. | The current wrapper already passes a name argument for its shell selection. Its new `exec_server_filter` input permits the empty-workspace-roots comparison and defaults to that existing shell selection. Executing the old/new workflow argument blocks against the same pristine binary changes selection from 31 shell cases, excluding the requested case, to exactly the requested case. The focused mode now builds only its integration target and exposes successful-case output. Local receipts are retained in `test-filter-proof-0NBXQJ/{verified,workflow-selector-verified}.json`; actionlint and whitespace pass. Hosted assertion execution and the original timeout cause remain unverified. |
| macOS cache repository expansion | Both macOS jobs in `40256590363` fail before ORAS invocation: `/bin/bash` rejects the downstream workflow's Bash 4 lowercase expansion as `bad substitution`. Their native archive fixtures and source guards pass. Upstream's pristine release workflow has no operation restoring our GHCR compiled cache. | Downstream-only shell compatibility failure. Deriving the repository with portable `tr` passes the local mixed-case check and workflow lint; native macOS release retries remain required. |
| Bazel trace executable resolution | Run `40305433807` reaches the already-built target, but `strace` cannot stat its logical executable path; the trace is empty. The trace invocation takes 3.032 seconds and reports four action-cache hits. Upstream disables runfile symlinks and resolves the executable through its test-setup manifest. Our added trace prefix does not perform that resolution; upstream has no corresponding prefix. | **Downstream diagnostic failure isolated.** A local reproduction through the actual Bazel test setup and retained binary fails with the same missing-path error. Binding the physical executable in `aaed14cecd` retains that harness and its arguments: full/focused listings select 72/1 cases, and an invalid libtest option retains exit 101. Workflow lint and whitespace checks pass. Hosted retry `40308835332` proves actual traced execution with 1,223,796 trace bytes; ordinary and traced cases still fail. The target is up-to-date, with four action-cache hits and an 8.514-second traced invocation. Source diffs remain identical. Artifact `12644761200`, SHA-256 `450b2b7ca1ae58f81507796b2462c7db83fe15ea46b8571ca1b81013edb42500`, retains the evidence. |
| Exact-hit compiled cache still rebuilds workspace crates | This GHCR target-cache wrapper is downstream-only. Corrected original-digest diagnostic `40225613962` observes stale input timestamps on checkout files and the downloaded V8 binding, followed by dependent rebuilds. The pristine upstream release does not execute our cold-to-warm restoration contract. | Downstream cache-reuse failure. Content-checked preparation and executable preservation now pass actual Codex warm reuse on all six release targets, including Windows, with zero Rust compilations. The earlier Windows dirty reasons remain unobserved; later successful reuse does not establish that historical cause. See [grouped-cache acceptance](release-build-checkpoints.md) and [source-cache evidence](source-cache-migration.md). Relevant runtime-test acceptance remains open. |
| Release source guard accepts dependency lockfile drift | A real Git fixture changes a dependency version while the exact downstream guard still exits zero: the guard excludes the entire root `Cargo.lock`. The pinned upstream release does not run this downstream cache-acceptance wrapper. | Downstream verification gap. A typed TOML comparison now permits only workspace-version synchronization from actual Cargo metadata and rejects dependency drift before accepting outputs. Eleven CLI cases and a Cargo-generated lockfile fixture pass locally. The dependency guard subsequently passes actual Codex grouped-cache and cumulative clean builds on all six targets; these receipts accept dependency identity for those builds, independently of the open runtime-test gate. See [release-build checkpoints](release-build-checkpoints.md). |
| Target archive loses fractional modification timestamps | The real OCI push/pull regression restores a fractional timestamp as whole seconds with the default tar format; selecting PAX preserves its exact nanoseconds. The upstream release has no equivalent GHCR target-archive operation. | Downstream transport defect; the PAX correction passes the real-OCI timestamp regression. Native multipart fixtures subsequently pass all six hosts, including timestamp/link retention, and actual grouped Codex restoration reuses compilation on all six release targets. See [release-build checkpoints](release-build-checkpoints.md). These cache results do not close runtime-test acceptance. |
| Windows maintained runtime fixture starts WSL Bash | Both Windows jobs in `40231090075` build the runtime and pass all 32 Java tests, then the unqualified Python subprocess `bash` produces a WSL setup error at the first invalid-target case, before the helper runs. The workflow itself uses Git Bash. This fixture/helper is downstream-only and has no upstream counterpart. | Downstream executable-discovery gap repaired by passing the workflow shell’s explicit native path into Python. Later native runs `40244139124` (x64) and `40244141254` (ARM64) verify the selected Git Bash, pass all 34 current helper tests, runtime assembly, the packaged reuse/invalidation fixture and source guards. Their separate Git-input environment repair is recorded below. These results establish native helper/fixture acceptance, not full Codex caching. |
| Windows process diagnostic receives several Git paths | Run `40235518339` fails in Python argument parsing before Java starts: PowerShell `Get-Command git.exe` returns several executable matches and expands the array into extra arguments. This diagnostic exists only downstream. | Downstream command-selection error. Selecting the first executable makes both fixed-command probes pass in `40235767566`, with zero remaining job processes. The full helper delay remains a separate investigation. |
| Windows helper verification reaches its 30-minute limit | Run `40237430190` cancels both Windows jobs during helper verification. The focused maintained inventory test locates an approximately nine-second Git delay before its Trace2 command-start event. Configuration/locale controls do not remove it; `40243815294` changes only Git stdin redirection and reduces the same test to 0.179–0.186 seconds on both hosts. This downstream helper/test has no upstream counterpart. | Inherited stdin is the selecting environment condition; the internal Git operation remains unidentified. Native verification now sets documented `GIT_REDIRECT_STDIN=NUL`; runs `40244139124` and `40244141254` pass all 34 tests and the packaged fixture, with Maven taking 46.653/64 seconds. No Codex source/test repair or Git-internal defect claim follows. Later actual Windows Codex grouped-cache runs pass unchanged-work reuse, native smokes and source/dependency guards, as recorded in [release-build checkpoints](release-build-checkpoints.md). Runtime-test acceptance remains open. See [helper migration evidence](source-cache-migration.md). |
| Cold failed-build retry discards unaffected library outputs | A controlled downstream fixture compiles a library, fails its executable, fixes only the executable, then invokes preparation again. Without a successful snapshot, the helper cleans the workspace and removes that library. This Java preparation has no upstream counterpart. | Downstream resumption gap; maintained Java and packaged-runtime regressions fail before the fix and pass with a distinct completed-preparation checkpoint. All 34 focused tests pass locally. Separate-runner producer `40241654118` and consumer `40241805865` subsequently verify compilation-error recovery with completed libraries reused. Actual Codex consumer `40294846242` also restores partial state after a deliberate post-compilation failure and compiles zero Rust packages. The cumulative six-target clean matrix passes separately. See [helper migration](source-cache-migration.md) and [release-build checkpoints](release-build-checkpoints.md); runtime-test acceptance remains open. |
| Integrated helper tests inherit a binding for an absent V8 package | Run `40233193525` fails before cache restoration or Codex compilation: upstream V8 setup exports `RUSTY_V8_SRC_BINDING_PATH`, which ordinary downstream Cargo fixtures inherit. They contain no V8 package, so `cargo clean -p v8` exits 101. Setting only the binding variable reproduces this locally. Upstream has no counterpart to these helper fixtures. | Downstream test-environment isolation error. Removing the inherited binding only from ordinary fixtures makes all 34 tests pass with that variable present in Maven's environment; the dedicated V8 fixture still configures its own binding. Hosted counterfactual remains pending. |
| Actual-workspace helper probe fails with an empty offline Cargo home | The downstream-only preparation probe invokes `cargo clean` before building. With forced offline mode and an empty home, Cargo cannot resolve the pinned crossterm Git patch. Changing only to the existing populated dependency home makes the same helper/source operation pass. The upstream release has no equivalent downstream freshness-preparation command or this empty-offline setup. | Downstream probe configuration error; no Codex source/test repair. The actual 166-package workspace-version lock synchronization separately passes the guard; source is restored clean. |
| Reduced Java helper initially fails compilation | The downstream extraction omits four existing old-main classes referenced by the selected helper. Including those classes restores compilation; the reduced module then passes 32 focused tests and real Cargo CLI reuse/invalidation checks. This Java tooling is absent from the pinned upstream build. | Downstream extraction error; no upstream source/test defect. See [helper migration](source-cache-migration.md). |
| Old helper does not preserve external V8 binding freshness | A real Cargo fixture reproduces a rebuild after only an external binding timestamp changes. Old main inventories Git inputs and omits the actual `RUNNER_TEMP` binding. The pinned upstream release has no equivalent downstream freshness helper. | Downstream cache-inventory gap; a separate content-checked binding inventory passes local reuse/invalidation and drift-recording checks. Hosted Codex integration remains pending. |
| ORAS Windows asset selection and escaped-filename GHCR cache-key hashing | The pinned upstream `.github` tree contains neither ORAS nor this GHCR Cargo-target wrapper. These are downstream-only operations; native fixtures isolate the wrapper corrections. | No upstream counterpart at this workflow boundary; downstream wrapper failures, not upstream test defects. |
| Local pnpm install rejects module-directory replacement without a TTY | Upstream parent SDK job `119599956750` successfully runs `pnpm install --frozen-lockfile` using Node 22 and pnpm 10.34.5 with its configured store. Our invocation selected a store inconsistent with existing module metadata; selecting the recorded existing store makes the unchanged frozen install pass. | Upstream install passes; isolated local store configuration mismatch. SDK job success and SDK runtime-test acceptance are not inferred from install success. |
| Diagnostic-workflow ShellCheck SC2155 | The new downstream-only fingerprint workflow combined command substitution and `export`, masking assignment failure. Upstream has no such diagnostic workflow; its maintained baseline pattern assigns before exporting. Splitting the declaration restores actionlint without suppression. | Downstream workflow construction error; no upstream test counterpart. |
| Initial fingerprint diagnostic dispatch returns HTTP 404 | GitHub lists no workflow registration for the new filename, and the default branch remains old `main`. This diagnostic exists only on `main.new`; no upstream counterpart exists. A path-limited push trigger on `main.new` supplies its registration/execution boundary without modifying old main. | Downstream workflow launch/setup failure; hosted execution remains unverified until its run is observed. |
| First fingerprint diagnostic emits no dirty reasons | The downstream diagnostic uses the older `cargo::core::compiler::fingerprint` log target. A Cargo 1.99.1 fixture emits reasons with `cargo::compiler::fingerprint` and remains silent with the old target. No equivalent upstream diagnostic workflow exists. | Downstream observation failure; correct only the filter and require emitted evidence before accepting the diagnostic. |
| Local tool PATH setup | Explicit tool paths restore the pristine local `just fmt-check`. Upstream parent repo-check job `119599956729` skips that command after an earlier manifest-check failure; separate Rust format job `119600408228` passes its narrower `cargo fmt` command. | Full upstream `just fmt-check` execution remains unknown; the narrower format pass does not close this comparison. |

## Historical comparison work still required

The earlier evidence in [build-baseline.md](build-baseline.md) also contains the following distinct failures. Their source/patch state must be retained from each run; none establishes a defect in the current pristine checkout. These comparisons remain open and cannot be omitted when closing the restart.

| Failure / owning boundary | Available comparison and remaining evidence |
| --- | --- |
| Package-test import without `CODEX_REPO_ROOT` | Supplying the actual checkout root makes all 27 tests pass. Upstream parent repo-checks skip the suite after the manifest failure; no matching hosted outcome is established. |
| Unix-socket fixture paths exceed `SUN_LEN` under our nested `TMPDIR` | Focused normal-temp execution passes. Compare the same upstream fixture invocation and temp path; do not apply a source repair. |
| Local daemon socket directory is protected by the executor | The fixed `/tmp/codex-daemon-1000` directory is a read-only mode-000 mount here. Preserve that protection; matching upstream native test behavior remains to be established. |
| Initial Linux nextest build exhausts disk (`40160045323`) | Tests and artifacts do not survive. Match the upstream nextest archive/shard command and runner storage rather than using release success as a proxy. |
| Local and hosted Bazel test/Clippy/release checks exhaust disk | Retain each run's patched source, target selection, consuming directory and measured capacity. Upstream selected runner-group storage and same-command outcomes remain unestablished. |
| macOS wrapper empty-array expansion and Windows drive-relative `df` | Historical downstream invocation corrections retain argument fixtures. Compare the upstream wrapper invocation; these are separate setup mechanisms, not pristine Rust failures. |
| Windows Bazel MSVC license and `SystemRoot` setup | Retain each historical patch set. Compare maintained upstream native MSVC bootstrap before judging source. |
| Windows GNU linking, doctor `PATHEXT`, WSL/no-distribution and compact-log directory setup | Separate historical outcomes require their own command, source, platform and upstream results; do not combine them with pristine Opus validation or infer a shared cause. |

No source/test repair, retry-policy relaxation, skip or diagnostic suppression is authorized by this ledger. A comparison is complete only when its outcome and relevant execution boundary are supported directly; preserve unknowns until the required evidence is available.

## Native Linux ARM64 daemon hashing and import controls

Run `40793215263` uses pristine selected upstream source at `dddc3c0a79528c4de8d9a20d51dd3bd89b02a3e4`. Its original CLI run passes 540 cases and times out on four daemon cases; its original app-server run passes 2,061 cases and fails one session-import case. Original failed attempts remain retained.

The selected upstream `MODULE.bazel` explicitly optimizes both BLAKE3 backends and Rust code at level 3 because daemon tests repeatedly hash large CLI executables. Our Cargo `ci-test` profile leaves that package unoptimized. Native profiling places 95.93% of sampled CPU in `blake3_hash4_neon` while the daemon repeatedly reads its approximately 1 GB executable. Adding only an external Cargo-home package-profile override matching upstream's optimization makes all four unchanged cases pass in 6.697 seconds after an 18m 41s incremental rebuild, with unchanged timeout/retry settings and clean source/dependency guards. This establishes the relevant build difference and passing environment control; it does not establish an independently executed native upstream ARM64 CI result.

The app-server import case passes unchanged in 0.318 seconds after only the owned temporary basename changes to one without a hyphen. The selected-source decoder attribution and equivalent macOS control are retained separately. Its original failure, full named passing receipt and source guards are preserved.

[Focused completion evidence](evidence/linux-arm-runtime-focused-completion.json) embeds both hash-verified raw archives, case joins, cache publication and remote lifecycle results. All 544 required CLI cases and 2,062 required app-server cases now have passing receipts across original runs and focused controls.

The retained [upstream macOS CLI evidence](evidence/upstream-macos-cli-comparison.json), receipt `cli-app_server_daemon-test`, also supplies the corresponding upstream outcomes for all four original Linux ARM64 timeouts. Its full 1,136-byte log has verified SHA-256 `3151547b912e4d6b15146b29ee18f81d84f9a1506c040cd196efc33c7150a9b8` and reports all 13 daemon cases passing, with zero failed, ignored or filtered. Run `40529953490`, job `122279717700`, executes under `darwin-sandbox`; its merge checkout and selected commit have identical complete source trees. The receipt's older two-case index is narrower than its full raw log. [The hashing diagnosis](evidence/linux-arm-daemon-hashing-diagnosis.json) now indexes all four exact names against that log.

This closes the corresponding-upstream-outcome gap without another build. The upstream result is macOS Bazel and the paired downstream control is Linux ARM64 Cargo; their environments are not identical. Successful libtest output does not expose assertion-by-assertion execution or child environment values. No native upstream Linux ARM64 result is claimed. [Checkpoint 1 acceptance](evidence/checkpoint-1-acceptance.json) records the final gate reconciliation with those limits and the owner-approved deferrals intact.

## Checkpoint 4 cumulative runs: failures shared with the pinned base

These failures were first observed in the checkpoint 4 cumulative runs of the 20 downstream patches at `dddc3c0a79`. Each one fails identically in a run of the same workflow with no patches applied, so the patches neither cause nor repair it. No upstream run of these exact cases on this commit has been compared; their upstream outcome is unknown, and the mechanism column records only what our logs show.

| Failure | Mechanism in our logs | Patched run | Run without patches |
| --- | --- | --- | --- |
| `codex-v8-poc` `tests::sandbox_feature_matches_linked_v8` | `v8-poc/src/lib.rs:69` asserts the crate's V8 sandbox feature equals the linked V8's; the feature reports `true` and the linked V8 `false`. | Linux nextest `42410922258` attempt 3 | Linux nextest `42419135579` |
| `codex-skills-extension` `host_roots::tests::repo_ancestry_without_project_marker_does_not_walk_parents` | `ext/skills/src/host_roots_tests.rs:415` finds the outer fixture directory's `.agents/skills`, which the test expects to be excluded; it passes when run alone, so something outside the fixture in the full run supplies the marker it walks to. | `42410922258` attempt 3 | `42419135579` |
| `codex-linux-sandbox` `root_metadata_symlinks_cannot_reopen_approved_denials` | The sandbox launcher panics: no system `bwrap` is on `PATH` and no bundled `codex-resources/bwrap` is next to the test executable. | `42410922258` attempt 3; Bazel `42410924616` | `42419135579`; Bazel `42403726387` |
| Bazel `//codex-rs/linux-sandbox:linux-sandbox-all-test` `managed_proxy` cases | `managed_proxy_mode_routes_through_bridge_and_blocks_direct_egress` and `unsupported_system_bwrap_falls_back_to_bundled_bwrap` fail in the same target as the case above. | Bazel `42410924616` | Bazel `42403726387` |
| Bazel `//bazel/rules/testing/wine:wine-test-support-unit-tests` | Times out at its 60-second limit; `pinned_powershell_runs_under_wine_with_a_pty` fails. | `42410924616` | `42403726387` |
| Bazel `//codex-rs/rollout:rollout-unit-tests` | `compression::tests::worker_preserves_alternate_group_through_materialization` fails. | `42410924616` | `42403726387` |
| Bazel `//codex-rs/exec-server:exec-server-exec_process-test` | Only `remote_process_preserves_empty_workspace_roots` fails, the case whose upstream disposition the owner accepted above. The four deferred shell cases fail without the patches and pass with them. | `42410924616` | `42403726387` |
| Bazel `core-all-test`, `core-all-wine-exec-test` and `app-server-all-wine-exec-test` | Reported `FLAKY, failed`: they fail and then pass on retry. | `42410924616` | `42403726387` |
| `codex-cli` `sandbox_network_proxy::sandbox_with_network_proxy_blocks_direct_loopback_access` | Fixed; caused by this repository's workflow, not by upstream or the runners. Commit `71aac59830` exported a job-wide `CURL_HOME` whose `.curlrc` sets `retry = 8`, `retry-connrefused` and `retry-max-time = 900`, so the test's curl retried the refused connection that the sandbox correctly produces until nextest's 60-second limit killed it. Since commit `2eb1ac8d69` the Linux test workflow points `CURL_HOME` at an empty configuration after the download steps, and focused run `42746049933` passes the test in 0.13 seconds. | Linux nextest `42707204751` (33 patches), focused `42722754655` (28 patches) and `42726279198` (27 patches) | Focused `42726284816` |
| `codex-core` `compact::tests::local_compaction_retries_preserve_instructions_and_tool_metadata::metadata_enabled` | Takes 42–55 seconds against the 60-second limit, so it times out when the runner is loaded. | Linux nextest `42712983966` (34 patches) timed out; `42707204751` (33 patches) passed in 54.6 seconds | `42419135579` (42.4 seconds) and `42543228575` (53.8 seconds) passed |

The patched Bazel run also failed `//codex-rs/cli:cli-rollout_trace-test` (`accepted_output_survives_interruption_and_shutdown`, deadline elapsed) and `//codex-rs/tui:tui-all-test` (`focus_palette`, missing exit DA1 query). Both pass in the patched Cargo run, and focused patched Bazel run `42419575029` passes both targets, so they were not patch regressions.

On the 35-patch queue (2026-11-10), native Bazel test run `42771464472` failed `//codex-rs/cli:cli-rollout_trace-test` again, this time in `accepted_output_survives_success_and_stream_failure` (its wiremock mock received no request). The earlier 35-patch run `42759554553` failed `//codex-rs/cli:cli-daemon_telemetry-test` (`foreground_update_honors_metrics_exporter_and_reports_unconfirmed`, 0 OTLP requests instead of 1). Each target passes in the other run, the patched Cargo run `42781922596` passes both tests, and focused patched Bazel run `42781918338` passes both targets. The telemetry command gives its config load and its final export 2 seconds each, and drops the export without an error when either runs out, so a loaded runner is the likely cause. Neither is a patch regression.

`codex-windows-sandbox` `unified_exec::tests::legacy_workspace_write_delete_is_limited_to_writable_roots` fails on the GitHub Windows x64 runner: the legacy (restricted-token) sandbox deletes the file in the `outside` directory, which is not a writable root (`tests.rs:829`). It fails identically on unpatched upstream (run `42845594613`, `patches=""`) and on the 35-patch queue (run `42845600974`), on both tries, so it is not a patch regression. The test root is created inside the repository checkout (`sandbox_cwd()`), so the likely cause is that the checkout's inherited ACLs grant write access to a group the restricted token keeps; the legacy sandbox relies on existing ACLs to deny writes. It still deserves an upstream report, which needs the owner's approval.

## Windows x64 nextest with upstream's setup (2026-11-10)

The Windows nextest workflow now matches upstream's runner setup (Dev Drive, DotSlash, the `ci-test` profile with the sandbox helper binaries, upstream's V8 artifacts) and runs the whole workspace except `codex-voice-host`, which needs GStreamer and `pkg-config` that the hosted runner lacks (`glib-sys` fails to build). Patched run `42851897440` (35 patches) and run `42873367591` without patches (`patches=""`) both fail; each has 15 to 21 failed and 5 to 11 timed-out tests out of about 21,900.

Failures in both runs, so the patches neither cause nor repair them: `codex-cli` `cloud_auth::cloud_list_only_allows_trusted_credential_destinations`, `features::mcp_list_reports_invalid_keybinding_reason`, `features::remote_start_rejects_add_dir_before_connecting`, `features::remote_start_rejects_writable_root_overrides_before_connecting`, `mcp_add_remove::add_rejects_blank_client_credentials_before_saving` and `mcp_login::tests::callback_line_is_bounded_and_does_not_expose_input_in_errors`; `codex-core` `suite::subagent_notifications::subagent_stop_replaces_stop_and_skips_internal_subagents`; `codex-tcp-tunnel` `diagnostics::tests::handshake_timeout_preserves_legacy_debug_output`; `codex-utils-pty` `conpty_ctrl_c_interrupts_powershell_foreground_child`; `codex-v8-poc` `tests::sandbox_feature_matches_linked_v8`; and four `codex-windows-sandbox` `unified_exec::tests::legacy_*` cases. The Dev Drive runs and the tests' own timing are the same in both.

Only in the unpatched run: `codex-core` `windows_sandbox` elevated cases (`windows_elevated_enforces_deny_read_and_protects_setup_marker`, `windows_elevated_powershell_preserves_relative_paths`, `windows_elevated_temp_only_core_and_direct_spawn_enforce_carveouts`, `windows_elevated_unified_exec_enforces_large_recursive_deny_reads`) and `codex-exec-server` elevated `tmpdir_uses_command_environment_over_rpc` and `file_system_elevated_relative_read_denial_uses_policy_cwd` (local and remote). These pass in the patched run, so they are intermittent on the runner.

Only in the patched run, all timeouts at the 60-second limit under load or a renamed test: `tool_metadata::...message_budget_removes_residual_values_and_markers_when_ordinary_input_fits`, `compact::tests::local_compaction_retries_preserve_instructions_and_tool_metadata`, `cyber_access_program_is_selected_per_turn`, `daybreak::exec_resolves_daybreak_for_api_keys_and_allows_an_off_override`, the `codex-tui` `command_center_new_checkout_and_worktree_preserve_source_and_default_branch` and `managed_worktree_transitions_bind_owner_and_preserve_only_fork_history` cases, and the `codex-core` `code_mode` and `windows_sandbox` timeouts. The unpatched run passes the same tests in 37 to 59 seconds, so they sit just under the limit and the patched run's extra load tips them over; none asserts a wrong result. `worker_guardian_sees_goal_set_after_spawn::test_case_1_user_goal` exists only with the patches (the unpatched name is `::user_goal`, which passes in 5 seconds), so its failure needs a separate look (see the corrections below).

Setup findings from building these lanes:

- Upstream's `setup-rusty-v8` action assumes upstream is the workspace root; this repository checks upstream into `upstream/`, so the Windows workflow downloads and verifies the V8 artifacts itself. Without them the V8 build script fetches a binding that does not exist (404).
- macOS needs `brew install pkgconf gstreamer gst-plugins-base` for `codex-voice-host`.
- The 46 GB free on a hosted macOS runner ran out while linking the debug test binaries (`ld: write() failed, errno=28`), so the workflow reclaims inactive Xcode versions and sets `CARGO_PROFILE_CI_TEST_DEBUG=line-tables-only`.
- On the Intel macOS runner the build took 1.5 hours and the tests were still running after another 2 hours, so the job limit is 360 minutes.

### Windows x64 with the 55-patch queue and corrections (2026-11-10)

Run `42915054701` (55 patches, including the six Windows fixes for the capture drain, the legacy PowerShell environment, ConPTY input, `SystemRoot`, the ConPTY interrupt and mouse capture): 21,935 tests, 14 failed and 9 timed out, against 18 and 11 for the 39-patch run `42911072854`. The `codex-utils-pty` ConPTY interrupt failure and three of the four `legacy_*` sandbox failures are gone (two `legacy_capture_*` cases now pass on retry); `legacy_workspace_write_delete_is_limited_to_writable_roots` still fails. Every other failure is one already listed above as shared with the unpatched run, an intermittent elevated-sandbox case (four `windows_elevated_*` cases failed in the unpatched run and in this run, but not in run `42851897440`), or a 60-second timeout of the load-sensitive tests listed above. The patched suite takes about 28% longer than the unpatched one (3,847 s against 3,011 s in the earlier pair); the cause is not isolated.

Corrections to what is written above and in the commit history:

- `worker_guardian_sees_goal_set_after_spawn::test_case_1_user_goal` failed only in the first patched run (`42851897440`, "deadline has elapsed" in `wait_for_guardian_reviews`). That run's queue contained the event-wait timeout patch, which was later dropped; the failure did not recur in `42911072854` or `42915054701`, which lack it. Turning `UnleashedBackgroundSubagents` off in that test was tried (`d04c206ac3`) and reverted (`b1da4840f7`) because it was not the cause. That the dropped patch caused the failure is an inference, not a proven result; rerunning the first run's queue on Windows would confirm it.
- The Windows skip check first scanned zero skip messages because nextest leaves a passing test's output out of the JUnit report. `--tool-config-file` now stores it, and a narrow run (`42928698049`) reported one skip message, classified.
- macOS ships bash 3.2, which fails on an empty array under `set -u`; the workflow guards its patch arrays and the skip check.
- The Windows `codex-voice-host` exclusion rests on this error: the `glib-sys v0.22.8` build script fails with "Could not run `PKG_CONFIG_ALLOW_SYSTEM_CFLAGS=1 pkg-config --libs --cflags glib-2.0 'glib-2.0 >= 2.56'`. The pkg-config command could not be found." Upstream builds `codex-voice-host` on Windows only through Bazel, which vendors the native link (`MODULE.bazel` `SYSTEM_DEPS_*` settings), and in the release workflow with Cygwin `pkgconf` and GStreamer (`setup-voice-windows.ps1`). Its Cargo nextest job has no such setup and no exclusion, and the workspace has no `default-members`, so voice-host is built there; no setup visible in the repository is missing from the hosted runner beyond the one the exclusion already names.

## Package smoke, Bazel symlinks, caching and baselines on rust-v0.167.0 (2026-11-11)

### Packaged exec-server shutdown test on Windows ARM64

The automatic release dry run `42860267858` failed `Package - aarch64-pc-windows-msvc` in
`scripts/codex_package/smoke_tests/test_codex_package.py::test_packaged_executor_initializes_and_enforces_sandbox[cli_root]`:
after the sandboxed `codex-exec-server.exe --listen stdio` ran and its stdin was closed, `process.wait(timeout=45)` raised
`subprocess.TimeoutExpired`. The `app_server_root` variant passed. Two narrow runs of `baseline-package-smoke.yml` for
`aarch64-pc-windows-msvc` on 0.167.0 give the same result, so the failure is not caused by the patch queue:

| Run | Queue | Result |
| --- | --- | --- |
| `42939327847` | none (unpatched) | 1 failed (`...[cli_root]`, `TimeoutExpired`), 9 passed, 2 skipped |
| `42939331881` | 54 patches | identical: 1 failed (same test, same timeout), 9 passed, 2 skipped |

The patched `x86_64-pc-windows-msvc` and `x86_64-unknown-linux-musl` package jobs passed in the dry run, so the failure is specific
to ARM64 (no unpatched x64 package run was made). Product code is unchanged; the failure belongs to upstream's ARM64 package.

How the release package job treats it. Upstream's `build-codex-packages` action builds the archives and runs pytest in one step, so a smoke-test
failure fails the step. The package jobs of `upstream-auto-release.yml` and `baseline-package-smoke.yml` now let that step continue on error. When it
fails, `.github/scripts/package-smoke-baseline.sh` requires every package and symbols archive (a build failure still fails the job), reruns the smoke
tests with JUnit reports, once per compression, and compares the failing test IDs with
`docs/test-baselines/<upstream commit>/package-smoke-<target>.json` through `compare-test-baseline.py`, the same way the nextest and Bazel baselines
work. A failure absent from the baseline fails the job; a baseline failure does not; with no baseline file any failure is new. An unpatched dispatch of
`baseline-package-smoke.yml` records the baseline (artifact `package-smoke-baseline-<target>`), and a patched dispatch compares. A new upstream version
whose package smoke tests fail therefore blocks the release until its baseline is recorded from an unpatched run.

The first unpatched recording run, `43004801025` (2026-11-11), shows that the failure is intermittent rather than constant:

- The package step's first pass failed `...[cli_root]` with the same `TimeoutExpired` on `codex-exec-server.exe --listen stdio`. The other tests passed: 9 passed, 2 skipped.
- The baseline step's rerun passed all 10 tests, `[cli_root]` included.
- The recorded `package-smoke-aarch64-pc-windows-msvc.json` therefore lists no failures.

All three first passes timed out (`42939327847`, `42939331881`, `43004801025`), and the one rerun passed. That pattern suggests the first launch of the freshly extracted executable is slow on ARM64. This is an inference that has not been isolated.

With an empty baseline, the job passes when the rerun is clean and fails when `[cli_root]` times out again.

### Windows Bazel and the Opus dangling symlink

Bazel builds `opusic-sys` for Windows with CMake. Every Windows Bazel job, native MSVC included, fails with
`opusic-sys-0.7.5 _bs.out_dir: child include/opus/opus_types.h is a dangling symbolic link`. Comparing main (with the
gnullvm local flags from `df5eb764ca`) against the same code with those flags removed:

| Job | With flags (`42914483021`) | Without flags (`42929299785`) |
| --- | --- | --- |
| gnullvm shard 1 | dangling error; 75 pass, 2 fail to build | dangling error; 75 pass, 2 fail to build |
| gnullvm shard 2 | no error; 74 pass, 1 fails | no error; 74 pass, 1 fails |
| gnullvm shard 3 | dangling error; 91 pass, 1 fails to build, 1 fails | dangling error; 90 pass, 1 fails to build, 2 fail |
| gnullvm shard 4 | dangling error; 94 pass, 1 fails to build, 4 fail | dangling error; 88 pass, 9 fail to build, 2 fail |
| msvc | dangling error; 333 pass, 4 fail to build, 6 fail | dangling error; 326 pass, 4 fail to build, 13 fail |

The flags (`--local_test_jobs`, `RUST_TEST_THREADS` and the skip list) are test-runner settings and do not touch the build action, so
they do not fix the error; they only reduce load-related test failures. The installed headers recorded in the retained
`opus-install` evidence are Windows file symlinks (reparse tag `0xA000000C`) whose targets are
`\\?\D:\o\execroot\_main\external\rules_rs++crate+crates__opusic-sys-0.7.5\opus\include\<header>.h`, and they do not resolve once
the action ends. The likely cause, not yet verified by a run, is that CMake's install preserves a symlinked source file as a symlink while Bazel's Windows
execution root presents the source headers as symlinks because the repository sets `startup --windows_enable_symlinks` (rules_python needs it). Upstream's
`.bazelrc`, `MODULE.bazel` and `third_party/voice` hold no setting for this, and upstream builds on Linux remote execution, where
the inputs are regular files. No fix that is neither a suppression nor a dependency patch has been found or verified; a candidate
is a CMake project include in `third_party/voice` that replaces installed symlinks with copies, which is a product-tree change.

Patch 54 (`windows-bazel/opus-installed-headers.patch`) is that candidate, added through the toolchain file the build script
already receives. The unpatched `rust-v0.167.0` baselines (`43003720882`, `docs/test-baselines/086ccc833b…/windows-bazel-*.json`)
still hit the error: the MSVC shard and gnullvm shards 1, 2 and 4 exit 1, and their `voice-host` test targets (4 in MSVC, 1 or
2 per gnullvm shard) fail to build. Those targets are absent from the baselines, so a patched run reports any failure in them
as new rather than excusing it. Gnullvm shard 3 builds no Opus target and exits 3 (test failures only).

### Compiled-dependency caches for the lint jobs

GHCR restores for the macOS lint jobs (`baseline-macos-lints.yml`):

| Run | Queue | aarch64 | x86_64 |
| --- | --- | --- | --- |
| `42958066219` (cold) | 53 patches | `cache_result=miss`, saved; 36 min | `cache_result=miss`; 51 min |
| `43011377187` (warm) | 54 patches | seed hit (27 s transfer), saved; 28 min | seed hit (41 s transfer), saved; 41 min |

- **Exact key missed in the warm run.** Each warm job missed its exact key, because the 54th patch changes the compiled inputs. It then restored the `cargo-seed-v2` entry and saved a new exact entry.
- **Partial saving.** The seed skips dependency compilation, but the workspace crates still compile, which bounds the saving.
- **Earlier warm attempt.** Its aarch64 job (`43005382829`) failed before the restore: a voice source download got `curl: (35) Connection reset by peer`. `05b12094dd` makes every workflow download retry such errors.
- **x86_64 in that attempt.** Its x86_64 job hit the cache (62 s transfer) and passed.

GHCR restores for the argument-comment lint `package` job:

- **Cold run `42951051998`:** `cache_result=miss`.
- **Warm run `43002517995`:** the exact-key pulls missed, and the `cargo-seed-v2` pulls hit, with transfers of 4 s and 2 s.

## Linux nextest lanes at `rust-v0.167.0`

Upstream's Linux x86-64 cargo job (`tests_linux_x64_remote` in `rust-ci-full-nextest-platform.yml`) runs the archive with a remote Docker exec-server (`scripts/test-remote-env.sh`). Upstream's blocking Linux gate is Bazel, which runs tests in a local environment. Upstream postmerge cargo runs fail at "Build nextest archive" (for example run `42120450138`), and the tag commit has no postmerge run, so upstream has no passing cargo nextest result to compare against. Our workflow therefore runs the archive twice, as two lanes: `local` (no remote environment, the Bazel-equivalent environment) and `remote` (upstream's Docker exec-server). The two baselines are `linux.json` (local) and `linux-remote.json` (remote).

Run [`43020352837`](https://github.com/holdmyspot-com/codex-unleashed/actions/runs/43020352837) (unpatched, 23,618 tests per lane): local lane 51 failed, 0 timed out; remote lane 190 failed, 0 timed out. The earlier single-lane run `43002480899` (remote environment for every test) had 191 failed and 1 timed out. Moving to a local lane removed 140 failures and the `rmcp_client::streamable_http_*` failures.

### Local lane (51 failures)

| Group | Count | Cause | Environment-independent |
| --- | --- | --- | --- |
| codex-tui, codex-cli `doctor_enterprise_network`, `exec-server-cli::standalone` snapshot and version assertions | 38 | Checked-in snapshots and the standalone test expect version `0.0.0`; the release-tag commit builds `0.167.0`. Also status/update-prompt width and wrap differences that follow from the longer version string. | Yes: a property of testing the release-tag commit |
| `codex-v8-poc` `sandbox_feature_matches_linked_v8`, `reads_typed_array_contents_with_the_compiled_binding_size` | 2 | The linked V8 is built with the sandbox (via `code-mode-runtime`), but v8-poc's own `sandbox` feature is off in a cargo workspace run. Bazel enables it with `crate_features=["sandbox"]`. Upstream's cargo command is identical. | Yes: a cargo-versus-Bazel feature difference |
| `codex-core` `network_approval::user_network_approval_once_session_and_denial_semantics` (3 cases) | 3 | The port-81 proxy command in the Python fixture times out after its 10 s socket timeout (`exit_code -1`) while the test waits for the approval prompt. Not root-caused: probably load or timing sensitivity because one runner executes what upstream splits into four partitions. | Not confirmed |
| `codex-exec-server` `shell_snapshot_*` `remote_*` (4) | 4 | `remote_pipe_recovery` and `remote_tty_recovery` count `xxxx` captures instead of `xx` (retry timing against a 1.1 s backoff); `remote_sandbox` fails an assertion at `exec_process.rs:368`; `blocked_descriptor_path` loses its websocket. "remote" here means the in-process exec-server transport. Not root-caused: probably timing under load. | Not confirmed |
| `codex-linux-sandbox` `root_metadata_symlinks_cannot_reopen_approved_denials` | 1 | The test finds the host apt `bwrap`, starts it, and the inner `codex-linux-sandbox` finds no system or bundled `bwrap` (`launcher.rs:60`). Upstream's package action asserts that the host has no `bwrap` on `PATH`; our workflow installs `bubblewrap`, so the test runs here but returns early ("system bubblewrap is unavailable") upstream. | No: caused by our host `bubblewrap` install |
| `codex-cli` `app_server_daemon_package::packaged_daemon_bootstrap_seeds_local_package` | 1 | `autoUpdateEnabled` is `true` where the test expects `false` (`package.rs:196`). Not root-caused. | Not confirmed |
| Remaining snapshot-only `codex-tui` cases | 2 | `daemon_menu` and `owned_transcript` snapshots; same version-string mismatch as the first row. | Yes |

The three `codex-core` `rmcp_client::streamable_http_*` tests pass in the local lane. In the remote lane they fail with `HTTP 403: Forbidden: Host header is not allowed`, because the test HTTP server runs on the host and the client runs inside the Docker exec-server.

### Remote lane (190 failures)

All local-lane failures above recur in the remote lane. The other failures are tests that the upstream remote job would also run, so they are upstream-comparable only if upstream's remote job ever ran; it has no successful run. Groups: 36 `build_with_auto_env` tests with a local stdio MCP fixture ("requires a local environment"; the remote environment registers no local one), 25 `node_repl`/`cua_repl` MCP handshakes ("Send message error Transport"), 3 "executor stdio server requires an explicit cwd", 3 `streamable_http_*` Host-header 403s, 7 "Guardian review did not start", about 25 `network_approval` assertions, hook and interrupt-hook mock verification failures, "environment `remote` only supports `bash`" cases, and the proxy-subprocess tests that cannot reach `ws://172.17.0.2:31987`. These were not root-caused individually.
