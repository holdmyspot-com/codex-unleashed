# Remote Runner SSH Sessions on This Repository's GitHub Actions

## Design Goals

- Obtain live connection details, deadlines and workspace retention from this repository's verified Actions
  integration.

## Guidance

The [composite action](../../../../../.github/actions/debug-failed-runner/action.yml) and its
[storage action](../../../../../.github/actions/debug-failed-runner/storage/action.yml) are authoritative for dependency
pins, inputs, outputs, the idle limit and cleanup ownership. Read
[runner-debug-smoke.yml](../../../../../.github/workflows/runner-debug-smoke.yml) when selecting its dispatch inputs, and
read the dispatched workflow itself for its job `timeout-minutes` and any readiness handshake its steps wait on.

The action's `idle-timeout-minutes` input bounds both the time before the client joins and each idle period at the shell
prompt. Include both, plus the job timeout, in the worker's deadlines. When a workflow exposes that input for dispatch,
such as `idle_timeout_minutes`, pass `10`.

To shut down explicitly, run `exit` in the remote shell, which ends the session and lets the job continue at once. If the
dispatched workflow still has steps to run and their results are not needed, cancel the run with `gh run cancel` and the
run's ID.

The action is detached: ordinary steps continue immediately, and the wait occurs during post-job cleanup. The
workflow's own steps run the dispatched commit, not later local fixes, so decide before waiting on one whether its
result is still needed. Register workspace cleanup in an earlier action's post operation so reverse post order runs it
after the remote wait.

Obtain the run identity after dispatch, enumerate its connection artifacts through the Actions API, and download the
applicable artifact while the session is alive. Finished-job logs are unavailable while the job runs. The published SSH
command is the supported handoff; the session registry uses private XDG directories that a separate process cannot
discover.

The integration supplies its authorized-key file through `UPTERM_AUTHORIZED_KEYS`; no account key upload is needed.
Read [known_hosts](../../../../../.github/actions/debug-failed-runner/known_hosts) for the pinned relay authority.
Exiting the remote shell lets post-job cleanup proceed; observe the action's post result and the storage action's
cleanup afterward.
