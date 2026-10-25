# Cargo input freshness helper

The reduced [tooling CLI module](../tooling/cli/pom.xml) prepares cached Cargo outputs using source contents and records successful inputs after compilation. It migrates only the command needed by caching from old main `545479059ac86394049b6efd3bf4297b1ca18d17`. It does not build the old release, repository-policy, SDK or scheduler tooling.

## Command and storage

The Java entry point is `com.holdmyspot.codexunleashed.tooling.Main`. Its supported command remains:

```text
clean-cached-release-binaries <workspace> <target> <binary ...>
clean-cached-release-binaries --record-source-inputs <workspace> <target>
```

Preparation reads actual Cargo metadata, snapshots source digests, restores timestamps only for unchanged inputs and invalidates requested executable units while retaining reusable libraries. Missing checkout snapshots cause workspace-package cleanup while retaining external dependency outputs. Recording refuses source drift since preparation. Successful and pending snapshots live in the Cargo target directory under `.codex-source-inputs`, so the compiled-output archive carries them between runners. The retained snapshot implementation ignores lockfiles when checking build-time source drift; the build workflow must separately verify allowed workspace-version synchronization without accepting dependency changes.

When `RUSTY_V8_SRC_BINDING_PATH` is configured, the helper also inventories that regular file and its parent in a separate target-specific snapshot. The upstream setup action must verify and materialize it before preparation. The helper hashes the declared bytes; it does not replace upstream's checksum verification. An unchanged binding regains its recorded timestamps. Changed content receives current timestamps so Cargo invalidates affected work. Missing binding metadata causes explicit cleanup of the cached `v8` package. Recording refuses binding content drift during compilation. Checkout confinement remains unchanged; this separate inventory is confined to the configured binding's actual parent, without persisting an absolute runner path.

Only the cache command is exposed by the reduced dispatcher. Its statuses remain zero for success, one for operational failure and two for invalid command arguments. Cargo and Git remain runtime prerequisites. The `runtime` Maven profile bundles Java 27 and the helper behind `runtime/bin/codex-tooling`; consumers do not need a system Java installation. Native platform verification and hosted integration remain required before supporting checkpoint D1 is complete.

## Dependency and source selection

The module uses old main's Jackson databind 3.2.3 and the Commons IO 2.20.0 version selected by its former Commons Compress dependency, now declared directly. TestNG 7.12.0 remains test-only. Maven compiler, test, jar and dependency-plugin versions come from old main. Warnings remain visible and nonfatal.

The initial 14-class review missed four existing implementation dependencies: `ArtifactJson`, `UnixFileTimes`, `WindowsFileTimes` and `UnixFileMetadata`. Compilation identified those missing classes before any tests ran. Including their existing source completes the 18-class helper closure. This was an incomplete downstream extraction, not a defect in Codex or the helper's old-main implementation. The reduced dispatcher and `V8BindingCache` make 20 production classes. Retained one-line Javadocs are expanded without behavior changes.

## Local verification

JDK 27 and Rust 1.99.1 are used. The 29 original focused tests pass before the V8 extension. A new real-Cargo regression first fails because touching an unchanged external binding recompiles its consumer. It passes after the extension, including same-mtime content invalidation and refusal to record build-time drift. The two unchanged dispatcher tests bring the focused suite to 32 passing tests.

From the repository root, set `CODEX_TOOLING_TEMP` to an existing owned temporary directory, then run the same verification entry point:

```sh
RUSTUP_TOOLCHAIN=1.99.1 mvn -B -ntp -f tooling/cli/pom.xml \
  -Dmaven.repo.local="$PWD/.cat/work/temp/build-caches/maven/repository" \
  -Djava.io.tmpdir="$CODEX_TOOLING_TEMP" verify
```

The module writes compiled outputs to `.cat/work/temp/build-caches/maven/source-cache`; temporary test fixtures use the explicitly supplied directory and clean up after themselves. The recorded invocation used the session's `cache-migration-40B3Cg` temporary directory.

A second, initially empty source folder contains only the final production source, its Maven declaration and retained dependency resolution. Building there and invoking the actual CLI establishes the complete prepare/build/record sequence with an external fixture crate standing in for V8:

| Input state | V8 fixture library | Consuming library | Observable result |
| --- | --- | --- | --- |
| Cold | Rebuilt | Rebuilt | Successful recording |
| Same bytes, newer input timestamps | Fresh | Fresh | Original binding timestamp restored |
| Changed binding bytes, original timestamp | Rebuilt | Rebuilt | Executable prints the new value, `9` |
| Binding changes after compilation | Recording refused | Recording refused | CLI exits 1; no accepted new snapshot |

The CLI also rejects an escaping target name with status 2 before workspace access. The temporary Cargo fixture is removed after verification. Evidence is retained in the session's `cache-migration-40B3Cg` directory: `source-cache-cli-proof.json`, `source-cache-suite-32.log`, `external-binding-red-2.log`, `external-binding-green.log` and the final target verification log. The parent owns retained evidence; hosted integration and review are its next consumers.

The focused tests retain Maven's duplicate-temp-property warning, TestNG's missing SLF4J provider messages and the native-access warning from extreme timestamp tests. They are not suppressed or treated as errors. Local success establishes this Linux helper boundary, not cross-platform or hosted Codex cache acceptance.

## Bundled runtime

Build and verify the runtime with the same owned temporary directory and Maven repository:

```sh
RUSTUP_TOOLCHAIN=1.99.1 mvn -B -ntp -f tooling/cli/pom.xml -Pruntime \
  -Dmaven.repo.local="$PWD/.cat/work/temp/build-caches/maven/repository" \
  -Djava.io.tmpdir="$CODEX_TOOLING_TEMP" verify
```

The profile stages only production dependencies and the helper JAR, then runs the unchanged old-main `DistributionMain` assembler from its build-only source path. Maven resets its own `modules` and `runtime` staging directories before each profile build. The helper's module descriptor requires Jackson databind and Commons IO and exports no Java packages; the supported interface is the CLI. The assembler uses the current JDK's `jlink`, writes the maintained launcher and marks a successfully assembled image with `.complete`. It grants the helper native access for its exact timestamp adapters.

The actual repository command passes all 32 focused tests and produces a 72,054,030-byte Linux runtime. Its launcher passes the same cold, unchanged-input, changed-content and recording-drift Cargo fixture with Java absent from `PATH` and `JAVA_HOME` unset. Only the helper and four production dependency JARs are staged; TestNG and its dependencies are excluded. Retained evidence: `source-cache-target-runtime-verify.log` and `source-cache-target-bundled-cli-proof.json`. A preliminary source-launch attempt used a path that did not match the assembler's declared package; placing the unchanged source under its package hierarchy fixes that invocation. This was downstream probe setup, with no Codex counterpart.

Linux success does not establish native Windows or macOS behavior, cross-runner cache reuse, or Codex release acceptance.

The focused [native runtime workflow](../.github/workflows/source-cache-runtime.yml) selects the existing six release hosts. It builds only this standalone module, runs the 32 focused tests and invokes the packaged launcher through Bash against the maintained [Cargo fixture](../.github/scripts/test-source-cache-runtime.py). That fixture checks actual library freshness, changed executable output and refused recording, and removes its temporary workspace while retaining command logs and a JSON receipt. It does not compile Codex or a standalone SDK. Artifacts retain evidence for one day and create no release.

The workflow selects Azul Zulu 27.0.1 on each native host. Azul's [package metadata API](https://api.azul.com/metadata/v1/zulu/packages/) lists GA JDK archives for all six OS/architecture pairs, including Windows ARM64; the retained selection response is `native-zulu-27-availability.json`. This establishes download availability rather than successful native execution. Maven identity is recorded from the host, and its build outputs, repository and temporary paths are explicitly owned runner directories.

Native run `40231090075`, restart `90ee746c8f48b36ae70758096e05a14f176d502b`, builds the runtime and passes all 32 focused tests with clean source guards on all six hosts. The maintained packaged Cargo fixture passes both Linux and both macOS hosts, including actual warm-library reuse, changed-binding invalidation, output `9`, invalid-target rejection and drift refusal. Both Windows fixtures fail before the helper starts: the unqualified Python subprocess `bash` launches WSL and emits setup errors. Their runtime/Java-test passes remain valid; their packaged-launcher/Cargo fixture gates remain failed.

The fixture now requires a third argument: the explicit Bash executable selected by its caller. The workflow resolves its Git Bash path, converts it to native spelling on Windows and passes that path rather than relying on Python executable discovery. It records Bash's version before invoking the packaged launcher. The corrected fixture passes locally; the Windows counterfactual is pending. This workflow is now manually dispatched for selected targets; its initial push trigger served registration and is removed to avoid automatically repeating verified hosts. Evidence: `source-cache-runtime-1-verified.json`, per-host artifacts and the two Windows job logs. Windows Java tests also take about 26 minutes, versus seconds on Linux; the reason remains under investigation and is not attributed to compilation.

## Dependency identity guard

The [lockfile verifier](../.github/scripts/verify-cargo-lock.py) requires Python 3.11 or newer and its standard library, including `tomllib`. It consumes the trusted upstream `Cargo.lock`, the post-build `Cargo.lock` and actual `cargo metadata --no-deps --format-version 1` output from the unchanged workspace. It never writes those inputs. Only source-absent workspace package versions and their exact version-qualified dependency references may synchronize to the versions Cargo reports. Typed TOML contents must otherwise match; formatting and key order do not affect comparison. A successful invocation prints a JSON receipt; malformed inputs or other changes exit 1. The workflow must produce this metadata from the pinned, unchanged workspace, verify every other tracked file separately and run the lockfile check before recording or cache upload.

The baseline release workflow now retains the trusted lockfile before compilation, produces actual Cargo metadata after the build and verifies dependency identity before cache upload. The original source guard still checks every other tracked file. A real Git fixture first demonstrated that the former whole-file exclusion accepts a changed dependency version. The maintained CLI regression covers eleven permitted/rejected cases, including checksums, registry sources, explicit empty sources, numeric types and malformed input; it also verifies that input files remain unchanged. A separate actual Cargo 1.99.1 build fixture verifies workspace-version synchronization using Cargo-generated lockfiles and metadata. Both fixture workspaces are removed after execution. Retained evidence: `lock-guard-red.json`, `lock-guard-target-proof/lock-verification.json` and `lock-guard-cargo-proof/commands.json`. Actionlint and whitespace checks pass. This verifies the downstream guard locally; a complete hosted Codex build with this guard remains unrun.

## Release workflow integration

The [release baseline workflow](../.github/workflows/baseline-release-builds.yml) preserves this standalone helper outside the upstream checkout and hashes its Maven declaration, production sources and build-only assembler into compiled-cache identity. Cached mode selects the same native Zulu JDK and Maven runtime profile used by the focused helper workflow; uncached mode does not build the helper. Maven outputs, repository and temporary files have separate owned runner paths.

Preparation runs after upstream V8 checksum verification and GHCR restoration, before the Linux sandbox helper or any release compilation. It includes every selected CLI, app-server and platform-helper executable. After native smokes, unchanged-source verification and the dependency identity guard pass, recording checks that inputs have not drifted, then GHCR receives the successful snapshot and compiled outputs. This integrates existing successful-build caching; it does not yet retain failed-run partial state across hosted jobs. The separate incremental-resumption checkpoint in [plan.md](../plan.md) remains unverified.

A local invocation against the actual pinned Codex workspace confirms all selected Linux binary owners are found and preparation succeeds with populated dependency downloads. Cargo synchronizes 166 workspace versions, which the new dependency guard accepts against actual Cargo metadata; tracked source is restored and verified clean afterward. The first probe used an empty Cargo home with forced offline mode and failed because `cargo clean` resolves patched Git dependencies; changing only to the existing populated home passes. This is probe setup, not an upstream source defect. The owned empty target is removed after verification; logs, lockfiles, metadata and hashes remain in `codex-helper-prepare-proof/verified.json`. This probe does not compile Codex or verify warm reuse.

The first integrated cold run, `40233193525`, fails during helper tests before cache restoration or Codex compilation. Upstream V8 setup exports `RUSTY_V8_SRC_BINDING_PATH`; ordinary helper fixtures inherit it despite containing no V8 package, so preparation requests `cargo clean -p v8` and Cargo exits 101. Setting only that environment variable reproduces the same failure locally (`inherited-v8-red.log`). Removing it from the ordinary fixture environment, while the dedicated V8 case explicitly configures its own binding, makes all 34 current tests pass with the inherited setting still present in Maven's environment (`inherited-v8-green.log`). This fixes downstream test isolation without changing upstream setup or source. Hosted retry, cold/warm behavior, transfer costs, invalidation and full Codex acceptance remain unverified for this cache identity.

## Failed-build resumption gap

A controlled local Cargo fixture prepares a cold target, compiles its library, then fails its executable at an intentional fixture-only compiler error. After fixing only the executable and touching unchanged library source, retry preparation removes the compiled library. No successful snapshot exists, so the current helper takes its whole-workspace cleanup branch. The public-operation regression is red for the intended missing behavior: `partial-source-cache-red/result.json`. This fixture and helper are downstream-only; upstream Codex has no equivalent preparation operation. No upstream source or test is changed.

Existing `.pending` state alone cannot safely replace a successful snapshot. It is written before preparation finishes and contains timestamps captured before freshness restoration. The helper now writes a separate `.resumable` checkpoint only after preparation and invalidation complete, using content-checked effective timestamps. It invalidates that checkpoint before changing preparation state and retains the last successful snapshot. On retry it compares current inputs against the completed-preparation checkpoint, preserving unaffected work while Cargo rebuilds changed inputs. V8 binding inputs follow the same contract. Missing or incomplete preparation state still requires the narrowly justified cleanup; preparation never records build success.

The maintained Java regression first fails because the retry rebuilds its unaffected library (`partial-resume-java-red.log`), then passes with the checkpoint. All 34 focused tests pass and runtime assembly succeeds (`partial-resume-runtime-verify.log`). The packaged Cargo regression also fails against the old runtime (`partial-runtime-red.log`) and passes against the new runtime (`partial-runtime-green/runtime-verification.json`): both V8 and workspace libraries are fresh after the controlled executable failure and focused fix, the binding timestamp is restored, and no successful snapshot exists until explicit recording. Warm reuse, changed-content invalidation, output `9` and drift refusal also pass. These are local fixture results. Private hosted partial-state transport, native verification of this revision and the final clean-build gate in [plan.md](../plan.md) remain unverified.

## Remaining integration gates

- Verify the bundled runtime and maintained launcher on every selected native host; local Linux verification passes.
- Verify the hosted preserved-helper build and its contribution to compiled-cache identity.
- Verify hosted preparation after V8 setup and restoration, before all release compilation, covering all selected executables.
- Verify hosted source/dependency guards and native smokes precede successful recording and cache upload.
- Verify the PAX archive and helper on the supported native platforms.
- Run hosted cold/warm Codex builds and representative changed-input invalidation. Fixture reuse does not satisfy those gates.

The upstream source and tests remain unchanged. Steps 1–3 and the full restart goal remain incomplete.
