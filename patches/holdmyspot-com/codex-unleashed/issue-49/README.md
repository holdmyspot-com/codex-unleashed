# Use a mountable procfs denial to test snapshot descriptor fallback

- Issue: [holdmyspot-com/codex-unleashed#49](https://github.com/holdmyspot-com/codex-unleashed/issues/49)
- Applies to: upstream `openai/codex` tag `rust-v0.167.0`, commit `086ccc833bc9cf01024ad2dd6af7dbd421b6bf9a`. No downstream prerequisites.

## Intent

`shell_snapshot_concurrent_replays_keep_independent_readers::blocked_descriptor_path` denies `/dev/fd` to show that replay falls back when the descriptor path is unavailable. In the Linux sandbox, `/dev/fd` is the symlink to `/proc/self/fd` that bubblewrap's `--dev` creates; masking it with tmpfs follows the symlink and fails with `ENOENT`, so bubblewrap exits before Bash starts. This is a test-only patch:

- On Linux the test denies `/proc`, which removes the descriptor path the same way. Its exec-server runs in the caller's PID namespace, because a new PID namespace mounts a fresh `/proc` over the denial.
- The replay profile fails if Bash sourced the snapshot through `/dev/fd`, so the test proves that the fallback ran.
- macOS keeps the `/dev/fd` denial.

## Verification

Red: unpatched run `40220417153` fails both attempts with `bwrap: Can't mount tmpfs on /newroot/dev/fd: No such file or directory`.

Green, from the patched upstream `codex-rs` directory on Linux with bubblewrap available:

```sh
cargo nextest run -p codex-exec-server -E 'test(shell_snapshot_concurrent_replays_keep_independent_readers)'
```

All selected cases are expected to pass.
