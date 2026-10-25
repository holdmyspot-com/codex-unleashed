# Removed worktree recovery

The restart cleanup removed 19 obsolete linked worktrees. The current `main.new` workspace and active independent upstream checkout remain available. Old `main` still points to `545479059ac86394049b6efd3bf4297b1ca18d17`.

Eighteen worktrees had uncommitted changes. Their tracked, staged, and untracked work was saved with `git stash push --include-untracked` before removal. Permanent local references retain each original HEAD and its saved changes independently of stash numbering. These references are local to this repository; ordinary branch pushes do not copy them. No changes were imported into `main.new` by this cleanup.

Every removal succeeded through `git worktree remove` without force. Verification established that each removed directory is absent, each retained HEAD reference matches its original commit, and every changes reference names a stash whose first parent is that HEAD.

## Retained references

All references below begin with `refs/archive/restart-worktrees/`. `head` retains the commit; `changes` retains uncommitted work. A dash means the worktree was clean.

| Worktree | HEAD reference suffix | Changes reference suffix |
| --- | --- | --- |
| stable-dependencies-JknHet | `stable-dependencies-JknHet/head` | `stable-dependencies-JknHet/changes` |
| intel-pool-reservation-r8iZbR | `intel-pool-reservation-r8iZbR/head` | — |
| argument-lint-selftests | `argument-lint-selftests/head` | `argument-lint-selftests/changes` |
| lint-comment-repair | `lint-comment-repair/head` | `lint-comment-repair/changes` |
| native-nextest-coverage | `native-nextest-coverage/head` | `native-nextest-coverage/changes` |
| remote-archive-reuse | `remote-archive-reuse/head` | `remote-archive-reuse/changes` |
| remote-environment-coverage | `remote-environment-coverage/head` | `remote-environment-coverage/changes` |
| scheduler-manager-availability | `scheduler-manager-availability/head` | `scheduler-manager-availability/changes` |
| symbol-fixture-diagnostic | `symbol-fixture-diagnostic/head` | `symbol-fixture-diagnostic/changes` |
| v8-manifest-authentication | `v8-manifest-authentication/head` | `v8-manifest-authentication/changes` |
| voice-native-acceptance | `voice-native-acceptance/head` | `voice-native-acceptance/changes` |
| voice-packaging | `voice-packaging/head` | `voice-packaging/changes` |
| voice-runtime-producer | `voice-runtime-producer/head` | `voice-runtime-producer/changes` |
| voice-wrapper-integration | `voice-wrapper-integration/head` | `voice-wrapper-integration/changes` |
| windows-lifecycle-proof | `windows-lifecycle-proof/head` | `windows-lifecycle-proof/changes` |
| windows-source-diagnostic | `windows-source-diagnostic/head` | `windows-source-diagnostic/changes` |
| macos-dsym-diagnostic-CemGrr | `macos-dsym-diagnostic-CemGrr/head` | `macos-dsym-diagnostic-CemGrr/changes` |
| macos-sampler-candidate-qBjJVJ | `macos-sampler-candidate-qBjJVJ/head` | `macos-sampler-candidate-qBjJVJ/changes` |
| incremental-release-probe | `incremental-release-probe/head` | `incremental-release-probe/changes` |

## Recovery

Choose a retained name from the table and a new worktree path beneath the project or its sibling `worktrees` directory:

```sh
name=argument-lint-selftests
git worktree add --detach ../worktrees/recovered-work "refs/archive/restart-worktrees/$name/head"
git -C ../worktrees/recovered-work stash apply --index "refs/archive/restart-worktrees/$name/changes"
```

For `macos-sampler-candidate-qBjJVJ`, the original index contained an intent-to-add entry that Git stash refused. The original index is retained as the blob reference `refs/archive/restart-worktrees/macos-sampler-candidate-qBjJVJ/original-index`. Cleanup removed only the intent-to-add index entry before saving all file contents. After applying its changes, restore that intent-to-add state with `git add --intent-to-add .github/scripts/diagnose-macos-exec-sample.sh` in the recovered worktree.

These archived changes are additional reconciliation inputs, separate from the fixed old-main inventory in `plan.md`. Their retention does not authorize migrating their behavior. Keep the references until the user decides whether to migrate or discard that work.

## Additional legacy checkouts

A later disk cleanup removed another 33 obsolete source checkouts from the sibling `worktrees` directory. These belonged to older independent upstream repositories, rather than this repository's linked-worktree list. Several contained uncommitted changes or incomplete Git history, so a complete filesystem archive was retained instead of relying on Git transfers.

The local archive is `.cat/archives/restart-legacy-worktrees/checkouts.tar.gz`. Its adjacent `directories.json` lists the 33 directory names; `removal-results.json` confirms all were removed. `checkouts.tar.gz.sha256` records its checksum. Before removal, `tar --compare` verified the archived file contents and metadata against every original checkout. The archive includes their existing Git object stores and linked-worktree metadata, even where historical objects were already missing. It does not repair that preexisting incomplete history.

The archive is local and ignored by Git; publishing `main.new` does not back it up externally. Keep it until final reconciliation decides whether its contents should be migrated or discarded. To recover the original layout, verify its checksum from the project root and extract all archived checkouts together into an empty sibling `worktrees` directory. Linked-worktree metadata names those original absolute paths.

```sh
sha256sum -c .cat/archives/restart-legacy-worktrees/checkouts.tar.gz.sha256
tar -xzf .cat/archives/restart-legacy-worktrees/checkouts.tar.gz -C ../worktrees
```

Before archiving, the cleanup saved the dirty state of `codex-rust-v0.157.1` and `codex-rust-v0.158.0-license-probe` in each checkout's newest stash. Recover their working changes with `git stash apply --index stash@{0}` inside the corresponding restored checkout. Other checkouts retain their working files directly in the archive.

The same cleanup deleted obsolete build caches from `.cat/work/temp/build-caches`, preserving only `restart-baseline-6aaac69` for the active baseline checks. These caches were rebuildable outputs, separate from saved source changes. Local free space increased from 440 MB to approximately 197 GB after cleanup; this does not change the capacity of GitHub-hosted runners.
