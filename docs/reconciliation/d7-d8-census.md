# D7 and D8 verification (origin/main b2c96ecb45; upstream tree a0df68dc1)

Note: origin/main is an overlay (workflows, tooling, patches, docs). It carries no MODULE.bazel, Cargo manifests, `.github/actions/setup-rusty-v8` or `scripts/codex_package`. Its workflows check out the patched upstream tree at run time and use that tree's actions.

## D7 census

| Consumer (path) | Artifact | Used by release/test builds? | Conclusion |
|---|---|---|---|
| upstream `codex-rs/code-mode-runtime/Cargo.toml:26` (`v8` with `v8_enable_sandbox`; pulled in by `code-mode-host`) and `codex-rs/v8-poc/Cargo.toml` | `v8 = "=152.2.0"` (`codex-rs/Cargo.toml:561`) | Yes. This is the only V8 dependency. | Prebuilt V8 needed |
| upstream `.github/actions/setup-rusty-v8/action.yml`, invoked from `build-cargo-package-binaries/action.yml:89`, `rust-ci-full.yml:347`, `rust-release-windows.yml:116` | `librusty_v8_*` archive and `src_binding_*` for tag `rusty-v8-v152.2.0` downloaded from **openai/codex** releases, checksums verified; sets RUSTY_V8_ARCHIVE and RUSTY_V8_SRC_BINDING_PATH | Yes. Overlay workflows call this same action: baseline-release-builds.yml:434, baseline-release-followup.yml:127, baseline-linux-tests.yml:201, baseline-macos-lints.yml:131, baseline-platform-lints.yml:161, baseline-windows-lints.yml:131, diagnose-cargo-fingerprints.yml:130, upstream-auto-release.yml:410. The overlay also caches the files (`focused-runtime-cache-setup/action.yml:26-44`, `tooling/.../V8BindingCache.java:115`, `UpstreamV8Script.java`). | Needed. Consumes upstream's published release, not a fork-built artifact. |
| upstream `MODULE.bazel:591-603, 622, 912-1051` (`v8_targets`, `rusty_v8_152_2_0_*` http_files, `rusty_v8_prebuilt_out_dir.patch`) | The same 12 archive/binding URLs under openai/codex `rusty-v8-v152.2.0` | Bazel builds. The overlay excludes `//third_party/v8:all` and `//codex-rs/v8-poc:v8-poc-unit-tests` from its Bazel test and lint targets (baseline-bazel-tests.yml:307, baseline-macos-bazel-tests.yml:250, baseline-windows-bazel-tests.yml:177, baseline-bazel-lints.yml:250). | Needed (downloaded, not built) |
| upstream `.github/workflows/rusty-v8-release.yml` (tag push `rusty-v8-v*`) | Builds and publishes `rusty-v8-vX` releases to the repository that runs it | No consumer in this fork. Every consumer URL points to openai/codex, and `gh release view rusty-v8-v152.2.0 -R openai/codex` shows 42 assets. The fork has no `rusty-v8`/`zsh` release. The overlay has no copy of this workflow. | **Not needed** |
| upstream `.github/workflows/v8-canary.yml` (called from `postmerge-ci.yml:17`) | Canary builds of V8 variants, artifacts only | Verification only. The overlay has no copy and no consumer. | **Not needed** (deferred) |
| upstream `patches/llvm_rusty_v8_custom_libcxx.patch` (`MODULE.bazel:18`), `v8/use_custom_libcxx` | Custom libc++ used when V8 is built from source (`BUILD.bazel:6 rusty_v8_from_source`) | Only when V8 is built from source, which no overlay build does. | Not needed |
| Patched zsh: `.github/workflows/rust-release-zsh.yml`, `.github/zsh-commit`, `scripts/codex_package/{codex-zsh,zsh-exec-wrapper.patch}` | none | Absent from both trees. They exist only on main.old (`docs/old-main-file-inventory.tsv:116,124,340,342`). Upstream has no zsh resource. It now ignores `shell_zsh_fork` and `unified_exec_zsh_fork` with the message "The patched zsh backend has been removed" (`codex-rs/core/src/config/managed_features.rs:256-260`, `core/tests/suite/deprecation_notice.rs:146-171`). | **Not needed**. Upstream removed the product behavior. |

Run evidence: stable release build run 42724961867 (success) ran the "Verify and configure upstream V8 artifacts" step successfully in the x86_64-unknown-linux-musl primary and app-server jobs. I did not check other targets' logs.

Conclusion: keep only the upstream-provided V8 artifact setup, which is already in use. `rusty-v8-release`, `v8-canary`, `rust-release-zsh`, `.github/zsh-commit` and the zsh wrapper have no consumer and stay omitted or deferred. The restart-scope D7 wording already allows this.

## D8 requirements

| Requirement | Status | Evidence |
|---|---|---|
| Keep caches for the newest three stable releases | Implemented | `GhcrRetentionPlan.java:34` `RETAINED_RELEASES = 3`; applied at `:243` over published, non-draft, non-prerelease stable tags. `cache-retention.yml:3` states the rule. Releases come from `openai/codex` (`:51`), retained refs feed the Actions-cache planner (`:130-142`), and `ActionsCacheRetentionPlan.java` keeps only caches of retained refs. |
| The GHCR and Actions-cache planners actually run and delete | Implemented | `cache-retention.yml:96-147` plans; `:149-176` deletes; `baseline-release-builds.yml:985` calls it after cached release builds (`:978-988`); a daily schedule runs at `:18-19` (cron `41 6 * * *`). |
| Retention-plan record kept one day | Implemented | `cache-retention.yml:184` `retention-days: 1`. |
| One-day Actions artifact retention across workflows | Implemented, one exception | 34 of 35 `actions/upload-artifact` steps in origin/main set `retention-days: 1`. Exception: `upstream-auto-release.yml:450` is `retention-days: 7` (release `assets-*` artifacts). It is the hand-off from the package build to the publish job, and the header comment (`:9-10`) describes it as 7-day. It differs from the one-day rule. Decide whether to lower it to 1, if publish always follows the build within a day, or document it as an exception. |
| No stray "two releases" wording | Implemented | A search of `.github`, `docs`, `tooling` and README found no "two stable" text. |
| Upstream workflows' artifact retention | Differs, not applicable | 41 of 43 upload steps in the upstream tree lack `retention-days` (default 90 days). They are upstream-owned workflows (rust-release, bazel, v8 and others) that the overlay does not run. Only the overlay's workflows matter. |

Undecided: only the 7-day artifact above (needs-owner-decision).
