# Start the background Windows read-ACL helper without inherited handles

- Applies to: upstream `openai/codex` tag `rust-v0.167.0`, commit `086ccc833bc9cf01024ad2dd6af7dbd421b6bf9a`. The
  patch changes only `codex-rs/windows-sandbox-rs/src/setup_launch.rs`, `setup_launch_tests.rs` and
  `setup_provisioning.rs`, which no earlier queue entry changes, so it applies after any prefix of the queue. It is
  queue entry 110 and is listed last.

## Intent

On a Windows machine where the elevated sandbox has not granted its read ACLs yet, the first sandboxed
`fs/writeFile` left `codex-exec-server --listen stdio` running after its stdin closed. The package smoke test
`test_packaged_executor_initializes_and_enforces_sandbox[cli_root]` then timed out waiting 45 seconds for it to exit,
on this queue and on unpatched upstream alike; a rerun in the same job passed.

The exec-server runs the filesystem helper through the sandbox wrapper with piped standard I/O. The wrapper runs
sandbox setup through `std::process::Command`, which lets the setup helper inherit every inheritable handle,
including the wrapper's stderr pipe. The setup helper then starts the read-ACL helper, which inherited the same
handles and keeps running after setup returns. The exec-server's stderr reader therefore did not see end-of-file:
after its two-second limit the exec-server killed the wrapper, but the blocking read stayed pending in Tokio's
blocking pool, and dropping the runtime at exit waited for it until the read-ACL helper finished. Later setups find
the helper's `Local\CodexSandboxReadAcl` mutex and start no helper, which is why only the first use failed.

The read-ACL helper now starts through `CreateProcessW` with a handle list that holds only a `NUL` device for its
standard input, output and error, so it inherits no handle of the processes that launched it. Its program,
arguments, environment and console behavior are unchanged. The setup helper itself still inherits handles; it exits
before the wrapper returns, so it cannot keep a pipe open after its launcher finishes.

## Verification

The new tests are in `codex-rs/windows-sandbox-rs/src/setup_launch_tests.rs` and run on Windows:

```sh
cargo test -p codex-windows-sandbox setup_launch
```

`isolated_child_does_not_keep_inheritable_pipe_open` starts a child with an inheritable pipe end open in the parent,
closes the parent's end and requires end-of-file while the child still runs. A child started with
`std::process::Command` would inherit the pipe end and keep it open until it exits, which this test detects; that
failing variant has not been run. The end-to-end check is the package smoke workflow `baseline-package-smoke.yml` for
a Windows target on a fresh runner.

`cargo check` and `cargo clippy --tests -- -D warnings` for `codex-windows-sandbox` pass for the target
`x86_64-pc-windows-gnullvm` from Linux, with the C build steps of `ring`, `aws-lc-sys` and `libz-sys` replaced by
stubs, which a type check does not link. The tests and the package smoke workflow have not been run on Windows with
this patch.
