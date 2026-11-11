# Old-main reconciliation registers

These registers record what happened to each part of the fixed old-main commit
`545479059ac86394049b6efd3bf4297b1ca18d17` and to the retained restart material. Each one was produced
read-only on 2026-11-10 against the current `main` and the patched upstream tree. They use one set of
dispositions: migrated, upstream-equivalent, omitted, deferred and needs-owner-decision.

| Register | Covers | Result |
| --- | --- | --- |
| [old-main-file-inventory.tsv](../old-main-file-inventory.tsv) | All 648 old-main paths, with the state recomputed against one `main` commit and an `evidence` column | 143 migrated, 71 upstream-equivalent, 45 omitted, 388 deferred, 1 needs-owner-decision |
| [old-main-patches-1-72.md](old-main-patches-1-72.md), [old-main-patches-73-144.md](old-main-patches-73-144.md) | The 144 old-main patch and mailbox rows | 34 rows changed since the `restart-scope.md` appendix was written; the appendix is stale where these tables disagree |
| [archived-worktree-refs.md](archived-worktree-refs.md) | The 38 refs under `refs/archive/restart-worktrees` | 18 HEAD refs are points on one 279-commit chain; the T9 product and fixture patches were re-tested; the confirmed ones are queue entries 40–55 |
| [legacy-checkouts.md](legacy-checkouts.md) | The 33 archived independent checkouts and the two named stashes | 29 migrated, 3 omitted, 1 needs-owner-decision; the archive checksum verifies |
| [artifacts-and-caches.md](artifacts-and-caches.md) | Actions caches, GHCR packages, workflow artifacts, remote branches and local caches | The gaps are listed at the end of the register |
| [d7-d8-census.md](d7-d8-census.md) | D7 (V8 and patched zsh consumers) and D8 (retention) | Only prebuilt upstream V8 has a consumer; retention is three days everywhere (owner decision 2026-11-10) |

Most `deferred` rows wait on D1, D3 to D8 or D10 in [restart-scope.md](../plans/restart-scope.md). Several
calls were made from file and commit names rather than a line-by-line comparison, and each register says
which.

## Decided 2026-11-10

- Old tests are carried over only when they cover a patch that was moved over. `ArtifactJsonApiTest.java` tests Java tooling, not a patch, so it is omitted. The red test `replayed_compacted_history_keeps_all_turns_when_fast_switching_is_disabled` belongs to the migrated `issue-34776/avoid-slow-agent-switching-and-resume.patch`, but that patch's `switching_back_replays_only_latest_compacted_conversation` already asserts that disabling fast switching replays the full conversation, so it is omitted as covered.
- The D11 license and policy content is retained.
- `workflow-review.gEypD6` (owner decision 2026-11-10): omitted. It is preserved as `refs/archive/legacy-worktrees/workflow-review.gEypD6/head`.
- Artifact retention (owner decision 2026-11-10): three days for every workflow artifact, including `upstream-auto-release.yml`'s `assets-*`.
- Cleanup (owner decision 2026-11-10): delete the old non-main branches and the 2026-10-04..10-07 artifacts that have no current producer; let the stale `main.new` Actions caches expire; delete this work's scratch branches once their contents are integrated.
- T9 (owner decision 2026-11-10): every old-queue product and fixture patch was re-tested on the restart baseline; the ones that failed red and passed green are queue entries 40–55 ([old-main-test-fixes](../../patches/holdmyspot-com/codex-unleashed/old-main-test-fixes/README.md)). 0090 and 0092 were inconclusive and are to be rerun.

## Open owner decisions

1. The stale-cache package name `cargo-cache-main-new` in [artifacts-and-caches.md](artifacts-and-caches.md): rename it once the release work is done (owner decision 2026-11-10), updating `.github/cache-retention-packages` with it.
