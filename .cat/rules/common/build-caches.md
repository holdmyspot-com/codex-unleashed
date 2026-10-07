# Build Cache Storage

## Design Goals

- Keep caches created by local project builds and verification under the project root's `.cat/work/temp`, as confirmed
  by the invoked tools' effective cache paths.
- Remove task-owned build artifacts and caches after their last required consumer finishes, and verify cleanup.

## Guidance

When running local builds, tests, formatting, or packaging for this project, store build outputs and dependency caches
under `.cat/work/temp/build-caches/<tool-or-purpose>`. Resolve this location from the project root to an absolute path
before invoking a tool from an upstream checkout or worktree.

Set each tool's cache and output options explicitly in the invocation environment. For Rust, set `CARGO_TARGET_DIR`,
`CARGO_HOME`, and any task-managed `RUSTUP_HOME` beneath that location. Set `UV_CACHE_DIR` for uv and the appropriate
cache option for other tools. For tools using the XDG cache default, set `XDG_CACHE_HOME` beneath that location.
Do not create or reuse project build caches in the user's home cache directories, `/tmp`, or checkout-local `target`
directories outside `.cat/work/temp`.

Before running the tool, confirm its effective cache and output paths are beneath the project root's `.cat/work/temp`.
Use separate subdirectories when verification requires isolated build state. Keep cache contents out of commits and
release assets. This rule governs local build storage; it does not relocate user configuration or installed application
data or change hosted CI cache storage.

Before invoking a build, assign its generated files and dependency caches a cleanup owner and their last required
consumer. Include cleanup in the owning workflow's terminal path for success, failure, and cancellation. Wait for
processes using the files to exit before removing them; remove intermediate outputs after their last consumer finishes.

At terminal cleanup, remove task-owned outputs, downloads, extraction directories, and caches with no remaining
consumer, then verify their absence. Remove only owned paths; do not delete another workflow's files or user data.
Retain release artifacts until publication or delivery finishes, and failure evidence until its recorded diagnosis or
recovery consumer finishes. Retain shared caches only while a named active build or recorded follow-up needs them;
possible future reuse alone does not justify retention. Record each retained path's cleanup owner and last consumer.
Report cleanup failures with the affected paths and remaining owner.
