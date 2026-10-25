# Stable upstream build baseline

## Source and scope

- **upstream_repository**: https://github.com/openai/codex
- **upstream_tag**: `rust-v0.164.0`, pinned in [upstream-ref](../.github/upstream-ref)
- **upstream_commit**: `6aaac694803004a63c6f393d153c96d22521a6b9`
- **rust_toolchain**: `1.99.1`, as declared by upstream `codex-rs/rust-toolchain.toml`
- **local_platform**: Linux x86_64 GNU
- **release_targets**: Linux x86_64/ARM64 musl, macOS Intel/Apple Silicon, Windows x86_64/ARM64 MSVC
- **source_changes**: Cargo regenerated workspace-version metadata; the verified one-line checker repair was applied after the successful unmodified-source Cargo build
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
| Rust tests | `just test` with the maintained local nextest profile | Running |
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

The package-test failure is an invocation prerequisite: `scripts/codex_package/targets.py` explicitly requires `CODEX_REPO_ROOT`. Supplying the actual upstream checkout root repairs the invocation without a source change. The manifest-check failure is separate: `codex-rs/code-mode/Cargo.toml` has no `[features]` table, but the checker retains a feature exception for it and rejects unused exceptions. [The baseline repair](../patches/upstream-baseline/README.md) removes only that obsolete exception. The existing checker failed on an isolated clean base, then passed after repair; the emitted `git format-patch` also applied with `git am` and passed on a second fresh base. Both temporary verification worktrees were removed. After the active Cargo build passed, the same patch was applied to that checkout and its cumulative checker passed. The final patch omits Git's optional mail signature; a fresh-base index application verified that its resulting checker blob exactly matches the tested commit.

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

After the verified checker repair was applied and the cumulative checker passed, `just test` was started with the maintained local profile and default features. Its command, start record, live log and eventual terminal receipt use the `just-test` prefix in the owned execution directory. Retain its owning tool session until the terminal outcome; do not infer test success from compilation or the executable smoke check.
