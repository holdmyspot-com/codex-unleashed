# Nonfatal baseline check policy

Base: upstream `rust-v0.164.0`, commit `6aaac694803004a63c6f393d153c96d22521a6b9`.

`0001-nonfatal-argument-lints.patch` implements the plan's nonfatal warning policy for the Bazel argument-comment-lint aspect. Upstream explicitly promotes `argument-comment-mismatch` and `uncommented-anonymous-literal-argument` to errors. The patch selects their warning levels and renames the internal flag list to match. Both diagnostics remain enabled and visible. It changes no Rust product source, dependency, feature or build profile.

The patch has no prerequisites. It also applies after the independent checker/formatter repairs in `../upstream-baseline`. Apply it only to the checkout used for baseline Bazel argument-linter validation. Native Cargo build, test and Clippy workflows do not consume this policy directory. Stop applying it when step 6 introduces strict diagnostics.

The emitted `git format-patch` was applied with `git am` to a fresh exact base; the resulting aspect matches the authored version. Both verification worktrees were removed. Bazel 9.2.0 `aquery` with `--config=argument-comment-lint` confirms the actual CLI lint action receives both `-W` flags. Full-repository execution through upstream's maintained `list-bazel-targets.sh` and CI wrapper passes across 1062 targets, including its manual unit-test targets and semantic exclusions. Bazel reports 1853.272 seconds elapsed and 1262.83 seconds for the critical path; earlier baseline work supplies ordinary within-checkout cache hits. Source diff snapshots remain identical before/after. Compiler/linker warnings remain visible. This verifies the Linux repository check, not other platform checks or the later strict-warning policy.
