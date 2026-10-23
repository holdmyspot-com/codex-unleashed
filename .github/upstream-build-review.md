# Upstream build-workflow adoption

## Review scope

The review covers upstream changes after `rust-v0.162.0`
(`c1382380de69521303b416720a52f42d51af6248`) through development commit
`2839ed763bd3f404398e1f0c95bf4e2d172213c0`, plus the preceding calendar month.
The current release queue continues to target `rust-v0.163.0`
(`83086188ae0614bd882818ff1aa23f3194962012`). The earlier month contains
1,512 commits, including 45 touching GitHub workflow, action, build-helper or
toolchain paths. Product changes are outside this workflow-adoption review.

## Adopted changes

| Upstream reference | Application here |
| --- | --- |
| Bazelisk update | Setup uses stable Bazelisk 1.30.0; the Bazel version and cache configuration remain explicit. |
| [#53897](https://github.com/openai/codex/pull/53897) | A standalone backport reserves nextest slots for daemon package cases and serializes the existing Bazel daemon integration target exclusively. Keeping the existing binary preserves downstream fixtures. |
| [#55693](https://github.com/openai/codex/pull/55693) | A standalone backport declares the macOS dsymutil wrapper and toolchain path as Rust binary action inputs. Cargo release builds retain their current configuration. |
| [#56032](https://github.com/openai/codex/pull/56032) | Unix release staging checks the exact demangled `codex_code_mode_host::main` symbol before stripping. Linux lookup includes local symbols. The shell helper replaces the relevant lookup behavior without restoring Python tooling. |
| [#46303](https://github.com/openai/codex/pull/46303) | GitHub assets upload one at a time to a draft; publication follows successful uploads. A failed upload leaves the draft unpublished. |
| [#47693](https://github.com/openai/codex/pull/47693) | DotSlash installation receives a scoped curl configuration with retry-all-errors. |
| V8 Windows source builds | Both V8 workflows apply the selected queue's Array.sort security backport before compilation, and include its contents in the source-cache key. |
| Diagnostic triggers | Compiler profiling accepts explicit dispatch and reusable calls; release tags do not start it automatically. |
| Artifact action and action pins | Updated upload actions use stable upload-artifact v7.0.2 with archive mode preserved. Existing immutable action pins remain where unchanged. Moving checkout, setup-python, and cache major-version references resolve to immutable commits. |
| Windows package helpers | Both Windows release matrices build and retain the sandbox service; Maven assembly copies it into resources and rejects its absence. |
| Argument-comment lint | A BuildBuddy task runs the pinned source tree's Bazel linter targets through the maintained scheduler wrapper and participates in the fast CI result gate. The task also runs the lint tool's Python wrapper and pinned-nightly Rust regressions with isolated toolchain caches. |
| V8 manifest authentication | The consumer authenticates raw manifest bytes against the source-controlled digest before trusting artifact hashes or exporting their paths. Cached files pass through the same check. |
| Scheduled full tests | Scheduled CI invokes the full test orchestrator once. Native Windows ARM and Docker remote-environment tests join the platform matrix; remote tests share the ordinary Linux test archive. |
| Voice runtime contents | Native producers stage and seal six target-specific runtimes. Maven assembly validates their receipts and includes the helper, runtime inventory, source identity, and legal materials in primary Codex archives. |

The symbol check establishes entrypoint availability. It does not replace
upstream's broader source-location smoke tests or establish package runtime
performance. Its regressions use actual Rust binaries with a local entrypoint
and with only a generic instantiation mentioning the host module.

## Existing coverage and differences

- Release builds run concurrently; publication and latest-version decisions
  share the repository publication lock. This covers #51198 and the applicable
  stable-pointer protection from #51186. The existing latest-version tooling
  remains responsible for version ordering.
- Windows Bazel target selection already uses duration estimates (#49103).
  Shared Bazel preparation and success-only cache saving cover #45554's intent.
- The queue already exceeds the earlier Bazel, Rust, Dylint, install-action and
  OpenSSL version updates. Those commits do not justify downgrading our pins.
- The build helpers already reconcile workspace lockfile versions before Bazel
  consumes them (#44025). Current release packaging uses the Maven tooling and
  bundled archives, so upstream's Python wheel staging and per-binary Windows
  compression queue do not map directly to our pipeline.
- R2, WinGet, provisioned macOS distribution, Python wheels, and SDK publication
  are separate distribution channels and remain excluded. Codex runtime helpers
  and voice resources are package contents, so that exclusion does not justify
  omitting them from a Codex package.
- Our release preparation produces and caches a verified Java runtime; removing
  it because upstream removes its differently scoped preparation workflow
  (#47095) would remove required functionality.
- Package distribution remains GitHub-only for this release. Npm publication
  stays disabled; npm dist-tag changes do not alter the current release path.

## Three-pass comparison

The review separates trigger and job coverage, build/cache/package contracts,
and a final cross-check of accepted changes and remaining differences. A file
inventory alone does not establish behavioral equivalence.

| Workflow or related files | Decision |
| --- | --- |
| `blocking-ci.yml`, `ci.yml` | Keep fork pull-request and main-branch entrypoints separate. BuildBuddy executes the Linux tasks. Full native/Bazel coverage remains a separate required release gate. |
| `postmerge-ci.yml` | Invoke the full test orchestrator once for scheduled dependency and environment drift checks, including native Windows Bazel coverage. |
| `cargo-deny.yml`, `codespell.yml`, `repo-checks.yml`, `sdk.yml` | Keep reusable BuildBuddy dispatch. The SDK task builds both executable prerequisites and runs TypeScript build, lint, and integration tests. Do not restore Python SDK publication or tests. |
| `rust-ci.yml` | Keep maintained formatting/benchmark and cargo-shear checks plus the downstream Darwin linker regression. Add argument-comment-lint through BuildBuddy instead of maintaining redundant native Cargo lint matrices. |
| `rust-ci-full.yml` | Keep explicit release-platform orchestration and failure aggregation, including native Windows ARM and Docker remote-environment runs. Do not adopt OpenAI-only runner groups or replace required native tests with cross-compilation. |
| `rust-ci-full-nextest-platform.yml` | Keep build-once archives, four shards, SDK/runtime relocation, and the cross-platform compiler scheduler. Remote-environment shards reuse the ordinary Linux archive; their reports and container cleanup remain separate. Retain the user-approved single legacy sandbox exclusion. |
| `bazel.yml` | Keep Linux BuildBuddy execution, native macOS validation, Windows cross-build/native callbacks, cache-save checks, and native SDK checks. The new compatibility-release resolver is absent from the selected base; adopt its environment setup together with its test harness on a future base update. The existing release target sweep includes bwrap. |
| `build-release.yml`, upstream `rust-release.yml` and `rust-release-windows.yml` | Keep one Cargo publication path, bundled Codex packages, provenance, source-identity checks, and serialized publication. Avoid duplicating every release build with an advisory Bazel build. Resolve runtime-content gaps before publication. |
| upstream `rust-release-provisioned-macos.yml` | Keep excluded: its provisioned distribution and signing credentials are not this repository's publication contract. |
| upstream `rust-release-argument-comment-lint.yml` | Keep lint-tool publication excluded from Codex-only release assets; CI lint coverage is a separate decision. |
| `rusty-v8-release.yml`, `v8-canary.yml` | Keep platform smoke checks and upstream dependency support artifacts. Apply the Windows source security backport and key the cache on it. Native Windows execution remains required verification. |
| `prepare-tooling.yml`, `tooling-ci.yml` | Keep the success-only verified JDK 27 runtime cache and launcher probe. Upstream's removal of its preparation workflow does not replace this runtime producer. |
| `buildbuddy-ci.yml` | Keep warm-runner delegation, task-result validation, and diagnostics. GitHub remains the platform orchestration and publication boundary. |
| `cache-cleanup.yml` | Keep Actions/GHCR cleanup. Every direct workflow artifact upload has one-day retention. Cache lifetimes and release assets are distinct contracts. |
| `profile-release-compiler.yml`, `diagnose-native-windows.yml`, `diagnose-archived-tests.yml` | Keep explicit diagnostics and archived-input reuse. Remove automatic release-tag profiling. |
| `blob-size-policy.yml` | Keep the Java checker and fork allowlist. Manual runs compare the selected commit against its first parent instead of comparing the commit with itself. |
| `codeql.yml` | Keep Java dependency extraction and analysis for the Maven tooling. |
| `upstream-release-check.yml`, `update-badges.yml` | Keep fork release detection and project status metadata; upstream has no equivalent downstream patch queue. |
| `rust-release-zsh.yml` | Keep the separately tagged runtime dependency build used by Codex package assembly. It does not add zsh assets to the Codex release. |
| `build-voice-runtime.yml`, upstream voice runtime production | Build native voice payloads for Codex packages, with one-day private artifact retention. Keep OpenAI signing and separate distribution channels excluded; fork packages do not claim those signatures. |
| `close-pull-requests.yml`, `close-unfunded-issues.yml` | Keep the repository's contribution policy. Do not import upstream CLA, stale-contributor, issue-deduplication, labeling, or translation automation. |
| upstream `cla.yml`, `close-stale-contributor-prs.yml`, `issue-deduplicator.yml`, `issue-labeler.yml`, `issue-translator.yml` | Keep excluded: the fork's contribution policy differs, and these automations do not build or publish Codex. |
| upstream `python-runtime-build.yml`, `python-runtime-release.yml`, `python-sdk-build.yml`, `python-sdk-cli-release.yml`, `python-sdk-release.yml` | Keep excluded: Python SDK/runtime distribution is outside this fork's Codex-only publication scope. |
| upstream `r2-release.yml` | Keep excluded: GitHub is the release destination. |
| upstream `Dockerfile.bazel`, workflow `README.md`, shared `zstd` | Treat the Dockerfile and documentation as runner support, not missing publication jobs. Compare build environment requirements at the BuildBuddy bootstrap boundary; retain the existing zstd launcher. |

### Open findings

- Native voice production and its integration with the hosted release workflow
  require execution on all six platforms. Local archive tests use fixture native
  bytes and do not establish native compilation, loading, or audio behavior.
- The asset-name subset check accepts incomplete package contents. It cannot
  serve as proof of runtime-content parity. Canonical assembly validates voice
  receipts and inventory; publication still requires accepted native payloads.
- Hosted argument-comment lint reports 174 missing parameter comments in
  downstream patches. Repair belongs in those owning patches; lint is not
  suppressed or excluded. The full aspect requires a passing hosted rerun.
- The scheduled full matrix, Windows ARM environment, and Docker remote tests
  require hosted execution. Local workflow checks establish wiring only.
- Native Windows lifecycle diagnostics remain separate from fixture validation;
  the pending diagnostic run determines the next repair boundary.

The final pass and adoption acceptance remain open while these findings lack
either an implemented, verified correction or an evidence-based keep decision.

## Verification boundaries

Focused shell tests exercise upload ordering, stop-before-publication on an
upload failure, empty-asset rejection, exact entrypoint selection, and rejection
of a generic instantiation. These run in the BuildBuddy repository-check task.
ShellCheck covers the helpers. Emitted patches must apply independently to the
declared base and integrate with the queue.

Full nextest/Bazel execution, macOS dSYM validation, and the complete release
workflow remain release acceptance gates. Local shell checks and successful
patch application do not establish those hosted outcomes. Actionlint 1.7.12
rejects the pre-existing GitHub `concurrency.queue` keys on both the baseline
and edited release workflow; record that validator limitation separately from
new diagnostics.

Maven verification covers package layout, missing-service rejection, source
build selection, CLI packaging, and the bundled-runtime release wrapper. Native
Windows compilation and service execution remain platform gates. The package
regression first fails on the absent service file, then passes with service
assembly enabled.

Voice archive regressions exercise the actual bundled Java assembler through
the maintained shell wrapper, including source/version preservation, legal
materials, invalid-option rejection before tooling, and staging cleanup. The
focused Maven `test` lifecycle stops at the existing runtime-staging MDEP-187
boundary; the focused `package` lifecycle and complete `verify` lifecycle are
the applicable execution paths for these distribution tests.
