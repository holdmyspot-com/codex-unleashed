---
name: upterm-runner-debugging
description: Use Upterm to inspect a retained remote runner after failure and retry work incrementally in its original workspace. Use for Upterm setup, connection, session-lifecycle troubleshooting, or adapting this repository's verified runner-debugging action.
---

# Debug retained runners with Upterm

## Design Goals

- Establish authenticated remote command execution on the retained worker, rather than accepting session startup as proof.
- Preserve failed-run evidence and valid completed work during incremental diagnosis.
- Keep the workspace available through remote debugging, then close the session and clean up owned resources.
- Reuse the working integration and its platform evidence without rediscovering its setup.

## Guidance

Use this skill when the task needs Upterm access to a remote worker. Work within the task's existing authorization for remote execution, workflow changes and publishing. A failed step can leave a worker alive; a worker already destroyed by its provider cannot be recovered by starting Upterm afterward. Arrange retention before teardown.

## Reuse the working integration

In this repository, read [remote-runner-debugging.md](../../docs/remote-runner-debugging.md) for the supported lifecycle and platform evidence. Read the [composite action](../../.github/actions/debug-failed-runner/action.yml) and its [storage action](../../.github/actions/debug-failed-runner/storage/action.yml) for the current dependency pins, inputs, outputs and cleanup ownership. For a small end-to-end check, read [runner-debug-smoke.yml](../../.github/workflows/runner-debug-smoke.yml) and use its current dispatch inputs and target choices. Those files are authoritative; preserve their versions unless the task requires changing them.

For another provider or repository, inspect its retention, supported remote shell, network access and teardown lifecycle. Reuse its supported facilities and adapt only the missing integration. Do not assume the GitHub action can run on another provider.

## Supply access and keep the workspace alive

1. Use an existing task-owned SSH key or generate a dedicated key pair in owned temporary storage with `ssh-keygen -t ed25519`. Keep the private key on the client. Pass only the public key to the remote workflow. Establish who removes the key pair after the session is terminal.
2. The verified integration passes an authorized-key file to Upterm through `UPTERM_AUTHORIZED_KEYS`; it does not require uploading that key to a GitHub account. Keep the key file available through the remote action's lifetime. Pin the relay's host key or certificate authority from an authoritative source; use the repository's [known_hosts](../../.github/actions/debug-failed-runner/known_hosts) for this integration.
3. Preserve the diagnostic workspace before opening the session. With the existing detached GitHub action, regular steps continue immediately; its wait happens during post-job cleanup. Register workspace cleanup in an earlier action's post operation so it runs after Upterm's post operation. An ordinary cleanup step after the detached action runs too early.
4. Consume the action's published SSH command and publish connection details while the job is alive. Do not query its sessions from a separate process using default directories: the action keeps its session registry in private XDG directories and does not export them to later steps. The published command is the supported handoff.

## Connect and execute

For the GitHub integration, obtain the run identity after dispatch, enumerate its connection artifacts through the Actions API, then download the applicable artifact while the session is alive. Finished-job log retrieval is unavailable while the job is running; use the connection artifact instead. Keep each run's artifacts in its own owned directory.

Read the destination from the published SSH command. Parse its arguments with a platform facility such as Python `shlex.split`; do not evaluate artifact text as shell code. Connect with the task-owned private key, `IdentitiesOnly=yes`, the pinned `UserKnownHostsFile`, `StrictHostKeyChecking=yes`, and a PTY (`ssh -tt`).

If the execution harness buffers command output or closes standard input, use one foreground owner with Python `pty.openpty()`, `subprocess.Popen()` and `select.select()` to drive SSH. Keep that owner alive through connection, remote execution, terminal exit and child reaping. A detached SSH process is not a durable session owner. Save its transcript in owned storage even on failure.

Wait for the remote shell prompt before sending commands. Immediate piped input was lost in the observed Upterm sessions. Windows Git Bash may render the prompt as `#\x1b[22m `: a literal uncolored `# ` check missed an already connected shell. Match the actual terminal output or use an appropriate terminal facility; do not diagnose authentication failure from a prompt-detection timeout. Reconnect to the same retained session when only the client failed.

Inspect the original failing command and environment, identify the root cause, make a focused change and retry affected work while retaining valid completed outputs. Verify an observable remote effect and the command's result; connection or banner output alone is insufficient. Keep the original failure distinct from a successful retry. Perform the task's required clean verification after incremental success.

## End and verify

Exit the remote shell when finished, reap the SSH child and observe the provider's terminal job/cleanup result. Do not cancel an active build merely to close a diagnostic session. Remove owned client keys and temporary state only after their last consumer is terminal. If a session expires without a connection, retain that outcome and the original logs rather than reporting execution success.

When changing this integration, use the cheapest representative diagnostic: create partial state, deliberately fail, connect to the same worker, verify that state, append the retry result, exit, and verify the retained result after the remote wait. Keep the deliberate failure visible. Verify unauthorized-key rejection and the affected lifecycle/platform boundary without rerunning unchanged Codex builds. If an unrelated key is refused with `session ... refused your key`, that is an authentication rejection even when SSH does not print `Permission denied (publickey)`.
