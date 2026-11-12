# Background subagent completion

- Issue: [holdmyspot-com/codex-unleashed#35](https://github.com/holdmyspot-com/codex-unleashed/issues/35)
- Applies to: upstream `openai/codex` tag `rust-v0.167.0`, commit `086ccc833bc9cf01024ad2dd6af7dbd421b6bf9a`.
- Prerequisites: the release queue through `issue-34776/avoid-slow-agent-switching-and-resume.patch`, which registers a neighboring feature in the shared configuration schema.
- Upstream status: upstream's `wake_on_agent_messages` feature (under development, default off) wakes only a root thread and keeps child completion mail queue-only. It does not preserve mail across an interrupted task start or defer goal continuation to running children, so this series builds on upstream's mailbox reservation instead of enabling that feature.

## Intent

A parent can finish its turn while V2 subagents continue working. It remains available for conversation and receives a new turn when a child finishes or fails. A result arriving during a user turn enters the existing input boundary, with its child identity preserved. An active goal remains active while the parent waits for a running child; automatic goal continuation defers to the child result.

## Scope

The parent remains responsible for delegated work after finishing its turn. Automatic delivery applies within the live app-server process and is best effort: exiting or restarting the process does not replay pending results or restart a child's execution. V1 agent tools keep their existing behavior.

## Feature configuration

### Codex feature flag

The `unleashed_background_subagents` feature is stable and enabled by default. Its configuration key is `[features].unleashed_background_subagents`:

```sh
codex --enable unleashed_background_subagents
codex --disable unleashed_background_subagents
```

The feature takes effect only with V2 collaboration, which upstream's `multi_agent_v2` flag selects and which stays disabled by default:

```sh
codex --enable multi_agent_v2 --enable unleashed_background_subagents
```

Disabling the feature keeps child results as queued mail and removes the automatic-resumption guidance. It does not stop running children.

### Project-specific `config.toml` property

None. This series adds no `[codex_unleashed]` property.

## Patch order

1. `0001-preserve-interrupted-mailbox-work.patch` returns mail that an automatic parent turn had taken to the session queue when that turn is interrupted or replaced before its task registers, and keeps background results from waking an interrupted parent again.
2. `0002-resume-parent-on-subagent-completion.patch` adds the feature flag. A child's completion or failure then starts a turn on an idle parent, and the spawn guidance tells the model it may finish its turn while delegated work runs. Fixtures that drive parent turns explicitly disable the feature, except the Guardian goal fixture, whose mock follows the user's message and answers wake turns.
3. `0003-defer-goal-continuation-to-live-children.patch` keeps automatic goal continuation from starting a parent turn while a direct V2 child is running.

## Reproduction

With V2 collaboration active, delegate work that outlasts the parent's turn. Let the parent finish that turn and answer an unrelated question while the child runs. When the child completes, the parent starts a new turn without another user prompt. A child failure also reaches the parent; interrupting the child produces no completion turn.

## Verification

From the patched upstream checkout root:

```sh
cd codex-rs && cargo nextest run -p codex-core -p codex-protocol -p codex-features -E 'test(background_subagent_) | test(mailbox_wake_tests) | test(mailbox_retention_tests) | test(abort_empty_active_turn_preserves_pending_input) | test(multi_agent_v2_followup_task_completion_notifies_parent_on_every_turn)'
cd codex-rs && cargo nextest run -p codex-core -p codex-code-mode-host -p codex-rmcp-client -E 'test(suite::scenarios::)'
cd codex-rs && cargo nextest run -p codex-app-server -E 'test(app_server_resumes_parent_after_background_child_finishes) | test(thread_goal_lifecycle_emits_analytics_and_clear_deletes_goal) | test(v2_child_idle_eviction_preserves_mail_and_followup_context) | test(worker_guardian_sees_goal_set_after_spawn)'
cd codex-rs && cargo nextest run -p codex-app-server-protocol --lib -E 'test(stable_precomputed_exports_match_schema_fixtures)'
cd codex-rs && cargo nextest run -p codex-core -E 'test(config_schema_matches_fixture)'
just fmt-check
```

The core checks interrupt and replace automatic parent turns before task registration and expect every unobserved child result and unrelated input to stay queued. They also expect completed and failed children to resume an idle parent, results during a user turn to arrive at an input boundary, and queue-only delivery when the feature is disabled. The scenario snapshots include the spawn guidance. The app-server checks drive public JSON-RPC turns: the parent answers prompts while its child runs, its goal stays active, and the child's completion resumes it. The schema checks expect the configuration schema to list the feature and the precomputed exports to include the mailbox wake policy.
