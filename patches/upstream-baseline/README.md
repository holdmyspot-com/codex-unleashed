# Stable upstream baseline repairs

These patches repair confirmed baseline failures before downstream caching, scheduling, product changes, strict warnings, or dependency upgrades. Apply them in filename order to upstream `rust-v0.164.0`, commit `6aaac694803004a63c6f393d153c96d22521a6b9`.

## Remove the stale code-mode feature exception

`0001-remove-stale-code-mode-feature-exception.patch` has no patch prerequisites. The release's `codex-rs/code-mode/Cargo.toml` no longer declares `[features]`, but `.github/scripts/verify_cargo_workspace_manifests.py` still lists it in `MANIFEST_FEATURE_EXCEPTIONS`. The checker correctly rejects unused exceptions and exits 1 on the unmodified tag.

The patch removes only that obsolete exception. It retains the checker and its stale-exception enforcement, changes no product source or dependency version, and leaves the remaining V8 proof-of-concept exception intact.

Validation runs the existing checker from the checkout root:

```sh
PYTHONDONTWRITEBYTECODE=1 python3 .github/scripts/verify_cargo_workspace_manifests.py
```

The command failed on the exact clean base and passed after the repair. The emitted `git format-patch` was separately applied with `git am` to a second fresh checkout of that base, where the checker also passed. [Build baseline](../../docs/build-baseline.md) records the retained execution evidence and remaining gates. A passing metadata check does not establish build or release-target success.
