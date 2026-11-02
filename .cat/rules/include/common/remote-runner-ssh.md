# Remote Runner SSH Sessions

## Design Goals

- Establish authenticated remote command execution on a retained worker and verify transferred files before using them.
- Keep the worker alive from dispatch until its last consumer is finished: no worker is lost to an unclaimed handoff,
  an idle-shell logout, a cancelled job, or a dropped client.
- Spend the worker's lifetime on commands whose results are still needed and are making progress; detect a stalled
  command early and end it from inside the session.
- Preserve failed-run evidence and valid completed work during incremental diagnosis, then close the session and clean
  up owned resources.
- Release each worker explicitly as soon as nothing needs it, rather than letting it run until a timeout.

## Guidance

Apply this rule whenever testing workflow changes on remote runners, including tests expected to pass, and for Upterm
setup or troubleshooting. Work within the task's existing authorization for remote execution, workflow changes and
publishing. Establish access before running the changed test commands and keep the connection open through testing and
incremental troubleshooting. A failure-only debug session is insufficient. A worker already destroyed by its provider
cannot be recovered by connecting afterward; arrange retention before teardown.

When the session runs on this repository's GitHub Actions workflows, lazy load `remote-runner-ssh/github-actions.md`
before dispatching the workflow.

### Reuse the working integration

Inspect the runner provider's retention, supported remote shell, network access, connection-detail handoff, deadlines
and teardown lifecycle. Reuse its supported facilities and adapt only the missing integration. Do not assume that one
provider's integration or lifecycle applies to another.

### Supply access and retain the workspace

1. Use an existing task-owned SSH key or generate a dedicated key pair in owned temporary storage with
   `ssh-keygen -t ed25519`. Keep the private key on the client and pass only the public key to the remote workflow.
   Establish who removes the key pair after the session is terminal.
2. Configure the session to accept only the task's authorized key, and keep the key file available for the session's
   lifetime. Pin the relay's host key or certificate authority from an authoritative source.
3. Preserve the diagnostic workspace and delay cleanup until the remote shell exits. When ordinary workflow steps keep
   running while the session waits, either hold them behind a readiness handshake or run the changed commands yourself
   through the connected shell. Register cleanup with the provider's lifecycle so retained state is not removed during
   the session.
4. Publish connection details through a handoff the client can retrieve while the worker is alive, and consume the
   integration's published SSH command. If access or retention fails, record and repair that failure before running the
   remote test.

### Track the worker's deadlines

Before dispatching, identify every limit that can end the worker: the deadline to join, the idle-shell logout, the
job's timeout and the provider's maximum lifetime. Record each one as an absolute time in the session's owner record
when it starts, and recompute it whenever the session's state changes.

Claim the worker as soon as its connection details are published: connect, verify a command, and start keeping the
shell busy before doing any other work. Do not dispatch a worker that nothing will claim before its join deadline, such
as while other work or a context reset will occupy the agent. When handing a worker to another owner, record its
deadlines in the handoff; the receiver claims it first.

An idle-shell logout ends the worker even while the client stays connected. Whenever no command is running, keep a
foreground keepalive command running in the remote shell, such as a loop that prints a timestamp every minute. End it
with a key or marker that it handles, and confirm its exit before sending the next command. Interrupting it with
Ctrl-C loses its completion marker. Never leave the shell at its prompt for longer than the idle limit minus a margin
for reconnecting.

### Connect and execute

Retain the provider's run and worker identities and retrieve live connection details through the supported handoff.
Keep each run's artifacts in its own owned directory. Verify a remote command's effect in the original workspace before
starting the changed test commands; a banner or published connection command alone is insufficient.

Read the destination from the published SSH command. Parse its arguments with a platform facility such as Python
`shlex.split`; do not evaluate artifact text as shell code. Connect with the task-owned private key,
`IdentitiesOnly=yes`, the pinned `UserKnownHostsFile`, `StrictHostKeyChecking=yes`, and a PTY (`ssh -tt`).

If the execution harness buffers command output or closes standard input, use one foreground owner process (for
example Python `pty.openpty()`, `subprocess.Popen()` and `select.select()`) to drive SSH. Keep it alive through
connection, remote execution, terminal exit and reaping its child. A detached SSH process is not a durable session
owner. Save its transcript in owned storage even on failure.

Wait for the remote shell prompt before sending commands; immediately piped input was lost in observed Upterm sessions.
Windows Git Bash may render the prompt as `#\x1b[22m ` and append an OSC terminal-title sequence, so stripping CSI color
sequences alone still leaves a prompt matcher waiting. Handle the actual terminal sequences, or normalize `PS1` and
`PROMPT_COMMAND` through the existing connection. Do not diagnose authentication failure from a prompt-detection
timeout. Reconnect to the same retained session and verify its workspace when only the client failed.

When transferring files through the interactive shell, encode binary content and send bounded command lines, counting
shell syntax in each line's length. Base64 chunks of 512 ASCII bytes worked on native macOS; roughly 1,000-byte chunks
plus shell syntax overflowed the terminal and lost input. Wait for a unique remote acknowledgement after each chunk
before sending the next, and handle partial local writes. After decoding in owned remote storage, compare the received
file's size and SHA-256 with the local original before executing it. If a transfer loses input, preserve its
transcript, use Ctrl-C to clear unfinished input when no test command is running, verify a short command, reset only the
incomplete file and resend with shorter acknowledged lines.

### Keep remote work necessary and progressing

Before waiting on any remote command, including a workflow step that is already running, confirm that its result is
still needed: it runs the source under test and answers a question that no faster check on the live session can. If it
runs superseded code or its outcome no longer matters, end it from inside the session now rather than waiting for it.

After starting a long remote command, verify within a few minutes that it is progressing, then recheck at intervals much
shorter than its expected duration. Use evidence the session can observe: its process tree, growth of its output or
logs, and CPU time consumed. Treat a command whose processes have stopped advancing as stalled; a merely live process is
not progress. For a stalled command, first capture its diagnostics (process tree, open files, thread or stack dumps,
recent log tail), then end its process tree from inside the remote shell and confirm that no descendants or populated
process groups remain.

Never cancel the provider's job or run, or kill the SSH client, to stop remote work: either ends the worker and its
retained workspace. Let a command that is still progressing finish; the reason not to cancel it is that its work is
valid, not that the session must outlive it.

Inspect the original failing command and environment, identify the root cause, make a focused change and retry the
affected work on the same worker while retaining valid completed outputs. Keep the original failure distinct from a
successful retry, and perform the task's required clean verification after incremental success.

### Shut down explicitly

Use a 10-minute idle limit for every session you dispatch, unless the integration cannot be configured below a longer
one. Combined with the keepalive, it reclaims a worker soon after its last consumer stops.

Shut the worker down yourself as soon as no remaining command, retry, or acceptance check needs it; never leave it to
reach its idle limit or job timeout. First record the session's results and copy every retained output that later work
needs. Then end any keepalive, run `exit` in the remote shell, reap the SSH child and confirm the session ended.
Disconnecting the client without `exit` leaves the session running.

If workflow steps would still run after the session ends and their results are not needed, cancel that run with the
provider's cancel command right after exiting. Record the cancellation as a deliberate shutdown, separate from any
product or provider failure. Cancelling skips the provider's post-job steps, so their cleanup result is not observable.
Otherwise, wait for the run to end and observe its terminal job and cleanup result. Never cancel while you still need
the worker.

### End and verify

Remove owned client keys and temporary state only after their last consumer is terminal. If a session expires or is
logged out, record that outcome, its cause and the original logs rather than reporting execution success.

When changing the remote-access integration, use the cheapest representative diagnostic: connect first, create partial
state, deliberately fail, verify that state over the still-open connection, append the retry result, exit, and verify
the retained result after the remote wait. Keep the deliberate failure visible. Verify unauthorized-key rejection and
the affected lifecycle or platform boundary without rerunning unchanged product builds. A `session ... refused your
key` message is an authentication rejection even without `Permission denied (publickey)`.
