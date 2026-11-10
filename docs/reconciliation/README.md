# Old-main reconciliation registers

These registers record what happened to each part of the fixed old-main commit
`545479059ac86394049b6efd3bf4297b1ca18d17` and to the retained restart material. Each one was produced
read-only on 2026-11-10 against the current `main` and the patched upstream tree. They use one set of
dispositions: migrated, upstream-equivalent, omitted, deferred and needs-owner-decision.

| Register | Covers | Result |
| --- | --- | --- |
| [old-main-file-inventory.tsv](../old-main-file-inventory.tsv) | All 648 old-main paths, with the state recomputed against one `main` commit and an `evidence` column | 143 migrated, 71 upstream-equivalent, 45 omitted, 388 deferred, 1 needs-owner-decision |
| [old-main-patches-1-72.md](old-main-patches-1-72.md), [old-main-patches-73-144.md](old-main-patches-73-144.md) | The 144 old-main patch and mailbox rows | 34 rows changed since the `restart-scope.md` appendix was written; the appendix is stale where these tables disagree |
| [archived-worktree-refs.md](archived-worktree-refs.md) | The 38 refs under `refs/archive/restart-worktrees` | 18 HEAD refs are points on one 279-commit chain; the T9 product and fixture patches need an owner decision |
| [legacy-checkouts.md](legacy-checkouts.md) | The 33 archived independent checkouts and the two named stashes | 29 migrated, 3 omitted, 1 needs-owner-decision; the archive checksum verifies |
| [artifacts-and-caches.md](artifacts-and-caches.md) | Actions caches, GHCR packages, workflow artifacts, remote branches and local caches | The gaps are listed at the end of the register |
| [d7-d8-census.md](d7-d8-census.md) | D7 (V8 and patched zsh consumers) and D8 (retention) | Only prebuilt upstream V8 has a consumer; retention is implemented except one 7-day artifact |

Most `deferred` rows wait on D1, D3 to D8 or D10 in [restart-scope.md](../plans/restart-scope.md). Several
calls were made from file and commit names rather than a line-by-line comparison, and each register says
which.

## Open owner decisions

1. `ArtifactJsonApiTest.java`: `ArtifactJson` is kept, but `main` has no counterpart to the old test. Restore it or omit it.
2. T9 in [archived-worktree-refs.md](archived-worktree-refs.md): the old-queue product and fixture patches (`issue-61/zzzz-0075..0098` and `issue-71/*`, such as the Windows mouse restore and close-remote-client-owner fixes). None of them is in `main` or upstream. Re-test each on the restart baseline, or omit them.
3. `workflow-review.gEypD6` in [legacy-checkouts.md](legacy-checkouts.md): the recommendation is to omit this pre-squash workflow redesign.
4. The red test `replayed_compacted_history_keeps_all_turns_when_fast_switching_is_disabled` from `codex-rust-v0.157.1-34776-red`, which is in no patch.
5. `upstream-auto-release.yml` keeps its build-to-publish `assets-*` artifact for 7 days instead of 1. Accept this as an exception or shorten it.
6. The 25 non-main remote branches, the 110 90-day artifacts from 2026-10-04 to 2026-10-07 (about 4.2 GB), the stale `main.new` cache entries and the stale `cargo-cache-main-new` package name in [artifacts-and-caches.md](artifacts-and-caches.md). Deleting any of them needs approval.
7. `docs/COMMERCIAL_TERMS.md` and the other D11 license and policy files, which need owner content.
