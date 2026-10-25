# Restart plan

## Goal and starting state

Rebuild Codex Unleashed incrementally from the latest stable OpenAI Codex release on an independent branch named `main.new`. Preserve `main` and its history as the source for selective migration and later discussion. The orphan root contains only this plan; upstream source and the minimum build setup enter in step 1.

Old-main source commit: `545479059ac86394049b6efd3bf4297b1ca18d17`.

Selected scope:

- Root contents: only `plan.md`.
- Product publication: patched Codex CLI and app-server packages; no standalone SDK releases. npm package delivery remains local-registry only.
- Build coverage: the existing release matrix, beginning with a local Linux check: Linux ARM64 and x86_64 musl, macOS Apple Silicon and Intel, and Windows ARM64 and x86_64 MSVC.
- GitHub runner cost: use only free standard GitHub-hosted runners. Do not provision or select paid larger runners; investigate storage consumption and adapt the build workflow within the free runners' limits.
- Branch publication: pushing ongoing work to `main.new` is authorized without repeated approval. Publishing releases remains a separate decision.

The selected stable tag and exact commit are resolved from upstream when step 1 starts and remain fixed through step 6. Do not silently move the baseline as new releases appear.

## Current execution

Step 1 is active. The selected upstream release is `rust-v0.164.0`, commit `6aaac694803004a63c6f393d153c96d22521a6b9`; Rust 1.99.1 matches its toolchain pin. [Build baseline](docs/build-baseline.md) records commands, individual gate states, execution ownership, and cleanup. The local default Cargo build, executable smoke, formatting, both SDK suites, installed Python SDK smoke, benchmark smoke and listed helper suites pass with warnings nonfatal. Clippy now passes on all six release platforms. Linux x86_64, Linux ARM64, Windows ARM64 and Windows x86_64 release builds and executable smokes pass, with verified evidence from [run 40159613742](https://github.com/holdmyspot-com/codex-unleashed/actions/runs/40159613742) and [follow-up run 40167625326](https://github.com/holdmyspot-com/codex-unleashed/actions/runs/40167625326). The two macOS release targets remain pending in the follow-up with the default job timeout. Native Linux tests in [run 40162222123](https://github.com/holdmyspot-com/codex-unleashed/actions/runs/40162222123) complete without disk exhaustion: 22589 tests pass and 66 fail, with root-cause investigation active. Full Bazel argument-comment lint passes. The local full Bazel suite passes 318 targets and fails 27, including environment-specific daemon-directory conflicts; a native free-runner full Bazel workflow is prepared to check the same maintained target selection without the additional disk-cache copy. Sandbox and skills fixture repairs pass 214 focused tests. Further verified test-only repairs address V8 feature unification and exactly 37 TUI failures; the full native suite still needs to pass with the cumulative patches. Dependency audit failures are retained under the owner's step-7 deferral. No original step is complete yet.

The `pnpm --version` availability probe succeeds with `12.15.0` after the owner grants write access to Corepack's original cache location. This proves tool availability only, not a Codex build or SDK check.

In the selected upstream checkout, Corepack honors its pnpm 10.34.5 pin. `pnpm install --frozen-lockfile`, `pnpm run format` and the complete `just fmt-check` pass. An upstream manifest checker has a stale code-mode exception; the root cause was established before repair. The one-line repair and its emitted patch pass the maintained checker in independent clean checkouts. The patch was then applied after the default build passed, and the cumulative checker passes.

The Python SDK exposed a separate root-formatter command mismatch. Both this mismatch and the stale manifest exception were double-checked against upstream's own failed CI jobs on the stable release's parent; the release commit changes only the Cargo version. Upstream's release builds pass. The formatter repair passes its focused tests, the complete isolated Python suite (271 passed, 38 skipped), real Rust formatting and fresh-base patch verification. It is applied to the active baseline; the cumulative Python suite and maintained formatting checks also pass. [Baseline patches](patches/upstream-baseline/README.md) record the exact scope and evidence.

At the owner's request, 19 obsolete linked worktrees were removed. Eighteen uncommitted change sets and all original worktree commits remain reachable through permanent local archive references. [Worktree recovery](docs/worktree-recovery.md) records those references and recovery commands. Include these additional saved changes in final reconciliation; cleanup does not select them for migration. A later cleanup also archived and removed 33 older source checkouts from independent repositories. Their verified local filesystem archive and recovery instructions are recorded in the same recovery document; include those saved changes in final reconciliation as well.

## Execution rules

The implementing agent owns each step in sequence and reads this file from the branch before resuming. Mark a checkbox complete only after its acceptance checks pass; record its implementation commit, exact commands, toolchain, configuration, results, diagnostic locations, and relevant timings beneath the step. Keep unrun and failed checks explicit. Reuse verified results only while their determining inputs remain valid.

Each checkbox below represents one change to apply. Split a step into smaller independently verifiable changes when evidence requires it, preserving the sequence. Prefer one working commit per coherent change. Pull individual patches or relevant hunks from the recorded old-main commit when suitable; write a smaller or better implementation when justified. Inspect prerequisites and separate mixed product, build, warning-policy, and dependency changes before importing them.

Before fixing a failure, reproduce it and identify its root cause. Compare the failing operation with the last passing revision using the same command, inputs, toolchain, configuration, and environment. If several changes intervene, test them individually or bisect them to isolate the responsible change. Fix its owning cause and rerun the failing check before broader verification. Do not infer a toolchain defect merely from a version difference or modify unchanged upstream source before investigating local differences.

When an unchanged stable upstream build or check fails locally, first assume our execution differs from upstream. Double-check source identity and compare the failing command, build system, selected targets, tool versions, prerequisite setup, environment and fixture locations with upstream's successful workflow for that commit. Distinguish release-build failures from additional checks that the release workflow did not run. Establish the relevant differences and test them before retaining a source repair or attributing a defect to upstream.

For behavior changes, first run a focused regression that fails for the intended missing behavior, then implement and verify the fix. Setup and compilation failures do not establish that behavioral regression. Generate reusable patches with `git format-patch`, document their exact base and prerequisites, and verify the emitted patch on a fresh base with only its required prerequisites. Run maintained upstream check entry points with their required options; formatting includes `just fmt-check`.

Keep warnings visible and nonfatal in steps 1–5. Do not import warning-as-error configuration early. Step 6 introduces fatal warnings; retain that policy in step 7. Do not silence warnings to satisfy either step.

The owner explicitly defers the two confirmed baseline dependency-audit findings (`RUSTSEC-2026-0387` and `RUSTSEC-2026-0385`) to step 7. Preserve their failed audit status and diagnostics; they do not block completing the earlier steps. Compilation, tests, smoke checks and other required gates still need to pass before caching. This exception does not suppress findings or authorize early dependency changes.

## Ordered changes

- [ ] **1. Establish a build of the latest stable upstream Codex with its default configuration.** Resolve the latest non-prerelease upstream release to a tag and commit; record both here. Inspect its maintained build and check entry points and document the exact default build command and required environment for the selected platforms. Use its default features and dependency resolution. Add only the source retrieval and minimum build setup needed for this baseline; do not import downstream caching, scheduling, product patches, dependency upgrades, or strict-warning policy. If the upstream default makes warnings fatal, document and narrowly disable that policy to honor this step. Acceptance: the baseline build and required upstream checks pass on each selected platform, with warning output retained and the resulting executable passing its maintained smoke check. Investigate any baseline failure before proceeding.

- [ ] **2. Add caching to the verified baseline.** Cache reusable downloads, generated inputs, and build outputs through the existing tool interfaces. Define invalidation from the actual inputs that determine each result. Keep build behavior and warning policy equivalent to step 1. Acceptance: cold and warm runs produce working outputs; unchanged inputs reuse cached work, a representative changed input rebuilds its affected work, and cached results pass the same relevant checks as the baseline. Record cache hits, misses, and elapsed times rather than assuming reuse.

- [ ] **3. Add the build scheduler and improve cache coverage toward build steps under five minutes.** Inspect and selectively reuse the scheduler from old main. Establish its work units, dependencies, resource limits, result reporting, and failure/resumption behavior against the verified cached build. Measure each scheduled step's wall-clock duration and distinguish cold from warm runs. Aim for each step to finish in under five minutes; a step at or above five minutes triggers investigation of reusable work and invalidation before changing its implementation. A cache miss is a hypothesis until evidence confirms it. Do not hide costly work by timing only the launcher or arbitrarily splitting a step. Acceptance: the scheduler runs the required build through completion, reports failures accurately, preserves valid completed work on resumption, and produces working outputs. Record per-step timings, cache evidence, and any remaining target misses with their established causes; do not claim the target is met while misses remain.

- [ ] **4. Apply product patches without build patches.** Inventory each old-main product outcome, compare it with the selected stable release, and migrate the still-needed behavior one coherent change at a time. Separate build changes and premature dependency or warning-policy changes from mixed patches. Keep product feature defaults and configuration contracts explicit. Acceptance: focused regressions demonstrate each required product outcome, the emitted patches work on their documented bases and prerequisites, and the cumulative build and upstream checks pass. Warnings remain nonfatal.

- [ ] **5. Resolve the causes of remaining warnings in Codex and its direct dependencies.** Inventory warnings from the cumulative build and required checks, identify the emitting component and root cause, and apply only the repairs needed for those causes. Prefer existing correct interfaces or configuration over unnecessary source changes. Keep warnings nonfatal and visible throughout this step. Do not import dependency backports under the label of warning cleanup; record any conflict with the dependency-backport deferral for discussion. Acceptance: the same build and check commands exercise the affected paths and no longer emit the inventoried warnings in Codex or its direct dependencies; focused regressions and cumulative checks pass. Record remaining diagnostics outside that scope separately.

- [ ] **6. Enable treating warnings as errors.** Introduce strict diagnostics only after step 5 passes. Define the checks and components covered, including Codex and its direct dependencies, and verify their actual warning behavior rather than relying only on flag presence. Acceptance: a clean cumulative build passes; a controlled representative warning fails at its diagnostic; the same fixture without the warning passes; required upstream checks and product regressions pass. Keep a failing command's original status and diagnostics.

- [ ] **7. Update Codex dependencies to their latest stable releases.** Resolve and record versions when this step starts, including direct dependencies and the transitive updates required by their supported resolution. Apply coherent updates individually, keeping manifests, lockfiles, build metadata, and required API adaptations consistent. Document compatibility constraints rather than silently substituting an older version. Acceptance: the selected dependency versions resolve, strict builds and required upstream checks pass across the selected platforms, and product regressions and relevant dependency audits pass or have explicitly recorded unresolved findings. Recheck scheduler timings and cache invalidation after dependency changes. Do not backport unreleased dependency fixes or features.

## Completion and reconciliation

The restart implementation is complete only when all seven original changes and the approved supporting changes have their stated evidence, and every old-main item below has a recorded disposition. Account/billing implementation and billable-token definitions are explicitly deferred; they are outside this restart's implementation acceptance scope. Updating this plan or producing an intermediate build does not establish completion. Before declaring completion, compare the final branch with the fixed old-main source commit, including changes outside `patches/`, and replace pending entries with migrated commit identities, verified upstream equivalents, or exact retained leftovers. Keep unresolved security and compatibility findings explicit.

Local build outputs and dependency caches belong under `.cat/work/temp/build-caches/` with explicit ownership and effective paths. Use the maintained local Rust helper once the new branch contains the required tooling; establish an equivalent explicit storage boundary for the bare upstream baseline before that helper exists. Each execution owns its temporary artifacts and cleanup after their last required consumer exits. Record any retained evidence or cache path, its owner, and its named next consumer.

## Checkpoint coverage and scope decisions

The source review covers all 648 tracked paths at the fixed old-main commit, grouped by responsibility: 185 patch-area files (including 143 patches), 259 tooling files, 86 GitHub files, 41 vendored upstream workflow files, 34 CAT files, 15 scripts, 15 documentation/assets files, and 13 root or license files. The review uses the tracked path inventory, patch README intent/scope headings, tooling command contracts, maintained developer documentation, and selected workflow/script bodies. It classifies migration scope; it does not establish runtime correctness or inspect every implementation line.

The original checkpoints cover upstream baseline setup (1), caching and scheduler behavior (2–3), product behavior (4), warning repairs and enforcement (5–6), stable Codex dependency updates (7), and deferral of unreleased dependency backports (8). Test-fixture repairs needed by those changes belong with their responsible change. New toolchain versions, packaging, release policy, and repository management do not become authorized merely because an existing patch combines them with covered work.

The following dispositions record the user-selected scope. The supporting checkboxes below implement retained capabilities at their stated prerequisites. D2 excludes standalone SDK publication; upstream SDK checks remain part of verification where required. Technical account enforcement, metering, billing implementation, and billable-token definitions are deferred to a later milestone. Keep optional changes out of the default upstream baseline. Source paths below refer to the fixed old-main commit and remain retrievable with `git show` after switching branches.

| ID | Scope not explicitly selected by the checkpoints | Old-main evidence | Recommendation / pending decision |
| --- | --- | --- | --- |
| D1 | JDK 27/Maven tooling and bundled Java runtime; retirement of project-owned Python tooling | `tooling/MIGRATION.md`, `tooling/bin/codex-tooling`, `tooling/{bootstrap,cli,distribution}/` | Approved: keep Java/Maven and its bundled runtime; migrate commands only when needed by approved steps, after the default baseline. |
| D2 | Upstream TypeScript SDK and downstream integration checks | `tooling/MIGRATION.md`, `.github/workflows/sdk.yml`, `.github/scripts/buildbuddy-ci.sh` | Omit standalone SDK publication and a separate SDK migration milestone. Publish patched Codex packages only. Retain upstream source/checks where needed to verify Codex; this is not a HoldMySpot SDK. |
| D3 | Installable package layouts, both CLI and app-server variants, resource/helper binaries, archive formats, and installers | `scripts/codex_package/README.md`, `.github/scripts/build-codex-package-archive.sh`, `tooling/MIGRATION.md` | Approved: retain both package variants, required resources, installers, and archive capabilities after product integration. |
| D4 | Public and restricted early-access npm package families, local Verdaccio testing, and optional public npm publication | `docs/NPM_RELEASES.md`, release workflow's npm job | Local registry only: retain local package assembly, Verdaccio publication, and installation tests. Omit public npm publication and restricted early-access package families. |
| D5 | Periodic upstream-release discovery and opt-in automatic release requests | `.github/workflows/upstream-release-check.yml`, `.github/workflows/build-release.yml` | Approved: retain discovery and manual release execution; defer automatic publication until release acceptance passes. |
| D6 | Vendor version/build numbers, immutable source tags and assets, latest-release ordering, artifact reuse for publication retries, manifests, SHA-256 checksums, source provenance, attestations, and reproduction tooling | `README.md`, `scripts/verify-release.sh`, `scripts/reproduce-release.sh`, `.github/workflows/build-release.yml`, tooling release command contracts | Approved: retain release identity, immutable releases, retries, verification, attestations, and reproduction tooling as a separate milestone. |
| D7 | Standalone V8 release/canary workflows and pinned patched-zsh artifact production | `.github/workflows/{rusty-v8-release,v8-canary,rust-release-zsh}.yml`, `.github/zsh-commit`, `scripts/codex_package/zsh-exec-wrapper.patch` | Approved conditionally: retain only artifact production required by the selected stable release or approved product behavior; defer independent legacy services. |
| D8 | Specific hosting/storage policies: BuildBuddy remote execution and snapshots, GitHub native runners, public GHCR compiled caches, two-stable-release retention, one-day Actions artifact retention | `docs/CI_RUNNERS.md`, `docs/BUILD_CACHE.md`, `.github/workflows/cache-cleanup.yml`, `.github/scripts/buildbuddy-*.sh` | Approved: retain suitable BuildBuddy/GitHub/GHCR providers and existing two-stable-release cache / one-day Actions artifact retention policies. GitHub execution must use free standard hosted runners; paid larger runners are excluded. |
| D9 | Toolchain upgrades independent of updating Codex's library dependencies | `patches/holdmyspot-com/codex-unleashed/issue-60/`, LLVM/native-tool upgrade patches in issue 61 | Deferred: optional toolchain upgrades follow a passing default baseline; move only demonstrated prerequisites earlier. |
| D10 | Additional downstream repository gates: manifest/lint alignment, TUI dependency boundaries, unused dependency checks, blob-size policy, CodeQL, and tooling CI | tooling policy command contracts, `.github/workflows/{repo-checks,blob-size-policy,codeql,tooling-ci}.yml` | Approved: retain relevant downstream checks incrementally; warning enforcement starts only in step 6. |
| D11 | Project website, logos/badges, installation and maintainer documentation, source-available license, third-party notices, terms/privacy/refund/support and early-access policies | `docs/`, `LICENSE.md`, `licenses/`, `README.md`, `SUPPORT.md`, `SECURITY.md`, `.github/workflows/update-badges.yml` | Approved with revised commercial policy: retain identity, website/docs, applicable notices, and HoldMySpot licensing. Replace paid early-access subscriptions and funding-based access language with account-required output-token usage billing; pricing tiers remain unpublished and unspecified. |
| D12 | Automatically close outside pull requests and unfunded issues, plus funding/quote issue templates | `.github/workflows/{close-pull-requests,close-unfunded-issues}.yml`, `.github/ISSUE_TEMPLATE/`, `CONTRIBUTING.md` | Do not change issues. Omit issue-closing automation and issue mutation from migration. Keep PR-closing automation deferred; do not inherit funding-based issue templates or policies. |
| D13 | Project-specific CAT coding/documentation rules, regression fixtures for those rules, and repository formatting/ignore settings | `.cat/`, `.codespell*`, `.gitattributes`, `.gitignore` | Approved: retain applicable CAT guidance, its regression fixtures, and repository hygiene, reviewing obsolete requirements before copying. |

## Supporting changes approved for migration

These changes supplement the original seven-step sequence. Introduce prerequisites only when the named consuming step needs them; packaging and release milestones follow the cumulative product build. Preserve verified earlier results and rerun checks whose inputs change.

- [ ] **Retain applicable repository guidance and hygiene (D13).** Review CAT rules and regression fixtures, ignore settings, attributes, and spelling settings against the new layout; migrate applicable content without importing obsolete requirements. Acceptance: guidance references resolve and hygiene settings keep generated state outside commits.
- [ ] **Migrate needed Java tooling (D1).** After step 1, bring across the smallest command set required by caching, scheduling, checks, or packaging. Acceptance: the relevant Maven verification and assembled-runtime checks pass on supported native hosts; the commands work without project-owned Python tooling or separately installed Java at runtime.
- [ ] **Integrate selected cache providers and retention (D8).** With steps 2–3, retain suitable BuildBuddy execution/snapshots, native GitHub runners, public GHCR caches, two-stable-release cache retention, and one-day Actions artifact retention. Acceptance: cache and execution handoffs work, secret material stays out of public caches, and retention previews preserve required retained versions before any authorized deletion.
- [ ] **Restore applicable downstream checks (D10).** Add relevant manifest/lint consistency, TUI boundaries, unused dependency, blob-size, CodeQL, and tooling checks alongside their owning changes. Acceptance: each maintained check runs at its real boundary; warnings remain nonfatal through step 5 and strict policy starts in step 6.
- [ ] **Retain required auxiliary artifact builds (D7).** Establish which V8/canary and patched-zsh artifact paths the selected release and approved product actually consume; retain only those required paths. Acceptance: those builds deliver verified artifacts to their consuming checks; unused standalone services remain deferred.
- [ ] **Restore installable CLI and app-server packages (D3).** After step 4, integrate required helpers/resources, installers, and supported archive formats. Acceptance: each selected platform installs and runs the correct variant with its resources, and independent consumers verify the archives.
- [ ] **Support npm packages through a local registry only (D4).** Build, publish to local Verdaccio, install, and smoke-test the retained packages. Acceptance: local consumers resolve the right native package and executable, and no public npm or private early-access publication route is enabled.
- [ ] **Restore release identity and verification (D6).** After cumulative strict builds and packaging pass, retain vendor build numbers, immutable tags/assets, latest ordering, publication retry inputs, manifests/checksums, provenance, attestations, and reproduction scripts. Acceptance: independent verification checks the actual package and source identities, reproduction exercises the documented boundary, and retries preserve the original artifact identity. Publication itself requires a separate instruction.
- [ ] **Restore upstream discovery and manual releases (D5).** After release verification is ready, retain stable-release detection and manual release execution. Acceptance: discovery selects the correct missing release and errors preserve existing state; manual execution uses the verified release pipeline. Automatic publication remains deferred.
- [ ] **Publish project identity and HoldMySpot licensing with revised usage policies (D11).** Use the reviewed template at [HoldMySpot Source-Available License 1.0](https://holdmyspot.com/source-available-license-1.0.md) and apply it to company-owned additions/modifications, preserving upstream and third-party licenses/notices. Retain website, branding, badges, and relevant documentation. Remove paid early-access subscriptions, restricted early-access distribution, and funding-based support/access promises; early-access timing carries no separate charge. Record the selected account and output-token billing direction in this plan; defer customer-facing billing documentation and pricing tiers to the later billing milestone. Acceptance: repository, package, website, and policy copies agree with the approved license and usage model; no rate or allowance is invented. Fill the License Notice with the licensor, covered materials, copyright, and applicable Usage Terms and Privacy Notice locations; include both the License Terms and accompanying Explanation in distributions. Completing that notice does not modify the template. Keep account, metering, and pricing requirements in the separate Usage Terms when that later milestone is ready.

## Account and usage billing: decisions before implementation

The selected product model makes source visible, requires a HoldMySpot account for use, and bills output-token usage under pricing tiers published later. No fee is charged for early-access status. These facts replace old-main subscription and funding-based access policies.

Technical authentication, metering, billing enforcement, and the billable output-token definition are deferred to a later milestone. HoldMySpot usage charges are additional to users' model-provider charges; detailed customer documentation of that distinction is also deferred. Before implementing the later milestone, settle the token definition, identify existing HoldMySpot service/API contracts, and define supported failure/offline behavior. Keep pricing values unresolved until the owner publishes them; no charges or payment operations are authorized by this plan.

The owner-selected template is `https://holdmyspot.com/source-available-license-1.0.md`. The complete Markdown template is reviewed following a successful HTTPS request with `User-Agent: Mozilla/5.0` and `Accept: text/plain,text/markdown,*/*`; requests through the browser retrieval tool and the default Python client return access errors. Section 3 places account/authentication, allowances, and required metrics collection/reporting in separately disclosed Usage Terms. It does not supply the project's pricing tiers or token-accounting definition. Old main's license remains comparison material; use the owner-selected template for adoption.

## Deferred dependency concerns

Do not modify dependency source to backport security fixes or features absent from stable releases. Record the affected package, exact version, canonical issue/advisory, observed impact, availability in stable, and required future decision here. A published stable dependency update belongs in step 7; an unreleased backport stays deferred. Recheck applicability against the selected baseline before treating an old patch description as a current vulnerability finding.

Existing old-main candidates for review:

- `patches/openai/codex/security-RUSTSEC-2024-0449/`: maintained YAML parser replacement and Bazel dependency aliases.
- `patches/openai/codex/security-RUSTSEC-2026-0346/`: Rustls update; related advisory identifiers 0347–0349.
- `patches/openai/codex/security-RUSTSEC-2026-0367/`: ZIP update; related advisory identifiers 0368–0369.
- Dependency source changes inside the mixed issue-61 group, including ICU mutex handling and the AWS-LC Windows TLS callback backport. Classify each against the selected release before considering migration.
- Additional unreleased fixes or desired dependency features: populate from the patch-by-patch review and new diagnostics. These concerns remain discussion items rather than implicit authorization to fix them.

New baseline audit findings, with unchanged locked dependency versions and RustSec database revision `21d909e4c8bd4e14c7aea58bf980c881a0053ab1`:

- `crypto-bigint 0.5.5`: [RUSTSEC-2026-0387](https://rustsec.org/advisories/RUSTSEC-2026-0387), timing variability in operations intended to be constant-time. The advisory lists `>=0.7.6` as patched. The dependency graph includes `elliptic-curve` through `p256`/`age`; exploitability in Codex remains unverified. Investigate a supported stable dependency upgrade in step 7, without backporting dependency source.
- `ctutils 0.4.2`: [RUSTSEC-2026-0385](https://rustsec.org/advisories/RUSTSEC-2026-0385), mask-generation and comparison timing issues. The advisory lists `>=0.4.4` as patched. The graph includes `digest`/`hmac`/`pbkdf2`/`zip`; exploitability in Codex remains unverified. Investigate a supported stable dependency upgrade in step 7. The current audit fails; do not report it as passing or silently suppress the findings.

## Old-main changes not yet migrated

At initialization all 143 old-main patch files are pending. This inventory names migration candidates, not verified requirements or approved deletions. The implementing agent updates each disposition as work proceeds. For a mixed patch, record migrated and retained hunks separately. Recover any item and its README from the fixed old-main commit with `git show <old-main-source-commit>:<path>`.

Non-patch areas also remain pending: `.cat` rules and tooling configuration; `.github` CI, release, repository and cache workflows; `tooling`; `scripts`; `MODULE.bazel` and other build configuration; `vendor`; package metadata; project/product documentation; licenses and policies; and repository hygiene files. Inventory their individual differences before final reconciliation; a directory summary does not establish that every change is migrated or discarded.

| Old-main patch path | Disposition / new commit / remaining concern |
| --- | --- |
| `patches/holdmyspot-com/codex-unleashed/background-terminal-transcript-hint/background-terminal-transcript-hint.patch` | Pending review; not migrated. |
| `patches/holdmyspot-com/codex-unleashed/base-configuration/codex-unleashed-configuration.patch` | Pending review; not migrated. |
| `patches/holdmyspot-com/codex-unleashed/branding-and-app-server-distribution/add-branding-and-build-metadata.patch` | Pending review; not migrated. |
| `patches/holdmyspot-com/codex-unleashed/branding-and-app-server-distribution/daemon-public-releases.patch` | Pending review; not migrated. |
| `patches/holdmyspot-com/codex-unleashed/branding-and-app-server-distribution/fix-branding-test-snapshots.patch` | Pending review; not migrated. |
| `patches/holdmyspot-com/codex-unleashed/branding-and-app-server-distribution/warn-on-openai-app-server.patch` | Pending review; not migrated. |
| `patches/holdmyspot-com/codex-unleashed/collapsed-tool-activity/collapsed-tool-activity.patch` | Pending review; not migrated. |
| `patches/holdmyspot-com/codex-unleashed/issue-34776/avoid-duplicate-startup-skills-refresh.patch` | Pending review; not migrated. |
| `patches/holdmyspot-com/codex-unleashed/issue-34776/avoid-slow-agent-switching-and-resume.patch` | Pending review; not migrated. |
| `patches/holdmyspot-com/codex-unleashed/issue-34776/background-session-skill-warmup.patch` | Pending review; not migrated. |
| `patches/holdmyspot-com/codex-unleashed/issue-34776/defer-implicit-skill-index.patch` | Pending review; not migrated. |
| `patches/holdmyspot-com/codex-unleashed/issue-35/0001-preserve-interrupted-mailbox-work.patch` | Pending review; not migrated. |
| `patches/holdmyspot-com/codex-unleashed/issue-35/0002-resume-parent-on-subagent-completion.patch` | Pending review; not migrated. |
| `patches/holdmyspot-com/codex-unleashed/issue-35/0003-defer-goal-continuation-to-live-children.patch` | Pending review; not migrated. |
| `patches/holdmyspot-com/codex-unleashed/issue-47/bash-snapshot-replay-startup.patch` | Pending review; not migrated. |
| `patches/holdmyspot-com/codex-unleashed/issue-47390/expanded-diff-previews.patch` | Pending review; not migrated. |
| `patches/holdmyspot-com/codex-unleashed/issue-48/wsl-explicit-pid-procfs.patch` | Pending review; not migrated. |
| `patches/holdmyspot-com/codex-unleashed/issue-49/descriptor-fallback-fixture.patch` | Pending review; not migrated. |
| `patches/holdmyspot-com/codex-unleashed/issue-50/composer-platform-fixture.patch` | Pending review; not migrated. |
| `patches/holdmyspot-com/codex-unleashed/issue-51/kitty-file-reference-assertion.patch` | Pending review; not migrated. |
| `patches/holdmyspot-com/codex-unleashed/issue-52/brokered-startup-completion-fixture.patch` | Pending review; not migrated. |
| `patches/holdmyspot-com/codex-unleashed/issue-53/metadata-message-budget-scheduling.patch` | Pending review; not migrated. |
| `patches/holdmyspot-com/codex-unleashed/issue-54/updater-executable-handoff.patch` | Pending review; not migrated. |
| `patches/holdmyspot-com/codex-unleashed/issue-55/cli-version-identity.patch` | Pending review; not migrated. |
| `patches/holdmyspot-com/codex-unleashed/issue-55/schema-test-data.patch` | Pending review; not migrated. |
| `patches/holdmyspot-com/codex-unleashed/issue-56/unused-core-test-imports.patch` | Pending review; not migrated. |
| `patches/holdmyspot-com/codex-unleashed/issue-57/app-server-time-provider-initialization.patch` | Pending review; not migrated. |
| `patches/holdmyspot-com/codex-unleashed/issue-58/seatbelt-metadata-order-fixtures.patch` | Pending review; not migrated. |
| `patches/holdmyspot-com/codex-unleashed/issue-59/update-yanked-spin.patch` | Pending review; not migrated. |
| `patches/holdmyspot-com/codex-unleashed/issue-60/stable-build-toolchains.patch` | Pending review; not migrated. |
| `patches/holdmyspot-com/codex-unleashed/issue-61/codespell-isolated-tool-environment.patch` | Pending review; not migrated. |
| `patches/holdmyspot-com/codex-unleashed/issue-61/formatter-warning-policy.patch` | Pending review; not migrated. |
| `patches/holdmyspot-com/codex-unleashed/issue-61/icu-mutex-thread-safety.patch` | Pending review; not migrated. |
| `patches/holdmyspot-com/codex-unleashed/issue-61/libcxx-explicit-runtime-configuration.patch` | Pending review; not migrated. |
| `patches/holdmyspot-com/codex-unleashed/issue-61/native-and-rust-warning-policy.patch` | Pending review; not migrated. |
| `patches/holdmyspot-com/codex-unleashed/issue-61/native-aws-lc-windows-tls.patch` | Pending review; not migrated. |
| `patches/holdmyspot-com/codex-unleashed/issue-61/native-bindgen-preprocessor-policy.patch` | Pending review; not migrated. |
| `patches/holdmyspot-com/codex-unleashed/issue-61/native-mingw-function-guard.patch` | Pending review; not migrated. |
| `patches/holdmyspot-com/codex-unleashed/issue-61/native-prost-rule-compatibility.patch` | Pending review; not migrated. |
| `patches/holdmyspot-com/codex-unleashed/issue-61/native-voice-cmake-initial-cache.patch` | Pending review; not migrated. |
| `patches/holdmyspot-com/codex-unleashed/issue-61/native-voice-meson-resource-directory.patch` | Pending review; not migrated. |
| `patches/holdmyspot-com/codex-unleashed/issue-61/native-voice-warning-diagnostics.patch` | Pending review; not migrated. |
| `patches/holdmyspot-com/codex-unleashed/issue-61/native-voice-warning-video-2-enums.patch` | Pending review; not migrated. |
| `patches/holdmyspot-com/codex-unleashed/issue-61/native-voice-z-sdk-relocation.patch` | Pending review; not migrated. |
| `patches/holdmyspot-com/codex-unleashed/issue-61/native-voice-z-sdk-sandbox-prefix.patch` | Pending review; not migrated. |
| `patches/holdmyspot-com/codex-unleashed/issue-61/native-voice-z-symbol-tools.patch` | Pending review; not migrated. |
| `patches/holdmyspot-com/codex-unleashed/issue-61/remote-download-options.patch` | Pending review; not migrated. |
| `patches/holdmyspot-com/codex-unleashed/issue-61/remove-empty-test-placeholder.patch` | Pending review; not migrated. |
| `patches/holdmyspot-com/codex-unleashed/issue-61/stale-dependency-policy.patch` | Pending review; not migrated. |
| `patches/holdmyspot-com/codex-unleashed/issue-61/typescript-lint-warning-policy.patch` | Pending review; not migrated. |
| `patches/holdmyspot-com/codex-unleashed/issue-61/upgrade-llvm-23-windows-driver.patch` | Pending review; not migrated. |
| `patches/holdmyspot-com/codex-unleashed/issue-61/upgrade-llvm-23-windows-static-def-parser.patch` | Pending review; not migrated. |
| `patches/holdmyspot-com/codex-unleashed/issue-61/upgrade-native-pkgconf-bootstrap.patch` | Pending review; not migrated. |
| `patches/holdmyspot-com/codex-unleashed/issue-61/upgrade-native-rust-runtime-selection.patch` | Pending review; not migrated. |
| `patches/holdmyspot-com/codex-unleashed/issue-61/upgrade-openssl.patch` | Pending review; not migrated. |
| `patches/holdmyspot-com/codex-unleashed/issue-61/upgrade-z-native-tools.patch` | Pending review; not migrated. |
| `patches/holdmyspot-com/codex-unleashed/issue-61/upgrade-zz-seatbelt-formatting.patch` | Pending review; not migrated. |
| `patches/holdmyspot-com/codex-unleashed/issue-61/upgrade-zzz-make-getopt-prototypes.patch` | Pending review; not migrated. |
| `patches/holdmyspot-com/codex-unleashed/issue-61/vendored-native-build-script-modes.patch` | Pending review; not migrated. |
| `patches/holdmyspot-com/codex-unleashed/issue-61/z-llvm-assembly-action-flags.patch` | Pending review; not migrated. |
| `patches/holdmyspot-com/codex-unleashed/issue-61/z-meson-setup-diagnostics.patch` | Pending review; not migrated. |
| `patches/holdmyspot-com/codex-unleashed/issue-61/z-opus-ninja-executable.patch` | Pending review; not migrated. |
| `patches/holdmyspot-com/codex-unleashed/issue-61/z-opus-search-directories.patch` | Pending review; not migrated. |
| `patches/holdmyspot-com/codex-unleashed/issue-61/z-python-explicit-initializers.patch` | Pending review; not migrated. |
| `patches/holdmyspot-com/codex-unleashed/issue-61/z-v8-python-platforms.patch` | Pending review; not migrated. |
| `patches/holdmyspot-com/codex-unleashed/issue-61/z-xz-windows-arm64-filter.patch` | Pending review; not migrated. |
| `patches/holdmyspot-com/codex-unleashed/issue-61/zz-abseil-windows-compiler-selection.patch` | Pending review; not migrated. |
| `patches/holdmyspot-com/codex-unleashed/issue-61/zz-native-tool-executable-configuration.patch` | Pending review; not migrated. |
| `patches/holdmyspot-com/codex-unleashed/issue-61/zz-native-z-hermetic-python-bootstrap.patch` | Pending review; not migrated. |
| `patches/holdmyspot-com/codex-unleashed/issue-61/zz-openssl-zz-gnu-clang-selection.patch` | Pending review; not migrated. |
| `patches/holdmyspot-com/codex-unleashed/issue-61/zz-v8-clang-mingw-detection.patch` | Pending review; not migrated. |
| `patches/holdmyspot-com/codex-unleashed/issue-61/zz-v8-windows-stack-assembly.patch` | Pending review; not migrated. |
| `patches/holdmyspot-com/codex-unleashed/issue-61/zzz-direct-dependency-versions.patch` | Pending review; not migrated. |
| `patches/holdmyspot-com/codex-unleashed/issue-61/zzz-libffi-darwin-linker-selection.patch` | Pending review; not migrated. |
| `patches/holdmyspot-com/codex-unleashed/issue-61/zzz-libffi-native-library-directory.patch` | Pending review; not migrated. |
| `patches/holdmyspot-com/codex-unleashed/issue-61/zzz-libffi-native-module-probe.patch` | Pending review; not migrated. |
| `patches/holdmyspot-com/codex-unleashed/issue-61/zzz-native-macos-test-runner.patch` | Pending review; not migrated. |
| `patches/holdmyspot-com/codex-unleashed/issue-61/zzz-ninja-elapsed-time.patch` | Pending review; not migrated. |
| `patches/holdmyspot-com/codex-unleashed/issue-61/zzz-strum-direct-dependency.patch` | Pending review; not migrated. |
| `patches/holdmyspot-com/codex-unleashed/issue-61/zzz-v8-msvc-driver-flags.patch` | Pending review; not migrated. |
| `patches/holdmyspot-com/codex-unleashed/issue-61/zzz-windows-msvc-assembly.patch` | Pending review; not migrated. |
| `patches/holdmyspot-com/codex-unleashed/issue-61/zzzz-0001-xz-windows-driver-flags.patch` | Pending review; not migrated. |
| `patches/holdmyspot-com/codex-unleashed/issue-61/zzzz-0002-cargo-c-standard.patch` | Pending review; not migrated. |
| `patches/holdmyspot-com/codex-unleashed/issue-61/zzzz-0003-aws-lc-c-standard.patch` | Pending review; not migrated. |
| `patches/holdmyspot-com/codex-unleashed/issue-61/zzzz-0004-rust-wrapper-parameter-files.patch` | Pending review; not migrated. |
| `patches/holdmyspot-com/codex-unleashed/issue-61/zzzz-0005-make-c-standard.patch` | Pending review; not migrated. |
| `patches/holdmyspot-com/codex-unleashed/issue-61/zzzz-0006-cargo-runtime-features.patch` | Pending review; not migrated. |
| `patches/holdmyspot-com/codex-unleashed/issue-61/zzzz-0007-ci-msvc-runtime-license.patch` | Pending review; not migrated. |
| `patches/holdmyspot-com/codex-unleashed/issue-61/zzzz-0008-darwin-large-unwind.patch` | Pending review; not migrated. |
| `patches/holdmyspot-com/codex-unleashed/issue-61/zzzz-0010-explicit-python-zipapps.patch` | Pending review; not migrated. |
| `patches/holdmyspot-com/codex-unleashed/issue-61/zzzz-0011-windows-execution-platform-owner.patch` | Pending review; not migrated. |
| `patches/holdmyspot-com/codex-unleashed/issue-61/zzzz-0013-rust-test-deadline-policy.patch` | Pending review; not migrated. |
| `patches/holdmyspot-com/codex-unleashed/issue-61/zzzz-0014-v8-windows-registry-link.patch` | Pending review; not migrated. |
| `patches/holdmyspot-com/codex-unleashed/issue-61/zzzz-0015-nmake-bootstrap-parallelism.patch` | Pending review; not migrated. |
| `patches/holdmyspot-com/codex-unleashed/issue-61/zzzz-0018-native-test-runfiles.patch` | Pending review; not migrated. |
| `patches/holdmyspot-com/codex-unleashed/issue-61/zzzz-0019-windows-test-executable-extensions.patch` | Pending review; not migrated. |
| `patches/holdmyspot-com/codex-unleashed/issue-61/zzzz-0021-windows-launcher-line-endings.patch` | Pending review; not migrated. |
| `patches/holdmyspot-com/codex-unleashed/issue-61/zzzz-0022-bazel-java-tooling-entry-point.patch` | Pending review; not migrated. |
| `patches/holdmyspot-com/codex-unleashed/issue-61/zzzz-0025-native-voice-meson-sanity-flags.patch` | Pending review; not migrated. |
| `patches/holdmyspot-com/codex-unleashed/issue-61/zzzz-0030-v8-warning-feature-guards.patch` | Pending review; not migrated. |
| `patches/holdmyspot-com/codex-unleashed/issue-61/zzzz-0032-cmake-install-prefix-path.patch` | Pending review; not migrated. |
| `patches/holdmyspot-com/codex-unleashed/issue-61/zzzz-0037-first-party-clippy.patch` | Pending review; not migrated. |
| `patches/holdmyspot-com/codex-unleashed/issue-61/zzzz-0038-windows-workspace-status.patch` | Pending review; not migrated. |
| `patches/holdmyspot-com/codex-unleashed/issue-61/zzzz-0040-windows-test-profile.patch` | Pending review; not migrated. |
| `patches/holdmyspot-com/codex-unleashed/issue-61/zzzz-0041-v8-concurrent-initialization.patch` | Pending review; not migrated. |
| `patches/holdmyspot-com/codex-unleashed/issue-61/zzzz-0042-windows-test-launcher-startup.patch` | Pending review; not migrated. |
| `patches/holdmyspot-com/codex-unleashed/issue-61/zzzz-0043-macos-remote-thread-budget.patch` | Pending review; not migrated. |
| `patches/holdmyspot-com/codex-unleashed/issue-61/zzzz-0046-settlement-test-yields.patch` | Pending review; not migrated. |
| `patches/holdmyspot-com/codex-unleashed/issue-61/zzzz-0047-guardian-prewarm-mock-listeners.patch` | Pending review; not migrated. |
| `patches/holdmyspot-com/codex-unleashed/issue-61/zzzz-0048-windows-test-dll-paths.patch` | Pending review; not migrated. |
| `patches/holdmyspot-com/codex-unleashed/issue-61/zzzz-0049-native-rpath-feature.patch` | Pending review; not migrated. |
| `patches/holdmyspot-com/codex-unleashed/issue-61/zzzz-0051-rust-linker-warning-policy.patch` | Pending review; not migrated. |
| `patches/holdmyspot-com/codex-unleashed/issue-61/zzzz-0052-windows-markdown-fixture.patch` | Pending review; not migrated. |
| `patches/holdmyspot-com/codex-unleashed/issue-61/zzzz-0054-windows-voice-negative-fixture.patch` | Pending review; not migrated. |
| `patches/holdmyspot-com/codex-unleashed/issue-61/zzzz-0057-mcp-binding-counter-isolation.patch` | Pending review; not migrated. |
| `patches/holdmyspot-com/codex-unleashed/issue-61/zzzz-0059-selected-plugin-pid-deadline.patch` | Pending review; not migrated. |
| `patches/holdmyspot-com/codex-unleashed/issue-61/zzzz-0061-gov-cloud-quit-pty.patch` | Pending review; not migrated. |
| `patches/holdmyspot-com/codex-unleashed/issue-61/zzzz-0062-sqlite-startup-close.patch` | Pending review; not migrated. |
| `patches/holdmyspot-com/codex-unleashed/issue-61/zzzz-0063-bazel-failed-test-logs.patch` | Pending review; not migrated. |
| `patches/holdmyspot-com/codex-unleashed/issue-61/zzzz-0064-bazel-cache-save-preflight.patch` | Pending review; not migrated. |
| `patches/holdmyspot-com/codex-unleashed/issue-61/zzzz-0068-macos-daemon-fixture-concurrency.patch` | Pending review; not migrated. |
| `patches/holdmyspot-com/codex-unleashed/issue-61/zzzz-0069-windows-profile-unload-polling.patch` | Pending review; not migrated. |
| `patches/holdmyspot-com/codex-unleashed/issue-61/zzzz-0070-zbus-6-advisories.patch` | Pending review; not migrated. |
| `patches/holdmyspot-com/codex-unleashed/issue-61/zzzz-0071-macos-simulator-cleanup.patch` | Pending review; not migrated. |
| `patches/holdmyspot-com/codex-unleashed/issue-61/zzzz-0072-native-linker-sdk-analysis.patch` | Pending review; not migrated. |
| `patches/holdmyspot-com/codex-unleashed/issue-61/zzzz-0073-buildbuddy-cache-chunking.patch` | Pending review; not migrated. |
| `patches/holdmyspot-com/codex-unleashed/issue-61/zzzz-0074-macos-pty-fixture-concurrency.patch` | Pending review; not migrated. |
| `patches/holdmyspot-com/codex-unleashed/issue-62/classify-wrapped-tls-errors.patch` | Pending review; not migrated. |
| `patches/holdmyspot-com/codex-unleashed/issue-63/protect-root-metadata-fixture-launcher.patch` | Pending review; not migrated. |
| `patches/holdmyspot-com/codex-unleashed/issue-64/isolate-no-marker-skills-fixture.patch` | Pending review; not migrated. |
| `patches/holdmyspot-com/codex-unleashed/issue-65/isolate-mitm-path-policy-from-dns.patch` | Pending review; not migrated. |
| `patches/holdmyspot-com/codex-unleashed/issue-66/provide-empty-roots-launcher-dependencies.patch` | Pending review; not migrated. |
| `patches/holdmyspot-com/codex-unleashed/issue-67/offline-marketplace-migration-fixture.patch` | Pending review; not migrated. |
| `patches/holdmyspot-com/codex-unleashed/issue-68/canonical-sandbox-helper-fixture.patch` | Pending review; not migrated. |
| `patches/holdmyspot-com/codex-unleashed/issue-69/integration-compiler-environment.patch` | Pending review; not migrated. |
| `patches/holdmyspot-com/codex-unleashed/issue-70/readable-commands.patch` | Pending review; not migrated. |
| `patches/holdmyspot-com/codex-unleashed/schema/refresh-config-schema.patch` | Pending review; not migrated. |
| `patches/openai/codex/issue-29872/release-build-warnings.patch` | Pending review; not migrated. |
| `patches/openai/codex/issue-32915/legacy-delete-ci-exclusion.patch` | Pending review; not migrated. |
| `patches/openai/codex/security-RUSTSEC-2024-0449/maintained-yaml-parser.patch` | Pending review; not migrated. |
| `patches/openai/codex/security-RUSTSEC-2024-0449/yaml-parser-bazel-aliases.patch` | Pending review; not migrated. |
| `patches/openai/codex/security-RUSTSEC-2026-0346/update-rustls.patch` | Pending review; not migrated. |
| `patches/openai/codex/security-RUSTSEC-2026-0367/zip-security-update.patch` | Pending review; not migrated. |
