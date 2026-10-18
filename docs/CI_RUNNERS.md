# CI runners

Linux repository checks, formatting, Cargo policy checks, SDK tests, Bazel tests,
Clippy, and release compilation checks run on BuildBuddy-hosted Linux x86-64
runners. Linux V8 compilation also runs there, including ARM64 cross-compilation
through remote execution. Native ARM64 V8 smoke tests run on GitHub ARM64 runners.
Native macOS and Windows checks run on GitHub runners.
Windows GNU test targets are cross-compiled on BuildBuddy; their stack assembly
callback executes on a native GitHub Windows runner.
The x86-64 zsh release build runs on BuildBuddy; ARM64 and macOS zsh builds run
on their native GitHub runners.

GitHub Actions submits each remote task, streams its logs, and propagates its
exit status. The submission action uses a checksum-verified BuildBuddy CLI.
The BuildBuddy API key travels through a secret execution header rather than
ordinary invocation environment metadata. GitHub publication credentials stay
on GitHub.

Remote tasks write deliverables and diagnostics to BuildBuddy's artifacts
directory. GitHub downloads them with `bb download artifacts`, verifies the
artifact manifest and the commit, task, target, and exit status in the result,
then passes the verified output to later steps. GitHub artifact retention is
1 day, as described in [BUILD_CACHE.md](BUILD_CACHE.md).

## Warm state

BuildBuddy snapshots preserve the toolchain, dependency downloads, compiled
outputs, and running Bazel server. Task and target properties keep unrelated
configurations in separate snapshot groups. Persistent build state lives outside
the repository checkout so the runner's Git cleanup does not remove it.
The tooling source fingerprint also partitions snapshots, so changed runtime
inputs cannot repeatedly restore an obsolete verification marker on a branch.

The tooling runtime is reused only after full Maven verification succeeds for
its source, build configuration, JDK version, operating system, and architecture.
A restored runtime still runs the launcher probe. Maven test temporary files
remain inside the checkout to preserve consistent physical paths across native
child processes.

Patched upstream sources retain their paths and modification times while the
resolved upstream commit and patch inputs match. A changed revision or patch set
causes a fresh reset, patch application, and Cargo metadata normalization before
the source is marked ready for reuse.

Snapshots and cache entries remain subject to BuildBuddy eviction and account
limits. A cold runner rebuilds missing state; a warm snapshot is not guaranteed.
The free tier's cache-transfer allowance does not guarantee unlimited runner use.

## Restore GitHub execution

The complete GitHub runner implementation is retained at commit
`9d6ae54ffbda36abe1452b650f3e9ad876cd9aa4`. Inspect it with:

```sh
git show 9d6ae54ffbda36abe1452b650f3e9ad876cd9aa4:.github/workflows/bazel.yml
```

Create a restoration branch and restore the workflow definitions from that
revision:

```sh
git switch -c ci/restore-github-runners
git restore --source=9d6ae54ffbda36abe1452b650f3e9ad876cd9aa4 -- .github/workflows
git restore --source=9d6ae54ffbda36abe1452b650f3e9ad876cd9aa4 -- .github/scripts/run-bazel-ci.sh .gitignore
git restore --source=9d6ae54ffbda36abe1452b650f3e9ad876cd9aa4 -- .github/scripts/build-zsh-release-artifact.sh
git add -- .github/workflows .github/scripts/run-bazel-ci.sh .gitignore
git add -- .github/scripts/build-zsh-release-artifact.sh
git rm -- .github/actions/run-buildbuddy/action.yml MODULE.bazel
git rm -- .github/scripts/buildbuddy-bootstrap.sh .github/scripts/buildbuddy-ci.sh
git rm -- .github/scripts/buildbuddy-prepare-upstream.sh .github/scripts/buildbuddy-tools.sh
git rm -- .github/scripts/buildbuddy-v8.sh .github/scripts/run-buildbuddy-ci.sh
git rm -- .github/scripts/buildbuddy-windows-cross.sh .github/scripts/test-bazel-cross-host.sh
git rm -- .github/scripts/buildbuddy-tooling-source-key.sh
git rm -- .github/scripts/run-windows-callback.sh
git rm -- .github/zsh-commit
actionlint
git diff --check
git diff --cached
```

Review subsequent workflow changes before committing the restoration, and retain
any fixes still required by the supported upstream release. Run CI and the
platform build workflows on the restoration branch before merging it. Product
patches and release history do not need to be reverted.
