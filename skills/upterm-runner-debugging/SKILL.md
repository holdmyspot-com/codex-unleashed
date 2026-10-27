---
name: upterm-runner-debugging
description: Keep an active Upterm connection to a remote runner while testing workflow changes, diagnosing failures and retrying work incrementally in its original workspace, across runner providers and operating systems.
---

# Debug retained runners with Upterm

## Design Goals

- Establish authenticated remote command execution on the retained worker, rather than accepting session startup as proof.
- Preserve failed-run evidence and valid completed work during incremental diagnosis.
- Keep the workspace available through remote debugging, then close the session and clean up owned resources.
- Reuse verified provider integrations without assuming their lifecycle applies to another provider.

## Guidance

Use this skill whenever testing workflow changes on remote runners, and for requested Upterm setup or troubleshooting. Work within the task's existing authorization for remote execution, workflow changes and publishing. Establish access before running the changed test commands and keep the connection open through testing and incremental troubleshooting, even when success is expected. Configuring a failure-only session is insufficient. A worker already destroyed by its provider cannot be recovered by starting Upterm afterward; arrange retention before teardown.

## Reuse the working integration

Inspect the runner provider's retention, supported remote shell, network access, connection-detail handoff and teardown lifecycle. Reuse its supported facilities and adapt only the missing integration. When using this repository's GitHub Actions integration, read [github-actions.md](references/github-actions.md) for its authoritative action files and provider-specific lifecycle. Do not assume that integration runs on another provider.

## Supply access and keep the workspace alive

1. Use an existing task-owned SSH key or generate a dedicated key pair in owned temporary storage with `ssh-keygen -t ed25519`. Keep the private key on the client. Pass only the public key to the remote workflow. Establish who removes the key pair after the session is terminal.
2. Configure Upterm to accept only the task's authorized key. Keep the key file available through the session's lifetime. Pin the relay's host key or certificate authority from an authoritative source. Use the selected integration's current installation, version and authentication contract.
3. Preserve the diagnostic workspace and delay cleanup until the remote shell exits. A detached session may let ordinary workflow steps continue immediately; establish a readiness handshake before they execute changed commands, or execute those commands directly through the connected shell. Register cleanup with the provider's lifecycle so retained state is not removed during the session.
4. Publish connection details through a handoff the client can retrieve while the worker is alive. Consume the integration's published SSH command. Do not assume another process can discover a session registry kept in private directories. If access or retention fails, record and repair that failure before running the remote test.

## Connect and execute

Retain the provider's run and worker identities and retrieve their live connection details through the supported handoff. Keep each run's artifacts in its own owned directory. Verify a remote command's effect in the original workspace before starting the changed test commands; a banner or published connection command alone is insufficient.

Read the destination from the published SSH command. Parse its arguments with a platform facility such as Python `shlex.split`; do not evaluate artifact text as shell code. Connect with the task-owned private key, `IdentitiesOnly=yes`, the pinned `UserKnownHostsFile`, `StrictHostKeyChecking=yes`, and a PTY (`ssh -tt`).

If the execution harness buffers command output or closes standard input, use one foreground owner with Python `pty.openpty()`, `subprocess.Popen()` and `select.select()` to drive SSH. Keep that owner alive through connection, remote execution, terminal exit and child reaping. A detached SSH process is not a durable session owner. Save its transcript in owned storage even on failure.

Wait for the remote shell prompt before sending commands. Immediate piped input was lost in the observed Upterm sessions. Windows Git Bash may render the prompt as `#\x1b[22m ` and append an OSC terminal-title sequence: stripping CSI color sequences alone still leaves a prompt matcher waiting. Handle the actual terminal sequences or normalize `PS1` and `PROMPT_COMMAND` through the existing connection; do not diagnose authentication failure from a prompt-detection timeout. Keep the connection open through commands, diagnosis and retries. Reconnect to the same retained session and verify its workspace when only the client failed.

Inspect the original failing command and environment, identify the root cause, make a focused change and retry affected work while retaining valid completed outputs. Verify an observable remote effect and the command's result; connection or banner output alone is insufficient. Keep the original failure distinct from a successful retry. Perform the task's required clean verification after incremental success.

## End and verify

Exit the remote shell when finished, reap the SSH child and observe the provider's terminal job/cleanup result. Do not cancel an active build merely to close a diagnostic session. Remove owned client keys and temporary state only after their last consumer is terminal. If a session expires without a connection, retain that outcome and the original logs rather than reporting execution success.

When changing the remote-access integration, use the cheapest representative diagnostic: connect first, create partial state, deliberately fail, verify that state over the still-open connection, append the retry result, exit, and verify the retained result after the remote wait. Keep the deliberate failure visible. Verify unauthorized-key rejection and the affected lifecycle/platform boundary without rerunning unchanged product builds. If an unrelated key is refused with `session ... refused your key`, that is an authentication rejection even when SSH does not print `Permission denied (publickey)`.
