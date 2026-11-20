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
| upstream `patches/llvm_rusty_v8_custom_libcxx.patch` (`MODULE.bazel:18`), `v8/use_custom_libcxx` | Custom libc++ used when V8 is built from source (`BUILD.bazel:6 rusty_v8_from_source`) | Applies to the source-built V8 path. | The Bazel lint cache seeds this path. |
| [Bazel V8 cache seed](../../.github/workflows/baseline-v8-bazel-cache.yml) and [argument-comment lint](../../.github/workflows/baseline-argument-comment-lint.yml) | Compiled V8 and ICU Bazel actions stored in private GHCR | The seed builds V8-dependent crates with the lint configuration and saves their actions. The lint workflow restores and reads that cache without writing it back. | Retain this producer and its lint consumer. |
| Patched zsh: `.github/workflows/rust-release-zsh.yml`, `.github/zsh-commit`, `scripts/codex_package/{codex-zsh,zsh-exec-wrapper.patch}` | none | Absent from both trees. They exist only on main.old (`docs/old-main-file-inventory.tsv:116,124,340,342`). Upstream has no zsh resource. It now ignores `shell_zsh_fork` and `unified_exec_zsh_fork` with the message "The patched zsh backend has been removed" (`codex-rs/core/src/config/managed_features.rs:256-260`, `core/tests/suite/deprecation_notice.rs:146-171`). | **Not needed**. Upstream removed the product behavior. |

The seed saved the archive at digest `sha256:d696641a660fc1197446f80efa594f2d258d0a9bbdcca17127952bdcd810fcec`.
The separate Bazel lint job restored that digest and passed argument-comment lint.
[Seed job](https://github.com/holdmyspot-com/codex-unleashed/actions/runs/43028587344/job/130549478517) · [Lint job](https://github.com/holdmyspot-com/codex-unleashed/actions/runs/43062905889/job/130669980651)

The archive-backed Cargo path passed on Linux x64 for both bundles in the stable release build. The Linux ARM64
app-server job configured the pinned upstream V8 archive and bindings, compiled the bundle and ran the native
executable, which reported `codex-app-server 0.167.0`. The ARM64 job later failed in the tooling action's post hook.
[Linux x64 release build](https://github.com/holdmyspot-com/codex-unleashed/actions/runs/42724961867) · [Linux ARM64 app-server job](https://github.com/holdmyspot-com/codex-unleashed/actions/runs/44765249193/job/136388485438)

Keep the upstream V8 artifact setup and the Bazel lint cache. The standalone fork V8 release, V8 canary and patched-zsh
artifact producers remain deferred because the selected release and product checks have no consumers for their outputs.

## D8 requirements

| Requirement | Status | Evidence |
|---|---|---|
| Keep caches for the newest three stable releases | Implemented | `GhcrRetentionPlan.java:34` `RETAINED_RELEASES = 3`; applied at `:243` over published, non-draft, non-prerelease stable tags. `cache-retention.yml:3` states the rule. Releases come from `openai/codex` (`:51`), retained refs feed the Actions-cache planner (`:130-142`), and `ActionsCacheRetentionPlan.java` keeps only caches of retained refs. |
| The GHCR and Actions-cache planners actually run and delete | Implemented | `cache-retention.yml:96-147` plans; `:149-176` deletes; `baseline-release-builds.yml:985` calls it after cached release builds (`:978-988`); a daily schedule runs at `:18-19` (cron `41 6 * * *`). |
| Retention-plan record kept three days | Implemented | `cache-retention.yml` `retention-days: 3` (owner decision 2026-11-10). |
| Three-day Actions artifact retention across workflows | Implemented | Owner decision 2026-11-10: every `actions/upload-artifact` step in the overlay's workflows, including `upstream-auto-release.yml`'s build-to-publish `assets-*` artifact (formerly 7 days), sets `retention-days: 3`; the earlier rule was one day. |
| No stray "two releases" wording | Implemented | A search of `.github`, `docs`, `tooling` and README found no "two stable" text. |
| Upstream workflows' artifact retention | Differs, not applicable | 41 of 43 upload steps in the upstream tree lack `retention-days` (default 90 days). They are upstream-owned workflows (rust-release, bazel, v8 and others) that the overlay does not run. Only the overlay's workflows matter. |

The scheduled prune on `main` at `808dd2e` retained the three newest stable releases in its inventory
(`rust-v0.170.0`, `rust-v0.169.1` and `rust-v0.169.0`) and the protected pins `rust-v0.163.0` and `rust-v0.167.0`.
Its receipt records 69 retained versions in `cargo-cache-main-new`, 12 in `cargo-cache` and eight retained managed
Actions caches. It records all 13 planned GHCR version deletions and all three planned Actions cache deletions.
The Linux ARM64 app-server evidence artifact expires three days after upload, within one second.
[Scheduled prune](https://github.com/holdmyspot-com/codex-unleashed/actions/runs/44561965574) · [Prune receipt](https://github.com/holdmyspot-com/codex-unleashed/actions/runs/44561965574/artifacts/14548294161) · [ARM64 evidence artifact](https://github.com/holdmyspot-com/codex-unleashed/actions/runs/44765249193/artifacts/14647892479)

The checkpoint 2 receipts cover native private-cache reuse and the handoff to a separate runner. The Linux ARM64 job
also saved verified compiled outputs using authenticated GHCR access. D8 still needs a current private cache archive
entry manifest to verify that credentials and configuration containing secrets stay outside the archive. The hosted
Bazel workflow runs without a BuildBuddy key, so BuildBuddy execution and snapshot handoff remain unverified.
[Checkpoint 2 acceptance](../evidence/checkpoint-2-acceptance.json) · [Stable cache consumer](../evidence/focused-runtime-cache-stable-consumer.json) · [Hosted Bazel workflow](../../.github/workflows/baseline-bazel-tests.yml)
