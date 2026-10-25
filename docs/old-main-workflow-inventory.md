# Old-main workflow inventory

Reviewed source: `545479059ac86394049b6efd3bf4297b1ca18d17`. These 26 paths are migration candidates, not enabled workflows or accepted capabilities. The inventory covers every tracked path under the old `.github/workflows/` directory; the `zstd` entry is an executable manifest. Dispositions remain governed by [plan.md](../plan.md).

Public npm publication and issue mutation are excluded. PR closure remains deferred. Auxiliary releases require a demonstrated consumer; automatic release publication remains deferred. Repository publication authorization for `main.new` does not authorize creating releases. Cache deletion remains disabled until the retention preview passes.

| Old-main path | Purpose | Owning plan scope | Migration constraint / side effects |
| --- | --- | --- | --- |
| `.github/workflows/bazel.yml` | Build, test, lint, and verify upstream Bazel targets across Linux, macOS, and Windows. | Checkpoint 1; D7 for V8-related checks | Uploads short-retention CI artifacts and uses caches; no prohibited publication or issue mutation found. |
| `.github/workflows/blob-size-policy.yml` | Check changed files against blob-size policy. | D10 | Verification only; no prohibited mutation found. |
| `.github/workflows/blocking-ci.yml` | Aggregate required pull-request checks, including blob-size, cargo-deny, codespell, repository checks, Rust CI, and SDK checks. | D10; D2 for the SDK check | Verification only; no prohibited mutation found. |
| `.github/workflows/build-release.yml` | Prepare, verify, build, and publish patched Codex release artifacts; also has a gated npm publication path. | D3, D4, D5, D6 | Creates GitHub releases/tags/assets after verification; D6 says publication requires a separate instruction. Public npm publication is explicitly available when `publish_npm` is true and is forbidden by D4. No workflow text identifies a paid early-access package family. |
| `.github/workflows/buildbuddy-ci.yml` | Run a selected Linux CI task remotely through BuildBuddy and upload its result artifact. | D8; D2 when called for SDK verification | Remote execution and Actions artifact upload; no prohibited publication or issue mutation found. |
| `.github/workflows/cache-cleanup.yml` | Prune unused release caches on schedule, workflow completion, or manual dispatch. | D8 | Deletes cache entries; D8 approves cache retention/cleanup policy. No listed prohibited mutation found. |
| `.github/workflows/cargo-deny.yml` | Run upstream Cargo dependency-policy checks. | Checkpoint 1 and 7; D10 for maintained repository gates | Verification only; no prohibited mutation found. |
| `.github/workflows/ci.yml` | Run and aggregate push CI checks, including Rust, repository, spelling, dependency, and SDK checks. | Checkpoint 1; D2 and D10 | Verification only; no prohibited mutation found. |
| `.github/workflows/close-pull-requests.yml` | Comment on and close newly opened or reopened outside pull requests. | D12 | PR comment and closure; explicitly deferred by D12. The workflow requests `pull-requests: write` and calls `createComment` and `pulls.update(state: closed)`. |
| `.github/workflows/close-unfunded-issues.yml` | Comment on and close issues that do not meet funding/approval policy. | D12 | Issue comment and closure; forbidden by D12. The comment also contains paid early-access solicitation language, which D12 excludes. The workflow requests `issues: write` and calls `createComment` and `issues.update(state: closed)`. |
| `.github/workflows/codeql.yml` | Run scheduled and change-triggered CodeQL analysis. | D10 | Security analysis only; no prohibited mutation found. |
| `.github/workflows/codespell.yml` | Run spelling checks. | D10 | Verification only; no prohibited mutation found. |
| `.github/workflows/postmerge-ci.yml` | Run scheduled/manual post-merge verification, including full Rust tests, SDK, Bazel, and V8 canary checks. | Checkpoint 1; D2, D7, D10 | Verification and artifact checks; V8 canary scope remains conditional under D7. No prohibited publication or issue mutation found. |
| `.github/workflows/prepare-tooling.yml` | Prepare a verified native tooling runtime for later jobs. | D1; migrate needed tooling after the default baseline | Preparation only; no prohibited mutation found. |
| `.github/workflows/repo-checks.yml` | Run downstream repository consistency and policy checks. | D10 | Verification only; no prohibited mutation found. |
| `.github/workflows/rust-ci-full-nextest-platform.yml` | Archive and execute sharded full nextest tests for a platform, then report the result. | Checkpoint 1 | Uploads test-result artifacts; no prohibited publication or issue mutation found. |
| `.github/workflows/rust-ci-full.yml` | Orchestrate full upstream checks and cross-platform nextest runs. | Checkpoint 1; D2 and D10 for included checks | Verification and short-retention artifacts; no prohibited publication or issue mutation found. |
| `.github/workflows/rust-ci.yml` | Run changed-target Rust CI, cargo-shear, platform linker checks, and aggregate results. | Checkpoint 1; D10 for dependency checks | Verification only; no prohibited mutation found. |
| `.github/workflows/rust-release-zsh.yml` | Build pinned patched-zsh artifacts and publish a standalone GitHub release. | D7 | Creates a GitHub release and uploads artifacts. D7 defers this independent service unless the selected release or approved product behavior requires the artifact. |
| `.github/workflows/rusty-v8-release.yml` | Build rusty-v8 release artifacts and publish a standalone GitHub release. | D7 | Creates or updates a GitHub release and uploads artifacts. D7 defers the independent service unless a consuming path is established. |
| `.github/workflows/sdk.yml` | Run upstream SDK checks through the BuildBuddy CI task. | D2 | Verification only; no standalone SDK publication found. |
| `.github/workflows/tooling-ci.yml` | Verify tooling across Linux, Windows, and macOS runners. | D10 | Verification only; no prohibited mutation found. |
| `.github/workflows/update-badges.yml` | Regenerate and commit the public-patch badge JSON. | D11 | Commits and pushes a repository change using `contents: write`; this aligns with D11’s approved badges/identity scope and is not among the listed prohibitions. |
| `.github/workflows/upstream-release-check.yml` | Discover new upstream releases and optionally request the release workflow. | D5 | With `AUTO_RELEASE=true`, dispatches `build-release.yml` with `automatic=true`; automatic release publication is deferred by D5 pending release acceptance. |
| `.github/workflows/v8-canary.yml` | Build V8 canary artifacts for CI checks. | D7 | Builds/uploads artifacts but does not publish a release. D7 retains it only if a selected release or approved product behavior consumes those artifacts. |
| `.github/workflows/zstd` | DotSlash manifest pinning the Windows zstd executable for x86-64 and ARM64 emulation. | D3, if required as a package/helper resource | Downloads a pinned upstream artifact; no publication or issue mutation. |

This is source review only. It does not demonstrate hosted execution, establish that D7 artifact producers are required, or authorize copying any workflow unchanged. The complete file-level ledger remains [old-main-file-inventory.tsv](old-main-file-inventory.tsv).

## Auxiliary artifact consumers (D7)

The pinned upstream lockfile resolves V8 152.2.0. Both old-main and pinned-upstream `.github/actions/setup-rusty-v8/action.yml` resolve its release tag and download matching libraries, bindings and checksums from `openai/codex`. The old-main package builder also uses that repository. The standalone `rusty-v8-release.yml` producer publishes to its current repository. Copying that producer does not establish a handoff to these consumers. Retain the verified upstream artifact setup for the baseline; an independent producer remains deferred unless an approved consumer needs its outputs.

Pinned upstream's package layout has no zsh resource or `codex-zsh` manifest. Old main adds that Unix resource and passes its checked-in `scripts/codex_package/codex-zsh` manifest to package assembly. That manifest pins `codex-zsh-v0.1.0` assets under `openai/codex`. Its standalone zsh producer publishes archives to the current repository; the inspected workflow does not generate the alternate manifest needed to connect those outputs to the default package consumer. Live zsh asset availability has not been verified. Revisit the resource, manifest and producer together when approved product patches and packaging require them.

These findings narrow the source-level producer/consumer assumptions; they do not enable auxiliary releases or establish canary-service acceptance. All D7 migration checkboxes remain open.
