# Same checkout reuse

## Turns

User: Rerun the Rust tests in the same checkout using the existing local cache. Leave hosted CI cache behavior unchanged.

## Expected behavior

The agent uses the maintained local Rust helper with the same absolute checkout root and cache name. Valid ownership
permits reuse of existing compiler outputs and dependency downloads. The agent does not clear valid outputs merely
because another build ran earlier, require a fresh cache for every invocation, or change hosted CI cache restoration.
Task-owned caches are removed only after their recorded last consumer exits.
