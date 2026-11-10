# CI test-environment gaps: upstream openai/codex (dddc3c0a79 + our patches) vs our baseline test workflows

Upstream files (all under upstream-rama-port/.github): NP = workflows/rust-ci-full-nextest-platform.yml, RF = workflows/rust-ci-full.yml,
BZ = workflows/bazel.yml, SC = actions/setup-ci/action.yml, PB = actions/prepare-bazel-ci/action.yml, RB = scripts/run-bazel-ci.sh,
RC = .bazelrc, NT = codex-rs/.config/nextest.toml, RE = scripts/test-remote-env.sh.
Our patches touch only action pins/runner names in those files; no test-setup difference introduced by them.
Ours (cp3-test-module/.github/workflows): LIN = baseline-linux-tests.yml, WIN = baseline-windows-tests.yml, BL = baseline-bazel-tests.yml,
BM = baseline-macos-bazel-tests.yml, BW = baseline-windows-bazel-tests.yml, AR = upstream-auto-release.yml.

Release gate (AR:254-290) runs: LIN (nextest, x64 Linux), WIN (nextest --workspace, x64+arm64), BM (Bazel on macOS arm64+x64). BL and BW are NOT in the gate.
Upstream RF:430-495 runs nextest on macOS arm64, Linux x64 (remote_env: true), Linux arm64, Windows x64, Windows arm64, plus Bazel on mac/linux x64/windows.

## A. Gaps (upstream does it, we do not)

| platform | upstream setup (file:line) | ours | tests affected / skip triggered | proposed change |
|---|---|---|---|---|
| Linux nextest | Remote env: NP:263-275 sources RE, exports CODEX_TEST_ENVIRONMENT=docker, CODEX_TEST_REMOTE_ENV, CODEX_TEST_REMOTE_ENV_CONTAINER_NAME, CODEX_TEST_REMOTE_EXEC_SERVER_URL; RE:50-83 starts privileged ubuntu:24.04 container with python3 zsh bubblewrap, copies debug `codex`, runs `codex exec-server`; teardown NP:343-352; enabled by RF:451 | missing (LIN has no docker step, no env) | skip_if_no_remote_env! (25 sites: core/tests/suite/remote_env.rs x13, network_approval.rs x4, agents_md.rs x2, apply_patch_cli.rs x2, rmcp_client.rs, view_image.rs, core/src/unified_exec/mod_tests.rs, ext/multi-env-copy-file/tests/suite/transfers.rs) print "Skipping test because it requires a remote test environment." and return Ok. Also every test_codex() test that calls build_remote_aware (core/tests/common/test_codex.rs:618-621) runs the LOCAL path only; the remote-exec-server path is never exercised. skip_if_remote! (75 sites) never fires, so we run those locally (extra coverage, not a gap) | Add to LIN before the test step: `cargo build -p codex-cli --bin codex`, `CODEX_TEST_REMOTE_ENV_CONTAINER_NAME=codex-remote-test-env-$GITHUB_RUN_ID source scripts/test-remote-env.sh`, export the four vars to GITHUB_ENV; teardown step with `docker rm -f` (always). Run a second nextest pass (local) and a remote pass, or follow upstream and run once with the env (upstream x64 does remote only; arm64/mac/win do local only). Needs docker on host (hosted ubuntu-24.04 has it) |
| Windows nextest | SC:20-23, 108 Dev Drive via setup-dev-drive.ps1; CI_BUILD_ROOT, CARGO_TARGET_DIR, TEMP on dev drive | missing; WIN uses default C: target and temp | No skips; changes path length, perf, slow-timeout (30s x2 in NT:1-6) failures, ReFS vs NTFS semantics. Not silent skips | Optional: call ./.github/actions/setup-ci (works on Windows) in WIN, or accept as perf-only |
| Windows nextest | SC:96-108 installs DotSlash, copies to WindowsApps ("Some integration tests spawn DotSlash from a stable system path") | missing | Tests that spawn `dotslash` fail or take fallback path; not skipped | Run setup-ci or install dotslash |
| Windows nextest | NP:226-231 builds codex-windows-sandbox bins codex-windows-sandbox-setup + codex-command-runner and NP:299-310 exports CARGO_BIN_EXE_codex_windows_sandbox_setup / CARGO_BIN_EXE_codex_command_runner | missing (WIN runs plain `cargo nextest run --target`) | codex_utils_cargo_bin::cargo_bin falls back to target dir (utils/cargo-bin/src/lib.rs:43-73). If bins are not built, core/tests/windows_sandbox.rs:137,173, exec-server/tests/exec_process/windows_sandbox.rs:108, exec-server/tests/file_system_windows.rs:487, windows-sandbox-rs/tests/helper_manifest.rs:22 error (fail, not skip) | Add `cargo build -p codex-windows-sandbox --bins` and export the two vars, or use upstream's archive+helper flow |
| Windows nextest | NP:292-294 `--test-threads 8` (RF:478,494); runner windows-x64 / windows-arm64 | WIN: no --test-threads; hosted windows-2025 / windows-11-arm (4 vCPU) | Timing only; NT windows-heavy groups (NT:107-130) tuned for 8 threads | Add `--test-threads 8` if timeouts recur |
| Windows / Linux / mac nextest | `--cargo-profile ci-test` via archive (NP:182-187; Cargo.toml:670 opt-level 0) | LIN uses ci-test; WIN uses default `test` profile (no --cargo-profile) | No skip; different codegen, debug-assertions same; disk/time | Add `--cargo-profile ci-test` to WIN selected-test.sh |
| macOS nextest | RF:431-441 macos-15-xlarge aarch64-apple-darwin nextest, setup-ci | no full nextest workflow on macOS (BM is Bazel only; baseline-macos-focused-runtime.yml runs a single sandbox_tty test) | All Cargo-only tests on macOS unrun in the gate. Seatbelt tests: skip_if_sandbox! (155 sites, checks CODEX_SANDBOX=seatbelt, only set when codex itself sandboxed) and "nested Seatbelt unavailable" ad hoc skips (cli/tests/sandbox_tty.rs, sandboxing/src/seatbelt_*_tests.rs) depend on host | Add a macOS nextest workflow (ci-test, setup-ci) to the gate for aarch64 (and x64 on macos-15-intel), same as LIN |
| Linux nextest | Linux arm64 job RF:457-463 | no arm64 Linux test lane in gate | Arm-specific cfg paths unrun | Add ubuntu-24.04-arm lane of LIN (matrix on target) |
| Linux nextest | sharding NP:271 `--partition hash:N/4`, separate archive builder | LIN: one 120-min runner, no partition | No skips; resource contention can turn into 30s timeouts | none needed; use partition if timeouts |
| Bazel (all OS) | BUILDBUDDY_API_KEY secret (BZ:90,190,290); RB:386-389 adds `--config=ci-linux|ci-macos|ci-windows[-cross]` only when key set; RC:115-221 | BL/BM/BW run without the key (RB:391 "local Bazel configuration"); BW passes `--config=ci-windows` explicitly | Linux: no `--platforms=//:rbe`, so tests run on the host with host-installed tools (zsh, bwrap) rather than in the RBE image (see section C). macOS: `--strategy=TestRunner=darwin-sandbox,local` (RC:200) not applied. Windows gnullvm: ci-windows-cross not applied (RB:258-262 falls back to ci-windows + msvc host), so CODEX_BAZEL_TEST_SKIP_FILTERS lacks `command_safety::powershell_parser::tests::` (RC:217), RUST_TEST_THREADS=1 (RC:213) and local_test_jobs=8 (RC:212) are missing; jobs capped 8 | Provide a BuildBuddy key to the gate, or mirror the flags by hand: mac `--strategy=TestRunner=darwin-sandbox,local`; Windows `--test_env=RUST_TEST_THREADS=1 --local_test_jobs=8` |
| Bazel Linux | BZ:84-87 CODEX_COMPAT_RELEASES via resolve_exec_server_releases.py | BL:62,260 supplies pinned JSON (ours differs on purpose) | none | none |
| Bazel Windows | BZ:174 BUILDBUDDY_API_KEY; PB MSVC env | BW has prepare-bazel-ci + voice tools (ours) | none | none |
| Linux nextest | NP:88 apt installs `build-essential pkg-config libcap-dev libasound2-dev bubblewrap` | LIN:167 same + bison flex strace; NO zsh | exec-server/tests/exec_process.rs:536 "skipping zsh test: /bin/zsh is unavailable" (zsh cases at :199-207,517-520, local_zsh_pipe, local_zshenv, zsh_profile_gateway_fallback, ...) skip if image lacks /bin/zsh; core scenarios_credential_masking.rs "requires a Bash or Zsh executor". Upstream x64 host also lacks zsh (only the Docker container gets it), so upstream has the same hole locally | Add `zsh` to LIN apt list (BL already does) and assert `/bin/zsh --version` |
| Linux nextest | SC:35-41 userns sysctls | LIN calls setup-ci (LIN:147) | none (parity) | none |
| Linux nextest | RF/NP env RUST_BACKTRACE, RUST_MIN_STACK=8388608, NEXTEST_STATUS_LEVEL=leak | LIN has first two; no STATUS_LEVEL | output only | none |

## B. Things we do that upstream does not (change which tests run)

| platform | ours (file:line) | effect |
|---|---|---|
| Linux nextest | LIN:163-167 installs bison flex strace; LIN:176-231 builds native voice libs and exports CODEX_TEST_VOICE_RUNTIME, LD_LIBRARY_PATH, PKG_CONFIG_PATH | CODEX_TEST_VOICE_RUNTIME only read by an #[ignore] test (voice-host/tests/packaged_runtime.rs:18), never run; LD_LIBRARY_PATH can change which libs tests load |
| Linux nextest | LIN:245-251 builds bundled `bwrap` (codex-bwrap) into ci-test dir | Tests find a bundled bwrap; managed_proxy.rs:471 "skipping system bwrap fallback test: bundled binaries require a release digest" interplay; upstream uses system bwrap only |
| Linux nextest | LIN:236-243 CURL_HOME with empty .curlrc during tests | Matches upstream default (no curl retry); fine |
| Linux nextest | LIN:155-160 deletes android/dotnet/ghcup | Frees disk; no test effect |
| Linux nextest | LIN:168-169 CARGO_HOME under RUNNER_TEMP; pinned toolchain from codex-rs/rust-toolchain.toml | none |
| Windows nextest | WIN:~40 GIT_REDIRECT_STDIN=NUL; LIBSQLITE3_FLAGS=SQLITE_DISABLE_INTRINSIC on x64 (WIN ~122) | Changes SQLite build; GIT stdin affects git subprocess tests |
| Windows nextest | WIN: setup-msvc-env for both targets (upstream only for aarch64, NP:99-103) | none |
| Windows nextest | Hosted windows-2025 instead of upstream's windows-x64 pool; build+run on same host, no archive | different CPU count |
| Bazel Linux | BL:136-137 installs zsh + bubblewrap on host; runs locally on host (no RBE) | RBE image (.github/workflows/Dockerfile.bazel:9-14) has only curl git python3 ca-certificates dotslash and user `ubuntu`; no zsh, no system bwrap, so upstream hits the "no system bubblewrap" / "zsh unavailable" skips under Bazel and we do not. We run MORE tests there |
| Bazel Linux | BL pins Bazel 9.2.0 binary download (BL:~190) instead of setup-bazel/bazelisk | none |
| Bazel macOS | BM runs on macos-15 / macos-15-intel (BM:34-38), 2 shards, `--disk_cache=` | upstream macos-15-xlarge; resource only |
| Bazel all | `--disk_cache=` and package-hash shard `:all` selection (BL:216-245) | same target set minus manual-tag exclusion subtlety: BL/BM use //... with `-//third_party/v8:all -//codex-rs/v8-poc:v8-poc-unit-tests` (same as BZ:93-99) |

## C. Skip sites mapped to setup

Macros in codex-rs/core/tests/common/lib.rs: skip_if_sandbox :579 (CODEX_SANDBOX=seatbelt), skip_if_no_network :605 (CODEX_SANDBOX_NETWORK_DISABLED set),
skip_if_test_condition :627, skip_if_remote :643, skip_if_no_remote_env :662, skip_if_wine_exec :678, skip_if_target_windows :697 (Windows target OS), skip_if_host_windows :752;
skip_if_mxc_unavailable exec-server/tests/common/mod.rs:37 (4 sites: exec_process/windows_sandbox.rs:26,92; capability_discovery.rs:1055; exec_process.rs:1906).
Environment selection: core/tests/common/test_environment.rs:7-9,86-130.

| skip site | trigger | status in our CI | classification |
|---|---|---|---|
| skip_if_no_remote_env (25 sites) | CODEX_TEST_ENVIRONMENT / CODEX_TEST_REMOTE_ENV unset | fires everywhere in ours (never set) | MISSING SETUP |
| skip_if_remote (75 sites), skip_if_wine_exec (157), skip_if_target_windows (91) | remote/wine env or Windows target | wine only exists under Bazel (bazel/rules/testing/wine, hermetic, panics unless Linux); remote not set so we run them | skip_if_wine_exec: by design (Bazel-only; nextest never selects Wine). skip_if_target_windows: by design (platform) |
| skip_if_no_network (1641 sites) | CODEX_SANDBOX_NETWORK_DISABLED set (only when codex itself is sandboxed) | not set in ours or upstream CI | by design (inert) |
| skip_if_sandbox (155) | CODEX_SANDBOX=seatbelt | not set (codex not self-sandboxed) | by design (inert) |
| skip_if_host_windows (34) | host is Windows | fires on Windows lanes in both | by design, allowlist |
| skip_if_mxc_unavailable (4) | codex_sandboxing::windows_mxc_available() false (native MXC absent) | fires on hosted Windows; upstream too unless runner has MXC | by design when platform lacks MXC; verify on windows-2025 / windows-11-arm that the runner has MXC, otherwise allowlist |
| exec-server/tests/exec_process.rs:536 "skipping zsh test: ... unavailable" | /bin/zsh missing | LIN: fires unless runner image has zsh; BL: installed | MISSING SETUP (LIN) |
| core/tests/suite/scenarios_credential_masking.rs "requires a Bash or Zsh executor" | no zsh/bash executor | same | MISSING SETUP (zsh) if zsh absent |
| bwrap skips: linux-sandbox/tests/suite/{sandbox,managed_proxy,denied_files_tests,nested_metadata_tests,root_metadata_tests,daemon_sockets_tests}.rs, exec-server/tests/exec_process.rs (bwrap/sandbox warning), cli/tests/exec_server/pid_namespace_tests.rs, cli/tests/sandbox_network_proxy.rs | bwrap unusable: userns disabled, /dev/shm, AppArmor | LIN installs bubblewrap and setup-ci sets userns sysctls (SC:35-41); BL same. Docker-run variants in LIN already compare | Skip means MISSING SETUP on Linux (should never fire); allowlist only WSL ones |
| linux-sandbox sandbox.rs WSL_INTEROP / WSLg skips (wslg_tests.rs) | not on WSL | by design |
| python3 skips: core/tests/suite/{exec,unified_exec}.rs, exec/tests/suite/sandbox.rs, linux-sandbox managed_proxy*.rs, sandbox.rs "git or python3" | python3 missing on PATH | hosted images have python3; Windows WIN has only what image ships | MISSING SETUP only if absent; assert `python3 --version` on Linux; on Windows allowlist (python/python3 naming) |
| core/tests/suite/live_cli.rs "OPENAI_API_KEY not set" | key unset | unset upstream too | by design |
| core/tests/suite/plugins.rs, rmcp_client.rs "test_stdio_server / test_streamable_http_server binary not available" | helper bin not built | nextest builds workspace bins as needed; Bazel has them as data | MISSING SETUP if it fires; none expected |
| codex_linux_sandbox_exe_or_skip! (lib.rs:~720) | codex-linux-sandbox missing | LIN builds it and exports CARGO_BIN_EXE_codex-linux-sandbox (LIN:245-251,400) | parity; Windows not applicable |
| sandboxing/src/seatbelt_*_tests.rs, cli/tests/sandbox_tty.rs "nested Seatbelt unavailable" | running inside an outer seatbelt (Bazel darwin-sandbox) | mac Bazel without RC:200 flags may run inside darwin-sandbox | by design if upstream also skips; verify by logging |
| tui/tests/suite/resize_reflow.rs "tmux unavailable" | tmux absent | test is #[ignore] (:18) | by design |
| login/tests/suite/login_server_e2e.rs port in use; uds / stdio-to-uds bind failures; rollout/compression_tests "no alternate supplementary group"; utils/pty/src/spawn_helper_tests.rs procfs/pidfd skips | runtime resource | host-dependent | by design (allowlist) but log counts |
| rmcp-client/src/oauth.rs, core/tests/suite/apply_patch_cli.rs "Windows symlink privilege unavailable" ; exec-server/tests/file_system_windows.rs "cannot create restricted tokens" | Windows hosted runner lacks privilege | Windows only | by design (Windows-host), allowlist |
| core/tests/suite/unified_exec_stdin_approval.rs "Linux symlink grant regression on another host OS" | host OS | by design |

## D. Not applicable / verified no gap

- NEXTEST profile: both use `[profile.default]` (NT:1-6: retries = 1, slow-timeout 30s x2). Ours: no --profile and no `--retries` override; retries = 1 applies in both. Flags `--no-fail-fast` identical.
- Test filters/exclusions: upstream nextest has none; ours only add -p/-E when the caller supplies them. Gate calls pass none for LIN, `--workspace` for WIN.
- Bazel test_tag_filters: identical (`-argument-comment-lint`), identical target exclusions.
- Python/Node/pnpm: upstream nextest and Bazel jobs install none (sdk.yml does, not a Rust test job).
- Git config: upstream sets `core.longpaths` on Windows only (SC:110-113); WIN sets it too (WIN:~88).
- Wine: Bazel-only (hermetic), not installed by any workflow.
- Ripgrep: neither installs it; denied_files_tests.rs "no protected rg" skip depends on the image on both sides.
