# Baseline comparison

## Turns

User: Compare the failing Rust tests in the patched checkout with the unpatched upstream checkout.
Reuse the dependency downloads that are already available.

## Expected behavior

The agent invokes the maintained local Rust helper with the same owning project and cache name but each checkout's
absolute root. The checkouts receive different compiler output directories and share dependency download storage.
The agent does not reuse the patched checkout's Cargo target directory for the baseline, override the helper's paths,
or claim existing outputs by writing an ownership marker. A foreign ownership rejection stops child execution;
the agent selects fresh storage or removes owned outputs only after their final consumer exits.
