# Stable upstream build baseline

## Source and scope

- **upstream_repository**: https://github.com/openai/codex
- **upstream_tag**: `rust-v0.164.0`, pinned in [upstream-ref](../.github/upstream-ref)
- **upstream_commit**: `6aaac694803004a63c6f393d153c96d22521a6b9`
- **rust_toolchain**: `1.99.1`, as declared by upstream `codex-rs/rust-toolchain.toml`
- **local_platform**: Linux x86_64 GNU
- **release_targets**: Linux x86_64/ARM64 musl, macOS Intel/Apple Silicon, Windows x86_64/ARM64 MSVC
- **source_changes**: Cargo regenerated workspace-version metadata; the verified checker and formatter repairs were applied after the successful unmodified-source Cargo build
- **warning_policy**: upstream defaults; no additional warnings-as-errors flags
- **features_and_profile**: default features and default development profile for the local baseline

The local default build establishes the unmodified upstream development baseline. It does not establish release-target compilation, packaging, tests, or a clean warning inventory. Each gate below needs its own evidence before step 1 completes.

## Maintained commands and acceptance gates

Upstream `docs/install.md` specifies `cargo build` from `codex-rs`. The root `justfile` supplies formatting and testing entry points. Upstream's release workflow uses Cargo as its release path; its parallel Bazel artifacts are advisory. Read the selected release's complete platform setup before running its target-specific build commands.

| Gate | Command or required evidence | State |
| --- | --- | --- |
| Exact source | Clean checkout at the pinned tag's commit | Passed |
| Local default build | `cd codex-rs` then `cargo build` | Passed on the fifth attempt; exit 0, 6 minutes 11.03 seconds wall time |
| Local executable | Built `codex --version` reports the selected upstream version | Passed: `codex-cli 0.164.0` |
| Formatting | `just fmt-check` from the upstream checkout root | Passed: all five maintained formatter groups |
| Rust tests | `just test` with the maintained local nextest profile | Both local runs interrupted after diagnosing environment differences; all 3 temporary-fixture regressions pass; native daemon checks still required |
| Additional upstream checks | Inventory maintained repository, lint, dependency, and platform check entry points; record individual commands and results | Unrun |
| Six release targets | Build the selected Cargo release targets with upstream prerequisites and default features; verify binaries on appropriate native hosts | Unrun |

### Repository checks executed

Commands run from the upstream root with Python bytecode writes disabled. Named logs and receipts live in the owned execution directory below. These results do not replace Rust or release-target checks.

| Command | Result | Evidence prefix |
| --- | --- | --- |
| `pnpm install --frozen-lockfile` | Passed with upstream-pinned pnpm 10.34.5; required SDK prepare build passed; npm configuration warnings retained | `pnpm-install` |
| `pnpm run format` | Passed | `pnpm-format` |
| `python3 .github/scripts/verify_cargo_workspace_manifests.py` | Failed on clean upstream; repaired checker, emitted patch and cumulative checker passed | `verify_cargo_workspace_manifests`, `manifest-repair-after`, `manifest-patch-verification`, `manifest-cumulative` |
| `python3 .github/scripts/verify_tui_core_boundary.py` | Passed | `verify_tui_core_boundary` |
| `python3 .github/scripts/verify_bazel_clippy_lints.py` | Passed | `verify_bazel_clippy_lints` |
| `python3 -m unittest discover -s .github/scripts -p 'test_resolve_python_cli_release.py'` | Passed, 12 tests | `test_resolve_python_cli_release` |
| `python3 -m unittest discover -s .github/scripts -p 'test_releases.py'` | Passed, 21 tests | `test_releases` |
| `python3 -m unittest discover -s scripts/codex_package -p 'test_*.py'` | Failed during imports: required `CODEX_REPO_ROOT` absent | `codex_package` |
| `CODEX_REPO_ROOT="$PWD" python3 -m unittest discover -s scripts/codex_package -p 'test_*.py'` | Passed, 27 tests | `codex_package-with-root` |
| `python3 -m unittest discover -s scripts/install -p 'test_*.py'` | Passed, 21 tests | `install` |
| `python3 -m unittest discover -s .github/scripts/macos-signing -p 'test_*.py'` | Passed, 13 tests | `macos-signing` |
| `uv run --no-project --with packaging==26.2 python -m unittest discover -s .github/scripts -p 'test_verify_pypi_release.py'` | Passed, 3 tests | `test_verify_pypi_release` |
| `./scripts/asciicheck.py README.md` | Passed | `readme-ascii` |
| `python3 scripts/readme_toc.py README.md` | Exited 0; upstream skips the check because README has no ToC markers | `readme-toc` |
| `just fmt-check` | Passed: Just, Rust, Bazel/Starlark, Python SDK and Python scripts | `just-fmt-check` |
| TypeScript SDK build and lint, maintained `pnpm -r --filter ./sdk/typescript run ...` commands | Passed with Node 26.12.1 and the Cargo-built CLI; upstream SDK CI instead selects Node 22 and a Bazel-built CLI | `typescript-build`, `typescript-lint` |
| TypeScript SDK tests | Failed: 47 passed, 2 failed; non-Git fixture inherited this checkout as an ancestor, and MCP conformance exceeded its maintained timeout | `typescript-test` |
| TypeScript non-Git-directory regression, unchanged source and CLI, `env -u TMPDIR pnpm exec jest --runTestsByPath tests/run.test.ts -t 'throws if working directory is not git'` | Passed: 1 passed, 23 unselected; restores the test-owned fixture's normal temporary-directory location | `typescript-non-git-standard-temp` |
| Python SDK frozen development dependency sync, Ruff lint and Ruff formatting check | Passed | `python-sdk-sync`, `python-sdk-lint`, `python-sdk-format` |
| Python SDK `uv run --only-group dev --frozen --no-sync pytest` | Failed: 268 passed, 38 skipped, 1 failed; root formatter command graph differs from the repository-wide Rust discovery assertion | `python-sdk-test` |
| TypeScript SDK tests with Node 22.23.4 and normal fixture temporary directories | Passed: all 49 tests, 5 suites; same Cargo-built CLI; 264.816 seconds | `typescript-node22` |
| Complete Python SDK suite with the formatter repair, isolated checkout | Passed: 271 passed, 38 skipped | `python-sdk-formatter-repair` |
| Formatter regressions after emitted patch applied to a fresh base | Passed: 7 tests | `formatter-patch-verification` |
| Real repository Rust formatting through the repaired driver | Passed: 7 bounded commands; existing nonfatal configuration warnings retained | `formatter-real-batched-check` |
| Cumulative Python SDK suite after both baseline repairs | Passed: 271 passed, 38 skipped | `python-sdk-cumulative` |
| Cumulative maintained `just fmt-check` | Passed: all 5 groups | `just-fmt-check-cumulative` |
| Three Rust regressions with normal fixture temporary directories | Passed: Git ancestry and both Unix-socket regressions; test execution 0.040 seconds after subset recompilation | `rust-standard-temp-focused` |
| Argument-comment-linter Python wrapper tests | Passed: 6 tests; native/nightly linter execution remains unrun | `argument-wrapper-tests` |
| Complete root GitHub helper-script unit suite, with packaging 26.2 | Passed: 82 tests | `github-script-tests` |
| `cargo shear`, upstream-pinned 1.11.2, without `--deny-warnings` | Exited 0 with 1 unlinked-file warning in `codex-core`; diagnostic retained for step 5 | `cargo-shear` |
| `cargo-deny --log-level warn --manifest-path ./codex-rs/Cargo.toml --all-features check`, upstream action's 0.19.0 | Failed: two dependency advisories; bans, licenses and sources pass | `cargo-deny` |

The package-test failure is an invocation prerequisite: `scripts/codex_package/targets.py` explicitly requires `CODEX_REPO_ROOT`. Supplying the actual upstream checkout root repairs the invocation without a source change. The manifest-check failure is separate: `codex-rs/code-mode/Cargo.toml` has no `[features]` table, but the checker retains a feature exception for it and rejects unused exceptions. [The baseline repair](../patches/upstream-baseline/README.md) removes only that obsolete exception. The existing checker failed on an isolated clean base, then passed after repair; the emitted `git format-patch` also applied with `git am` and passed on a second fresh base. Both temporary verification worktrees were removed. After the active Cargo build passed, the same patch was applied to that checkout and its cumulative checker passed. The final patch omits Git's optional mail signature; a fresh-base index application verified that its resulting checker blob exactly matches the tested commit.

The dependency audit uses the exact cargo-deny version and arguments selected by upstream's pinned action. Its official binary archive was verified against the release API SHA-256 digest `0e8c2aa59128612c90d9e09c02204e912f29a5b8d9a64671b94608cbe09e064f`. RustSec database revision `21d909e4c8bd4e14c7aea58bf980c881a0053ab1` reports `RUSTSEC-2026-0387` for locked `crypto-bigint 0.5.5` and `RUSTSEC-2026-0385` for locked `ctutils 0.4.2`. These are audit findings on unchanged dependency versions, not compilation failures. The authoritative advisory files list fixed versions `>=0.7.6` and `>=0.4.4`, respectively; application exploitability has not been established. Keep the failed audit explicit, retain the advisory database and diagnostics, and record the stable-update candidates in the plan without changing the baseline dependencies early. This result does not satisfy the required audit gate.

## Reproduce the local source and build

From this project's root, retrieve an independent upstream checkout:

```sh
git clone --depth 1 --branch "$(cat .github/upstream-ref)" https://github.com/openai/codex.git upstream
git -C upstream rev-parse HEAD
git -C upstream status --short
```

Require the commit above and a clean checkout before using its results. Install prerequisites through the selected upstream release's maintained instructions. Keep source changes out of the baseline and diagnose environment or dependency acquisition failures before proposing upstream source repairs.

Select fresh, task-owned storage beneath this project's build-cache directory. The executor removes this storage after its last build/check/diagnosis consumer exits. Source downloads and compiler output remain separate from published packages.

```sh
project_root="$PWD"
baseline_cache="$project_root/.cat/work/temp/build-caches/restart-baseline-6aaac69"
mkdir -p "$baseline_cache/cargo-home" "$baseline_cache/target" "$baseline_cache/tmp" "$baseline_cache/xdg" "$baseline_cache/uv"
export CARGO_HOME="$baseline_cache/cargo-home"
export CARGO_TARGET_DIR="$baseline_cache/target"
export TMPDIR="$baseline_cache/tmp"
export XDG_CACHE_HOME="$baseline_cache/xdg"
export UV_CACHE_DIR="$baseline_cache/uv"
```

The toolchain pin selects Rust 1.99.1. Installed toolchains remain available through the existing Rustup installation; any newly managed toolchain installation also belongs beneath the project build-cache directory. Do not introduce remote compiler caching or reuse old-main build output in this baseline.

On this host, first produce the native prefix, extracted distribution development files, pkg-config wrapper and verified V8 artifacts described below. These are required build inputs, not optional optimizations. With those outputs in the recorded layout, the successful default build uses:

```sh
export PATH="$baseline_cache/native-tools/cmake-3.31.8-linux-x86_64/bin:$baseline_cache/rust-tools/bin:$PATH"
export PKG_CONFIG_PATH="$baseline_cache/native-build-1/prefix/lib/pkgconfig:$baseline_cache/native-build-1/prefix/share/pkgconfig:$baseline_cache/distro-root/usr/lib/pkgconfig"
export PKG_CONFIG="$baseline_cache/native-tools/bin/pkg-config-relocated"
export LD_LIBRARY_PATH="$baseline_cache/native-build-1/prefix/lib:$baseline_cache/distro-root/usr/lib/x86_64-linux-gnu"
export RUSTY_V8_ARCHIVE="$baseline_cache/rusty_v8/librusty_v8_ptrcomp_sandbox_release_x86_64-unknown-linux-gnu.a.gz"
export RUSTY_V8_SRC_BINDING_PATH="$baseline_cache/rusty_v8/src_binding_ptrcomp_sandbox_release_x86_64-unknown-linux-gnu.rs"
cd upstream/codex-rs
cargo build
```

## Current execution ownership

The first clean `cargo build` exited 101 after 3 minutes 5.58 seconds. Its log is `cargo-build.log` and terminal receipt is `cargo-build.receipt` in the owned execution directory below. `gstreamer-sys` 0.25.2 failed its `pkg-config` probe for `gstreamer-1.0 >= 1.28`. The default workspace includes `codex-voice-host`; its Linux GNU dependencies explicitly select the GStreamer bindings' `v1_28` feature. No earlier passing baseline exists and no upstream source changed before this failure.

On this Ubuntu 24.04 host, GStreamer development files are absent and the configured distribution candidate is only 1.24.2. Installing that candidate would not satisfy the declared requirement. Upstream `third_party/voice/README.md` documents the native recipe and `sources.json` pins GStreamer 1.28.6 and its dependency archives. The selected recovery supplied those native development inputs using upstream's recipe, then reran the identical default Cargo build. It did not exclude `voice-host`, lower its version requirement, or bypass the probe.

CMake is also absent. An attempted Python package installation of `cmake==3.31.8` failed resolution without installing packages; the Python package repository does not supply that exact version. The alternative setup uses Kitware's official Linux binary archive and adjacent SHA-256 manifest for CMake 3.31.8, matching upstream's existing CMake version. Both setup attempts remain separate from source changes and build acceptance.

The CMake archive passed its adjacent SHA-256 check. All eleven native source archives were downloaded from upstream's manifest URLs, then verified and built by unmodified `third_party/voice/build_native.py`. The native recipe passed in 1 minute 53.87 seconds, producing `native-build-1/prefix` and `built.json` below the owned cache root. Its invocation used `--target x86_64-unknown-linux-gnu`, host GCC/G++, Make, pkg-config and Bash, and the verified CMake executable. The command and complete step logs are retained in `native-build-1.log` and the native output directory. `pkg-config` subsequently reported 1.28.6 for GStreamer core, app and audio.

The second `cargo build` exited 101 after 18.11 seconds on `alsa-sys` 0.4.0: the host has the ALSA runtime but no `alsa.pc` or development headers. CPAL, another voice-host dependency, requires ALSA on Linux. The exact installed runtime version is Ubuntu `libasound2t64=1.2.11-1ubuntu0.3`. Matching runtime and `libasound2-dev` packages were downloaded with `apt-get download` and extracted with `dpkg-deb --extract` into the owned `distro-root`; no system package installation was performed.

For local package relocation, an unchanged copy of `alsa.pc` is exposed under `distro-root/usr/lib/pkgconfig/` and pkg-config is invoked through a two-line wrapper adding `--define-prefix`. This placement matters: applying that option directly to the multiarch metadata directory incorrectly derives `usr/lib` as its prefix. The corrected probe returns existing `distro-root/usr/include` and `distro-root/usr/lib/x86_64-linux-gnu` paths. The third Cargo attempt adds those prerequisite search paths and the native prefix, with the same default build command and unchanged source.

The restart goal owns the temporary source checkout and command captures in `.cat/sessions/01a170c7-eade-7431-b337-01b356b20b2d/temp/baseline-3SlL5r/`, and the fresh build state in `.cat/work/temp/build-caches/restart-baseline-6aaac69/`. Their next consumers are the step-1 build, required checks, and root-cause diagnosis. Remove these owned paths only after those consumers finish, preserving material results in this document first. Other tasks' caches and checkouts are outside this cleanup scope.

The third Cargo attempt exited 101 after 13.48 seconds when V8 152.2.0 tried to download sandbox-enabled bindings from Denoland and received HTTP 404. The selected upstream release already handles this in `.github/actions/setup-rusty-v8/action.yml`: it supplies OpenAI's matching static library and bindings, verifies the downloaded checksum manifest against a digest pinned in `third_party/v8`, then verifies both artifacts. That exact action run block was extracted and executed for `x86_64-unknown-linux-gnu`; verification passed. `setup-rusty-v8.log`, its receipt and `rusty-v8.env` retain the result. This uses upstream's existing artifact configuration without changing V8 versions or feature defaults.

The fourth Cargo attempt exited 101 after 11.35 seconds because Bubblewrap requires libcap development headers and pkg-config metadata. Ubuntu `libcap-dev` and `libcap2`, both `1:2.66-5ubuntu2.4`, were downloaded and extracted into the owned distribution prefix. The package metadata hardcodes `/usr/lib64` and `/usr/include`; the local metadata copy uses `${prefix}/lib/x86_64-linux-gnu` and `${prefix}/include` so pkg-config relocation points to the actual extracted files. The fifth Cargo attempt keeps the same default command and adds these prerequisite inputs plus upstream's verified V8 overrides.

Cargo's normal default build also regenerates workspace-version metadata in the release tag's lockfile. A structural comparison established exactly 166 workspace entries changing from `0.0.0` to `0.164.0`, matching the tag's workspace version. All external dependency identities, dependency lists, checksums and other lockfile data remain unchanged. This generated change is recorded separately from authored source repairs; the original clean checkout evidence precedes all build attempts.

The maintained check tools were installed into the owned `rust-tools` prefix using `cargo install --locked --root <prefix> just@1.51.0 dotslash cargo-nextest`. Installed versions are Just 1.51.0 (matching upstream setup-ci), DotSlash 0.5.7 and cargo-nextest 0.9.149. Their build output is isolated in `tool-target`, separate from Codex output. Tool installation passed; its success does not establish Rust test success.

The fifth default Cargo attempt passed with exit 0. Cargo reports 5 minutes 52 seconds; the enclosing command's wall time is 6 minutes 11.03 seconds. This attempt reused normal local Cargo state from the earlier failed prerequisite attempts, so it is not a clean cold-build or warm-cache benchmark. No authored source repair had been applied to that checkout when compilation passed. `cargo-build-5.log` and `cargo-build-5.receipt` retain this result. The executable smoke check passed and printed `codex-cli 0.164.0`; see `codex-version.log` and its receipt.

After the verified checker repair was applied and the cumulative checker passed, `just test` was started with the maintained local profile and default features. Its command, start record, log and receipt use the `just-test` prefix. This run was interrupted through its owning session (exit 130) after diagnosing invalid temporary-fixture setup. Besides changing Git ancestry, the long project-nested `TMPDIR` causes Unix socket binds to fail with `path must be shorter than SUN_LEN`. This is environment evidence, not an upstream source defect. A focused three-test retry removed that override and retained the same native prerequisite and warning configuration; all three passed. Its prefix is `rust-standard-temp-focused`. Package selection changed Cargo feature unification and triggered seven minutes of subset recompilation; the test execution itself took 0.040 seconds. This focused result does not replace the full maintained suite.

The corrected full `env -u TMPDIR just test` run exposed a separate host boundary and was also interrupted through its owning session (exit 130). Its evidence prefix is `just-test-2`. Daemon tests cannot create their fixed shared socket root in this agent sandbox: `findmnt` identifies `/tmp/codex-daemon-1000` as read-only tmpfs with mode `000`, and `stat` confirms mode `000`, UID 1000. Upstream `codex-rs/uds/src/daemon_directory.rs` deliberately fixes that root independently of HOME, TMPDIR and CODEX_HOME, and requires owner-only mode `0700`; its comments state that sandboxes hide this directory. The observed mount and permission evidence is retained in `daemon-socket-mount.txt`. Keep that protection intact and run the affected checks on ordinary native hosts. Neither a timeout increase nor a product-source repair resolves this execution-boundary mismatch.

## Comparison with upstream's successful execution

The owner requires a second check of assumptions before attributing failures to stable upstream. [Upstream release run 39894017941](https://github.com/openai/codex/actions/runs/39894017941) completed successfully for this exact pinned commit. The saved API responses enumerate all 87 jobs across three pages, including successful Cargo builds for all six release targets. These are control evidence from upstream, not proof that this branch reproduces those builds. This run does not contain the SDK workflow or the workspace-manifest check; its success does not resolve those additional local check failures.

The retained Linux x86_64 release-job log confirms upstream's target-specific release Cargo commands, musl prerequisite script, Rust 1.99.1 setup and verified V8 overrides. Our successful default development build uses the documented local GNU workspace command. The target, profile and host setup differ; keep their evidence separate. Remaining comparisons must follow the relevant upstream job rather than treating all commands as one stable-build gate.

The TypeScript non-Git failure was caused by our environment. Setting `TMPDIR` beneath this project's Git checkout made the test's temporary directory a member of a repository. Codex's `get_git_repo_root` walks ancestor `.git` entries directly, so Git environment flags would not repair this fixture. Rerunning the same focused test with only `TMPDIR` removed passed. The test owns and removes that fixture; CAT logs and workflow state remain inside their owned project directories. No source change was needed. The independent conformance timeout still needs investigation, including the Cargo-versus-Bazel and Node-version differences.

The Python formatter assertion also fails in a fresh checkout of the exact tag, without the manifest repair or generated lockfile. Upstream commit `9127f21890265323701ea080b6f0fe0a164bcb54` added the repository-wide discovery assertion but did not change `scripts/format.py`; the latter still invokes `cargo fmt`. The verified [formatter repair](../patches/upstream-baseline/README.md) retains that discovery requirement and bounds commands to [Windows' command-line limit](https://learn.microsoft.com/en-us/windows/win32/api/processthreadsapi/nf-processthreadsapi-createprocessw). It passed focused tests, the full isolated Python SDK suite, real Rust formatting and fresh-base patch verification before application to the active baseline. Its cumulative Python SDK suite also passes (271 passed, 38 skipped), as does the complete maintained `just fmt-check`. No product source changed.

The second check against upstream CI confirms these two source-level check failures. The stable release commit has one parent, `342ed69a174c206c1de52a59491b131e0753cfa5`, and changes only `codex-rs/Cargo.toml`. The parent's [SDK job](https://github.com/openai/codex/actions/runs/39710355092/job/119599956750) reports the identical Python assertion, with 268 passed, 38 skipped and 1 failed. Its [repository-check job](https://github.com/openai/codex/actions/runs/39710355092/job/119599956729) reports the identical stale code-mode exception. Both complete logs and the parent/changed-file API evidence are retained in the execution directory. These check failures also occur upstream; they do not contradict successful stable release compilation.

The upstream SDK log also reports all 49 TypeScript tests passing against its Bazel CLI, with conformance completing in about 106 seconds. Locally, the official Node 22.23.4 Linux archive was downloaded and verified against its distribution SHA-256 manifest to match the workflow's Node 22 major. The full rerun with that Node version and normal test-fixture temporary directories passed all 49 TypeScript tests against the same Cargo CLI. Conformance took 263.818 seconds, within its maintained timeout. This establishes the corrected invocation's success; it does not isolate Node version versus resource contention as the cause of the earlier conformance timeout. The exact stable published Linux musl CLI was separately downloaded and verified against the release API's SHA-256 digest (`90dd0b03b28afcb7fa733a4f73b413db2916a47347efd43fb669cc1c774ee8e4`); it reports `codex-cli 0.164.0` and is available as an independent behavioral control if needed. Download and version checks do not establish test success or reproduction by this branch.

## Native release-build workflow

[baseline-release-builds.yml](../.github/workflows/baseline-release-builds.yml) prepares independent builds for all six release targets. [upstream-commit](../.github/upstream-commit) supplies the exact source identity, paired with the existing tag file. Each job preserves the restart commit, tag, source commit and verified check patches outside its ephemeral source checkout, checks out the pinned upstream source at the workspace root so local upstream actions keep their expected paths, then checks and applies those patches.

The workflow reuses upstream's pinned Rust, Zig, V8 and MSVC setup, musl/OpenSSL/libcap recipe, bwrap strip/digest handoff, Windows SQLite setting, macOS debug-info setting, and separate primary/app-server/helper Cargo commands. It adds no fatal warning flags or cross-run build cache. Native CLI and app-server version smoke checks follow compilation. It retains source provenance, build logs and Cargo timings as one-day Actions evidence. It does not create releases, packages, tags, deployments or issue updates. This workflow covers release-target compilation and version smoke checks; full tests, lint, native voice packaging and the other required checks need their separate gates.

The runner labels select the matching architectures from [GitHub's standard native runner matrix](https://docs.github.com/en/actions/reference/runners/github-hosted-runners): Ubuntu 24.04 x86_64/ARM64, macOS 15 Apple Silicon/Intel, and Windows 2025 x86_64/Windows 11 ARM64. These differ in capacity from upstream's private runner labels; successful upstream jobs do not prove these hosts have sufficient resources. The organization's larger-hosted-runner API reports that hosted runner management is unsupported for this organization; this does not establish standard-runner unavailability.

The workflow runs on pushes to `main.new` affecting its definition, source pins or baseline patches. The owner authorized ongoing pushes to this branch. Commit `2bce2c263068fbf085ffc76a3b50a4b5da3ee6d5` was pushed and started [baseline run 40159613742](https://github.com/holdmyspot-com/codex-unleashed/actions/runs/40159613742); its terminal results remain required. A path-filtered push permits the first build while preserving old main: [GitHub requires manual-dispatch workflows on the default branch](https://docs.github.com/en/actions/how-tos/manage-workflow-runs/manually-run-a-workflow). Manual dispatch is also declared for later use when that requirement is met. Local actionlint 1.7.12 checks the workflow and its shell scripts; a passing static check does not establish any native build result.

[baseline-linux-tests.yml](../.github/workflows/baseline-linux-tests.yml) supplies a separate Ubuntu 24.04 x86_64 GNU execution boundary for the full Rust suite. It reuses upstream's `setup-ci` host preparation, Rust pin, nextest 0.9.103, V8 action, `ci-test` Cargo profile, default nextest profile and Linux sandbox helper handoff. It runs the whole suite in one job rather than transporting archives into four shards. It adds no build cache or fatal warning flags. Normal test fixture locations remain in effect, and the host's daemon directory is not hidden by this agent's sandbox.

The GNU test job supplies ALSA/libcap development packages and builds the exact pinned native voice sources with unchanged upstream `third_party/voice/build_native.py`, matching the successful local prerequisite route. CMake 3.31.8 is verified against its official distribution SHA-256 manifest; each native source archive is checked against upstream's source manifest before the builder independently verifies it again. Build logs, the native builder receipt, source identities, nextest diagnostics and available JUnit/timing files are retained for one day. This prepares a test gate; static validation alone does not pass it, and non-Linux test coverage remains separate.
