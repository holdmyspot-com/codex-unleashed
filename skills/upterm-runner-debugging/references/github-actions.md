# Upterm on this repository's GitHub Actions runners

## Design Goals

- Agents obtain live connection details and preserve the workspace using this repository's verified Actions integration.

## Guidance

Read [remote-runner-debugging.md](../../../docs/remote-runner-debugging.md) for the supported lifecycle and platform evidence. Read the [composite action](../../../.github/actions/debug-failed-runner/action.yml) and its [storage action](../../../.github/actions/debug-failed-runner/storage/action.yml) for current dependency pins, inputs, outputs and cleanup ownership. Read [runner-debug-smoke.yml](../../../.github/workflows/runner-debug-smoke.yml) when selecting its supported smoke-test dispatch inputs. These files are authoritative; use their current values.

The existing action is detached: ordinary steps continue immediately, and the wait occurs during post-job cleanup. Before testing changed commands, connect and use a readiness handshake or run the commands through the connected shell. A failure-only invocation does not establish access before testing. Register workspace cleanup in an earlier action's post operation so reverse post order runs it after the remote wait; an ordinary cleanup step after the detached action runs too early.

Obtain the run identity after dispatch, enumerate its connection artifacts through the Actions API, and download the applicable artifact while the session is alive. Finished-job log retrieval is unavailable while the job is running; use the live connection artifact. The action's published SSH command is the supported handoff. Its session registry uses private XDG directories unavailable to a separate process using defaults.

The integration supplies its authorized-key file through `UPTERM_AUTHORIZED_KEYS`; no account-key upload is needed. Read [known_hosts](../../../.github/actions/debug-failed-runner/known_hosts) for the pinned relay authority used by this integration. Observe the action's post result and the owning storage action's cleanup after deliberately ending the remote shell.
