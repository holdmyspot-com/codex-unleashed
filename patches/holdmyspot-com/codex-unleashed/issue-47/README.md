# Avoid repeating Bash startup when replaying shell snapshots

- Issue: [holdmyspot-com/codex-unleashed#47](https://github.com/holdmyspot-com/codex-unleashed/issues/47)
- Applies to: upstream `openai/codex` tag `rust-v0.167.0`, commit `086ccc833bc9cf01024ad2dd6af7dbd421b6bf9a`. No downstream prerequisites.

## Intent

Noninteractive Bash sources `.bashrc` for a `-c` command when `SSH_CLIENT` is set or when stdin looks like a network connection. The exec-server's restricted seccomp policy makes `getpeername()` fail with `EPERM`, and Bash treats that failure as a network connection. Snapshot replay already restores the state that the startup files produced, so running `.bashrc` again repeats its side effects and can fail inside a read-only sandbox. Replay now passes `--norc` to Bash. The captured state, environment policy and sandbox are unchanged.

## Verification

The `remote_sandbox` case sets `SSH_CLIENT`, so it fails on Debian-family Bash without the fix on any host. On GitHub-hosted Linux runners, `remote_pipe_recovery`, `remote_tty_recovery` and `remote_sandbox` also fail without the fix through the seccomp `EPERM` path.

From the patched upstream checkout root:

```sh
cd codex-rs && cargo nextest run -p codex-exec-server -E 'test(shell_snapshot) - test(blocked_descriptor_path)'
just fmt-check
```

All selected tests are expected to pass. The `blocked_descriptor_path` case fails for a separate reason (the bubblewrap `/dev/fd` mount) and is excluded here.
