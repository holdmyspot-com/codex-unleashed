# Python replacement contract

The project uses JDK 27 and Maven for its build, release, and CI tooling. The implementation follows
`holdmyspot-com/cat-client` at `f19a1d98c7f6567b7a6795cfc892fa3b7709634a`: a Maven reactor, named Java modules,
TestNG behavior tests, Checkstyle and PMD checks, and a distribution with a bundled Java runtime.

The migration preserves each retained tool's inputs, outputs, failure decisions, artifacts, and workflow handoffs.
The Python SDK and runtime package are retired by the user's explicit choice; a Java SDK is outside this task.
The TypeScript SDK, Rust CLI, app-server, patch queue, release identities, and installed application data remain supported.

## Acceptance gates

- [x] JDK 27 Maven reactor passes `tooling/mvnw verify`, including tests and static checks.
- [x] Bundled runtime executes the CLI without Python or a separately installed Java runtime.
- [x] All 58 inventoried project tooling Python source/test files have replacements or explicit retirement coverage.
- [x] Embedded Python in shell scripts and GitHub workflows has Java replacements.
- [x] Python SDK/runtime sources, notebook, release workflows, and SDK CI routes are retired.
- [x] Retained TypeScript SDK build, lint, and native test commands remain executable locally.
- [x] Local release staging, manifest verification, native package assembly, and local npm publication retain compatible artifacts.
- [x] Maintained CI step bodies and upstream-checkout callers invoke the compiled tooling distribution locally.
- [x] Maven outputs, repositories, wrapper downloads, and test fixtures stay under `.cat/work/temp`.
- [x] Final source/caller audit finds no project-owned Python implementations or invocation routes.
- [ ] Native Linux, macOS, and Windows tooling CI passes the complete Maven and assembled-launcher checks.

## Implementation order

1. Maven platform and installer staging: four existing staging outcomes, Java tests, and CLI failure mapping.
2. Runtime distribution and launchers: named module, bundled runtime, missing-runtime failure, and execution from another directory.
3. Release metadata and publication: version/build-number rules, installers, manifest, provenance, licenses, upstream detection, and npm.
4. Package assembly: target/variant choices, Cargo and prebuilt inputs, resource resolution, archives, and license collection.
5. Cache tools: release keys/retention, source timestamps, and binary source-input fingerprints, including current dirty-source behavior.
6. V8/Bazel tools: staging, module checksum updates, canary decisions, and BuildBuddy invocation.
7. Repository policy tools: CI results, Cargo manifests/shear, TUI boundary, lint alignment, and blob-size checks.
8. Caller integration and SDK retirement: workflow setup, shell invocations, embedded Python, docs, and retirement audit.
9. Complete Maven verification, assembled launcher checks, and release/package compatibility checks.

Each production behavior receives a focused failing Java test before implementation. A migrated command replaces
its callers only after its behavior and assembled-launcher gates pass. Compilation prerequisites alone do not establish
behavioral equivalence. Existing Python tests remain available until their corresponding Java boundaries pass.

## Verification state

The Maven reactor runs behavior tests, Checkstyle, and PMD through `tooling/mvnw verify`.
Its distribution includes the CLI and its complete dependency module set in a bundled Java runtime.
Local migration acceptance includes 326 Maven tests, static checks, 48 native TypeScript SDK tests, both native package
families, independent archive and release-manifest consumers, the live upstream-release checker, and completed-output
cleanup. Hosted workflows and native Windows/macOS execution remain separate integration gates.

The CLI provides these commands:

| Command | Contract |
| --- | --- |
| `stage-release-installers` | Stages the shell and PowerShell installers. |
| `next-release-build-number` | Selects a vendor build number from a UTF-8 tag inventory. |
| `cache-prefix` | Associates dependency caches with an upstream release. |
| `stable-releases` | Discovers the two retained stable upstream releases. |
| `prune-actions-caches` | Reports or deletes obsolete managed Actions caches. |
| `check-ci-results` | Requires explicit success from every serialized CI dependency. |
| `release-cache-keys` | Derives compatible Cargo download, compiled-target, and V8 cache identities. |
| `resolved-v8-crate-version` | Resolves Cargo's V8 package or the unambiguous pinned module URL. |
| `get-codex-package-version` | Prints the workspace version with the exact vendor build suffix, defaulting to `dev` only when absent. |
| `write-cargo-cache-manifest` | Writes ordered byte-size and path records for the target, registry archive, and Git cache roots. |
| `resource-manifest-assets` | Prints platform-order release asset names, declared sizes, and digests for a DotSlash resource manifest. |
| `normalize-source-timestamps` | Normalizes source access and modification times while preserving Git metadata. |
| `clean-cached-release-binaries` | Prepares Cargo reuse from source digests and records successful build inputs with `--record-source-inputs`. |
| `check-cargo-shear` | Accepts only verified release warnings from unchanged baseline source paths. |
| `detect-upstream-release` | Compares stable upstream releases with exact vendor tags and appends GitHub outputs and summaries. |
| `v8-canary-changes` | Selects general and Windows matrices using merge-base versions and case-sensitive path policies. |
| `ensure-release-tag` | Reserves the original artifact build's immutable source tag before emitting publishing provenance. |
| `generate-source-provenance` | Verifies exact patch-derived source trees with independent Git indexes before writing release provenance. |
| `generate-release-manifest` | Hashes release artifacts and the active patch queue while preserving release and workflow metadata. |
| `collect-third-party-licenses` | Copies locked Cargo dependency license payloads and reports missing evidence without changing supplied expressions. |
| `build-codex-package` | Builds or accepts executable inputs, resolves resources, assembles branded packages, and writes repeated archive outputs. |
| `publish-npm-from-release` | Downloads or consumes six package archives, creates both npm families, packs them, and optionally publishes to the selected registry. |
| `check-blob-size` | Checks committed added and modified blobs against the size limit and exact allowlist, with console and GitHub summary reports. |
| `verify-bazel-clippy-lints` | Checks Cargo workspace lint levels against explicit Bazel flags, with optional file overrides and ordered diagnostics. |
| `run-bazel-with-buildbuddy` | Preserves Bazel startup and payload arguments, selects the permitted BuildBuddy host, adds configured caches, and forwards child status. |
| `rusty-v8-bazel` | Stages Bazel or upstream Cargo artifact pairs and checks or updates module checksums in an explicit checkout. |
| `verify-tui-core-boundary` | Checks dependency keys and raw Rust import lines for direct TUI dependencies on codex-core. |
| `verify-cargo-workspace-manifests` | Checks workspace metadata, lint opt-in, crate names, and feature toggles in an explicit repository; `--upstream` selects the pinned upstream exception policy. |

The upstream preparation action uses the Java cache prefix. Cache cleanup uses the Java Actions pruner and
stable-release selector. The four terminal CI workflows use the Java dependency-result checker. The corresponding
Python cache-retention and CI-result implementations are retired. Release preparation uses the Java build-number
selector, installer staging uses the Java byte-preserving stager, and compiler cache setup uses Java key derivation.
Their Python implementations and tests are retired. Release normalization uses Java and preserves Cargo library-cache
reuse. V8 version selection uses Java in release builds, canary metadata, artifact setup, and the local release script.
V8 staging uses Java in the canary and V8 release workflows. Module checksum operations use the Java command.
The project-owned V8 and BuildBuddy Python helpers and tests are retired. Upstream-owned Python tests and
Chromium/V8 build tools remain external dependencies.

Python SDK/runtime retirement is integrated. The TypeScript SDK job is byte-for-byte preserved. Its build, lint,
and 48 native tests, including MCP conformance, run against the Java-built package with isolated configuration and
local mock APIs. Local test isolation hides the enclosing patch repository's Git metadata while retaining the
upstream checkout's metadata, so temporary non-Git fixtures remain outside a repository. Hosted job execution remains
a separate integration gate. The Python SDK exclusion in the third-party license document
remains a legal reference, without a corresponding source or invocation route.

Active task evidence resides in `.cat/work/temp/java-migration-16644316454297986942`. The staging and runtime slices
have failing and passing receipts there. Local build outputs, wrapper downloads, dependency repositories, and Java test
fixtures reside below `.cat/work/temp/build-caches`.

Build-number selection preserves release-specific numbering, excluded vendor forms, arbitrary-size integers, and Unicode
tag/whitespace handling. Its CLI reads strict UTF-8 from standard input and rejects invalid upstream tags before reading
a live input stream. Runtime assembly and CLI dispatch are available predecessors.

The project launcher is `tooling/bin/codex-tooling`. It bootstraps a missing or incomplete runtime through Maven,
preserves command standard input, and uses `.cat/work/temp/build-caches/tooling` for local temporary and XDG data.
The distribution producer creates `runtime/.complete` only after linking and writing its launcher. The project launcher
requires that marker and an executable launcher before consuming the image. A failed bootstrap leaves no accepted
readiness state; a retry rebuilds an incomplete image. The real Maven-bootstrap gate passes from another directory
and selects build 30 from the original input stream.

The `setup-tooling` action supplies JDK 27, verifies the Maven reactor, and publishes the compiled launcher through
`CODEX_UNLEASHED_TOOLING`. Upstream preparation runs this action before invoking the CLI. Generated module staging
removes obsolete jars before copying the current runtime dependencies and deletes those staged jars after successful
runtime linking. Publishing obtains tooling from the workflow commit in its own checkout while preserving the
release's source checkout for package and installer inputs.

Hosted release-workflow execution, hosted npm publication, and native V8/Bazel integration remain open.
GHCR archive and registry behavior is covered by Java tests that invoke the maintained Bash helpers and real tar
with controlled registry transport.

Cargo Shear's Java command accepts only verified findings from the matching release commit with unchanged baseline
sources. The Rust CI caller invokes it with the maintained baseline. The Python command and tests are retired.
The process boundary preserves both output streams and ordinary nonzero tool status for policy evaluation.

Surefire supplies the wrapper's temporary directory at JVM startup. The storage test verifies the real default
temporary allocation beneath the project cache, including the JDK's cached directory selection.

Upstream release detection uses Java. API failures leave the output and summary unchanged instead of requesting a
new build. The workflow retains its `AUTO_RELEASE=true` publishing gate. Its local behavior test executes the
maintained step through a fresh runtime with isolated Java GitHub fixtures and no Python or Java executable on PATH.

V8 canary decisions use Java. The command takes an explicit checkout with `--base` and `--head`, or `--force` for
manual dispatch without reading Git. Its path policy preserves historical helper paths and recognizes the Java
replacement sources. Unicode path ordering follows code points, and wildcard path segments span directory separators.
The Python canary source and tests are retired.

Immutable release-tag reservation uses Java in the publishing workflow. Its policy and HTTP tests cover permission
failures, concurrent creation, lost-response retries, exact build provenance, and Unicode tag paths against a local
HTTP server. The bundled image includes the HTTP client module; the server module is available only to tests.
The CLI accepts `--api-base` for an explicit endpoint and defaults to GitHub's public API. It requires `GH_TOKEN` only
for actual reservation, and emits the original build's source SHA, branch reference, and run attempt after verification.
The Python release-tag implementation and tests are retired.

Source provenance uses Java in both release jobs. The producer replays the ordered patch queue and stages checkout
sources in separate temporary Git indexes, including ignored additions introduced by patches. It rejects unrelated
source edits, preserves the user's index, and removes audit indexes and command captures on success or failure.
The report preserves raw binary-diff SHA-256, ASCII JSON, lowercase Unicode escapes, literal slashes, sorted keys,
and two-space indentation for objects and arrays. The Python producer is retired. Local integration tests execute
both maintained job commands with a fresh bundled runtime; native Windows and hosted CI execution remain unrun.

Release-manifest generation uses Java in publishing and the local release script. The manifest includes recursive
artifact hashes and byte sizes, excludes its own output, and includes only the repository's active patch queue.
Path ordering compares filesystem components, preserving dependent patch order and original artifact names.
Supplied build dates remain unchanged; omitted dates use an injected UTC clock at second precision. Target lists
retain duplicate entries and Unicode whitespace trimming. The consumer verifier accepts the resulting release using
its independent active queue without the builder's scratch checkout. The Python generator and its test are retired.
The local release script composes its default upstream checks, the supported Java package-build override with real
native inputs, artifact collection, and manifest generation. The independent consumer verifies checksums, active
patch identities, and the upstream commit. Hosted CI remains a separate integration gate.

Cargo license collection uses Java in both release build jobs. Its standalone command runs locked Cargo metadata,
retains Cargo diagnostics, and copies payload bytes without modification. A declared expression supplies evidence
when a crate has no payload file. Strict missing-evidence failures retain the notices report and prevent a successful
workflow handoff. Tests execute real offline Cargo with isolated cache storage and a fresh bundled runtime.
The package-layout workspace fallback uses the same Java collector. The Python collector and tests are retired.

The Java package legal-material component copies the supplied offline documents and prioritizes prepared Cargo
licenses over workspace metadata collection. It rejects missing prepared directories and notices, preserves prepared
payload timestamps and supported permissions, and skips Cargo when no workspace manifest is available.
The Java target and variant models cover all eight release targets, both executables, and retained host defaults.
Directory assembly places the code-mode host beside the entrypoint, retains the nested zsh resource path, and writes
metadata with the retained field order. Unix executables receive all three execute bits; validation requires the owner
execute bit. Nonempty output requires explicit replacement. The release archive wrapper invokes this command.

The Java archive writer provides tar.gz, tgz, tar.zst, and ZIP with slash-name member ordering and unchanged payload
bytes. Tar and ZIP retain source Unix permission modes, including executable and restricted files.
Tar modification-time overrides and the gzip header clock are independent; supplied member PAX timestamp text
remains exact. Tar headers omit access, status-change, and creation timestamps. ZIP uses source timestamps. The zstd
command selector prefers the native executable and supports the repository DotSlash fallback. Compressor input trees
are removed on success and failure. The Python archive module and tests are retired.

Archive environment options use a lazy timestamp policy. ZIP ignores tar and gzip options, empty tar archives do not
consume member or blanket timestamps, and member overrides take priority without parsing an unused blanket value.
An explicit empty override differs from absence. Decimal integer inputs retain Unicode digits, valid separators, and
large values; numeric member JSON uses the retained shortest round-trip text and explicit timestamp strings remain
unchanged. Gzip header times require the unsigned 32-bit range. Large and negative tar times use PAX metadata, with
rounded ustar header values for floating-point times. JSON accepts the retained NaN and Infinity spellings, rejects
additional native spellings and trailing documents, and keeps the last duplicate member value.

The Java DotSlash resolver selects the target's first provider, distinguishes optional platform absence from malformed
metadata, and verifies archive size and SHA-256 before extraction. Cached archives are checked again before reuse.
ZIP and tar.gz members copy raw executable bytes; duplicate names select the final member, and tar links resolve within
archive metadata. Downloads use owned sibling temporary files, preserve existing destinations after HTTP failures,
and remove partial downloads. Local HTTP tests cover redirects and failure cleanup. The package command composes
resource resolution. The corresponding Python modules are retired.

Shebang manifests and V8 checksum files share the retained ASCII and Unicode line-boundary policy. A final terminator
does not create an additional empty line; interior empty lines remain visible to validation.

The Java V8 resolver retains exact source-build bypass values and requires both caller overrides together. Generated
overrides resolve the single Cargo lockfile version, download the release's archive and binding, and validate both
against lowercase checksum metadata with exact filename coverage. Cached artifacts are checked before reuse, checksum
metadata is refreshed on each resolution, and failed replacements are deleted. Windows targets skip this override
route. Cargo source builds consume these overrides. The Python V8 resource module is retired.

The Java package-version provider reads the upstream workspace package version and appends the exact vendor build
number. An absent build number defaults to `dev`; an explicitly empty number remains empty. Missing, malformed,
non-string, or unreadable workspace versions fail without an unbranded fallback. Package assembly and
`get-codex-package-version <checkout>` share this provider. The query reads its explicit checkout independently of
the caller's working directory. Package assembly resolves the version from the same workspace and environment.

The external build-command boundary inherits input and both output streams, passes a complete explicit child
environment and working directory, and returns the ordinary exit status to its caller. Process ownership covers
waiting and interruption cleanup. Cargo source builds select only missing executable targets, retain supplied
prebuilt inputs, and use the managed package cache as the default Cargo target directory. Real offline Cargo fixtures
cover complete and helper-only builds, caller-selected target directories, and ordinary compiler failure.

Windows command execution explicitly encodes each literal argument using Microsoft C runtime quote and backslash
rules, including empty arguments and values surrounded by literal quotes. The bundled launcher and CLI tests select
the JDK's legacy command-line emission so it preserves that encoding. The command boundary rejects conflicting JDK
quoting and batch-file executables; npm runs through its installed Node entrypoint. Native Windows timestamp tests
use the independent .NET filesystem reader to retain negative seconds and far-future FILETIME values.

The package command requires `--repo` for repository legal documents and resource manifests. `--workspace` selects
the upstream checkout root containing `codex-rs`; without it, the command uses `CODEX_PACKAGE_WORKSPACE_ROOT` or the
repository.
Prebuilt inputs accept any executable permission bit; assembled Unix packages require the owner execute bit.
An implicitly allocated package directory is removed after failure and retained after successful output. Explicit
package directories retain caller ownership. A fresh standalone runtime produces all four archive formats, which
native consumers read with unchanged executable bytes, while Python, external Java, Cargo, and Codex commands are
rejected on PATH.

The maintained release archive wrapper invokes the compiled tooling and supplies repository and workspace context.
It preserves the primary/app-server bundle selection, target-suffixed input names, Windows helper names, and fixed
tar.gz/tar.zst release filenames. Its unique staging directory includes temporary package resources and is removed
after success or failure. Integration tests consume both archive formats for Linux and Windows package layouts on
Linux. Real Codex and app-server binaries also pass the maintained archive wrapper's gzip and zstd paths; independent
GNU tar extraction preserves payload bytes, links, and permissions. Its staging directories close after delivery.
Native Windows and hosted release-workflow execution remain unrun. The replaced Python package modules,
entrypoint, license collector, and their tests are retired. The Java version query replaces the Python-only patch;
the retained queue applies to its declared upstream base.

The npm version component preserves exact supported release prefixes and converts the vendor separator from `+` to
`-` without normalizing the version text. Base components accept Unicode decimal digits; vendor build digits remain
ASCII. The npm archive extraction component validates all ordinary member and link paths before writing, preserves
payload bytes and modification times, restores supported permissions, and removes its spool on every outcome.
Original PAX and GNU naming metadata remain available for confinement checks because the archive library normalizes
extended names. Malformed gzip construction closes the raw source stream. Ordinary-file, directory, confined-link,
and extended-name tests pass on Linux. Old GNU and PAX sparse versions 0.0, 0.1, and 1.0 retain their raw payloads,
including extended Unicode names. A narrow Commons Compress 1.28.0 reader adapter prevents already consumed old GNU
sparse metadata from being read again while decoding extended names; its removal condition is the maintained sparse
tests passing with the unmodified reader.

The npm assembler produces public and early-access package families, each with six native packages and one selector.
It retains vendor bytes, complete license trees, platform metadata, optional dependency versions, insertion-ordered
JSON, and the original Node launcher. Shared directory copying preserves supported attributes while dereferencing
source links. Native Node tests exercise optional platform packages and vendor fallback, literal arguments, and exit
status without starting Codex. Native npm packing creates all fourteen archives with retained metadata and legal
payloads. The publisher CLI accepts relative and absolute output directories from another working directory and
retains the local registry default. Its extraction staging and npm caches close as soon as their work ends; implicit
output is removed after failure and retained after success. Actual npm publication to a local HTTP registry retains
family order, access, latest tag, credentials, and tarball legal payloads. The maintained workflow uses the compiled
command. Its fresh-runtime test checks npm argument and cwd handoff with a controlled command boundary, native archive
inputs, and rejected Python and external Java commands. The Python publisher and its test are retired.

The Java release downloader uses an explicit anonymous GitHub client for public metadata. Authenticated construction
continues to require a nonblank token. Lookup retains exact tag spelling and HTTP status, and response decoding rejects
invalid UTF-8. Asset selection uses the last duplicate name and downloads the six package archives in target order.
Failed downloads preserve existing output files and remove partial resources. Local HTTP tests cover these boundaries
through the complete CLI, including packing downloaded archives and removing implicit output after lookup failure.
Hosted release-workflow execution remains an integration gate.

The blob-size workflow uses Java with the retained base/head comparison, exact allowlist paths, and byte-size limit.
The policy preserves Git binary classification, excludes deleted objects, treats renames as added objects, and rounds
KiB reports to one decimal using ties-to-even. Invalid revisions or options preserve existing summary evidence.
Real Git tests and the maintained workflow step run through a fresh bundled runtime without Python or external Java.
The Python blob-size script is retired. Native npm consumer tests use independent EOF input and captured output so
their child descriptors remain isolated from Surefire's control streams.

The Cargo manifest validator provides separate project and pinned upstream policies. The upstream policy recognizes
the additional code-mode feature exception; `ALLOW_STALE_CODE_MODE_FEATURE_EXCEPTION=1` permits its unused entry
while retaining validation of any supplied features. The project policy recognizes only its declared V8 exception.
Validation reads TOML without changing sources, preserves explicit boolean decisions, checks root/workspace/target
dependency tables, and resolves dangling dependency links before deciding whether their paths are internal.

The repository-check wrapper invokes this Java validator through `CODEX_UNLEASHED_TOOLING` or its project launcher.
It resolves the project before changing into the upstream checkout and stops subsequent checks on manifest failure.
Its fresh-runtime test executes the actual wrapper with controlled boundaries for the remaining Python, formatting,
and pnpm checks. The actual upstream Python, formatting, and pnpm checks run through the wrapper on the declared base.
Complete hosted workflow execution remains a separate integration gate.
The cheap upstream checker's policy stage also invokes the Java validator with the project exception policy.
Its fresh-runtime test checks the actual policy stage and failure stopping without Python.
The Python manifest implementation is retired; the complete cheap checker validates the current public stable release
and the live zsh and ripgrep resource inventories.

All TOML consumers share a reader that retains native date, time, local datetime, and offset datetime objects.
Quoted temporal text remains a string. Manifest package identity, V8 lockfile versions, and package metadata enforce
their string boundaries without accepting a native date as an identifier or version. Invalid native calendar values
fail parsing. The manifest validator resolves repository aliases before comparing canonical dependency paths.

The TUI boundary validator uses Java in both repository-check wrappers. It scans raw Rust lines, including comments,
retains Unicode word and whitespace decisions, and reports one failure per matching line with its original line number.
Dependency checks retain exact keys rather than resolving renamed package fields. Both caller tests stop subsequent
checks after a forbidden import and use a fresh bundled runtime. The Python TUI validator is retired.

The Bazel Clippy alignment checker uses Java in both wrappers. Cargo supplies the authoritative normalized string
levels; Bazel parsing accepts only the retained prefix and long or short lint flag forms. Unrecognized flags are
ignored, duplicate recognized flags fail with both line numbers, and mismatches produce ordered diagnostics with
an opted-in member example. The CLI retains file override options, repeated-option selection, help, and failure
statuses. The Python checker and the cheap policy stage’s embedded Python are retired. Resource-manifest processing
uses the Java extractor; complete workflow execution remains a separate gate.

Source-input cache preparation uses Java in both release jobs. The command writes pending inputs before cleanup,
cleans only workspace packages when a successful snapshot is absent, and retains external dependency output.
Warm preparation compares successful and interrupted source digests, restores unchanged timestamps, marks changed
sources, and invalidates only the requested executable units in library-owning packages. Binary-only package owners
receive sorted Cargo cleanup selectors. Successful recording requires unchanged prepared inputs, permits generated
Cargo lockfile changes, atomically writes the snapshot, and removes pending state. Failed cleanup or compilation
leaves pending state available for recovery without recording success. Native Unix timestamp adapters retain negative
fractional and large epoch nanoseconds beyond the installed JDK's filesystem conversion range. The Windows adapter
uses native FILETIME values for affected timestamps, preserving the filesystem's 100-nanosecond precision and range,
capturing native error codes, and closing each file handle after the operation. Native macOS and
Windows execution and complete hosted release execution remain integration gates. The Python cache helper and its
tests are retired.

The npm archive reader retains raw PAX naming values until it selects the authoritative filename. GNU sparse archives
use `GNU.sparse.name`; their synthetic `path` placeholder does not determine the extracted filename. Selected names
require valid UTF-8 and confined destination paths. This preserves sparse payloads when a producer truncates a
placeholder inside a UTF-8 character, while malformed real filenames and escaping paths still fail before extraction.

The Java Bazel planner uses explicit environment and event-file inputs. Authenticated upstream workflows select the
OpenAI tenant only with the exact GitHub Actions repository identity and, for pull requests, a literal non-fork proof.
Other authenticated runs use the generic tenant. Missing credentials remove remote-execution CI configurations only
before the program-argument separator. Explicit startup and cache choices remain effective; configured disk caches
retain their bounded size and age settings. The CI and query Bash wrappers invoke the compiled command through a
shared shell launcher. Bazel workflow callers select the maintained project wrappers while executing in the upstream
checkout. The project-owned Python wrapper is retired. Native Windows execution and complete Bazel workflow delivery
remain integration gates.

The `rusty-v8-bazel` command takes a checkout, an operation, and the operation's options. Bazel staging builds and
materializes the requested configuration before querying output paths, including when `--skip-build` is supplied.
The upstream libcxx configuration comes first; additional configurations retain caller order without duplicates.
External query paths resolve against Bazel's output base and other paths against its execution root. Staging writes
a gzip library with a zero modification clock, unchanged binding bytes, and an ordered lowercase SHA-256 manifest.
Ordinary and sandbox profiles retain the Unix and Windows artifact filenames.

Module checksum operations preserve unrelated source text, require exact manifest coverage, and reject missing,
duplicate, or mismatched checksums before updating. Version inference uses the checkout's main module even when an
alternate module file is supplied. Source reading uses universal newline semantics; changed files use native output
newlines. The manifest parser retains the module helper's Unicode whitespace, blank-line, and filename rules, which
differ from the download checksum parser's grammar.

The canary and V8 release workflow commands execute through a fresh bundled runtime with Python and external Java
rejected on PATH and an inert native Bazel boundary. Their local delivery checks cover normal and sandbox artifacts,
Windows-named upstream Cargo staging, payload bytes, checksums, working directories, and capture cleanup. The Bazel
checksum step runs the Java module and consumer-selector checks against the upstream checkout before retaining
`just test-github-scripts`, whose discovery covers additional upstream-owned tests. A fresh-runtime test executes
both Java checks and verifies that a missing selector prevents delivery to the retained upstream check boundary.
The boundary fixture records recipe invocation. The actual checksum step invokes both Java checks and the maintained
upstream helper suite on the declared base. The project-owned Python
helpers are retired. Real Bazel compilation and native macOS and Windows behavior remain separate integration gates.
Python required by external Chromium and upstream V8 builds remains a dependency.

GHCR cache release discovery uses the bundled Java `stable-releases` command. Retained release lines reach awk through
its environment input so GNU and BSD implementations preserve the same inventory. Its fresh-runtime tests execute real
Bash and tar while native fixtures supply only registry transport, release inventory, and package inventory. The
checks cover tag refusal before transport, legacy and stable restore identities, reuse across upstream versions,
legacy fallback, GNU tar preference, hard-linked dependencies, release executable and symbol removal, retained
release aliases, and refusal to delete when release discovery is empty or fails. A colon-bearing local temporary
path checks archive streaming and release-alias publication order; native Windows drive-letter execution remains
a separate gate. Owned temporary archive directories are empty after each completed operation.

Both release jobs write complete Cargo cache manifests through the bundled Java launcher. The writer preserves
target, registry archive, and Git root order, sorts descendants with platform path semantics, and records raw file
sizes and lexical paths using native text newlines. It follows regular-file links and an explicitly linked root
without recursing through directory links below that root. Missing roots contribute no records; an absent
`CARGO_TARGET_DIR` fails before replacing the destination. Fresh-runtime checks execute both maintained step bodies
with Python and system Java rejected on PATH and independently verify the TSV records.

The cheap upstream-release checker reads resource asset records through the bundled Java launcher and compares them
with the selected GitHub release inventory. Extraction retains platform insertion order and the first provider with
a URL or GitHub release type. URL basenames retain percent-encoded spelling and omit query and fragment components.
Manifest extraction failure stops the checker before its comparison loop; malformed input and metadata mismatches
cannot reach the success marker. Package archive selection and release inventory extraction share the strict
DotSlash JSON reader. The maintained zsh and ripgrep manifests are checked independently against the CLI's records.

The cheap checker's whitespace preflight preserves line padding only in the generated owned startup-frame snapshot.
Normal source whitespace checks and the snapshot's blank-EOF checks remain enabled. The frame retains its bytes;
the checker does not rewrite generated expectations.

Release reproduction preflight requires Git and jq. The recorded workflow commit supplies its build and packaging
toolchain. The isolated prerequisite check makes Python unavailable on PATH and reaches the recorded-source Git
retrieval boundary; an inert transport refusal verifies temporary work-directory cleanup. This check does not
download, build, or reproduce a release archive.

Bazel preparation tests execute the maintained metadata command with real offline Cargo. They require the local
release package version to agree with its manifest while the resolved path dependency remains unchanged. Source
preservation, shared setup, repository cache restore, cache identity, and compact execution logs remain policy checks.
