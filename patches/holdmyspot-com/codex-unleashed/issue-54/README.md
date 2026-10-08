# Manual updater handoff

## Intent

Selecting **Install latest public stable** in `/daemon` uses the requesting Codex CLI's updater. An older OpenAI updater cannot satisfy the request with its own release channel. The menu text stays the same.

Canonical issue: https://github.com/holdmyspot-com/codex-unleashed/issues/54

## Scope

The request replaces an updater with a different executable identity, including a legacy updater with no recorded executable identity. A matching updater stays running. Updater launches record their executable identity, and Windows successor handoff preserves the complete PID ownership record.

The existing public-release routing patch downloads Codex Unleashed's bundled Codex and app-server artifacts. This patch repairs worker selection; it does not introduce another artifact format or change version display.

## Base and dependencies

Declared base: upstream `rust-v0.161.0`, commit `979011409de0a60b52f179721948e65531d26144`, matching the current release pipeline and #35.

The patch applies independently to that base. Codex Unleashed's existing `branding-and-app-server-distribution/daemon-public-releases.patch` supplies repository download routing. Verification uses the complete current release patch queue, including the authorized #35 baseline repairs. The paused background terminal/task features are excluded.

## Reproduction

Connect to a managed app-server with an older updater already owning the update socket. Invoke `/daemon` and select **Install latest public stable**. The request must reach the requesting CLI's updater even if the older updater considers its own release current.

## Verification

Run the maintained commands from the patched upstream checkout root:

- `just test -p codex-app-server-daemon`: all daemon tests pass; the updater subprocess fixture is ignored outside its parent tests.
- `just clippy -p codex-app-server-daemon`: daemon lint passes.
- `just fmt-check`: maintained formatting checks pass.

The handoff regression uses isolated local processes and sockets. It covers different updater executables, legacy PID records, and reuse of a matching updater without changing its PID record. Native Windows execution requires a Windows host.

## Upstream status

The fix is downstream. The canonical issue tracks upstream applicability and release inclusion.
