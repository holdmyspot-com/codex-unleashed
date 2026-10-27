# Improve CAT troubleshooting on remote runners

## Design Goals

- CAT uses a retained remote worker when its environment is needed to diagnose a failure, with evidence of connection, execution, incremental reuse and cleanup.
- CAT preserves valid completed work and the original failure while diagnosing the cause, and verifies the required clean result afterward.
- The common guidance works across remote execution providers; provider-specific setup remains separate.

## Prompt

Update CAT to support troubleshooting on retained remote runners. Inspect the existing CI debugging, execution lifecycle, temporary-resource and incremental-progress guidance first; amend the responsible shared rules rather than adding overlapping instructions.

When a failure depends on a remote environment, prefer preserving and inspecting that same worker over repeatedly launching fresh workers. First determine whether the provider supports keeping the worker alive after a failed step, before teardown. Use an available authenticated debugging facility or a restricted SSH session when authorized. Reuse supported facilities before building new infrastructure. Keep provider-specific commands separate from the common procedure; do not make GitHub Actions or any particular SSH relay a requirement.

Record the worker and run identities, exact failing command, revision, toolchain, configuration, logs and reusable output locations. Keep the worker alive before destructive cleanup; publish connection information through an explicit handoff. The controller must own the session, its deadline and cleanup. A session must not expose unauthenticated access or publish private credentials. Distinguish failure of a step from termination of its worker: a destroyed or expired worker cannot be reconnected to. When retention is unavailable, preserve diagnostic artifacts and valid partial outputs and use the smallest reproducible replacement environment.

Connect, inspect the actual failure, identify its root cause, then apply one focused change and retry the affected work incrementally. Preserve unaffected completed outputs. Record which work was reused and which changed inputs required invalidation. Do not clean before each retry. Do not patch source or tests merely to accommodate an unexplained environment mismatch. Once the incremental attempt passes, run the clean verification required by the task, retaining separate results. Remote debugging does not turn the original failed attempt into a passing one.

Prove the behavior with the cheapest representative test: retain a worker after a controlled failure; reject an unauthorized client; connect with the authorized client; verify pre-failure output still exists; retry unfinished work on that worker; then end the session and verify cleanup and faithful failure reporting. Connection setup alone does not prove successful remote execution or incremental reuse. Also cover timeout/no connection and unavailable retention without inventing a successful outcome. Exercise each claimed platform or provider boundary; reuse existing evidence for unchanged behavior.

Deliver the focused rule/tooling changes, their regression evidence, supported capabilities and remaining limitations. Continue independent authorized work while awaiting remote results. Avoid repeated expensive builds when a small diagnostic establishes the same fact.
