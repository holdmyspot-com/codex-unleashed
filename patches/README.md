# Patch queue

Downstream patches to upstream `openai/codex`, generated with `git format-patch`. Each issue directory has a README that names the patch's upstream base, its prerequisites and its verification commands.

A patch never contains a copy of a dependency, whole or vendored, even one changed by a single line. When a fix needs a dependency changed and no release provides it, the dependency stays at its released version and the finding is recorded as accepted (see "Deferred dependency concerns" in [restart-scope.md](../docs/plans/restart-scope.md)).

The queue sits on upstream `openai/codex` tag `rust-v0.171.0` (commit `ccf9be8cc458bd6b8336fe84d68d8893f2691a5e`). Each patch carries the files its own change regenerates (schemas, TypeScript bindings, `config.schema.json`, the Python SDK models, `MODULE.bazel.lock`); there is no separate regenerated-outputs patch.

`MODULE.bazel.lock` is stale from `rust-toolchain/rust-1.99.patch` onward: that patch and the `dependency-upgrades` patches change `codex-rs/Cargo.lock`, and `dependency-upgrades/compatible-updates.patch`, `dependency-upgrades/transitive-updates.patch` and `release-reproduction/aws-lc-c-standard.patch` change `MODULE.bazel`, without regenerating it. Regenerate it once on the full queue with `baseline-platform-lints.yml` and `generators="bazel-lock"`, and add the result as a patch.

After the last patch, the release sets the workspace packages in `codex-rs/Cargo.lock` to the version in `codex-rs/Cargo.toml` with `codex-tooling align-workspace-lock --source-root ROOT`, which commits the change, so that Cargo neither rewrites the lockfile nor rejects a `--locked` build. Test and lint workflows that rewrite the manifest to `0.0.0` skip this step because the lockfile already records `0.0.0`.

The release applies these patches in this order:

1. [issue-47/bash-snapshot-replay-startup.patch](holdmyspot-com/codex-unleashed/issue-47/bash-snapshot-replay-startup.patch)
2. [winget-daemon-fixture/create-codex-home.patch](holdmyspot-com/codex-unleashed/winget-daemon-fixture/create-codex-home.patch)
3. [issue-49/descriptor-fallback-fixture.patch](holdmyspot-com/codex-unleashed/issue-49/descriptor-fallback-fixture.patch)
4. [base-configuration/codex-unleashed-configuration.patch](holdmyspot-com/codex-unleashed/base-configuration/codex-unleashed-configuration.patch)
5. [issue-47390/expanded-diff-previews.patch](holdmyspot-com/codex-unleashed/issue-47390/expanded-diff-previews.patch)
6. [collapsed-tool-activity/collapsed-tool-activity.patch](holdmyspot-com/codex-unleashed/collapsed-tool-activity/collapsed-tool-activity.patch)
7. [issue-34776/avoid-duplicate-startup-skills-refresh.patch](holdmyspot-com/codex-unleashed/issue-34776/avoid-duplicate-startup-skills-refresh.patch)
8. [issue-34776/background-session-skill-warmup.patch](holdmyspot-com/codex-unleashed/issue-34776/background-session-skill-warmup.patch)
9. [issue-34776/avoid-slow-agent-switching-and-resume.patch](holdmyspot-com/codex-unleashed/issue-34776/avoid-slow-agent-switching-and-resume.patch)
10. [background-terminal-transcript-hint/background-terminal-transcript-hint.patch](holdmyspot-com/codex-unleashed/background-terminal-transcript-hint/background-terminal-transcript-hint.patch)
11. [issue-35/0001-preserve-interrupted-mailbox-work.patch](holdmyspot-com/codex-unleashed/issue-35/0001-preserve-interrupted-mailbox-work.patch)
12. [issue-35/0002-resume-parent-on-subagent-completion.patch](holdmyspot-com/codex-unleashed/issue-35/0002-resume-parent-on-subagent-completion.patch)
13. [issue-35/0003-defer-goal-continuation-to-live-children.patch](holdmyspot-com/codex-unleashed/issue-35/0003-defer-goal-continuation-to-live-children.patch)
14. [issue-70/readable-commands.patch](holdmyspot-com/codex-unleashed/issue-70/readable-commands.patch)
15. [branding-and-app-server-distribution/add-branding-and-build-metadata.patch](holdmyspot-com/codex-unleashed/branding-and-app-server-distribution/add-branding-and-build-metadata.patch)
16. [branding-and-app-server-distribution/warn-on-openai-app-server.patch](holdmyspot-com/codex-unleashed/branding-and-app-server-distribution/warn-on-openai-app-server.patch)
17. [branding-and-app-server-distribution/daemon-public-releases.patch](holdmyspot-com/codex-unleashed/branding-and-app-server-distribution/daemon-public-releases.patch)
18. [issue-54/updater-executable-handoff.patch](holdmyspot-com/codex-unleashed/issue-54/updater-executable-handoff.patch)
19. [release-build-warnings/debug-only-release-items.patch](holdmyspot-com/codex-unleashed/release-build-warnings/debug-only-release-items.patch)
20. [release-build-warnings/bubblewrap-netlink-initializers.patch](holdmyspot-com/codex-unleashed/release-build-warnings/bubblewrap-netlink-initializers.patch)
21. [release-build-warnings/nextest-local-profile.patch](holdmyspot-com/codex-unleashed/release-build-warnings/nextest-local-profile.patch)
22. [fatal-warnings/deny-workspace-warnings.patch](holdmyspot-com/codex-unleashed/fatal-warnings/deny-workspace-warnings.patch)
23. [release-build-warnings/rmcp-client-doc-links.patch](holdmyspot-com/codex-unleashed/release-build-warnings/rmcp-client-doc-links.patch)
24. [release-build-warnings/workspace-rustdoc.patch](holdmyspot-com/codex-unleashed/release-build-warnings/workspace-rustdoc.patch)
25. [rust-toolchain/rust-1.99.patch](holdmyspot-com/codex-unleashed/rust-toolchain/rust-1.99.patch)
26. [dependency-upgrades/compatible-updates.patch](holdmyspot-com/codex-unleashed/dependency-upgrades/compatible-updates.patch)
27. [dependency-upgrades/latest-releases.patch](holdmyspot-com/codex-unleashed/dependency-upgrades/latest-releases.patch)
28. [dependency-upgrades/serde-saphyr.patch](holdmyspot-com/codex-unleashed/dependency-upgrades/serde-saphyr.patch)
29. [dependency-upgrades/release-adaptations.patch](holdmyspot-com/codex-unleashed/dependency-upgrades/release-adaptations.patch)
30. [dependency-upgrades/transitive-updates.patch](holdmyspot-com/codex-unleashed/dependency-upgrades/transitive-updates.patch)
31. [flaky-tests/wine-pty-timeout.patch](holdmyspot-com/codex-unleashed/flaky-tests/wine-pty-timeout.patch)
32. [flaky-tests/rollout-trace.patch](holdmyspot-com/codex-unleashed/flaky-tests/rollout-trace.patch)
33. [flaky-tests/otel-policy-export.patch](holdmyspot-com/codex-unleashed/flaky-tests/otel-policy-export.patch)
34. [flaky-tests/focus-palette.patch](holdmyspot-com/codex-unleashed/flaky-tests/focus-palette.patch)
35. [flaky-tests/compaction-request-parsing.patch](holdmyspot-com/codex-unleashed/flaky-tests/compaction-request-parsing.patch)
36. [flaky-tests/file-watcher-paused-time.patch](holdmyspot-com/codex-unleashed/flaky-tests/file-watcher-paused-time.patch)
37. [old-main-test-fixes/windows-capture-reader-drain.patch](holdmyspot-com/codex-unleashed/old-main-test-fixes/windows-capture-reader-drain.patch)
38. [old-main-test-fixes/legacy-powershell-environment.patch](holdmyspot-com/codex-unleashed/old-main-test-fixes/legacy-powershell-environment.patch)
39. [old-main-test-fixes/windows-shell-policy-system-root.patch](holdmyspot-com/codex-unleashed/old-main-test-fixes/windows-shell-policy-system-root.patch)
40. [old-main-test-fixes/windows-mouse-capture-initialization.patch](holdmyspot-com/codex-unleashed/old-main-test-fixes/windows-mouse-capture-initialization.patch)
41. [old-main-test-fixes/close-remote-client-owner.patch](holdmyspot-com/codex-unleashed/old-main-test-fixes/close-remote-client-owner.patch)
42. [old-main-test-fixes/host-hook-cwd.patch](holdmyspot-com/codex-unleashed/old-main-test-fixes/host-hook-cwd.patch)
43. [old-main-test-fixes/ui-plugin-startup-fixtures.patch](holdmyspot-com/codex-unleashed/old-main-test-fixes/ui-plugin-startup-fixtures.patch)
44. [old-main-test-fixes/local-stdio-mcp-fixtures.patch](holdmyspot-com/codex-unleashed/old-main-test-fixes/local-stdio-mcp-fixtures.patch)
45. [old-main-test-fixes/remote-shell-fixtures.patch](holdmyspot-com/codex-unleashed/old-main-test-fixes/remote-shell-fixtures.patch)
46. [old-main-test-fixes/trusted-plugin-executor-fixture.patch](holdmyspot-com/codex-unleashed/old-main-test-fixes/trusted-plugin-executor-fixture.patch)
47. [old-main-test-fixes/interrupt-host-cwd-fixtures.patch](holdmyspot-com/codex-unleashed/old-main-test-fixes/interrupt-host-cwd-fixtures.patch)
48. [old-main-test-fixes/host-loopback-mcp-fixtures.patch](holdmyspot-com/codex-unleashed/old-main-test-fixes/host-loopback-mcp-fixtures.patch)
49. [old-main-test-fixes/feedback-thread-cwd-fixture.patch](holdmyspot-com/codex-unleashed/old-main-test-fixes/feedback-thread-cwd-fixture.patch)
50. [old-main-test-fixes/guardian-local-capability-fixtures.patch](holdmyspot-com/codex-unleashed/old-main-test-fixes/guardian-local-capability-fixtures.patch)
51. [release-build-warnings/stale-cargo-deny-entries.patch](holdmyspot-com/codex-unleashed/release-build-warnings/stale-cargo-deny-entries.patch)
52. [flaky-tests/subagent-stop-hook-wait.patch](holdmyspot-com/codex-unleashed/flaky-tests/subagent-stop-hook-wait.patch)
53. [flaky-tests/app-server-client-leak-timeout.patch](holdmyspot-com/codex-unleashed/flaky-tests/app-server-client-leak-timeout.patch)
54. [flaky-tests/startup-probe-da1-last.patch](holdmyspot-com/codex-unleashed/flaky-tests/startup-probe-da1-last.patch)
55. [mcp-startup-ctrl-c/mcp-startup-ctrl-c.patch](holdmyspot-com/codex-unleashed/mcp-startup-ctrl-c/mcp-startup-ctrl-c.patch)
56. [flaky-tests/windows-slow-e2e-timeouts.patch](holdmyspot-com/codex-unleashed/flaky-tests/windows-slow-e2e-timeouts.patch)
57. [flaky-tests/feedback-upload-timeout.patch](holdmyspot-com/codex-unleashed/flaky-tests/feedback-upload-timeout.patch)
58. [flaky-tests/windows-sandbox-helper-sharing-violation.patch](holdmyspot-com/codex-unleashed/flaky-tests/windows-sandbox-helper-sharing-violation.patch)
59. [flaky-tests/windows-arm64-load-timeouts.patch](holdmyspot-com/codex-unleashed/flaky-tests/windows-arm64-load-timeouts.patch)
60. [flaky-tests/macos-arm64-daemon-timeouts.patch](holdmyspot-com/codex-unleashed/flaky-tests/macos-arm64-daemon-timeouts.patch)
61. [release-reproduction/deterministic-archives.patch](holdmyspot-com/codex-unleashed/release-reproduction/deterministic-archives.patch)
62. [release-reproduction/shared-package-builder.patch](holdmyspot-com/codex-unleashed/release-reproduction/shared-package-builder.patch)
63. [release-reproduction/archive-command.patch](holdmyspot-com/codex-unleashed/release-reproduction/archive-command.patch)
64. [subagents/picker_state_controls.patch](holdmyspot-com/codex-unleashed/subagents/picker_state_controls.patch)
65. [release-voice-runtime/producer.patch](holdmyspot-com/codex-unleashed/release-voice-runtime/producer.patch)
66. [release-voice-runtime/package-integration.patch](holdmyspot-com/codex-unleashed/release-voice-runtime/package-integration.patch)
67. [release-voice-runtime/installed-package-test.patch](holdmyspot-com/codex-unleashed/release-voice-runtime/installed-package-test.patch)
68. [release-voice-runtime/opus-fortification.patch](holdmyspot-com/codex-unleashed/release-voice-runtime/opus-fortification.patch)
69. [release-voice-runtime/producer-targets.patch](holdmyspot-com/codex-unleashed/release-voice-runtime/producer-targets.patch)
70. [release-reproduction/code-mode-symbol-validation.patch](holdmyspot-com/codex-unleashed/release-reproduction/code-mode-symbol-validation.patch)
71. [release-voice-runtime/ingress-ordering.patch](holdmyspot-com/codex-unleashed/release-voice-runtime/ingress-ordering.patch)
72. [windows-command-capture/partial-output-errors.patch](holdmyspot-com/codex-unleashed/windows-command-capture/partial-output-errors.patch)
73. [release-reproduction/dev-drive-bfs.patch](holdmyspot-com/codex-unleashed/release-reproduction/dev-drive-bfs.patch)
74. [release-reproduction/aws-lc-c-standard.patch](holdmyspot-com/codex-unleashed/release-reproduction/aws-lc-c-standard.patch)
75. [release-reproduction/python-format.patch](holdmyspot-com/codex-unleashed/release-reproduction/python-format.patch)
76. [release-reproduction/symbol-test-format.patch](holdmyspot-com/codex-unleashed/release-reproduction/symbol-test-format.patch)
77. [release-voice-runtime/voice-non-rtp-ingress.patch](holdmyspot-com/codex-unleashed/release-voice-runtime/voice-non-rtp-ingress.patch)
78. [release-voice-runtime/voice-capture-timestamp.patch](holdmyspot-com/codex-unleashed/release-voice-runtime/voice-capture-timestamp.patch)
79. [release-reproduction/dev-drive-volume-predicate.patch](holdmyspot-com/codex-unleashed/release-reproduction/dev-drive-volume-predicate.patch)
80. [release-voice-runtime/query-stdout.patch](holdmyspot-com/codex-unleashed/release-voice-runtime/query-stdout.patch)
81. [release-voice-runtime/loader-path.patch](holdmyspot-com/codex-unleashed/release-voice-runtime/loader-path.patch)
82. [windows-command-capture/platform-guards.patch](holdmyspot-com/codex-unleashed/windows-command-capture/platform-guards.patch)
83. [native-test-prerequisites/compile-byte-streams.patch](holdmyspot-com/codex-unleashed/native-test-prerequisites/compile-byte-streams.patch)
84. [native-test-prerequisites/macos-arm64-deep-home-scheduling.patch](holdmyspot-com/codex-unleashed/native-test-prerequisites/macos-arm64-deep-home-scheduling.patch)
85. [release-reproduction/msvc-environment-reuse.patch](holdmyspot-com/codex-unleashed/release-reproduction/msvc-environment-reuse.patch)
86. [mxc-ancestor-guard/guard-protected-ancestors.patch](holdmyspot-com/codex-unleashed/mxc-ancestor-guard/guard-protected-ancestors.patch)
87. [telemetry-off/telemetry-off.patch](holdmyspot-com/codex-unleashed/telemetry-off/telemetry-off.patch)
88. [windows-read-acl-helper/inherit-no-handles.patch](holdmyspot-com/codex-unleashed/windows-read-acl-helper/inherit-no-handles.patch)
89. [release-reproduction/reuse-prepared-musl-package-tools.patch](holdmyspot-com/codex-unleashed/release-reproduction/reuse-prepared-musl-package-tools.patch)
90. [release-reproduction/reuse-verified-package-executables.patch](holdmyspot-com/codex-unleashed/release-reproduction/reuse-verified-package-executables.patch)
91. [flaky-tests/windows-prompt-prefix-home-normalization.patch](holdmyspot-com/codex-unleashed/flaky-tests/windows-prompt-prefix-home-normalization.patch)
92. [flaky-tests/synthetic-websocket-and-rmcp-integration.patch](holdmyspot-com/codex-unleashed/flaky-tests/synthetic-websocket-and-rmcp-integration.patch)
93. [flaky-tests/websocket-probe-and-rmcp-http-boundary-fixtures.patch](holdmyspot-com/codex-unleashed/flaky-tests/websocket-probe-and-rmcp-http-boundary-fixtures.patch)
94. [flaky-tests/rmcp-oauth-issuer-and-origin-fixtures.patch](holdmyspot-com/codex-unleashed/flaky-tests/rmcp-oauth-issuer-and-origin-fixtures.patch)
95. [flaky-tests/rmcp-limit-and-doctor-websocket-report-fixtures.patch](holdmyspot-com/codex-unleashed/flaky-tests/rmcp-limit-and-doctor-websocket-report-fixtures.patch)
96. [flaky-tests/rmcp-configured-response-cap-fixture.patch](holdmyspot-com/codex-unleashed/flaky-tests/rmcp-configured-response-cap-fixture.patch)
97. [flaky-tests/rmcp-oauth-authorization-origin-fixture.patch](holdmyspot-com/codex-unleashed/flaky-tests/rmcp-oauth-authorization-origin-fixture.patch)
98. [flaky-tests/rmcp-sse-event-limit-fixture.patch](holdmyspot-com/codex-unleashed/flaky-tests/rmcp-sse-event-limit-fixture.patch)
99. [flaky-tests/rmcp-oversize-discovery-and-error-body-fixture.patch](holdmyspot-com/codex-unleashed/flaky-tests/rmcp-oversize-discovery-and-error-body-fixture.patch)
100. [flaky-tests/rmcp-header-bearing-redirect-refusal-fixture.patch](holdmyspot-com/codex-unleashed/flaky-tests/rmcp-header-bearing-redirect-refusal-fixture.patch)
101. [flaky-tests/rmcp-stdio-line-limit-fixtures.patch](holdmyspot-com/codex-unleashed/flaky-tests/rmcp-stdio-line-limit-fixtures.patch)
102. [release-build-warnings/debug-only-test-imports.patch](holdmyspot-com/codex-unleashed/release-build-warnings/debug-only-test-imports.patch)
103. [rmcp-stdio/close-after-oversized-line.patch](holdmyspot-com/codex-unleashed/rmcp-stdio/close-after-oversized-line.patch)

These patches from the `rust-v0.167.0` queue are not applied on `rust-v0.171.0`:

- [issue-34776/defer-implicit-skill-index.patch](holdmyspot-com/codex-unleashed/issue-34776/defer-implicit-skill-index.patch) is left out. Upstream now builds the implicit skill index asynchronously through per-skill environment access (#59280), so deferring it needs a new design. The file still targets `rust-v0.167.0`.
- [rama-0.4/rama-0.4.patch](holdmyspot-com/codex-unleashed/rama-0.4/rama-0.4.patch) is left out. `rama-crypto` 0.4.0, the only 0.4 release, pins `time =0.3.47`, and the upstream `sqlx-core` git override needs `time ^0.3.48`, so no lockfile resolves both. The network proxy stays on rama 0.3 with its Hickory advisory exceptions. The file still targets `rust-v0.167.0`.
- `release-build-warnings/bazel-module-versions.patch` is dropped: `rust-v0.171.0` requires `platforms` 1.1.0 and `rules_cc` 0.2.25.
- `old-main-test-fixes/conpty-lifecycle-input-fixture.patch` is dropped: upstream #60872 made the same fix.
- `old-main-test-fixes/conpty-interrupt-readiness-fixture.patch` is dropped: upstream #59369 rewrote the fixture around a Python child that resets its own Ctrl+C disposition.
- `windows-bazel/opus-installed-headers.patch` is dropped: upstream #59752 and #59972 made the same fix.
- `windows-bazel-voice-host/voice-host-test-runtime.patch` is dropped: upstream #59082, #59231 and #59369 made the same fix.
- `bazel-downloads/zlib-launchpad-fallback.patch` is dropped: `rules_rs` 0.2.3 already lists the Launchpad URLs.
- `flaky-tests/agents-overview-pin-refresh-race.patch` is dropped: the upstream test already waits for the pin result with the pending request ID.
