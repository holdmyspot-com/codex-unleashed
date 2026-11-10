# Patch rows 1-72 reconciliation

Base: origin/main b2c96ecb45 (restart-scope.md identical to the worktree copy); upstream tree: upstream-rama-port (base dddc3c0a79 + queue). Flagged rows `zzzz-0007-ci-msvc-runtime-license` (row ~87) and `zip-security-update` (last row) are in the other half, not covered here; `upgrade-zz-seatbelt-formatting` is row 57 and is covered.

| # | old patch | recorded disposition | current disposition | evidence | changed? |
|---|---|---|---|---|---|
| 1 | `background-terminal-transcript-hint` | not migrated (step-4 candidate) | migrated: queue #11 `background-terminal-transcript-hint.patch` (patches/README.md) | README order #11; patch file in origin/main | yes |
| 2 | `base-configuration/codex-unleashed-configuration` | not migrated (step-4 prerequisite) | migrated: queue #4 `codex-unleashed-configuration.patch` (patches/README.md) | README #4; all 3 old paths covered | yes |
| 3 | `branding/add-branding-and-build-metadata` | not migrated | migrated: queue #17 `add-branding-and-build-metadata.patch` (patches/README.md) | README #17; 10/10 old paths covered | yes |
| 4 | `branding/daemon-public-releases` | not migrated | migrated: queue #19 `daemon-public-releases.patch` (patches/README.md) | README #19; 10/10 old paths covered | yes |
| 5 | `branding/fix-branding-test-snapshots` | not migrated (test-only companion) | migrated, folded into add-branding-and-build-metadata.patch (54/58 old paths) and warn-on-openai-app-server.patch (1); 3 residual paths not matched by any queue patch | path overlap of old diff paths with queue patches; residual 3 not individually checked | yes |
| 6 | `branding/warn-on-openai-app-server` | not migrated | migrated: queue #18 `warn-on-openai-app-server.patch` (patches/README.md) | README #18; 6/9 old paths direct, rest in add-branding | yes |
| 7 | `collapsed-tool-activity` | not migrated | migrated: queue #6 `collapsed-tool-activity.patch` (patches/README.md) | README #6; 31/31 paths | yes |
| 8 | `issue-34776/avoid-duplicate-startup-skills-refresh` | not migrated | migrated: queue #8 `avoid-duplicate-startup-skills-refresh.patch` (patches/README.md) | README #8; 6/6 paths | yes |
| 9 | `issue-34776/avoid-slow-agent-switching-and-resume` | not migrated | migrated: queue #10 `avoid-slow-agent-switching-and-resume.patch` (patches/README.md) | README #10; 14/14 paths | yes |
| 10 | `issue-34776/background-session-skill-warmup` | not migrated | migrated: queue #9 `background-session-skill-warmup.patch` (patches/README.md) | README #9; 11/11 paths | yes |
| 11 | `issue-34776/defer-implicit-skill-index` | not migrated | migrated: queue #7 `defer-implicit-skill-index.patch` (patches/README.md) | README #7; 2/2 paths | yes |
| 12 | `issue-35/0001-preserve-interrupted-mailbox-work` | not migrated | migrated: queue #12 `issue-35/0001-preserve-interrupted-mailbox-work.patch` (patches/README.md) | README #12; 12/12 paths | yes |
| 13 | `issue-35/0002-resume-parent-on-subagent-completion` | not migrated | migrated: queue #13 `issue-35/0002-resume-parent-on-subagent-completion.patch` (patches/README.md) | README #13; 44/45 paths (1 other in 0003) | yes |
| 14 | `issue-35/0003-defer-goal-continuation-to-live-children` | not migrated | migrated: queue #14 `issue-35/0003-defer-goal-continuation-to-live-children.patch` (patches/README.md) | README #14; 5/5 paths | yes |
| 15 | `issue-47/bash-snapshot-replay-startup` | not migrated; draft removed from active tree | migrated: queue #1 `issue-47/bash-snapshot-replay-startup.patch` (patches/README.md) | README #1; patch present in origin/main; 3/3 paths | yes |
| 16 | `issue-47390/expanded-diff-previews` | not migrated (step-4 candidate) | migrated: queue #5 `expanded-diff-previews.patch` (patches/README.md) | README #5; 12/12 paths | yes |
| 17 | `issue-48/wsl-explicit-pid-procfs` | not migrated (step-4 candidate) | not migrated | no queue patch touches linux-sandbox bwrap.rs; upstream-rama-port bwrap.rs still masks /proc for WSL without the `!inherit_pid_namespace` guard (lines ~351-356) | no |
| 18 | `issue-49/descriptor-fallback-fixture` | not migrated (step-4 candidate) | migrated: queue #3 `issue-49/descriptor-fallback-fixture.patch` (patches/README.md) | README #3; both old paths covered | yes |
| 19 | `issue-50/composer-platform-fixture` | not migrated | not migrated | no queue overlap; footer_state.rs:92 still calls `is_probably_wsl()` in tests | no |
| 20 | `issue-51/kitty-file-reference-assertion` | not migrated | not migrated | no queue overlap; tui/src/pets/mod.rs:375 still has `!output.contains("cG5n")` | no |
| 21 | `issue-52/brokered-startup-completion-fixture` | not migrated | not migrated | no queue overlap; approvals.rs:3570 test unchanged | no |
| 22 | `issue-53/metadata-message-budget-scheduling` | not migrated (step-3 scheduling) | not migrated | nextest.toml has no message_budget/threads-required override; nextest-local-profile.patch touches the file for an unrelated key | no |
| 23 | `issue-54/updater-executable-handoff` | not migrated | migrated: queue #20 `issue-54/updater-executable-handoff.patch` (patches/README.md) | README #20; 6/6 paths | yes |
| 24 | `issue-55/cli-version-identity` | not migrated (step-4 companion to branding) | not migrated | add-branding patch has no `name = "codex-cli"`; cli/src/main.rs:130-136 still version=CLI_VERSION, bin_name="codex" only | no |
| 25 | `issue-55/schema-test-data` | not migrated (conditional) | not migrated | config-schema/BUILD.bazel unchanged (no test_data_extra/CARGO_MANIFEST_DIR); Cargo.lock overlap is incidental | no |
| 26 | `issue-56/unused-core-test-imports` | not migrated; two hunks split | split: (a) body_json import in openai_file_mcp.rs: upstream-equivalent, import absent at base dddc3c0a79 (only body_partial_json at line 47); (b) ReasoningEffort import in scenarios.rs: not applicable, still used at scenarios.rs:1333 (recorded as unsuitable) | git show dddc3c0a79 grep of matchers; scenarios.rs:1333 | yes |
| 27 | `issue-57/app-server-time-provider-initialization` | not migrated (step-5) | not migrated | message_processor.rs:390-392 still binds `let time_provider` then `Some(time_provider)`; rust-1.99.patch touches the file only for an unrelated wildcard fix | no |
| 28 | `issue-58/seatbelt-metadata-order-fixtures` | not migrated (step-4) | not migrated | seatbelt_tests.rs:1962-1976 still orders .codex/.git/.agents; rust-1.99.patch hunks in that file are unrelated `&` drops | no |
| 29 | `issue-59/update-yanked-spin` | not migrated (step-7 candidate) | migrated via dependency-upgrades (compatible-updates.patch etc.): Cargo.lock in upstream-rama-port has spin 0.9.9 | upstream-rama-port codex-rs/Cargo.lock:14793-14794; dependency-upgrades/README intent | yes |
| 30 | `issue-60/stable-build-toolchains` | not migrated; deferred to D9 | split: Rust toolchain + the 44 Rust-1.99 source adaptations migrated (rust-toolchain/rust-1.99.patch, pins 1.99.1, queue #28); bubblewrap network.c migrated (release-build-warnings/bubblewrap-netlink-initializers.patch, #22); Bazel 9.2->9.3 and other build-module upgrades NOT migrated (.bazelversion 9.2.0; only platforms/rules_cc via bazel-module-versions.patch #24) | 44/49 old paths in rust-1.99.patch; rust-toolchain/README says D9 allowed this prerequisite earlier | yes |
| 31 | `issue-61/codespell-isolated-tool-environment` | not migrated (D10) | not migrated | upstream-rama-port .github/workflows/codespell.yml still uses codespell-project/actions-codespell | no |
| 32 | `issue-61/formatter-warning-policy` | not migrated (step 6) | not migrated | no queue patch carries formatter warning enforcement (single path overlap with rust-1.99 is incidental workflow pin) | no |
| 33 | `issue-61/icu-mutex-thread-safety` | deferred dependency-source | deferred (unchanged) | no nested ICU patch in upstream patches/ or MODULE.bazel; restart-scope 'never contain dependency copies' rule in patches/README.md | no |
| 34 | `issue-61/libcxx-explicit-runtime-configuration` | not migrated (step-5) | not migrated | no queue overlap; _LIBCPP defines absent | no |
| 35 | `issue-61/native-and-rust-warning-policy` | not migrated (step 6) | partially migrated: Rust warning policy (workspace deny + Bazel `-Dwarnings` in .bazelrc) in fatal-warnings/deny-workspace-warnings.patch (#25); native compiler/linker feature, bindgen/rustdoc Bazel flags and regression fixtures not migrated (Bazel linker_messages intentionally not denied per its README) | deny-workspace-warnings.patch touches .bazelrc and Cargo lint table; .cargo/config.toml overlap with rust-1.99 | yes |
| 36 | `issue-61/native-aws-lc-windows-tls` | deferred dependency-source | deferred (unchanged) | MODULE.bazel selects aws-lc 5.1.0.bcr.1 with no override patch | no |
| 37 | `issue-61/native-bindgen-preprocessor-policy` | not migrated | not migrated | no rules_rs bindgen patch in patches/ (only the 5 upstream rules_rs_* patches) | no |
| 38 | `issue-61/native-mingw-function-guard` | not migrated; split | not migrated (both parts) | patches/llvm_windows_mingw_compat.patch is upstream's own; no F_X86_NATIVE guard patch or Werror import-lib change registered | no |
| 39 | `issue-61/native-prost-rule-compatibility` | deferred backport | deferred (unchanged) | no grep-includes rules_rust patch registered | no |
| 40 | `issue-61/native-voice-cmake-initial-cache` | not migrated (step 5) | not migrated | no queue overlap | no |
| 41 | `issue-61/native-voice-meson-resource-directory` | not migrated (conditional) | not migrated | no queue overlap | no |
| 42 | `issue-61/native-voice-warning-diagnostics` | not migrated | not migrated | no queue overlap | no |
| 43 | `issue-61/native-voice-warning-video-2-enums` | deferred functional dependency-source | deferred (unchanged) | no queue overlap | no |
| 44 | `issue-61/native-voice-z-sdk-relocation` | not migrated; split | not migrated (both parts) | no queue overlap | no |
| 45 | `issue-61/native-voice-z-sdk-sandbox-prefix` | not migrated | not migrated | only MODULE.bazel/lock noise overlap | no |
| 46 | `issue-61/native-voice-z-symbol-tools` | not migrated (conditional) | not migrated | no queue overlap | no |
| 47 | `issue-61/remote-download-options` | not migrated; split | not migrated | upstream-rama-port .bazelrc:65 `--remote_download_toplevel`, :115 `--remote_download_minimal` still present | no |
| 48 | `issue-61/remove-empty-test-placeholder` | not migrated (pinned upstream retains placeholder) | upstream-equivalent: core/tests/suite/scenarios_read_only_mcp.rs does not exist at current base dddc3c0a79 (verified with git cat-file); upstream moved since the recorded pin | git cat-file -e dddc3c0a79:codex-rs/core/tests/suite/scenarios_read_only_mcp.rs fails | yes |
| 49 | `issue-61/stale-dependency-policy` | not migrated; split steps 5-7 | not migrated for the old hunks: deny.toml still has RUSTSEC-2024-0320 (line 80), audit.toml:8 and `"OpenSSL"` (line 147); cargo-deny.yml has no --deny flags. Unrelated: latest-releases.patch removed the RUSTSEC-2024-0449 exception | grep of upstream-rama-port deny.toml / cargo-deny.yml | no |
| 50 | `issue-61/typescript-lint-warning-policy` | not migrated | not migrated | sdk/typescript/package.json:38-39 lint scripts lack --max-warnings=0 | no |
| 51 | `issue-61/upgrade-llvm-23-windows-driver` | not migrated; mixed | not migrated | MODULE.bazel: llvm 0.8.11, apple_support 2.1.0 unchanged; rules_cc now 0.2.20 (was 0.2.18 when recorded; old patch needs 0.2.25) | no |
| 52 | `issue-61/upgrade-llvm-23-windows-static-def-parser` | not migrated (deferred rules_cc internal) | deferred (unchanged) | rules_cc override list has only rules_cc_windows_native_llvm.patch | no |
| 53 | `issue-61/upgrade-native-pkgconf-bootstrap` | not migrated; mixed | not migrated | MODULE.bazel still uses pkgconfig_src from foreign_cc_tools (line 207); rules_foreign_cc 0.15.1 with only make_* patches | no |
| 54 | `issue-61/upgrade-native-rust-runtime-selection` | deferred dependency-internal | deferred (unchanged) | no queue overlap; no rules_rust_unwind_driver_selection patch | no |
| 55 | `issue-61/upgrade-openssl` | not migrated (step-7) | partially migrated: Cargo side moved (openssl 0.10.82 in Cargo.lock via dependency-upgrades patches); Bazel openssl module still 3.5.4.bcr.0 (MODULE.bazel:494), so the Bazel module/link-input part is not migrated | upstream-rama-port Cargo.lock:11216-11217; MODULE.bazel:494 | yes |
| 56 | `issue-61/upgrade-z-native-tools` | not migrated; mixed | not migrated | rules_foreign_cc 0.15.1, bazel_skylib 1.9.0 unchanged; only incidental lock overlap | no |
| 57 | `issue-61/upgrade-zz-seatbelt-formatting` | No migration needed; pinned upstream already passes `.display()` directly (seatbelt_tests.rs:2034,2072,2110) | migrated: rust-toolchain/rust-1.99.patch (queue #28) carries the three `&x.display()` -> `x.display()` hunks in sandboxing/src/seatbelt_tests.rs (config_toml, pre_commit_hook, allowed_file) for Clippy useless_borrows_in_formatting. The recorded 'upstream-equivalent' claim was wrong: the base had the `&` (the patch's own context shows `-&config_toml.display()`) | rust-1.99.patch hunks @@ -2031, -2069, -2107; README lists macOS lint run 42649792550 | yes |
| 58 | `issue-61/upgrade-zzz-make-getopt-prototypes` | deferred GNU Make backport | deferred (unchanged) | only rules_foreign_cc_make_cppflags/xcompile/msvc_stdint patches registered | no |
| 59 | `issue-61/vendored-native-build-script-modes` | not migrated (conditional step 5) | not migrated | only tikv-jemalloc-sys_dependency_flags/make_env patches; no libgit2-sys annotation | no |
| 60 | `issue-61/z-llvm-assembly-action-flags` | deferred | deferred (unchanged) | no queue overlap beyond lock noise | no |
| 61 | `issue-61/z-meson-setup-diagnostics` | not migrated (conditional) | not migrated | no queue overlap | no |
| 62 | `issue-61/z-opus-ninja-executable` | deferred D9 | deferred (unchanged) | no queue overlap | no |
| 63 | `issue-61/z-opus-search-directories` | deferred backport | deferred (unchanged) | no queue overlap | no |
| 64 | `issue-61/z-python-explicit-initializers` | deferred D9 | deferred (unchanged) | only .bazelrc overlap via deny-workspace-warnings.patch (-Dwarnings), unrelated to the Python initializer flag | no |
| 65 | `issue-61/z-v8-python-platforms` | superseded / deferred | unchanged | no queue overlap beyond lock noise | no |
| 66 | `issue-61/z-xz-windows-arm64-filter` | not migrated; defer to step 7 | not migrated | patches/xz_windows_stack_args.patch is upstream's; no arm64-filter change | no |
| 67 | `issue-61/zz-abseil-windows-compiler-selection` | not migrated; deferred | deferred (unchanged) | only upstream abseil_windows_gnullvm_thread_identity.patch registered | no |
| 68 | `issue-61/zz-native-tool-executable-configuration` | deferred | deferred (unchanged) | no queue overlap | no |
| 69 | `issue-61/zz-native-z-hermetic-python-bootstrap` | not migrated | not migrated | no bootstrap_impl setting; .bazelrc overlap is only -Dwarnings | no |
| 70 | `issue-61/zz-openssl-zz-gnu-clang-selection` | not migrated; deferred D9 | deferred (unchanged) | Bazel openssl module remains 3.5.4.bcr.0 | no |
| 71 | `issue-61/zz-v8-clang-mingw-detection` | deferred | deferred (unchanged) | upstream v8_source_portability.patch is upstream's own | no |
| 72 | `issue-61/zz-v8-windows-stack-assembly` | not migrated; covered by selected upstream | upstream-equivalent per recorded evidence (unchanged) | no queue overlap | no |

Changed: 25 yes, 47 no.
