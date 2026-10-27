# Remote runner workflow tests

## Design Goals

- Workflow changes tested on remote runners are exercised through an active Upterm connection that preserves the worker and completed work for incremental troubleshooting.
- Agents verify remote results and cleanup without treating a configured or merely started session as active use.

## Guidance

Whenever testing workflow changes that execute on remote runners, read and use [SKILL.md](../../skills/upterm-runner-debugging/SKILL.md) for the Upterm procedure. This applies across runner providers and operating systems, including tests expected to pass; a failure-only debug action does not satisfy this requirement. Read-only workflow inspection without remote execution does not require a session.

Arrange retention before teardown, establish authenticated Upterm access to each worker under test, and verify remote command execution before starting its changed test commands. Keep the connection open while those commands run and while diagnosing and retrying failures. Use the same worker and workspace for incremental retries, preserving valid completed outputs and original failure evidence. Do not disconnect between attempts to provision a replacement runner. If the client connection is interrupted while the worker remains available, reconnect to that worker and verify its retained state before continuing.

Use a runner-side readiness handshake or execute the tests through the connected shell so the changed commands cannot finish before the client connects. If access or retention cannot be established, report the observed prerequisite failure and repair that integration before running the remote test; do not silently fall back to unattended execution.

Record evidence of the live connection, commands, results and retained state. After the required tests and any incremental retries finish, perform the required clean verification, close the session deliberately, reap the client and verify remote cleanup. Keep failures distinct from successful retries; session startup, connection artifacts and banners alone do not prove the tests ran.
