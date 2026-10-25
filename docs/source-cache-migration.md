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

Only the cache command is exposed by the reduced dispatcher. Its statuses remain zero for success, one for operational failure and two for invalid command arguments. Java 27, Cargo and Git are current runtime prerequisites. A bundled runtime and maintained launcher are still required before hosted integration; this source migration does not complete supporting checkpoint D1.

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

## Remaining integration gates

- Build and verify the maintained launcher with its bundled runtime on the selected native hosts.
- Preserve the helper before upstream checkout and include its source and Maven declaration in compiled-cache identity.
- Run preparation after verified V8 setup and compiled-target restoration, before any Cargo compilation; include all selected release executables.
- Verify source, dependency identity and native smokes before successful recording and cache upload.
- Verify the PAX archive and helper on the supported native platforms.
- Run hosted cold/warm Codex builds and representative changed-input invalidation. Fixture reuse does not satisfy those gates.

The upstream source and tests remain unchanged. Steps 1–3 and the full restart goal remain incomplete.
