# Responsive history replay and `/subagents` switching

- Issue: [openai/codex#34776](https://github.com/openai/codex/issues/34776)
- [avoid-slow-agent-switching-and-resume.patch](avoid-slow-agent-switching-and-resume.patch) applies to upstream `rust-v0.159.1`, commit `8e68a98ef03cdde76d2e6800791ebdf1b3b95b24`.
- [defer-implicit-skill-index.patch](defer-implicit-skill-index.patch) applies independently to upstream `rust-v0.160.0`, commit `a956835d020762cb2b570053af06f643a11c0ecc`. It requires no other downstream patch.
- [avoid-duplicate-startup-skills-refresh.patch](avoid-duplicate-startup-skills-refresh.patch) applies independently to the same `rust-v0.160.0` base. It requires no other downstream patch.
- [background-session-skill-warmup.patch](background-session-skill-warmup.patch) applies independently to the same `rust-v0.160.0` base. It requires no other downstream patch.

## Intent

Keep session resumption responsive with many writable roots and make `/subagents` switch back to the main agent quickly when its history spans many compactions. Initial resumption renders a bounded recent history page; scrolling back retrieves older pages without deleting messages. The feature flag controls the compaction window used when switching agents. The restored conversation accepts input while skill discovery and optional skill metadata load. An early request waits for discovery before building its execution context. Startup also defers skill-invocation lookup preparation until a command first accesses a skill.

## Feature configuration

### Codex feature flag

The feature flag is stable and enabled by default:

```text
unleashed_agent_fast_switching  stable  true
```

Enable or disable the Codex feature flag with:

```text
codex features enable unleashed_agent_fast_switching
codex features disable unleashed_agent_fast_switching
```

If setting the flag in `config.toml` directly, put it under `[features]`:

```toml
[features]
unleashed_agent_fast_switching = true
```

### Project-specific `config.toml` property

None. This patch adds no project-specific property. Its feature flag belongs
under `[features]` as shown above. With the default flag enabled, agent switching replays the latest compaction window. Disabling it restores full-history replay for switches. Initial history pagination and the plain-message permission-check optimization apply independently of this flag. The three skill startup patches add no feature flag or project-specific property; their optimizations apply unconditionally.

## Reproduction

Resume a main session with several compaction windows, start a worker from
`/subagents`, then switch back to the main agent. The return switch should stay
responsive as older conversation windows accumulate. Also resume a session containing ordinary assistant messages under a restricted filesystem policy with many writable roots. Initial replay stays responsive and scrolling back reveals older messages.

## Verification

`switching_back_replays_only_latest_compacted_conversation` exercises the
`/subagents` return switch with fifty compaction windows, checks that switching
takes less than three seconds, and compares the result with a one-window
session. It also confirms that older history remains cached and that disabling
the feature restores full-history replay.

Run the focused replay, pagination, visualization, and switching checks from `codex-rs/`:

```sh
just test -p codex-tui -E 'test(replay_plain_assistant_messages_with_many_writable_roots_stays_responsive) | test(beginning_navigation_holds_the_view_until_the_last_page_arrives) | test(inline_visualization) | test(switching_back_replays_only_latest_compacted_conversation) | test(initial_resume_keeps_compacted_history_when_fast_switching_is_enabled)'
```

The checks expect replay of 32 plain assistant messages with 64 writable roots to take less than three seconds and retain every message. Pagination initially excludes older answers and retrieves them when navigating to the beginning. Visualization checks retain trusted links and reject unsafe fragments. Switching remains responsive and ordinary resumption retains loaded compaction history.

Run the TUI suite from `codex-rs/`:

```sh
just test -p codex-tui
```

The declared release-tag base contains snapshots expecting `v0.0.0`, while
its manifests report `v0.159.1`. Full-suite checks in this environment contain
73 baseline snapshot failures; an untouched checkout reproduces the same
failure list. The focused checks and formatting pass independently.

For the skill-index patch, run the skill suites from the checkout root:

```sh
just test -p codex-skills-extension -p codex-skills
```

The suites expect all tests to pass outside an injected Git-root sandbox. In this managed sandbox, `repo_ancestry_without_project_marker_does_not_walk_parents` finds the injected ancestor `.git` and fails on both untouched upstream and the patched tree. All other skill checks pass. `implicit_skill_index_resolves_aliases_on_first_use` checks first-use resolution of a scripts-directory symlink, document lookup, sharing across unchanged snapshots, and exclusion of disabled skills. The existing suites cover skill discovery, configuration isolation, invalidation, and invocation detection.

For the startup refresh patch, run its focused checks from the checkout root:

```sh
just test -p codex-tui -E 'test(startup_skills_refresh_) | test(startup_skill_load_order_preserves_runtime_error_recurrence)'
```

The checks expect one background startup refresh and no duplicate blocking request for the first session in the same directory. Later session configurations, different directories, and explicit user refreshes still request fresh metadata. A background failure reaches the normal error display. Skill mentions remain available whether metadata arrives before or after session configuration. The full `just test -p codex-tui` command above covers retained history, startup input, warning aggregation, thread switching, and directory changes.

For the `rust-v0.160.0` base, the full TUI suite in this managed environment includes 75 baseline snapshot and fixture failures. The startup patch retains that exact failure list; the focused startup checks pass. No unrelated snapshot expectations or fixture behavior change.

For the background warmup patch, run its focused checks from the checkout root:

```sh
just test -p codex-core --lib -E 'test(resume_warmup_tests)'
```

The checks expect session availability while skill discovery is paused, an early execution context to wait for discovery, and resume/fork reconstruction to avoid skill scans. Execution still discovers skills. Cancelling a waiting context preserves the warmup for a later request, and a failed warmup retains normal execution discovery.

For watcher lifecycle checks, run:

```sh
just test -p codex-app-server -E 'test(late_skill_watches_) | test(cold_resume_refreshes_skill_metadata_after_watches_are_ready) | test(skills_changed_notification_is_emitted_after_skill_change) | test(projects_persist_and_assign_threads) | test(assigned_forks_inherit_projects_for_persistent_and_ephemeral_children)'
```

These checks expect only the active listener generation to accept a registration, and a closed listener to reject a late result.

`cold_resume_refreshes_skill_metadata_after_watches_are_ready` expects a cold
resume to publish its response before refreshing metadata when watches are
ready, then deliver normal notifications for subsequent skill edits. New
sessions and forks retain watch registration before their responses.

Run the affected core and app-server suites from the checkout root:

```sh
just test -p codex-core -p codex-app-server
```

The integration suites require the standalone `codex-code-mode-host` and
`codex-linux-sandbox` binaries in the Cargo target directory, as in the upstream
workspace test build. The public thread start/fork checks also verify that
background watch registration preserves response ordering.

In this managed environment, the affected suites include 311 failures also
reproduced on untouched upstream. The tool-history retention stress check
exceeds the local profile's 60-second deadline during the concurrent suite;
isolated runs pass on upstream and the patched test binary. The focused resume
and watch checks pass. Static checks report the same three unused imports as
upstream, in `tools/registry.rs`, `openai_file_mcp.rs`, and `scenarios.rs`.

Run the affected static checks from the checkout root:

```sh
just clippy -p codex-core -p codex-app-server
```

## Implementation

The background warmup patch retains plugin setup required by hooks and MCP. The session owns a background skill warmup; execution and startup prewarm contexts await it before their normal configuration-specific discovery. History restoration captures model metadata without discovering plugins or skills. The worker logs skill errors and uses the session's shutdown signal. Cold client resumes register skill watches alongside normal commands and events. Registration waits for the resume response and restored-state notifications before invalidating cached metadata and sending a refresh notification. That refresh covers changes before watches are ready. Results from a closed or replaced listener are discarded. New sessions and forks retain synchronous watch registration; later file changes use the existing watcher notifications. Cancelling a waiting request keeps the warmup handle available to the next request. Releasing the session's handle aborts unfinished work. No warmup result persists across process restarts.

The startup refresh patch starts the app's existing background metadata request before projecting the initial session. The widget consumes its directory marker on the first session configuration. A matching session retains any metadata already delivered and skips the redundant blocking request. Subsequent configurations and directory changes keep the existing refresh path. Background results, failures, and stale-directory responses use the existing app event handlers.

The skill-index patch keeps skill discovery and enabled-skill configuration in startup. It builds the derived implicit-invocation index once on first lookup, resolving document and scripts-directory aliases then. Unchanged snapshot clones share the index. A changed disabled-skill selection creates a separate index. This patch changes no conversation loading, filesystem permission checks, or persistent caches.

Completed and replayed plain markdown skips visualization-context construction. Messages containing visualization directives or content references use the existing filesystem permission and fragment validation. Live streams construct their context before later chunks arrive. Plain transcript pages also skip context construction. No filesystem permission result is cached across messages or configuration changes.

Upstream owns bounded initial history hydration and older-page cursors in this base; this patch preserves that path and extends its navigation regression test.

## Formatting

Run `just fmt-check` from the checkout root. The command checks Rust, Just, Bazel/Starlark, and Python formatting.

## Upstream status

This downstream patch applies to the declared upstream base above. The canonical issue tracks slow agent switching; the patches address history replay and skill preparation on the resume startup path.
