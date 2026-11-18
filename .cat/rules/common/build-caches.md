# Build Cache Storage

## Design Goals

- Keep caches created by local project builds and verification under the project root's `.cat/work/temp`, as confirmed
  by the invoked tools' effective cache paths.
- Remove task-owned build artifacts and caches after their last required consumer finishes, and verify cleanup.
- Keep compiler outputs for independent permitted local checkouts separate while permitting compatible dependency
  downloads to be shared.

## Guidance

This rule governs local build storage only for work that the current build plan permits to run locally. Before setting
up a local build or cache, read the `Fixed constraints` section of `${CAT_PROJECT_ROOT}/plan.md` for execution placement.
That section is authoritative and may change; do not copy its current list of remote-only work into this rule. If the
plan excludes the requested work from local execution, follow its placement requirement and do not create local build
outputs or caches for that work.

For permitted local builds, tests, formatting, or packaging, store build outputs and dependency caches under
`.cat/work/temp/build-caches/<tool-or-purpose>`. Resolve this location from the project root to an absolute path before
invoking a tool from an upstream checkout or worktree.

Set each tool's cache and output options explicitly in the invocation environment. Set `UV_CACHE_DIR` for uv and the
appropriate cache option for other tools. For tools using the XDG cache default, set `XDG_CACHE_HOME` beneath that
location.
Do not create or reuse project build caches in the user's home cache directories, `/tmp`, or checkout-local `target`
directories outside `.cat/work/temp`.

Before running the tool, confirm its effective cache and output paths are beneath the project root's `.cat/work/temp`.
Use separate subdirectories when verification requires isolated build state. Keep cache contents out of commits and
release assets. This rule governs local build storage; it does not relocate user configuration or installed application
data or change hosted CI cache storage.

Keep compiler outputs for different permitted local checkouts in separate subdirectories, such as one per canonical
checkout path, while permitting compatible dependency downloads to be shared. Do not reuse another checkout's compiler
outputs, and do not adopt existing unowned outputs; select a fresh cache name or remove the outputs after their last
consumer exits.

Before invoking a build, assign its generated files and dependency caches a cleanup owner and their last required
consumer. Include cleanup in the owning workflow's terminal path for success, failure, and cancellation. Wait for
processes using the files to exit before removing them; remove intermediate outputs after their last consumer finishes.

At terminal cleanup, remove task-owned outputs, downloads, extraction directories, and caches with no remaining
consumer, then verify their absence. Remove only owned paths; do not delete another workflow's files or user data.
Retain release artifacts until publication or delivery finishes, and failure evidence until its recorded diagnosis or
recovery consumer finishes. Retain shared caches only while a named active build or recorded follow-up needs them;
possible future reuse alone does not justify retention. Record each retained path's cleanup owner and last consumer.
Report cleanup failures with the affected paths and remaining owner.
