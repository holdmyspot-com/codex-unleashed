# Background subagent completion

Canonical issue: [codex-unleashed #35](https://github.com/holdmyspot-com/codex-unleashed/issues/35).

## Intent

A parent can finish its turn while V2 subagents continue working. It remains
available for conversation and receives a new turn when a child finishes or fails.
A result arriving during a user turn enters the existing input boundary, with its
child identity preserved. An active goal remains active while the parent waits
for a running child; automatic goal continuation defers to the child result.

## Scope

The parent remains responsible for delegated work after finishing its turn. The
model-visible spawn guidance explains that pending work is incomplete and that
an idle parent resumes automatically. This behavior uses V2 agent communication;
V1 agent tools retain their existing behavior.

Automatic delivery applies within the live app-server process. Pending result
delivery is best effort. Exiting or restarting the process does not provide
durable callback replay or restart a child's execution from its recorded agent
metadata.

Ctrl+B and Claude-compatible task inspection belong to
[issue #45](https://github.com/holdmyspot-com/codex-unleashed/issues/45). The
read-only task output switcher belongs to
[issue #46](https://github.com/holdmyspot-com/codex-unleashed/issues/46). These
controls are outside this feature's scope.

## Feature configuration

### Codex feature flag

The feature flag is stable and enabled by default:

```text
unleashed_background_subagents  stable  true
```

The configuration key is `[features].unleashed_background_subagents`.

Enable:

```sh
codex --enable unleashed_background_subagents
```

Disable:

```sh
codex --disable unleashed_background_subagents
```

### Project-specific `config.toml` properties

This feature has no project-specific `[codex_unleashed]` properties. Its default
enables automatic parent resumption when V2 collaboration is active. The
upstream `multi_agent_v2` flag retains its independent default of `false`; a
model or configuration must select V2 collaboration. For an explicit V2 setup:

```sh
codex --enable multi_agent_v2 --enable unleashed_background_subagents
```

Disabling the downstream flag retains queued child-result delivery and omits the
automatic-resumption guidance. It does not stop running children.

## Upstream base

- Tag: `rust-v0.160.0`
- Commit: `a956835d020762cb2b570053af06f643a11c0ecc`
- Upstream status: downstream feature; no upstream adoption is assumed.

## Patch order

Apply the preceding release-queue patches first, including
`issue-34776/avoid-slow-agent-switching-and-resume.patch`, which registers a
neighboring feature in the shared configuration schema. Then apply this series:

1. `0001-preserve-interrupted-mailbox-work.patch` preserves unobserved results and
   queued mail when an automatic parent turn is interrupted before registration,
   and keeps the embedded rollout schema consistent with the mailbox policy.
2. `0002-resume-parent-on-subagent-completion.patch` enables V2 completion
   notifications and adds guidance for yielding the parent turn.
3. `0003-defer-goal-continuation-to-live-children.patch` keeps an active-goal
   parent available while its child runs and verifies the public app-server flow.

Later patches depend on all preceding patches. The shared
`schema/refresh-config-schema.patch` applies after the runtime patches and
publishes the combined generated schema. The project's `scripts/apply-patches.sh`
uses this order.

## Reproduction

With V2 collaboration active, delegate work that outlasts the parent's initial
turn. Have the parent finish that turn and answer an unrelated question while
the child remains active. Completing the child produces a new parent turn
without another user prompt. A child failure also produces a parent result;
interrupting the child does not produce a completion callback.

## Verification

Apply the complete release queue to the declared base. Run from the patched upstream
checkout root, with build outputs and caches in the project's task-owned
build-cache directory. Each check uses a fresh task-owned temporary directory,
so fixtures do not inherit project markers from earlier checks. Declared-base
verification uses Rust 1.95.0, the version
selected by that release's Rust CI. Linux verification with the release's
sandbox-enabled V8 archive adds `--features codex-v8-poc/sandbox` to workspace
test and Clippy commands. Scoped commands that add this feature also select
`-p codex-v8-poc`. Each test process has a private writable checkout overlay,
PID namespace, temporary directory, and Codex home; these fixtures preserve
normal writable-checkout behavior without sharing test-created metadata.

```sh
just fmt-check
just clippy -p codex-core -p codex-protocol -p codex-features -p codex-goal-extension -p codex-app-server
just test -p codex-app-server-protocol --lib -E 'test(stable_precomputed_exports_match_schema_fixtures)'
just test -p codex-core -p codex-protocol -p codex-features -E 'test(background_subagent_) | test(mailbox_wake_tests) | test(mailbox_retention_tests) | test(abort_empty_active_turn_preserves_pending_input)'
just test -p codex-core -p codex-code-mode-host -p codex-rmcp-client -E 'test(suite::scenarios::)'
just test -p codex-core -p codex-code-mode-host -p codex-rmcp-client -E 'test(child_turn_start_preserves_root_attribution) | test(background_subagent_)'
just test -p codex-core -p codex-protocol -p codex-features -p codex-goal-extension -p codex-app-server -p codex-code-mode-host -p codex-rmcp-client
just test -p codex-app-server -E 'test(app_server_resumes_parent_after_background_child_finishes) | test(thread_goal_lifecycle_emits_analytics_and_clear_deletes_goal)'
just test
```

The focused core checks exercise actual model requests, parent conversation,
success and failure delivery, active-turn coordination, independent child
identities, simultaneous completions, follow-up, interruption before task
registration, retained follow-up context, and feature opt-out. The app-server check exercises public
JSON-RPC turn events and conversation while the child is active, both with and
without an active goal. Package-default test selection also builds the standalone
app-server executable used by the integration fixture. Required
formatting and package checks must pass on a fresh application of the emitted
patch before the patch is accepted.
The protocol check verifies that the embedded rollout schema matches the current
message types without changing the public API exports.
The scenario checks verify the model-visible catalog guidance. Controls for
message-board notices, guardian handoffs, residency, authorization history,
access-program inheritance, service tiers, root-turn attribution, and cold resume disable automatic
child completion when they require explicit parent turns. Model-description
fixtures disable the additional completion guidance when comparing exact text.
Selecting `codex-code-mode-host` and `codex-rmcp-client` builds the host and MCP
server executables required by integration scenarios.
