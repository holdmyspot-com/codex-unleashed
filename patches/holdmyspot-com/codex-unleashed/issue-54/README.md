# Manual updater handoff

- Issue: [holdmyspot-com/codex-unleashed#54](https://github.com/holdmyspot-com/codex-unleashed/issues/54)
- Applies to: upstream `openai/codex` tag `rust-v0.167.0`, commit `086ccc833bc9cf01024ad2dd6af7dbd421b6bf9a`.
- Prerequisites: the release queue through `branding-and-app-server-distribution/daemon-public-releases.patch`, which routes the requesting CLI's updater to Codex Unleashed releases.
- Upstream status: downstream fix; the issue tracks upstream applicability.

## Intent

Selecting **Install latest public stable** in `/daemon` uses the requesting
Codex CLI's updater. An older OpenAI updater cannot satisfy the request with its
own release channel. The menu text stays the same.

## Scope

The request replaces an updater with a different executable identity, including
a legacy updater with no recorded executable identity. A matching updater stays
running. Updater launches record their executable identity, and Windows
successor handoff preserves the complete PID ownership record. The handoff
refuses to proceed when ownership changed, and keeps upstream's stderr-pruning
pause and rollback around the successor start.

## Reproduction

Connect to a managed app-server with an older updater already owning the update
socket. Invoke `/daemon` and select **Install latest public stable**. The request
must reach the requesting CLI's updater even if the older updater considers its
own release current.

## Verification

```sh
just test -p codex-app-server-daemon
just clippy -p codex-app-server-daemon
just fmt-check
```

The handoff regression uses isolated local processes and sockets. It covers
different updater executables, legacy PID records, and reuse of a matching
updater without changing its PID record. The Windows handoff needs a Windows
host.
