# Register of retained artifacts and remote caches

Read-only recon, repo holdmyspot-com/codex-unleashed. Gathered via `gh api` (caches: 40 entries, 9.14 GB; artifacts: 527, 5.12 GB, none expired), `git ls-remote origin`, du and doc/workflow grep.
Caveats: `orgs/holdmyspot-com/packages` returns HTTP 403 (local token lacks `read:packages`), so GHCR counts come from docs only. No `main.new` branch exists on origin any more, but 28 caches still carry `refs/heads/main.new`. "Gap" means a missing owner, last consumer, or cleanup evidence for plan.md's reconciliation requirement.

## 1. GitHub Actions caches (by key prefix)

| item | kind | key | size | owner | last consumer | cleanup policy & evidence | gap |
|---|---|---|---|---|---|---|---|
| upstream downloads cache | Actions cache | `upstream-dddc3c0a79528c4de8d9a20d51dd3bd89b02a3e4-downloads-v1-<target>-<os-arch>` x14 (6 targets) | 6.54 GB total (393-546 MB each); accessed 2026-11-07T16:21Z .. 2026-11-10T00:54Z; 5 on `refs/heads/main`, 9 on stale `refs/heads/main.new` | upstream-pinned setup action (the key's `dddc3c0a79` is the upstream commit); consumers `baseline-release-builds.yml`, `baseline-{linux,macos,windows}-focused-runtime.yml`, `diagnose-cargo-fingerprints.yml`; policy in `cache-retention.yml` | `baseline-release-builds.yml` | `plan-actions-cache-retention` keeps `upstream-<ref>-` only for a retained ref/pinned commit (docs/cache-migration-preflight.md "Retention planning and pruning"). Otherwise 7-day unread eviction. `cache-retention` artifact exists (17 runs), so the workflow runs. | Two duplicate sets per target (main and main.new) = about 4 GB. Pin dddc3c0a79 is the upstream-rama-port base; no evidence the retained-ref set matches it. Nothing proves the main.new copies get pruned after the rename. |
| rusty-v8 source cache | Actions cache | `upstream-rust-v0.163.0-rusty-v8-source-{x86_64,aarch64}-pc-windows-msvc-sandbox-<hash>` | 313 MB + 475 MB; accessed 2026-11-07/08 | upstream setup action, tag v0.163.0 | none in `.github` (grep for `rusty-v8-source` finds nothing) | `plan-actions-cache-retention` drops it once v0.163.0 is not a retained tag. v0.163.0 is the old `rust-v0.163.0` pin. | Tag-scoped to v0.163.0, last accessed 2026-11-07/08, no current consumer. Candidate for deletion; no deletion evidence. |
| bazel repository cache | Actions cache | `upstream-rust-v0.163.0-bazel-repository-{x86_64,aarch64}-apple-darwin-<hash>` | 487 MB x2; accessed 2026-11-08 | upstream setup action | key string only appears in bazel workflows by upstream's own naming | Same v0.163.0-tag rule as above. | Same gap: v0.163.0 and unused. |
| setup-zig tarball | Actions cache | `setup-zig-tarball-zig-linux-{x86_64,aarch64}-0.14.0` | 98 + 90 MB (2 entries each); 2026-11-09T00:22Z .. 2026-11-10T01:45Z | third-party `setup-zig` action, used by `baseline-release-builds.yml`, `baseline-release-followup.yml`, `baseline-platform-lints.yml`, `upstream-auto-release.yml` | `baseline-release-builds.yml` / release-followup | Unkeyed by ref: the planner "leaves every other cache to GitHub's 7-day eviction". | No owner doc. 2 entries per key (main.new + main). |
| setup-zig build cache | Actions cache | `setup-zig-cache-v2-build-zig-linux-{x86_64,aarch64}-0.14.0--<runid>-<attempt>` | 217 MB + 137 MB (2 each); accessed 2026-11-09T00:30Z .. 2026-11-10T00:54Z | setup-zig | release builds | Key ends in a run ID and attempt, so the planner keeps only the newest per prefix and branch. | Retains 1 per branch (main/main.new), so one stale per prefix after rename. |
| setup-uv | Actions cache | `setup-uv-2-x86_64-unknown-linux-gnu-ubuntu-24.04-3.12.3-<hash>` | 36.6 MB (2) | `astral-sh/setup-uv` in `baseline-platform-lints.yml` | `baseline-platform-lints.yml` | 7-day eviction only. | Duplicate (hash differs), no owner doc. |
| tooling runtime | Actions cache | `tooling-runtime-v1-<os>-<arch>-{temurin,zulu}-27.0.x-<sha256>` x6 (macOS-X64 zulu twice: +9 and +0.3.1) | 27-33 MB each (about 177 MB); 2026-11-06 .. 2026-11-08 | repo's own tooling setup (JDK 27 runtime), key not found by grep of `.github`/`.cat` in this worktree | unknown | 7-day eviction only. | Key prefix is not in this tree, so the producer is unidentified (`tooling-runtime-v1`). The stale macOS-X64 `zulu-27.0.1+9` entry is superseded by `+0.3.1`. |
| tooling maven | Actions cache | `tooling-maven-macOS-X64-jdk27-<hash>` | 81 MB; 2026-11-07 | same as above | unknown | 7-day eviction | Producer not found in tree. |
| scheduler memory history | Actions cache | `scheduler-memory-history-v1-<target>-<runid>-1` x5 | 0.1-0.2 MB each; 2026-11-05 .. 2026-11-06, `refs/heads/main.new` | not found in tree (grep empty) | unknown | planner keeps newest per prefix+branch | Producer not found. Negligible size, but ref is the removed `main.new`. |

Total 40 entries, 9.14 GB. Oldest last_accessed 2026-11-05T19:51Z (scheduler history), newest 2026-11-10T01:45Z. No cache key is documented with an owner in docs/ except the `upstream-<ref>-` family.

## 2. GHCR packages

| item | kind | path | size | owner | last consumer | cleanup policy & evidence | gap |
|---|---|---|---|---|---|---|---|
| `cargo-cache` | GHCR container (Cargo target caches, `-parts-v2` and `-partial` tags) | `ghcr.io/holdmyspot-com/cargo-cache`, protects branch `main.old` | not listable here (403). Per docs: 124 versions, 49 tagged, 75 untagged at artifact 12685459536; later preview 52 retained / 85 obsolete | `.github/cache-retention-packages`, `cache-retention.yml` | release build (`baseline-release-builds.yml`) and old-main `cache-cleanup.yml` | Docs: two newest seed lines per target; keeps newest 3 stable releases plus pinned refs; deletes untagged. Previews only, no deletion verified (docs/cache-migration-preflight.md lines 40-100). | The D8 conflict between old-main cleanup and the restart pin is open (lines 124-168). Current count and tags unverifiable with this token. |
| `cargo-cache-main-new` | GHCR container | `ghcr.io/holdmyspot-com/cargo-cache-main-new`, listed as protecting branch `main`, the rename target | unknown | `.github/cache-retention-packages` | release build | same planner | The package name still says `main-new`, which is stale after the rename; the retention file now points at `main`. Contents and existence unverified. |
| owned migration fixtures | GHCR versions | versions `1444749307`, `1454098082`, `40503122313`-run versions | n/a | `cleanup-owned-cache-fixtures.yml` | fixture runs | docs/source-cache-migration.md:187 and cache-migration-preflight.md:190-235 record deletion with verified absence | Closed per docs (not re-verified live). |

## 3. Workflow artifacts (527 total, 5.12 GB, 0 expired)

Workflow `retention-days: 1` (main since 2026-11), `upstream-auto-release.yml` uses 7. 414 artifacts from `main` created in 2026-11 (expiring 2026-11, 1-day); 3 from `main.new`; 110 from `main` created 2026-10-04..10-07 that expire 2027-01 (default 90 days).

| item (name prefix) | kind | key | size | owner | last consumer | cleanup policy & evidence | gap |
|---|---|---|---|---|---|---|---|
| `baseline-*` (clippy, rustdoc, dependency-lints, linux-tests, `*-primary`, `*-app-server`, bazel shards), `regenerated`, `checkpoint-tests-*`, `smoke-*` | workflow artifact | e.g. `baseline-clippy-aarch64-unknown-linux-musl` x44, `baseline-linux-tests` x28, `smoke-x86_64-apple-darwin-primary` x3 (387 MB) | 4-390 MB each; about 1.4 GB across the group | `baseline-*.yml`, `release-checkpoint-tests.yml` | same workflows | `retention-days: 1`; expiry date 2026-11-10..11 | Self-expiring. Matching ones are the same day's runs. |
| `cache-retention` | workflow artifact | 17 runs | 0.3 MB total | `cache-retention.yml` (line 178-184) | operator review | `retention-days: 1` | none |
| `cargo-timings-rust-release-*`, `codex-package-rust-licenses-*` | workflow artifact | 3 each, 6 + 2 targets | 0.4-12.5 MB | `baseline-release-followup.yml`, `baseline-*lints.yml`, release flows | none | Expiry 2027-01-02..03 (90-day default), so a workflow-less `retention-days` or an old-main producer | Producer not located. Expires 2027-01; no earlier deletion evidence. |
| `v8-canary-150.4.0-{release,ptrcomp-sandbox}-<target>` | workflow artifact | 3 each (about 13 targets) | 120-350 MB each; about 4.2 GB total, the bulk of artifact storage | no producer in current `.github/` (grep empty); apparently old-main V8 canary runs 2026-10-05..07 | none known | 90-day expiry 2027-01-03..05, no cleanup | Largest single retained item. Orphan from `main.old` workflows; needs owner decision (delete now or let expire). |
| `bazel-execution-logs-{test,clippy,verify-release}-*` | workflow artifact | 3 each | 22-99 MB each (about 0.6 GB) | no producer in current tree (old main) | none | 90-day expiry 2027-01-03..05 | Orphan, same as above. |
| `shared-frida-native-K3ah3h` | workflow artifact | 1, created from `main.new`, expires 2027-02-01 | 92 MB | not in tree | unknown | 90-day | Orphan, newest long-retention artifact. |
| `sarif-artifact-{actions,java-kotlin,javascript-typescript}` | workflow artifact | 1 each, expire 2027-01-05 | 0.1 MB | CodeQL/code-scanning (not in `.github`) | none | 90-day | negligible |

## 4. Remote branches (origin; 27 non-main refs plus `main`, `main.old`; 95 tags)

All non-main branches have no reference in docs/, plan.md or `.github` (grep). Dates are tip-commit dates.

| item | kind | path | size | owner | last consumer | cleanup policy & evidence | gap |
|---|---|---|---|---|---|---|---|
| `ci/*` (17): baseline-observation, clean-upstream-{baseline,feedback-mac,full-windows}, gov-cloud-causal-probe, isolated-warmup-windows, macos-voice-probe, native-log-recovery, native-repair-verification, sqlite-clean-regression, windows-{failure-loop,launcher-probe,repair-verification,startup-probe,v8-first-use} | branch | `refs/heads/ci/*`, tips 2026-10-15..10-17 (`native-repair-verification` and `windows-failure-loop` share tip 4cb9cba39) | n/a | old-main restart probes | none known | none documented | No owner, no cleanup record. Delete candidates; they may contain evidence of the restart. |
| `ci-verify-*` (5): v8-arm64-XeqjpNMI (2026-10-08), v8-macos-162, v8-macos-grpc-162, v8-macos-jobs2-162, windows-native-162 (2026-10-13) | branch | `refs/heads/ci-verify-*` | n/a | probes for the upstream 0.162 work | none | none | Same gap. |
| `diagnostic/*` (3): intel-pool-reservation, windows-workload-paths-01a16988 (2026-10-24), mac-native-driver (2026-10-15) | branch | `refs/heads/diagnostic/*` | n/a | diagnosis sessions | none | none | Same gap. |
| `diagnose-incremental-release` | branch | tip 2026-10-04 | n/a | old main | none | none | Same gap. |
| `update/latest-stable-dependencies` | branch | tip 2026-10-25 | n/a | dependency update automation | none | none | Check for an open PR before deleting. |
| `cat/cp3-linux-release-lJZ2HK` | branch | tip 2026-11-01 | n/a | CAT session | none | none | Same gap. |
| `main.old` | branch | fixed commit 545479059a | n/a | frozen reference (do not modify) | `cargo-cache` retention pins it | explicit, per plan | none |
| tags | tag | `checkpoint-1..5-*`, `checkpoint-incremental-build-recovery`, about 80 `rust-v0.158.0+15` .. `rust-v0.162.1+21`+ release tags | n/a | release workflow | cache retention uses them for "retained stable" | `cache-retention.yml` derives retained refs from releases | Not touched by this task. |

## 5. Local caches (this machine)

| item | kind | path | size | owner | last consumer | cleanup policy & evidence | gap |
|---|---|---|---|---|---|---|---|
| restart baseline build state | local build cache | `/home/gili/codex-unleashed/workspace/.cat/work/temp/build-caches/restart-baseline-6aaac69` (absent in this worktree, present in main workspace) | 3.4 GB | restart goal (docs/build-baseline.md:288, 351); docs/worktree-recovery.md:64 preserves it deliberately; includes distro-archives, native-build-1, native-tools, nightly-rustup, dylint-tools, last modified 2026-10-31 | baseline checks at commit 6aaac69 | worktree-recovery.md records deletion of other caches while "preserving only" this one; no deletion condition stated | No documented end-of-life condition (e.g. after plan.md completion). Owner decision: keep as reusable cache or delete. |
| maven cache | local build cache | `/home/gili/codex-unleashed/workspace/.cat/work/temp/build-caches/maven` | 188 MB | not documented in docs/ | unknown | none | Not mentioned in docs/worktree-recovery.md's "preserving only restart-baseline"; undocumented survivor. |

## Summary of gaps for plan.md reconciliation
1. GHCR package contents unverifiable with the local token (needs `read:packages` or workflow-token inventory); `cargo-cache-main-new` name is stale after the main rename.
2. About 4.2 GB of 90-day orphan artifacts (`v8-canary-*`, `bazel-execution-logs-*`, `shared-frida-native-*`) from old main, expiring 2027-01/02, no producer in the current tree.
3. 28 Actions caches are still scoped to the removed `main.new` ref, and 6.5 GB of the 9.1 GB total is the duplicated `upstream-dddc3c...-downloads-v1` family; retention only prunes by ref and prefix and nothing proves it has run post-rename.
4. `tooling-runtime-v1`, `tooling-maven`, `scheduler-memory-history-v1` cache keys have no producer found in this tree.
5. 26 non-main branches (`ci/*`, `ci-verify-*`, `diagnostic/*`, etc.) have no owner or cleanup record.
6. Local `restart-baseline-6aaac69` (3.4 GB) and `maven` (188 MB) have no stated end-of-life.
