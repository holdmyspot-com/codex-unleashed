# Remote Runner SSH Sessions

## Design Goals

- Keep the worker alive from dispatch until its last consumer is finished: no worker is lost to an unclaimed handoff,
  an idle-shell logout, a cancelled job, or a dropped client.
- Spend the worker's lifetime on commands whose results are still needed and are making progress; detect a stalled
  command early and end it from inside the session.
- Release each worker explicitly as soon as nothing needs it, rather than letting it run until a timeout.

## Guidance

This rule adds this project's requirements to the plugin's `cat:remote-runner-session` skill, which supplies the
integration checks, key handling, connection, file transfer, retry and integration-test procedure. Apply both whenever
testing workflow changes on remote runners and for Upterm setup or troubleshooting. Where they differ, this rule's
idle limit and shutdown steps apply.

When the session runs on this repository's GitHub Actions workflows, lazy load `remote-runner-ssh/github-actions.md`
before dispatching the workflow.

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
