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
