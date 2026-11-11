# Archived legacy checkouts (33) and their stashes

Scope: `.cat/archives/restart-legacy-worktrees/` in the workspace (read-only). Final branch `origin/main` = 632804d34c; old main `origin/main.old` = f2830d9125 per local ref (RULES fixes 545479059a as the old-main identity).

## 1. Checksum

`sha256sum -c .cat/archives/restart-legacy-worktrees/checkouts.tar.gz.sha256` (run from the workspace root, the path inside the .sha256 is project-relative): **OK** (sha256 4e6a6b56...ed8dc4, 1,615,068,703 bytes).

## 2. Directories

`tar -tzf` lists exactly 33 top-level directories (313,910 entries), identical to `directories.json`; `removal-results.json` records `removed: true` for them. Extraction was done only into a fresh `mktemp -d` under `$TMPDIR` (4.7 GB, treated as untrusted; git run with `core.fsmonitor=false`, `core.hooksPath=/dev/null`; no hook or script ran) and has been deleted.

Layout: 5 are full repos (`codex-rust-v0.157.1`, `codex-rust-v0.158.0-license-probe`, `codex-rust-v0.159.0-expanded-diff`, `resume-release-0.160.0`, `resume-background-runtime`) plus `daemon-unleashed-queue(-final)` and `workflow-review.gEypD6`; the rest are linked worktrees of those repos (their `.git` file names an absolute path under the removed `worktrees/` dir). Objects for `daemon-unleashed-*` and `resume-background-runtime` have a broken alternate chain (parent commit cb7799623b missing, "Failed to traverse parents"), so only HEAD, status and diffs could be read there; HEAD a956835d02 itself resolves.

## 3. Method for dispositions

Every checkout's HEAD is an upstream `openai/codex` release commit (tag shown) with Codex Unleashed work layered on as uncommitted files or one to two local commits. For each, I computed the set of added source lines (committed own commits plus working-tree diff plus untracked files, excluding `.snap.new` and `.pending-snap`) and measured how many appear as added lines in (a) the 55 patch files of `origin/main` `patches/` and (b) the 185 files of `origin/main.old` `patches/`. "cov main" and "cov old" below are those percentages. Unmatched residue was inspected: version bumps in `Cargo.lock`, insta snapshot whitespace, and small test-name variants. Own local commits (Gili Tzabari / Codex Unleashed author) are not reachable from `origin/main` or `origin/main.old`; they exist in the archive only (their objects are also present loose in the project object store but on no ref, so `gc` could drop them).

Note: `docs/plans/restart-scope.md` rows for several of these patch files still read "not migrated", while the patch files are present on `origin/main`. I treat "migrated" as "the patch file is on origin/main"; the scope doc may need a refresh.

## 4. Per-checkout table

HEAD is detached in every checkout except `workflow-review.gEypD6` (branch `review/workflows`). Remote `origin` is `https://github.com/openai/codex.git` except where noted. "own commits" are not on any remote or main branch.

| Checkout | HEAD (tag) | Recorded changes | cov main / old | Disposition and evidence |
|---|---|---|---|---|
| codex-rust-v0.157.1 | 064c6b8c73 (rust-v0.158.0) | clean tree; stash f5acc388cb: 29 files +737/-172 (config, features, tui replay/exec_cell/snapshots) | stash 95% old | migrated: stash lines match `issue-34776/avoid-slow-agent-switching-and-resume`, `issue-47390/expanded-diff-previews`, `background-terminal-transcript-hint`, `collapsed-tool-activity` patches. 5% residue = expanded-diff and transcript-hint test variants for the 0.157/0.158 base. Also retained as `refs/archive/legacy-worktrees/codex-rust-v0.157.1/{head,changes}`. |
| codex-rust-v0.157.1-34776-red | 25cc22b1c3 (own commit "tui: replay only current conversation when switching" on 36650394c) | 20 files +419/-165 | 82% / 84% | migrated for the switching/replay work (`issue-34776/avoid-slow-agent-switching-and-resume.patch`, test `measure_switch_back_with_compactions` is in main). needs-owner-decision for one red test `replayed_compacted_history_keeps_all_turns_when_fast_switching_is_disabled`, which is in no patch file on main or main.old (low value, likely a superseded red test). |
| codex-rust-v0.157.1-patch-check | 36650394c5 (rust-v0.157.1) | clean | n/a | omitted: pristine upstream tag, nothing to migrate. |
| codex-rust-v0.158.0-branding-combined | 064c6b8c73 (rust-v0.158.0) | 42 files +872/-217, untracked `cli/tests/build_info.rs`, `tui/build.rs` | 84% / 86% | migrated: `branding-and-app-server-distribution/add-branding-and-build-metadata.patch` plus 34776/47390/collapsed-activity patches. Residue = snapshot whitespace and test variants. |
| codex-rust-v0.158.0-build-info | 0d7d4ffeef (own commits e192981561 "Restore Codex Unleashed branding and build metadata", 0d7d4ffeef "Update session header snapshots for provider branding" on 064c6b8c73) | clean | 61% / 61% | migrated: same branding patch plus `fix-branding-test-snapshots.patch`, `warn-on-openai-app-server.patch`. Low match is box-drawing snapshot lines with trailing spaces (version `vtest`), not logic. |
| codex-rust-v0.158.0-build-info-combined | 064c6b8c73 (rust-v0.158.0) | 30 files +770/-172, untracked `cli/tests/build_info.rs` | 92% / 94% | migrated (branding, 34776, 47390, collapsed-activity patches). |
| codex-rust-v0.158.0-license-probe | 064c6b8c73 (rust-v0.158.0) | clean; stash 25c5eb7e2e: `codex-rs/Cargo.lock` only, 158+/158-, `version = "0.0.0"` to `"0.158.0"` | n/a | omitted: lockfile version bump only, regenerated by every build; no source value. Also retained as `refs/archive/legacy-worktrees/codex-rust-v0.158.0-license-probe/{head,changes}`. |
| codex-rust-v0.158.0-windows-probe | 064c6b8c73 (rust-v0.158.0) | 28 files +579/-14 | 93% / 95% | migrated (34776, 47390, hint, collapsed-activity patches); the 5% residue is the same feature-disabled expanded-diff test variants. |
| codex-rust-v0.159.0-expanded-diff | 687a119f0f (rust-v0.159.0) | 40 files +689/-38, untracked build_info.rs, tui/build.rs | 92% / 94% | migrated: `issue-47390/expanded-diff-previews.patch` + branding/34776 patches. |
| daemon-unleashed-author | a956835d02 (rust-v0.160.0); origin = local sibling checkout | 53 files +818/-806 (mostly Cargo.lock), 4 untracked incl. `scripts/install/test_install_ps1.ps1`, openai-server-warning snapshot | 95% / 97% | migrated: `branding-and-app-server-distribution/daemon-public-releases.patch` (170 lines), `add-branding...`, `warn-on-openai-app-server.patch`, `fix-branding-test-snapshots.patch`. `test_install_ps1` is named in both patch sets. |
| daemon-unleashed-queue | a956835d02 (rust-v0.160.0); origin = local sibling | 119 files +2755/-771, 7 untracked | 95% / 99% | migrated: full queue (collapsed-activity 666 lines, background-session-skill-warmup 339, avoid-slow-agent-switching 301, daemon-public-releases 170, branding). Residue: a `cargo clippy ... -D unused_imports` line (script text) and test variants. |
| daemon-unleashed-queue-final | a956835d02 (rust-v0.160.0); origin = local sibling | 124 files +2906/-774, 8 untracked | 95% / 99% | migrated (same queue as above, plus warning snapshot). |
| daemon-unleashed-verify | a956835d02 (rust-v0.160.0); origin = local sibling | 10 files +352/-710, 1 untracked `test_install_ps1.ps1` | 96% / 99% | migrated: `daemon-public-releases.patch` (170/171 lines); only unmatched line is `version = "0.160.0"`. |
| release-0.159.1-branding-series | 04dcf349d6 (own commits 942e38b3d6 "Restore Codex Unleashed branding and build metadata", 04dcf349d6 "Update branding session-header expectations", on 8e68a98ef0) | clean | 86% / 86% | migrated: branding/snapshot patches (add-branding 89 lines, fix-branding-test-snapshots 9). Residue = ratatui buffer-dump snapshot lines. |
| release-0.159.1-branding-series-v2 | d4a5c85d26 (own commits 319ee074f6, d4a5c85d26 on 8e68a98ef0) | clean | 93% / 95% | migrated (same; v2 supersedes v1). |
| release-0.159.1-check | 8e68a98ef0 (rust-v0.159.1) | 41 files +1007/-234, 3 untracked incl. `.rendering_tests.rs.pending-snap` | 95% / 97% | migrated (queue patches); the pending-snap is an unaccepted insta artifact, discard. |
| release-0.159.1-final-check | 8e68a98ef0 (rust-v0.159.1) | 40 files +747/-39 | 94% / 96% | migrated. |
| release-0.159.1-final-v2 | 8e68a98ef0 (rust-v0.159.1) | 40 files +841/-76 | 93% / 95% | migrated. |
| release-0.159.1-final-v3 | 8e68a98ef0 (rust-v0.159.1) | 40 files +849/-76 | 95% / 97% | migrated. |
| release-0.159.1-upstream-compare | 8e68a98ef0 (rust-v0.159.1) | 40 files +747/-38 | 95% / 96% | migrated (matches final-check except 1 deletion line). |
| resume-34776-author | a902a91db2 (own commit "Keep history replay and subagent switches responsive" on 8e68a98ef0) | 70 untracked `.snap.new` only (analytics plot snapshots) | 97% / 100% | migrated: `issue-34776/avoid-slow-agent-switching-and-resume.patch` (301 of 301 lines). The `.snap.new` files are unaccepted insta output, discard. |
| resume-34776-queue | 8e68a98ef0 (rust-v0.159.1) | 93 files +1959/-150, 5 untracked (build_info.rs, config-schema/tests/codex_unleashed.rs, tui/build.rs, tool_activity.rs) | 95% / 99% | migrated (full queue on 0.159.1). |
| resume-34776-verify | 8e68a98ef0 (rust-v0.159.1) | 15 files +622/-167, 70 untracked `.snap.new` | 97% / 100% | migrated (avoid-slow-agent-switching 301/302 lines; the other is a version line). |
| resume-background-author | 611851ed48 (own commit "Make restored sessions available during skill discovery" on a956835d02; branch `resume-background-patch` exists in repo) | 5 files +293/-168 (some staged), 3 `.snap.new` | 100% / 100% | migrated: `issue-34776/background-session-skill-warmup.patch` (339/340 lines). |
| resume-background-base | a956835d02 (rust-v0.160.0) | Cargo.lock +159/-159, 3 `.snap.new` | 0% (lockfile) | omitted: version-bump lockfile churn only. |
| resume-background-emitted | a956835d02 (rust-v0.160.0) | 8 files +354/-204, untracked `resume_warmup_tests.rs` | 98% / 98% | migrated: warm-up patch; residue = intermediate `watch_registration_pending` select code, absent only from the old-main set and present in a final form on main (`watch_registration_pending` in main patches). |
| resume-background-final | a956835d02 (rust-v0.160.0) | 11 files +479/-204, untracked `resume_warmup_tests.rs` | 100% / 100% | migrated: `background-session-skill-warmup.patch`. |
| resume-background-runtime | a956835d02 (rust-v0.160.0); origin = local sibling | 108 files +2621/-379, 5 untracked | 94% / 98% | migrated (queue on 0.160.0). |
| resume-release-0.160.0 | a956835d02 (rust-v0.160.0) | 93 files +1959/-150, 5 untracked | 95% / 99% | migrated. |
| resume-skill-index-author | 46cb676514 (own commits e5ae5f2... "Defer implicit skill index construction until first use", 46cb676514 "Avoid blocking startup on a duplicate skills refresh" on a956835d02) | Cargo.lock +159/-159, 1 `.snap.new` | 91% / 97% | migrated: `issue-34776/avoid-duplicate-startup-skills-refresh.patch` (115 lines) + `defer-implicit-skill-index.patch` (60). |
| resume-skill-index-perf | a956835d02 (rust-v0.160.0) | 100 files +2181/-334, 5 untracked | 94% / 98% | migrated (queue; includes `skills.implicit_index.build` tracing, present on main). |
| resume-skill-index-verify | a956835d02 (rust-v0.160.0) | 7 files +337/-163, 71 untracked (mostly `.snap.new`) | 94% / 98% | migrated (avoid-duplicate-startup-skills-refresh 115/117). |
| workflow-review.gEypD6 | 1c8ca98a9a on branch `review/workflows` ("Redesign release workflows and strengthen provenance", 2026-09-06); origin = the workspace repo (local) | clean | n/a | omitted (owner decision 2026-11-10): pre-squash workflow redesign. Commit is on no `origin/main` or `main.old` ancestry; it is retained as `refs/archive/legacy-worktrees/workflow-review.gEypD6/head`. Later `main.old` commits (f2830d9125, 5d07a33b04, bc2cc503cc) rewrote the same workflows, and the restart rebuilds them per `docs/old-main-workflow-inventory.md`. I did not diff its YAML against the rebuilt workflows. |

Counts (33 rows): migrated 29 (one with a sub-item needing an owner decision: the red test in codex-rust-v0.157.1-34776-red), omitted 3 (codex-rust-v0.157.1-patch-check, codex-rust-v0.158.0-license-probe, resume-background-base), omitted by owner decision 1 (workflow-review.gEypD6, 2026-11-10).

## 5. Named stashes

Both stashes exist only inside the archive (stash stack of each repo); the workspace `git stash list` holds 19 other stashes, none with these names. Both entries are `On (no branch): Preserve obsolete checkout before restart cleanup`, 2026-10-25; they are also retained as refs in the project (`refs/archive/legacy-worktrees/<name>/changes`, SHAs f5acc388cb and 25c5eb7e2e), so `git stash apply` is not required to recover them (and was not run).

- **codex-rust-v0.157.1** (stash f5acc388cb, parent 064c6b8c73, 29 files +737/-172): config table and feature flag (`config_toml.rs`, `features/src/lib.rs`, `config.schema.json`), TUI replay/thread routing/exec_cell/transcript hint code, ps-output snapshots, `Cargo.lock` version churn. 95% of 371 added lines are in main.old patches. Disposition: migrated, with the 5% residue (transcript-hint and expanded-diff-disabled test variants) obsolete variants of tests that the patch files cover.
- **codex-rust-v0.158.0-license-probe** (stash 25c5eb7e2e, parent 064c6b8c73): `Cargo.lock` only, `version = "0.0.0"` to `"0.158.0"`. Disposition: omitted (regenerated build output).

## 6. Caveats

- Coverage is by added-line matching, not a proof of byte equivalence; residue was inspected and is test/snapshot/lockfile text.
- Per-checkout "migrated" means the work is carried by patch files on `origin/main` (`patches/holdmyspot-com/codex-unleashed/...`), not that it is applied in an upstream tree. Applied state: `/home/gili/codex-unleashed/worktrees/upstream-rama-port` contains the queue (e.g. `measure_switch_back_with_compactions` in `tui/src/app/tests/session_lifecycle_requests.rs`).
- Nothing in the repo or workspace was modified; `git fetch -q origin` was run once in the cp3-test-module worktree by mistake while exploring (updates remote-tracking refs only).
