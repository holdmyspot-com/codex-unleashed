# Reserve Nextest execution resources for expensive budget tests

- Issue: [https://github.com/holdmyspot-com/codex-unleashed/issues/53](https://github.com/holdmyspot-com/codex-unleashed/issues/53)
- Applies to: upstream `openai/codex` `rust-v0.160.0`, commit `a956835d020762cb2b570053af06f643a11c0ecc`

## Intent

Large-message serialization, image resizing, and retained tool-call history checks pass separately but can exhaust the existing deadline while competing with the integration suite. Reserve the Nextest execution pool for these measured workloads without changing deadlines, retries, inputs, budget limits, or assertions. Ordinary cases retain their existing scheduling.

## Scope and reproduction

This patch repairs the baseline behavior exercised by the focused check. It applies independently to the declared upstream base and introduces no feature flag or project configuration property. The regression preserves the command result, security boundary, or presentation assertions relevant to this issue.

## Verification

Apply the complete release queue to the declared upstream base, then run from
the patched upstream checkout root:

```sh
just test --features codex-v8-poc/sandbox -E 'test(message_budget_sheds_inventory_without_changing_tool_results_or_history) | test(detail_policies_apply_the_expected_budgets) | test(executed_tool_call_recorder_bounds_retained_history_and_keeps_latest_calls)'
just fmt-check
just clippy --features codex-v8-poc/sandbox
just test --features codex-v8-poc/sandbox
```

All selected checks expect to pass. The sandbox feature matches the Linux V8 build used by the release verification environment. Verify the emitted patch in a fresh checkout of the declared base; the complete release queue also requires the maintained workspace checks.

## Upstream status

The issue tracks this downstream baseline repair against [upstream 0.160.0](https://github.com/openai/codex/releases/tag/rust-v0.160.0).
