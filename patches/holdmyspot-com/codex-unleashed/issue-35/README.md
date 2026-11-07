# Background subagent completion

- Issue: [holdmyspot-com/codex-unleashed#35](https://github.com/holdmyspot-com/codex-unleashed/issues/35)
- Applies to: upstream `openai/codex` main, commit `dddc3c0a79528c4de8d9a20d51dd3bd89b02a3e4`.
- Prerequisites: the release queue through `issue-34776/avoid-slow-agent-switching-and-resume.patch`, which registers a neighboring feature in the shared configuration schema.
- Upstream status: upstream's `wake_on_agent_messages` feature (under development, default off) wakes only a root thread and keeps child completion mail queue-only. It does not preserve mail across an interrupted task start or defer goal continuation to running children, so this series builds on upstream's mailbox reservation instead of enabling that feature.

## Intent

A parent can finish its turn while V2 subagents continue working. It remains available for conversation and receives a new turn when a child finishes or fails. A result arriving during a user turn enters the existing input boundary, with its child identity preserved. An active goal remains active while the parent waits for a running child; automatic goal continuation defers to the child result.

## Scope

The parent remains responsible for delegated work after finishing its turn. Automatic delivery applies within the live app-server process and is best effort: exiting or restarting the process does not replay pending results or restart a child's execution. V1 agent tools keep their existing behavior.

## Patch order

1. `0001-preserve-interrupted-mailbox-work.patch` returns mail that an automatic parent turn had taken to the session queue when that turn is interrupted or replaced before its task registers, and keeps background results from waking an interrupted parent again.

## Verification

From the patched upstream checkout root:

```sh
cd codex-rs && cargo nextest run -p codex-core -p codex-protocol -E 'test(mailbox_wake_tests) | test(mailbox_retention_tests) | test(abort_empty_active_turn_preserves_pending_input)'
cd codex-rs && cargo nextest run -p codex-app-server-protocol --lib -E 'test(stable_precomputed_exports_match_schema_fixtures)'
just fmt-check
```

The core checks interrupt and replace automatic parent turns before task registration and expect every unobserved child result and unrelated input to remain queued. The schema check expects the precomputed stable exports to include the mailbox wake policy.
