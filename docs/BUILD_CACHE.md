# Build caches

Bazel jobs in this repository use two caches. Both are optional: a job that cannot reach one runs cold or on the other.

## GHCR disk caches (always available)

The Bazel disk caches live in private GHCR packages, restored and saved by `.github/scripts/bazel-cc-disk-cache.sh`,
`.github/scripts/v8-bazel-cache.sh` and `.github/scripts/ghcr-cargo-target-cache.sh` with the job's `GITHUB_TOKEN`. They
carry the compiled C++ actions, chiefly V8, and need no key. Every workflow keeps them whether or not a BuildBuddy key is
present, so they are the fallback when the key is absent.

## BuildBuddy remote cache (trusted runs with the key)

The repository secret `BUILDBUDDY_API_KEY` is for the generic BuildBuddy host, `grpcs://remote.buildbuddy.io`. When it is
present on a trusted run, `.github/scripts/buildbuddy-cache.sh configure` appends these lines to the upstream checkout's
`user.bazelrc`, which upstream's `.bazelrc` imports and `.gitignore` excludes:

- `--remote_cache` and `--experimental_remote_downloader`, both at the generic host;
- `--remote_header=x-buildbuddy-api-key=<key>`.

Bazel then reads and writes the remote cache next to the GHCR disk cache. **Remote execution is not used.** Upstream's
`ci-linux`, `ci-macos`, `ci-windows-cross` and `ci-v8` configurations require it and run tests in upstream's container
image, while the maintained test baselines describe native execution on the hosted runner. The key is therefore never
given to upstream's `run-bazel-ci.sh` or `run_bazel_with_buildbuddy.py`, which would select those configurations.

Workflows that use it: `baseline-bazel-tests`, `baseline-macos-bazel-tests` (also called by `upstream-auto-release`),
`baseline-windows-bazel-tests`, `baseline-bazel-lints` through `bazel-clippy-job`, `baseline-argument-comment-lint`,
`baseline-v8-bazel-cache`, `baseline-bazel-release-checks` and `baseline-ownership-tests`.

### Trust

- No workflow runs on `pull_request`; the Bazel workflows start from `workflow_dispatch` or `schedule` only.
  `buildbuddy-cache.sh` also writes the key only for a `push`, `workflow_dispatch` or `schedule` run in
  `holdmyspot-com/codex-unleashed`, so a pull-request run, a fork or a local command gets no key.
- Upstream's `is_trusted_upstream_run` accepts only `openai/codex`. That check chooses between upstream's OpenAI tenant and
  the generic host. It is false here and must stay false: this key does not work on the OpenAI tenant, and trusting this
  repository would send it there. Nothing in this repository patches or disables the check.
- The key is passed as an environment variable only to the `configure` and `finish` steps. `finish` runs before evidence
  is uploaded, removes the key from `user.bazelrc`, and redacts and then fails on any copy of the key found in the
  evidence directories. GitHub also masks the secret in job logs. Neither cache archive contains `user.bazelrc`.
- Anyone who can dispatch a workflow on a branch can run code with the key and write to the remote cache. Restrict write
  access to the repository accordingly.

Rotate the key by replacing the repository secret; no file in the repository holds it.
