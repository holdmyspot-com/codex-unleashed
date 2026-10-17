# Build warning enforcement

## Intent

Codex and project-owned tooling treat warnings as errors. Third-party dependency warnings remain nonfatal and visible; dependency source patches address compilation, security, or runtime failures. Formatting checks also reject unexpected warning diagnostics, including warnings from commands that exit successfully. Stable rustfmt remains supported through a narrow exception for its unsupported `imports_granularity=Item` option, matching upstream's formatting behavior.

## Reference and upstream base

- [Canonical issue](https://github.com/holdmyspot-com/codex-unleashed/issues/61).
- Base: `rust-v0.163.0`, commit `83086188ae0614bd882818ff1aa23f3194962012`.
- [Upstream import-formatting tracking issue](https://github.com/rust-lang/rustfmt/issues/4991).

## Scope and dependencies

`formatter-warning-policy.patch` updates the existing upstream formatter wrapper and its CI callers. It requires no other downstream patch. It includes process-boundary tests and runs them in repository checks. This patch covers formatter diagnostics; the issue's other build-tool enforcement gates are separate work.

`native-and-rust-warning-policy.patch` enables Cargo's warning policy, strict rustdoc diagnostics, and strict Bazel Rust target and execution-platform actions. Native compiler warning enforcement is explicit on project-owned targets. External native builds do not inherit a global `treat_warnings_as_errors` feature. V8's build configuration also omits its own `-Werror`, preserving its warning flags and C++ sources. The warning-policy regression checks both a fatal project warning and a successful dependency build with visible warning output. The production V8 action regression, `bazel test //tools/build-warnings/dependencies:v8_warning_policy_test`, verifies that its compile arguments retain `-Wextra` and omit `-Werror`. Apply the current-toolchain patch from issue #60 first, including its linker-argument compatibility adaptation.

The native probe first requires a clean C program to build, then requires a deprecated-function warning to fail at the compiler diagnostic. It rejects an unrelated failure as evidence of warning enforcement. Remaining build-tool and hosted-platform acceptance gates are tracked separately in the issue.

`vendored-native-build-script-modes.patch` explicitly selects the existing vendored build scripts for `tikv-jemalloc-sys` and `libgit2-sys`. This resolves rules_rs's well-known-annotation advisories without changing native library providers. It applies after the issue #60 toolchain patch. Cold module-extension evaluation must complete without either advisory.

`libcxx-explicit-runtime-configuration.patch` defines the custom libc++ runtime's minimum availability-header version and LLVM-libc selection as zero. These match the existing preprocessor branches and remove undefined-macro warnings. The patch includes a native configuration regression test and applies independently of the other patches.

`icu-mutex-thread-safety.patch` releases the currency-name cache mutex when cache-entry allocation fails. It retains this runtime fix without adding mutex annotations or refactoring ICU locks for compiler diagnostics. ICU warnings remain visible and nonfatal.

`native-mingw-function-guard.patch` corrects the native x86 function macro's `#ifndef` spelling in the pinned MinGW headers and enables fatal diagnostics in import-definition preprocessing. Its regression tests compile the real header for Windows x86-64 and ARM64 and assert that native symbols remain enabled only on the corresponding architecture. Apply it after the issue #60 toolchain patch and `native-and-rust-warning-policy.patch`, which provides its test directory.

`native-bindgen-preprocessor-policy.patch` preserves the compiler's optimization and warning-policy arguments when generating Rust bindings. It translates MSVC's `/WX` and `/WX-` options to their Clang equivalents and retains argument boundaries and the existing toolchain's deterministic macro-redaction policy. Its regression suite participates in `just bazel-test`. Apply it after the issue #60 toolchain patch and `native-and-rust-warning-policy.patch`.

`native-aws-lc-windows-tls.patch` backports AWS-LC's MinGW TLS-callback fix, preserving thread-exit cleanup under LLVM 22. It uses the upstream section attributes and `_tls_used` reference while retaining the MSVC path. A focused thread-exit cleanup regression participates in `just bazel-test`. Apply it after the issue #60 toolchain patch and `native-and-rust-warning-policy.patch`.

`native-make-bootstrap-policy.patch` adds a runtime probe that builds the selected Make tool and executes a Makefile. It does not backport GNU Make warning fixes.

`upgrade-llvm-23-windows-driver.patch` selects hermetic LLVM 0.8.24
(LLVM 23.1.2) and aligns its direct Bazel dependencies. LLVM 23 honors Rust's
Windows GNU `-nolibc` option under `-Werror`. The custom libc++, ARM64 MinGW,
and warning-policy patches use this module's toolchain layout. Windows MSVC
compiles use `/WX`; links also pass `/WX` to lld-link. GNU and macOS
toolchains retain their fatal-warning flags. The merged link resource tree
includes the selected compiler's builtin headers for foreign builds that
combine compilation and linking flags. GNU Make retains its released source. Apply it after the issue #60
toolchain patch and `native-and-rust-warning-policy.patch`.

The exception matches only this complete diagnostic in the Rust formatter group:

```text
Warning: can't set `imports_granularity = Item`, unstable features are only available in nightly channel.
```

Repeated occurrences are suppressed. The same text from another formatter, a different unsupported option, an altered diagnostic, or any additional warning remains fatal. A nonzero formatter status remains a failure. The source carries a `WORKAROUND` comment linking the tracking issue; the exception is removable when the option stabilizes. Stable rustfmt ignores the import-splitting option and applies its remaining formatting rules.

## Reproduction

The unpatched wrapper discards successful command output and accepts warnings. Its regression tests exercise real child processes that emit warning diagnostics with a zero exit status. The tests also cover clean output, source text containing `warning:`, exact exception matching, mixed diagnostics, and original failure statuses.

### Bazel output downloads

`remote-download-options.patch` uses the canonical `remote_download_outputs`
option. Local commands download top-level outputs, and CI defaults to minimal
downloads. Commands that require local artifacts pass
`--remote_download_outputs=toplevel` after their configuration arguments.
The download policy and cache reuse remain enabled.

Apply the issue #60 Bazel toolchain patches and
`native-and-rust-warning-policy.patch` first. Run `just check-build-warnings`
from the patched checkout. It verifies clean CI option expansion with and
without the explicit download override, then verifies that native compiler
warnings fail compilation. Conflicting download aliases fail the check.

### Stale dependency policy

`stale-dependency-policy.patch` removes five unused policy entries: a Git source,
a license allowance, two dependency-wrapper exceptions, and a resolved advisory
ignore. Cargo audit and Cargo deny use matching advisory exceptions. The Cargo
deny workflow rejects stale policy entries instead of leaving warning diagnostics.
Dependency-version duplication warnings remain visible and require separate work.

Apply the security patches, including the maintained YAML parser replacement,
before verification. From the patched checkout, run:

```sh
cargo deny --log-level warn --manifest-path codex-rs/Cargo.toml --all-features check \
  --deny unmatched-source --deny license-not-encountered \
  --deny unused-wrapper --deny advisory-not-detected
just fmt-check
just bazel-lock-check
```

The dependency check passes without stale-policy findings. Reintroducing an
unused policy entry fails the corresponding check. Advisory, license, dependency
ban, and source checks remain enabled.

### Rustls Bazel metadata

`rustls-bazel-metadata.patch` refreshes the Rustls crate fact in
`MODULE.bazel.lock` for version 0.23.46. Apply it after the issue #60 toolchain
patches, this issue's Bazel module changes, and the
`security-RUSTSEC-2026-0346/update-rustls.patch` security update. It changes no
other crate facts.

After Cargo resolves the workspace lockfile versions, run `just bazel-lock-check`
from the patched checkout. The check passes; a stale Rustls 0.23.45 fact causes
the maintained check to reject the lockfile.

## Dependency-usage source inventory

`remove-empty-test-placeholder.patch` removes the upstream core integration-test
placeholder `scenarios_read_only_mcp.rs`. The file contains only a module comment,
has no tests, and is absent from the suite's module registry. Removing it resolves
Cargo shear's unlinked-file warning without excluding source files from the check.
The patch applies independently.

With Cargo shear 1.11.2 on `PATH`, run the project's maintained check:

```sh
tooling/bin/codex-tooling check-cargo-shear /path/to/patched-checkout
```

The check reports zero warnings and no findings. With the placeholder present,
it fails with `shear/unlinked_files`. Local execution uses the project's
`run-local-rust` helper and checkout-specific build storage.

## Native symbol tools

`native-voice-z-symbol-tools.patch` supplies Bazel-declared LLVM `nm` and
`readelf` to the Unix native voice build. Meson uses these tools to extract
shared-library symbols without depending on tools installed on a remote worker.
Warning checks remain enabled. Explicit tool paths preserve spaces and quotes.

Apply the issue #60 toolchain patches and this issue's native voice build
patches first. From the patched checkout, run:

```sh
python3 -m unittest discover -s third_party/voice -p 'test_*.py'
bazel build //third_party/voice:native_prefix_linux_x86_64
just fmt-check
just bazel-lock-check
```

The tests pass with CMake and the LLVM toolchain available on `PATH`; platform
tests skip outside their supported host. The Linux native prefix builds without
missing symbol-tool warnings. Hosted remote execution remains a separate gate.

## Direct Bazel dependency versions

`zzz-direct-dependency-versions.patch` pins bazel_lib to 3.7.2, matching the
version required by the updated XZ module, and makes direct dependency version
mismatches fatal. Apply it after `z-xz-windows-arm64-filter.patch`.
`tools/build-warnings/check-direct-dependencies.sh` runs Bazel's resolver with
the strict check; it must succeed without a direct dependency mismatch. The
normal `just bazel-lock-check` also runs with this policy.

## Assembly compiler arguments

`z-llvm-assembly-action-flags.patch` sends preprocessing options only to inputs
that use the preprocessor and C/C++ compiler options only to compiler actions.
Raw assembly keeps its target, assembler, and fatal-warning options. C and
preprocessed assembly retain their declared definitions and header search paths.
Apply the LLVM 23, native warning policy, and remote download options patches first.

```sh
just check-build-warnings --platforms=//:windows_x86_64_gnullvm
just check-build-warnings
just fmt-check
just bazel-lock-check
```

The warning check compiles raw assembly, preprocessed assembly, and C in
fastbuild, debug, and optimized modes. Its independent deprecated-function
fixture must still fail at the expected compiler diagnostic. Preliminary
analysis failures print their captured log and preserve the original status.
The Windows GNU command checks cross-compilation; native Windows execution
and the complete release build remain separate gates.

## Meson setup diagnostics

`z-meson-setup-diagnostics.patch` prints Meson's detailed setup log when setup
fails and preserves the setup command's failure status. This exposes compiler
diagnostics before Bazel removes the sandbox. It applies after the stable native
tool, Meson runfiles, Meson resource directory, and assembly argument patches.

`just check-build-warnings` builds a deliberately failing Meson project and
requires both its setup failure and a detail written only to the Meson log.
The fixture has the `manual` tag and does not participate in wildcard builds.
`just fmt-check` and `just bazel-lock-check` remain required checks.

## V8 Python generator platforms

`z-v8-python-platforms.patch` resolves inspector-generator dependencies for
Linux execution workers, Windows, and the native host. It uses MarkupSafe 3.0.4
with pinned SHA-256 hashes to provide Python 3.11 wheels; the older pin falls
back to a source build that fails hash enforcement. Jinja2 keeps its existing
version. The generator uses the supported `py_zipapp_binary` launcher so its
dependencies do not depend on generated repository names or directory runfiles.
The workspace keeps its manifest runfiles policy.

Apply this patch after `upgrade-z-native-tools.patch`, which supplies
rules_python 2.4.2. Run `tools/build-warnings/v8/check-python-platforms.sh` with
the normal Bazel build options. It analyzes the actual inspector generator for
Linux and Windows, then generates the inspector files on the execution host.
Both analyses and generation must succeed. Native Windows execution remains a
separate CI gate; cross-platform analysis does not establish that runtime result.

## Abseil public API compatibility

`native-abseil-lifetime-attribute.patch` registers the selected Abseil dependency and tests its public containers, status API, and mutex guards. It preserves the dependency source and annotations.

```sh
bazel test //tools/build-warnings/abseil:lifetime_attribute_test
```

The expected result is successful execution with dependency warnings nonfatal.

## V8 MinGW pointer formatting

`zz-v8-clang-mingw-detection.patch` recognizes MinGW when compiling with Clang
as well as GCC. V8 selects pointer format specifiers for the Windows GNU ABI
rather than treating its 64-bit pointers as `long`. The regression checks formatted values as well as compiler compatibility. Apply the native warning policy, LLVM 23, and V8 Python generator prerequisites before verification.

```sh
bazel build @v8//:codex_pointer_formats_test --platforms=//:windows_x86_64_gnullvm --output_groups=compilation_outputs
bazel test //tools/build-warnings/v8:pointer_formats_test
just fmt-check
just bazel-lock-check
```

The compile regression includes V8's actual headers, checks its MinGW
classification, and type-checks signed, unsigned, and hexadecimal pointer
formats. The executable verifies the formatted values. Run the test on a native
Windows GNU execution host for its Windows runtime gate; Linux execution and
cross-compilation do not establish that outcome. The complete V8 build remains
a separate acceptance gate.

## OpenSSL initialization and provider compatibility

`native-openssl-unused-globals.patch` and `native-openssl-unused-options.patch` add public API and CLI probes without changing dependency source. The initialization test repeatedly initializes TLS and loads and unloads the base and default providers. The CLI probe runs version and server-help commands.

```sh
bazel test //tools/build-warnings/openssl:ssl_init_test
bazel build //tools/build-warnings/openssl:cli_probe
```

`upgrade-openssl.patch` selects OpenSSL 4.0.1.bcr.0, whose
Bazel integration includes the
[upstream source-header repair](https://github.com/raccoons-build/bazel-openssl-cc/pull/56).
The released integration uses the existing `opensslconf.h` source header,
retains generated platform configuration headers, and keeps Bazel's dependency
validation enabled. The patch updates the Rust bindings to `openssl` 0.10.81
and `openssl-sys` 0.9.117, which support OpenSSL 4. The dependency source retains its released warning behavior. Apply it after
`native-openssl-unused-globals.patch`, `native-openssl-unused-options.patch`,
and `upgrade-llvm-23-windows-driver.patch`.

Resolve the workspace package versions before running the Bazel checks:

```sh
cargo update --workspace --manifest-path codex-rs/Cargo.toml
bazel test //tools/build-warnings/openssl:configuration_header_test --spawn_strategy=local --nouse_action_cache --disk_cache=
bazel test //tools/build-warnings/openssl:rust_bindings_test
just fmt-check
just bazel-lock-check
```

The check compiles and links the crypto library without a sandbox or cached
actions, then initializes it successfully. Without the repair, compilation
fails because the source header shadows its declared generated copy. Run the
same target on native Windows for platform acceptance; a Linux pass establishes
only the unsandboxed header-selection boundary. The Rust regression verifies
that the bundled OpenSSL 4 library supports hashing and TLS context creation
through the selected bindings. The older `openssl-sys` 0.9.111 rejects
OpenSSL 4 during its build script.


## GoogleTest Windows paths

`upgrade-llvm-23-windows-test-paths.patch` exercises path normalization and separator handling through GoogleTest’s public API. It uses the selected GoogleTest release without source backports.

```sh
bazel test //tools/build-warnings/googletest:path_separator_test
```

The test passes when path normalization and splitting preserve the expected components.

## Windows static-library DEF generation

`upgrade-llvm-23-windows-static-def-parser.patch` limits rules_cc's DLL export
processing to libraries that produce a DLL. Static-only libraries do not invoke
Bazel's Windows-only DEF parser on a Linux execution host. Dynamic libraries
retain their existing export processing. Apply it after the LLVM 23 toolchain
patch, which selects rules_cc 0.2.25.

```sh
bazel build //tools/build-warnings/windows-exports:static_link_probe --platforms=//:windows_x86_64_gnullvm --keep_going
```

The probe links a Windows executable against a static archive, verifies the
function's archive symbol, and checks that the executable does not import that
function from a DLL. Without the fix, the static library invokes `no_op.bat` on
Linux and fails. The cross-build does not execute the Windows binary or establish
DLL-export behavior; Windows runtime and release builds remain separate gates.

## Unix metadata-tool bootstrap

`upgrade-native-pkgconf-bootstrap.patch` builds pkgconf 3.0.8 from its
checksum-pinned release tag with Meson 1.12.1. The existing Unix pkg-config target
and native Mac execution constraints remain available. The bootstrap does not
compile the legacy pkg-config package's bundled GLib or disable integer-conversion
warnings. Its unavailable bootstrap provider prevents selecting the host's
pkg-config during optional dependency discovery.

The Meson integration preserves LLVM's merged header/runtime resource directory
when compilation arguments follow linker arguments. Compiler and linker warnings
remain fatal. A C/C++ regression declares the hermetic C++ runtime archives,
links both languages, and executes a standard-library conversion.

Apply it after the LLVM 23 toolchain and resource-header patch:

```sh
bazel build //tools/build-warnings/pkg-config:metadata_probe //tools/build-warnings/meson:resource_directory_probe --keep_going
```

The metadata probe checks version lookup, variables, compiler flags, and private
static dependency flags. Both probes pass on their execution host; Linux results
do not establish native Mac acceptance. The bootstrap builds only its required
CLI executable. With `upgrade-z-native-tools.patch`, rules_foreign_cc preserves
explicit `target_args["compile"]` and both probes use that supported property.

`upgrade-native-pkgconf-probe-archiver.patch` supplies the resource probe's
LLVM archiver as a declared build input and an absolute Meson `AR` path.
Apply it after `upgrade-native-pkgconf-bootstrap.patch` and its prerequisites.
The same probe command compiles C and C++, creates its static archive, links,
and runs the executable without requiring an archiver on the worker's `PATH`.
The generated build script and Meson log must identify the declared `llvm-ar`.

## Foreign C/C++ build tools

`upgrade-z-native-tools.patch` selects rules_foreign_cc 0.16.0, CMake 4.4.4,
Meson 1.12.1, Ninja 1.13.2, rules_python 2.4.2, bazel_skylib 1.9.2,
GNU Make 4.4.1.bcr.2, and rules_cc_autoconf 0.27.0. Apply the current issue #61
toolchain, pkgconf, and OpenSSL prerequisites first.

The toolchain uses the registry's Make source build with compiler warnings
enabled and nonfatal, without string-comparison or assertion-counter source backports.
Obsolete Make bootstrap overrides are absent. Meson's Python executable declares
its package initializers and required runfiles without changing the repository's
global runfiles policy. The Meson linker-resource fix remains active. The native
voice rule declares the compiler and platform attributes required by the updated
foreign-build utilities.

Resolve workspace package versions, then run from the patched checkout:

```sh
cargo update --workspace --manifest-path codex-rs/Cargo.toml
bazel test //tools/build-warnings/make-bootstrap:make_warnings_test //tools/build-warnings/make-bootstrap:make_bootstrap_test
bazel build //tools/build-warnings/cmake:stable_version_probe //tools/build-warnings/meson:resource_directory_probe //third_party/voice:pkg_config_unix
bazel build //third_party/voice:native_prefix_linux_x86_64
just fmt-check
just bazel-lock-check
```

The CMake probe requires version 4.4.4. The Make analysis test requires nonfatal
compiler warnings and rejects `-w` and `/w`; its runtime test executes a Makefile.
The Meson probe links and executes its C/C++ fixture. The complete Linux prefix
preserves visible dependency diagnostics and fails on unsuccessful build commands. Native macOS and Windows checks remain separate
platform acceptance gates.

## Native probe execution platform

`upgrade-zz-native-probe-execution.patch` keeps the Meson executable helper out
of wildcard target expansion. The public `resource_directory_probe` still
builds and runs it as an execution-platform tool. Apply
`upgrade-z-native-tools.patch` first.

```sh
bazel build //tools/build-warnings/meson/... --nobuild --platforms=//:windows_x86_64_msvc --repo_env=BAZEL_MSVC_RUNTIME_VISUAL_STUDIO_EULA=1
bazel build //tools/build-warnings/meson:resource_directory_probe
just fmt-check
just bazel-lock-check
```

The Windows-target analysis selects only the public probe and its source group,
without configuring the helper's LLVM runtime dependencies for MSVC. On Unix,
the explicit public probe still compiles, links, and runs its C/C++ executable.

## OpenSSL Rust probe linker inputs

`upgrade-zz-openssl-link-inputs.patch` declares the OpenSSL installation tree as
compile data for the Rust binding probe. The library directory emitted by the
OpenSSL build script is therefore available in the linker sandbox. Apply
`upgrade-openssl.patch` and `upgrade-z-native-tools.patch` first.

```sh
bazel test //tools/build-warnings/openssl:rust_install_inputs_test
bazel build //tools/build-warnings/openssl:rust_bindings_test --platforms=@llvm//platforms:macos_arm64
just fmt-check
just bazel-lock-check
```

The analysis test requires the installation tree in the Rust compiler/linker
action's inputs. The cross-build checks Darwin ARM64 linking; execution of the
resulting test remains a native macOS gate. An unsandboxed link can succeed
without the declared input and does not establish sandbox correctness.


## OpenSSL Rust library names

`z-openssl-library-names.patch` supplies `ssl:crypto` through openssl-sys's
supported `OPENSSL_LIBS` setting for the Bazel-managed installation. Bazel's
MSVC outputs are `ssl.lib` and `crypto.lib`; the Rust build script's normal
Windows defaults request `libssl` and `libcrypto` instead. The explicit selection
also matches the Unix archives through Rust's normal platform naming convention.
It retains static linking and the declared Bazel installation inputs.

Apply this patch after `upgrade-zz-openssl-link-inputs.patch`, which
supplies the focused regression. `bazel test //tools/build-warnings/openssl:rust_bindings_test`
verifies cryptography and TLS through the actual Rust bindings. Native Windows
CI runs the same regression on the MSVC platform; successful Unix execution
alone does not establish that platform's link result.

## GNU Make modern C declarations

`upgrade-zzz-make-getopt-prototypes.patch` gives GNU Make's bundled `getopt`
and `getenv` declarations their existing implemented signatures. Modern Darwin
compilers can compile these sources with fatal warnings without accepting obsolete
non-prototype declarations. Apply `upgrade-z-native-tools.patch` first.

```sh
bazel build @make//:make --platforms=@llvm//platforms:macos_arm64
bazel test //tools/build-warnings/make-bootstrap:make_bootstrap_test
just fmt-check
just bazel-lock-check
```

The cross-build compiles the actual Darwin implementation and links GNU Make.
The runtime probe checks Makefile execution on its execution host. Native macOS
execution remains a separate gate.

## Bundled Opus build executable

`z-opus-ninja-executable.patch` supplies the underlying Ninja 1.13.2
executable directly to Opus's Cargo build script. It declares the executable as
a build input and retains CMake's resolved toolchain. The version matches the
existing foreign-build tool selection. This Cargo action runs outside the
foreign-build wrapper's environment. Apply `upgrade-z-native-tools.patch` and
`vendored-native-build-script-modes.patch` first.

```sh
bazel test //tools/build-warnings/opus:bundled_opus_test
bazel build //tools/build-warnings/opus:bundled_opus_test --platforms=@llvm//platforms:macos_arm64
just fmt-check
just bazel-lock-check
```

`z-opus-search-directories.patch` follows the Ninja executable patch. The build
script emits its optional `lib64` search path only when that directory exists.
Linux executors can therefore target Darwin without adding a nonexistent linker
search directory. Fatal linker diagnostics remain enabled.

The test builds the real bundled codec, links its Rust bindings, and checks its
version string. The Darwin cross-build verifies compilation and linking; native
macOS execution remains a separate gate.

## Abseil Windows compiler selection

`zz-abseil-windows-compiler-selection.patch` keeps Abseil's MSVC pragmas
inside `_MSC_VER` guards and supplies `dbghelp` when Clang targets MinGW.
The existing MSVC library selection and non-Windows behavior remain in use.
Abseil stays at version `20250814.1`.

The patch targets `rust-v0.163.0`
(`83086188ae0614bd882818ff1aa23f3194962012`) with the earlier issue-61
warning-policy and Abseil patches applied. Its regression executable uses CPU
discovery and symbolization so a successful object compilation alone cannot
hide missing Windows link dependencies.

Verification commands from the patched checkout root:

```sh
bazel build --platforms=//:windows_x86_64_gnullvm //tools/build-warnings/abseil:windows_platform_test
bazel test --platforms=//:rbe //tools/build-warnings/abseil:windows_platform_test
```

The Windows executable builds with warnings treated as errors; the Linux
control test passes. Native Windows CI also runs the regression executable.
Issue [#61](https://github.com/holdmyspot-com/codex-unleashed/issues/61)
tracks adoption of the equivalent upstream corrections.

## CCTZ strptime availability

`zz-cctz-strptime-availability.patch` exercises Abseil’s public time-parsing fallback without changing CCTZ source or configuration.

```sh
bazel test //tools/build-warnings/abseil:strptime_fallback_test
```

The test passes when the selected dependency parses the supported timestamp through its public API.

## Explicit Python package initializers

`z-python-explicit-initializers.patch` enables Bazel's explicit initializer
semantics. Python packages supply their own required `__init__.py` files rather
than receiving empty files for every directory in their runfiles. This removes
the deprecated implicit-initializer behavior reported by the built-in hash tool;
it does not disable the warning. Apply it after the issue #61 build-tool patches.

`bazel test //tools/build-warnings/python:hash_initializers_test` verifies that
the actual hash tool has no generated empty package initializers.
`bazel build //tools/build-warnings/meson:resource_directory_probe` exercises
Meson's Python tool and native compiler/linker integration with this policy.
The owning rules_python migration is tracked in
[rules_python #2945](https://github.com/bazel-contrib/rules_python/issues/2945).

## Windows XZ ARM64 filter configuration

`z-xz-windows-arm64-filter.patch` selects Bazel module `xz` 5.4.5.bcr.9 and
enables the ARM64 encoder and decoder that its Windows build already compiles.
The Windows configuration lacks those entry points, leaving the internal filter
initializer unused under strict compiler checks. This repair supplies the missing
configuration rather than excluding the source or disabling its diagnostic.
Apply it after the issue #61 native build patches. The registry module's required
`bazel_lib` dependency resolves to 3.7.2 as part of this direct dependency update.

`bazel test //tools/build-warnings/xz:arm64_filter_test` verifies ARM64 filter
availability and an actual compression/decompression round trip.
`bazel build //tools/build-warnings/xz:arm64_filter_test --platforms=//:windows_x86_64_gnullvm`
compiles and links the Windows regression with strict compiler diagnostics.
Native Windows CI executes the same regression on the MSVC platform.

The source configuration is maintained in the
[Bazel Central Registry XZ module](https://github.com/bazelbuild/bazel-central-registry/tree/main/modules/xz/5.4.5.bcr.9).

## Rust runtime selection on Darwin

`upgrade-native-rust-runtime-selection.patch` removes C++ compiler-runtime
selection from Rust's explicit-runtime links on Darwin. Rust supplies its runtime
archives and disables default libraries there; the unused selection otherwise
makes LLVM 23 reject object-only Darwin links. Windows GNU retains compiler-rt
selection because Rust's `-nolibc` pre-link arguments still require it.
Project compiler and linker warnings remain fatal; dependency warnings remain visible and nonfatal.
Apply the issue #60 toolchain patch and this issue's LLVM driver fixtures first.

```sh
bazel build //tools/build-warnings/llvm-driver:linked_macro --platforms=@llvm//platforms:macos_aarch64
bazel build //tools/build-warnings/llvm-driver:linked_macro --platforms=@llvm//platforms:macos_x86_64
bazel build //tools/build-warnings/llvm-driver:windows_gnullvm_link_probe --platforms=//:windows_x86_64_gnullvm
bazel test //tools/build-warnings/llvm-driver:all
just fmt-check
```

Both Darwin cross-links and the Windows GNU Rust binary link must pass.
The test loads the procedural macro on the
compiler's execution platform and checks its expansion on the test platform.
Remote compilation can use Linux even when the target platform is macOS, so
cross-links and that test do not establish native macOS macro loading. The full
platform build remains required.

## Native voice Meson resources

`native-voice-meson-resource-directory.patch` preserves the declared linker
resource directory in the native voice recipe's Meson compilation arguments.
Meson appends those arguments when linking; the selected merged resource tree
supplies both builtin headers and runtime libraries. Other compiler arguments
and toolchains without a declared link resource directory retain their behavior.
Apply the LLVM 23 and pkgconf bootstrap prerequisites before verification.

The focused probe uses the native recipe's generated Bazel configuration, its
execroot, the declared Meson source tree, and the declared Ninja executable:

```sh
python3 tools/build-warnings/check-native-meson.py /path/to/native_prefix_linux_x86_64.json /path/to/bazel-execroot /path/to/meson-source /path/to/ninja
python3 -m unittest discover -s third_party/voice -p 'test_*.py'
bazel build //third_party/voice:native_prefix_linux_x86_64 --keep_going
```

Use a project-owned `TMPDIR` for the probe. It compiles and links the shared
C/C++ fixture with the real declared compiler flags, executes its standard-library
conversion, prints the build logs, and removes its scratch files on exit.
The complete native prefix and non-Linux platforms remain separate gates.

## Native voice CMake defaults and diagnostics

`native-voice-cmake-initial-cache.patch` supplies generic compiler, installation,
platform, and offline-discovery defaults through CMake's supported
[`-C` initial cache](https://cmake.org/cmake/help/v3.31/manual/cmake.1.html).
The cache uses literal bracket arguments and records its settings in build
provenance. C-only and C++-only projects can share these defaults without unused
command-line variable warnings. Project-specific options remain `-D` arguments.

`native-voice-warning-diagnostics.patch` follows that patch. Native dependency
commands retain their exit status when their logs contain compiler, linker,
CMake, Meson, or GNU configure warnings. Warnings remain visible in the
console, command logs, and receipt, including multiline context. Commands
that fail retain their nonzero status.

```sh
python3 -m unittest discover -s third_party/voice -p 'test_*.py'
just fmt-check
bazel build //third_party/voice:native_prefix_linux_x86_64 --keep_going
```

The regressions exercise real child processes, build C-only and C++-only CMake
fixtures, preserve literal cache values, and require an unused project-specific
option to remain visible without failing a successful command. Use the declared build tools on `PATH` and a
project-owned `TMPDIR`. The complete native prefix and hosted platform builds
remain separate acceptance gates.

## Native GLib printf compatibility

`native-voice-warning-glib-1-printf.patch` supplies a public-API runtime probe for the checksum-pinned GLib printf implementation. It checks wide integers, padding, and long output without changing dependency source.

```sh
bazel test //tools/build-warnings/glib:printf_test
```

The expected result is successful execution against the native SDK.

## Native GLib log writer

`native-voice-warning-glib-2-writer.patch` tests the public log-writer callback and user-data contract without changing GLib source.

```sh
bazel test //tools/build-warnings/glib:log_writer_test
```

The expected result is one callback with the supplied state and message.

## Native GStreamer pad compatibility

`native-voice-warning-gstreamer-1-quarks.patch` supplies shared native SDK test support and a pad API runtime probe. It preserves the dependency source, including its unused quark storage and option-parsing callback.

```sh
bazel test //tools/build-warnings/gstreamer:pad_quarks_test
```

The expected result is successful execution against the native SDK.

## Native GStreamer enum generation

`native-voice-warning-video-2-enums.patch` keeps two enum expressions on single lines so glib-mkenums emits their public values. This fixes missing runtime enum registrations independently of warning policy.

```sh
bazel test //tools/build-warnings/gstreamer:enum_values_test
```

Both public flags must be present with their declared values.

## Native GStreamer audio conversion

`native-voice-warning-z-audioconvert.patch` tests loading the built audio conversion plugin and constructing its element. It preserves the dependency source and logging category.

```sh
bazel test //tools/build-warnings/gstreamer:audio_convert_test
```

The expected result is successful plugin loading and element construction on Linux.

## Native SDK dependency metadata

`native-voice-z-sdk-relocation.patch` makes the pinned libffi, PCRE2, and zlib
pkg-config templates relative to their installed metadata directory. The SDK
exporter requires relocatable metadata for exported transitive dependencies as
well as its primary modules. Bazel's non-hermetic build-script path check remains
enabled. Apply this patch after the native voice compatibility series above.

```sh
python3 tools/build-warnings/check-native-sdk-relocation.py <native-prefix-json> <execroot>
python3 -m unittest discover -s third_party/voice -p 'test_*.py'
just fmt-check
bazel build //codex-rs/voice-host:codex-voice-host --keep_going
```

Use a project-owned `TMPDIR` and the declared build tools. The focused probe
extracts the checksum-verified templates, renders their installation variables
through CMake, moves the SDK, and queries its compiler and linker directories
with the declared pkg-config executable. All three dependencies resolve inside
the moved SDK. The complete consumer build verifies the generated SDK and Rust
build-script metadata; Windows and macOS execution remain separate gates.

## Native SDK sandbox metadata

`native-voice-z-sdk-sandbox-prefix.patch` binds pkgconf's per-module prefix
variables to the consuming Cargo action's declared SDK. This preserves logical
input paths when pkgconf follows Bazel's individual metadata-file symlinks to
the outer execroot. Version probes and non-hermetic path validation remain
enabled. Apply it after the pkgconf bootstrap and SDK relocation patches.

With a project-owned `TMPDIR`, run from the patched checkout:

```sh
bazel build //codex-rs/voice-host:codex-voice-host --keep_going
bazel aquery 'mnemonic("CargoBuildScriptRun", deps(//codex-rs/voice-host:codex-voice-host))' --output=jsonproto > <actions-json>
python3 tools/build-warnings/check-native-sdk-sandbox.py <actions-json> <execroot> <native-sdk>
just fmt-check
```

Use the built target's actual execroot and SDK directory. The probe reads the
generated GLib action's environment and queries all twelve exported SDK modules
through a directory of file symlinks. Every include and library directory stays
inside the consuming sandbox. The complete voice-host build verifies Rust's
metadata validation and final linking. Native Windows and macOS acceptance
remain separate gates.

## Prost rule compatibility

`native-prost-rule-compatibility.patch` backports
[rules_rust PR #4261](https://github.com/bazelbuild/rules_rust/pull/4261).
It removes an unused dependency on the retired `grep-includes` target from the
Prost aspect. The maintained protobuf compiler, plugins, and runtime remain in
use. Apply it after the issue #60 toolchain patch and this issue's LLVM 23 patch,
which selects rules_cc 0.2.25.

```sh
bazel test //tools/build-warnings/prost:round_trip_test --keep_going
```

The test generates a message with the registered Prost toolchain and verifies
an encode/decode round-trip. Without the backport, target analysis fails because
the unused legacy dependency no longer exists.

## Codespell tool isolation

`codespell-isolated-tool-environment.patch` runs the existing Codespell check
through uv's isolated tool environment. It preserves the action's package
requirement, repository configuration, ignore list, count output, and failure
status. The workflow caches tool dependencies using its own configuration as
the cache input. It applies independently against the declared base.

```sh
uv tool run --from 'codespell[toml]>=2.2.4' codespell --count --skip ./.git --ignore-words .codespellignore
just fmt-check
```

Tool installation produces no root-pip warning. Spelling findings retain their
nonzero status; this patch does not establish that upstream's spelling inventory
is clean. The Docker action's pinned version and latest stable version both
install Python packages as root.

## Verification

`typescript-lint-warning-policy.patch` sets a zero-warning threshold on the
TypeScript SDK's lint and lint-fix commands. Its process-boundary test invokes
the maintained lint script with an ESLint warning and requires a nonzero status
with the warning-threshold diagnostic. Normal SDK lint and build commands pass.

```sh
pnpm -r --filter ./sdk/typescript run lint
pnpm -r --filter ./sdk/typescript run build
pnpm -r --filter ./sdk/typescript run test --runInBand --runTestsByPath tests/lintWarningPolicy.test.ts
```

From the patched checkout root:

- `just test-github-scripts` passes, including ten formatter regression tests.
- `just fmt-check` passes with stable rustfmt.
- Source whitespace checks pass. Nested patches use Git's empty-line spelling for empty context. Their hunk counts remain consistent when trailing context is shortened.
- After Cargo resolves the workspace lockfile versions, `just bazel-lock-check` passes without crate-annotation advisories, including on a cold module-extension evaluation.
- `just check-build-warnings` passes on Unix: the clean fixture builds and the warning fixture fails at its expected diagnostic.
- `bazel test //third_party/v8/libcxx_config:config_test` passes with both runtime configuration values explicitly defined as zero.
- `bazel test //tools/build-warnings:mingw_function_guard_x86_64_test //tools/build-warnings:mingw_function_guard_aarch64_test` passes without preprocessor warnings and preserves both architectures' macro expansion.
- `bazel test //tools/build-warnings/bindgen:preprocessor_policy_test` passes all six argument-preservation and MSVC normalization regressions.
- `bazel build //tools/build-warnings/bindgen:warning` fails specifically at `expected_bindgen_warning`, proving that a header warning is fatal during binding generation.
- `bazel test //tools/build-warnings/make-bootstrap:make_bootstrap_test` builds the selected GNU Make and verifies its Makefile execution. Dependency warnings remain visible and nonfatal.
- `bazel test //tools/build-warnings/aws-lc:thread_test` verifies that worker-thread exit invokes its registered destructor exactly once and an unused thread does not invoke it again.
- `bazel build //tools/build-warnings/aws-lc:thread_test --platforms=//:windows_x86_64_gnullvm` compiles and links the Windows regression executable without warnings. On a Windows execution host, run the same target with `bazel test` to verify thread-exit cleanup; cross-compilation alone does not establish that runtime outcome.
- `bazel test //tools/build-warnings/llvm-driver:all` verifies warning-free Windows GNU linking with `-nolibc` and requires the MSVC linker to reject an intentionally triggered warning when fatal-linker warnings are enabled. These Linux-hosted driver checks do not establish Windows runtime behavior.
- On Windows with the installed MSVC/SDK environment, `bash tools/build-warnings/check.sh --config=ci-windows-msvc` checks native MSVC warning enforcement.

Local verification uses the project's maintained `run-local-rust` helper with checkout-specific outputs and dependency caches beneath `.cat/work/temp/build-caches`. The same commands apply after applying the emitted patch to a fresh checkout of the declared upstream base.

## Upstream status

The declared upstream release permits ignoring the stable-rustfmt import-layout warning and does not reject other successful formatter warnings. Rustfmt's import-granularity option remains unstable in its tracking issue.

AWS-LC [commit `8cca33b97eb64be944a3d90406230676e8a7b931`](https://github.com/aws/aws-lc/commit/8cca33b97eb64be944a3d90406230676e8a7b931) fixes MinGW TLS callback registration. The queue retains the backport while its AWS-LC dependency lacks that fix.

Dependency warnings remain nonfatal. Source backports are limited to compilation failures, security problems, and incorrect runtime behavior.

## V8 Windows stack compatibility

`zz-v8-windows-stack-assembly.patch` exposes a bounded Windows stack-assembly fixture through V8’s production library macro. Its C++ fixture checks the existing ICU definitions; its executable checks callback arguments and stack alignment. Dependency compiler arguments retain their released behavior. Apply the existing LLVM assembly-action patch and V8 MinGW detection patch first. V8 remains at version `15.0.245.2`.

```sh
bazel build @v8//:codex_windows_stack_assembly_test --platforms=//:windows_x86_64_gnullvm
bazel test //tools/build-warnings/v8:windows_stack_assembly_test --platforms=//:windows_x86_64_gnullvm
just fmt-check
just bazel-lock-check
```

The regression uses V8's production library macro and selected Windows stack
assembly. Its C++ fixture requires the ICU definitions, and its executable
checks callback arguments and stack alignment. Cross-compilation verifies the
compile and link boundary; run the test on a Windows execution host for runtime
acceptance. Full V8 builds and hosted platform checks remain separate gates.

## Native tool executable configuration

`zz-native-tool-executable-configuration.patch` preserves the caller's
execution-platform configuration when selecting a foreign-build executable.
The toolchain already transitions to its execution platform; its executable
selector uses that configuration instead of performing a second transition.
Ninja's selected path therefore belongs to its declared tool inputs, including
when a Linux worker precedes the native macOS execution platform.
Apply `upgrade-z-native-tools.patch` and its prerequisites first.

```sh
bazel build //tools/build-warnings/meson:ninja_native_macos_path_probe --nobuild --platforms=@llvm//platforms:macos_arm64 --extra_execution_platforms=//:rbe,@llvm//platforms:macos_arm64
bazel build //third_party/voice:pkg_config_unix --platforms=//:rbe
just fmt-check
just bazel-lock-check
```

The analysis regression requires the selected Ninja executable in the
toolchain's declared inputs. The Linux control builds the actual pkgconf
executable. Native macOS acceptance builds
`//third_party/voice:pkg_config_unix_native_macos_aarch64_` on a macOS ARM64
execution host. Analysis alone does not establish native execution.

## Hermetic Python tool bootstrap

`zz-native-z-hermetic-python-bootstrap.patch` selects rules_python's supported
`bootstrap_impl=script` launcher. Unix tools start through their declared
interpreter without running host Python first. This prevents Apple's host
Python from injecting an unrelated library search path into native builds.
rules_python retains its Windows launcher selection. Dependency versions and
compiler warning checks remain unchanged. Apply the native tool executable
configuration patch first.

```sh
bazel build //tools/build-warnings/meson:hermetic_bootstrap_probe --platforms=//:rbe
bazel build //third_party/voice:pkg_config_unix --platforms=//:rbe
just fmt-check
just bazel-lock-check
```

The bootstrap regression runs the real Meson tool with a rejecting host
`python3` first on `PATH` and requires nonempty version output. Its action uses
the tool's executable provider to preserve declared runfiles. Explicitly
overriding `--@rules_python//python/config_settings:bootstrap_impl=system_python`
must fail with `Unexpected host Python bootstrap`. The regression runs on Unix;
Windows launcher and complete platform builds remain separate acceptance gates.

## OpenSSL socket error sentinels

`zz-openssl-socket-sentinel.patch` checks invalid-socket errors through OpenSSL’s public BIO API without changing socket sentinel expressions in dependency source.

```sh
bazel test //tools/build-warnings/openssl:socket_error_test
```

The regression requires invalid socket operations to report the expected failure and error queue.

## OpenSSL Windows GNU Clang selection

`zz-openssl-zz-gnu-clang-selection.patch` includes the existing Windows GNU
Clang compiler setting in OpenSSL's non-MSVC platform groups. Windows x86-64
assembly generation selects `mingw64` instead of an empty flavor that produces
ELF assembly. Existing GCC, clang-cl, and MSVC selections remain supported.
The Microsoft SDK's Winsock guard applies only to MSVC and clang-cl. GNU
Windows builds use `WIN32_LEAN_AND_MEAN` and let MinGW define its own guard.
The generator is visible to the downstream regression package for inspection.
The GNU analysis fixture clears runner-specific extra toolchains so native MSVC
CI also inspects the intended GNU compiler configuration.
Apply the OpenSSL upgrade and native tool prerequisites first.

```sh
bazel test //tools/build-warnings/openssl:windows_perlasm_test --platforms=//:local_linux
bazel build //tools/build-warnings/openssl:ssl_init_test --platforms=//:windows_x86_64_gnullvm
just fmt-check
just bazel-lock-check
```

The analysis regression requires the MinGW flavor in every production Windows
x86-64 GNU assembly-generation action. The build compiles and links the actual
generated objects into the SSL regression executable. Native Windows execution
remains a separate runtime gate.

## OpenSSL notifier close-on-exec helper

`zz-openssl-zzz-rio-cloexec-guard.patch` exercises notifier signaling, readiness, and clearing through OpenSSL’s public API. It leaves the dependency’s unused helper unchanged.

```sh
bazel test //tools/build-warnings/openssl:rio_notifier_test
```

The regression passes when signaling makes the notifier ready and clearing resets it.

## Darwin libffi compiler and linker separation

`zzz-libffi-darwin-linker-selection.patch` lets libffi's Autoconf compile-only
probes run with fatal warnings while keeping Apple's native linker for normal
and Libtool partial links. The native linker selector belongs in `LDFLAGS` and
Libtool's `LTCC`, so compile-only commands do not receive it. Compiler and linker
diagnostics remain enabled. The tracking issue is
[#61](https://github.com/holdmyspot-com/codex-unleashed/issues/61).

The declared base is `rust-v0.163.0`
(`83086188ae0614bd882818ff1aa23f3194962012`). Apply the preceding native
voice build patches, including `zz-native-tool-executable-configuration.patch`,
first.

```sh
cd third_party/voice
python3 -m unittest test_build_native
cd ../..
just fmt-check
just bazel-lock-check
```

With Clang on `PATH`, the regression constructs the Darwin configuration on a
Unix host, compiles with `-Werror`, links and runs a real fixture, and checks that
normal and partial-link driver traces select `/usr/bin/ld`. The native-builder
suite passes. These checks exercise the host compiler; they do not substitute
for a native Darwin build. On an Apple Silicon Mac,
`bazel build //third_party/voice:native_prefix_macos_aarch64` verifies the complete
libffi configure, build and install path as part of the native voice prefix.

## Darwin unsigned pointer formatting

`zzz-v8-darwin-pointer-format.patch` corrects V8's Darwin unsigned pointer format
to print decimal values. The pointer-format regression from
`zz-v8-clang-mingw-target-detection.patch` checks hexadecimal, signed decimal,
and unsigned decimal output using V8's real headers. The tracking issue is
[#61](https://github.com/holdmyspot-com/codex-unleashed/issues/61).

The declared base is `rust-v0.163.0`
(`83086188ae0614bd882818ff1aa23f3194962012`) with the preceding issue-61
V8 patches, including `zz-v8-clang-mingw-target-detection.patch`.

```sh
bazel test @v8//:codex_pointer_formats_test
just fmt-check
just bazel-lock-check
```

The expected result is a passing pointer-format test on Darwin, Linux, and
Windows. Native Darwin execution verifies Darwin's format selection and runtime
behavior; a Linux execution alone does not establish Darwin coverage.

## Direct enum dependency alignment

`zzz-strum-direct-dependency.patch` upgrades the direct workspace `strum`
dependency to 0.28.0, matching the selected `strum_macros` version. Cargo removes
the two older package versions without adding or upgrading other external
packages. The declared base is `rust-v0.163.0`
(`83086188ae0614bd882818ff1aa23f3194962012`) with the preceding issue-61
dependency patches. The tracking issue is
[#61](https://github.com/holdmyspot-com/codex-unleashed/issues/61).

```sh
tools/build-warnings/check-enum-dependencies.sh
just fmt-check
just bazel-lock-check
bazel test //codex-rs/protocol:protocol-unit-tests //codex-rs/state:state-unit-tests
```

The dependency check succeeds only when Cargo resolves one version of each enum
package. Protocol and state unit tests verify affected enum users. The complete
TUI suite remains a separate acceptance gate.

## Native Intel macOS test runner

`zzz-native-macos-test-runner.patch` selects `macos-15-intel` for the native
`x86_64-apple-darwin` Bazel test matrix entry. The fork's corresponding workflow
uses the same runner selection. ARM and Intel jobs retain their existing target
cache namespaces. The declared base is `rust-v0.163.0`
(`83086188ae0614bd882818ff1aa23f3194962012`); the runner correction applies
independently. The tracking issue is
[#61](https://github.com/holdmyspot-com/codex-unleashed/issues/61).

Verification requires the Intel matrix job to report an x86-64 host and build
`darwin_x86_64` targets. The ARM job must report an ARM64 host and build
`darwin_arm64` targets. A green job whose actual host differs from its declared
native target does not establish that target's coverage. GitHub's
[runner reference](https://docs.github.com/en/actions/reference/runners/github-hosted-runners)
documents the host architectures of these runner labels.


## V8 Python execution platforms

`zzz-v8-python-execution-platforms.patch` makes V8's existing Python 3.11
dependencies available on ARM Linux remote workers and both macOS
architectures. The selected package versions and hashes remain unchanged.
The tracking issue is
[#61](https://github.com/holdmyspot-com/codex-unleashed/issues/61).

The declared base is `rust-v0.163.0`
(`83086188ae0614bd882818ff1aa23f3194962012`) with the preceding issue-61
V8 dependency and Python bootstrap patches.

```sh
tools/build-warnings/v8/check-python-platforms.sh
just fmt-check
just bazel-lock-check
```

The regression resolves V8's code generator for Linux x64, Windows x64,
Linux ARM64, macOS x64, and macOS ARM64, then generates the inspector sources
on the executing host. All configurations must resolve without a missing-wheel
error. Analysis on one host does not establish native execution on the other
platforms; hosted V8 canary builds remain separate acceptance gates.

## V8 inspector runtime

`zzz-v8-inspector-python-runtime.patch` generates V8 inspector sources on
workers without a system `unzip` command. It launches the existing zipapp with
Bazel's declared execution-platform Python runtime and uses Python's zip
extraction support. Package versions remain unchanged. The tracking issue is
[#61](https://github.com/holdmyspot-com/codex-unleashed/issues/61).

The declared base is `rust-v0.163.0`
(`83086188ae0614bd882818ff1aa23f3194962012`) with the preceding issue-61
V8 build patches. The Python execution-platform patch supplies the additional
wheel configurations needed by ARM Linux and macOS workers. These two fixes
can apply in either order.

```sh
tools/build-warnings/v8/check-inspector-runtime.sh
tools/build-warnings/v8/check-python-platforms.sh
just fmt-check
just bazel-lock-check
```

The runtime regression generates the actual inspector sources for Linux and
Windows target configurations on a Linux execution host while an executable
fixture makes `unzip` unavailable. Both builds must succeed using the declared
runtime. This cross-platform check does not establish native Windows or macOS
execution; hosted V8 builds remain separate acceptance gates.

## V8 MSVC driver flags

`zzz-v8-msvc-driver-flags.patch` selects C++20 and Windows library options
using the compiler driver. MSVC and clang-cl receive CL syntax. MSVC selects
C++20 once; clang-cl selects C++20 through its supported override. GNU drivers
retain GNU syntax. It also exposes the existing assembly-library fixture to
the compile-action regression. Package versions remain unchanged. The tracking
issue is [#61](https://github.com/holdmyspot-com/codex-unleashed/issues/61).

The declared base is `rust-v0.163.0`
(`83086188ae0614bd882818ff1aa23f3194962012`) with the preceding issue-61
V8 build and inspector-runtime patches.

```sh
bazel test --repo_env=BAZEL_MSVC_RUNTIME_VISUAL_STUDIO_EULA=1 \
  //tools/build-warnings/v8:windows_pointer_flags_test \
  //tools/build-warnings/v8:windows_library_flags_test
just fmt-check
just bazel-lock-check
```

The analysis regressions require C++20, require one language selection for MSVC,
preserve clang-cl's exception, RTTI, and stack-protector options, and reject GNU C++ standard and
warning flags in Windows CL compile actions. They inspect the pointer fixture
and the assembly library's C++ fixture without compiling their dependencies.
Native Windows compilation remains a separate acceptance gate, including the
assembly source and linker behavior.

## Native Windows assembly

`zzz-windows-msvc-assembly.patch` keeps MASM actions free of C/C++ compiler
options, maps external include directories to MASM's include option, and makes
assembly warnings fatal through `/WX`. C/C++ compilation retains its existing
options. The build-warning fixtures select MASM sources for native MSVC and GNU
sources for other drivers. Dependency versions remain unchanged. The tracking
issue is [#61](https://github.com/holdmyspot-com/codex-unleashed/issues/61).

The declared base is `rust-v0.163.0`
(`83086188ae0614bd882818ff1aa23f3194962012`) with the preceding issue-61
build-warning patches.

```sh
bazel test //tools/build-warnings/assembly/native:flags_test \
  //tools/build-warnings/assembly:native_fixture_selection_test
just fmt-check
just bazel-lock-check
```

The analysis regressions use rules_cc's native Windows toolchain configuration
to check raw and preprocessed assembly arguments, fixture selection, and the
retained C compiler options. They run without executing MSVC tools and do not
establish native compilation. A Windows MSVC build of
`//tools/build-warnings/assembly:compile_inputs` remains a separate acceptance
gate for source syntax and command execution.

## Native libffi library directory

`zzz-libffi-native-library-directory.patch` uses libffi's supported
`--disable-multi-os-directory` option for the native voice prefix. Libraries
install in the declared `lib` directory without invoking GCC's multilib query,
which Clang does not support. Project warnings remain fatal and dependency
versions remain unchanged. The tracking issue is
[#61](https://github.com/holdmyspot-com/codex-unleashed/issues/61).

The declared base is `rust-v0.163.0`
(`83086188ae0614bd882818ff1aa23f3194962012`) with the preceding issue-61
native voice build patches.

```sh
python3 -m unittest discover -s third_party/voice -p test_build_native.py
just fmt-check
just bazel-lock-check
```

The recipe regressions require explicit native library placement on Unix and
Windows. An integration comparison runs libffi 3.8.0's configure with Clang 23:
the configured `libdir` and `toolexeclibdir` stay the same, and the unsupported
`-print-multi-os-directory` diagnostics disappear. Native macOS acceptance also
requires the module-probe patch below and full platform verification.

## Ninja elapsed edge times

`zzz-ninja-elapsed-time.patch` preserves the 64-bit elapsed start time between
Ninja's edge-start and edge-completion callbacks. It fixes the MSVC C4244 error
in the direct Ninja 1.13.2 Bazel module without changing the dependency version.
The BCR build exposes Ninja's existing core as a library so the regression runs
its real `Builder`; the executable retains its compiler options and Windows
preprocessor definitions. Ninja's existing build-log representation is outside
this patch's scope.

The declared base is `rust-v0.163.0` (`83086188ae0614bd882818ff1aa23f3194962012`)
with the preceding issue-61 module and build-policy patches. The nested patch
applies to the Ninja 1.13.2 BCR source archive and its published build overlay.
The canonical tracking issue is <https://github.com/holdmyspot-com/codex-unleashed/issues/61>.
Remove the workaround when the selected Ninja module preserves elapsed edge
start times without narrowing and the regression passes without the type fix.

Run from the patched checkout:

```sh
bazel test //tools/build-warnings/ninja:elapsed_time_test --test_output=errors
bazel build @ninja//:ninja
just fmt-check
just bazel-lock-check
```

The runtime test checks an ordinary elapsed time and a start time above
`INT_MAX`. Each edge has exactly one start and completion callback, both
callbacks receive the same start time, and completion cannot precede the start.
The test-only baseline loses the high bit in the completion callback. The
patched native test preserves it. Linux execution does not establish native
Windows/MSVC acceptance; that remains a hosted-platform gate.

## Native libffi Darwin module configuration

`zzz-libffi-native-module-probe.patch` selects bundled libtool's documented
`LT_MULTI_MODULE` configure-time control for the Darwin libffi recipe. The
C/assembly shared library uses the linker's default behavior without probing
or adding the obsolete `-single_module` flag. Capability-cache values,
warning rejection, and dependency versions remain unchanged. The tracking
issue is [#61](https://github.com/holdmyspot-com/codex-unleashed/issues/61).

The declared base is `rust-v0.163.0`
(`83086188ae0614bd882818ff1aa23f3194962012`) with the preceding issue-61
native voice patches, including `zzz-libffi-native-library-directory.patch`.
The control is documented directly in libffi 3.8.0's bundled
`m4/libtool.m4`, in `_LT_REQUIRED_DARWIN_CHECKS`. Remove this workaround when
the bundled libtool configuration omits the obsolete probe on modern Darwin.

```sh
python3 -m unittest discover -s third_party/voice -p test_build_native.py
just fmt-check
just bazel-lock-check
```

The recipe regression runs the bundled libtool probe with the environment
selected by the native builder. It must complete without warning diagnostics
and retain `capability=no`; a plain C Mach-O shared library must link with
linker warnings fatal. Linux uses Clang and `ld64.lld` for this bounded
cross-link check. Native macOS uses its native linker. The complete
voice-prefix build and its consumers remain required native-platform gates.

## Windows compiler flag ownership and Rust wrapper parameter files

The `zzzz-0001` through `zzzz-0005` series applies in numeric order after the
existing issue-61 queue. Its upstream base is `rust-v0.163.0`
(`83086188ae0614bd882818ff1aa23f3194962012`). The series uses the existing
native MSVC analysis toolchain and dependency overrides. Adjacent patch
registrations account for the ordering dependency; each file contains one fix.

- `zzzz-0001-xz-windows-driver-flags.patch` restricts GNU stack flags to
  Windows Clang, GCC, and MinGW GCC drivers. MSVC receives neither GNU flag;
  the GNU Windows configuration retains both flags.
  Its GNU analysis fixture clears runner-specific extra toolchains so native
  MSVC CI does not replace the compiler configuration under inspection.
- `zzzz-0002-cargo-c-standard.patch` lets Cargo build scripts choose their C
  dialect without also exporting the toolchain's default dialect. Explicit
  compile flags, the selected runtime, and fatal warnings remain enabled.
- `zzzz-0003-aws-lc-c-standard.patch` selects AWS-LC's explicit C11 dialect
  without also adding the toolchain's default C17 dialect. Its shared feature
  selection covers the libraries, bundled tools, and tests.
- `zzzz-0004-rust-wrapper-parameter-files.patch` lets Bazel spill long Rust
  process-wrapper arguments into a multiline parameter file. The parser
  preserves LF and CRLF files, backslashes, spaces, quotes, empty arguments,
  and child compiler response-file references. An unreadable wrapper file
  reports its path and retains the underlying I/O error.
- `zzzz-0005-make-c-standard.patch` selects GNU Make's explicit C11 dialect
  without also adding the toolchain's default dialect. Its analysis regression
  reuses the native C-standard fixture from `zzzz-0003`.

The compiler-action checks inspect the native MSVC toolchain configuration.
They require the intended dialect or driver flags and retain `/WX` or
`-Werror`. The Cargo check also requires exactly one static-runtime flag.
The transport test uses 600 environment files to exceed Windows' process
command-line limit and verifies that their compiler environment is available.
The maintained wrapper suite includes a parameter file larger than that limit.

Run the focused checks from the patched checkout root:

```sh
bazel test //tools/build-warnings/xz:msvc_flags_test //tools/build-warnings/xz:gnu_flags_test //tools/build-warnings/cargo-c-standard:flags_test //tools/build-warnings/aws-lc:crypto_standard_test //tools/build-warnings/aws-lc:ssl_standard_test
bazel test @rules_rust//util/process_wrapper:process_wrapper_test //tools/build-warnings/wrapper-params:transport_test
bazel test //tools/build-warnings/make:standard_test
just fmt-check
just bazel-lock-check
```

Each command succeeds. The action checks establish compiler configuration;
full native Windows compilation and execution remain separate required gates.
The AWS-LC action checks cover crypto and SSL directly; native CI also covers
jitterentropy and other transitive targets. Linux execution of the transport
test establishes parameter-file transport, while native Windows execution
establishes behavior at the Windows process-creation boundary. The Bazel JVM
startup diagnostic is a separate launcher issue and is not suppressed by this
series. No dependency version changes are required.

## Cargo native runtime features

`zzzz-0006-cargo-runtime-features.patch` supplies the configured target or
execution-platform Rust toolchain flags to Cargo build scripts and uses those
flags when querying Rust's target configuration. Native build scripts receive
the `crt-static` feature that matches the selected Rust runtime, avoiding
conflicting MSVC runtime options. Explicit build-script flags and fatal-warning
settings remain enabled. Dependency versions remain unchanged.

The base is `rust-v0.163.0`
(`83086188ae0614bd882818ff1aa23f3194962012`) with the preceding issue-61
queue, including the Cargo C-standard and parameter-file patches. The tracking
issue is [#61](https://github.com/holdmyspot-com/codex-unleashed/issues/61).

```sh
bazel test //tools/build-warnings/cargo-runtime:toolchain_flags_test //tools/build-warnings/cargo-runtime:static_runtime_test //tools/build-warnings/cargo-runtime:dynamic_runtime_test
bazel test @rules_rust//cargo/private/cargo_build_script_runner:bin_test @rules_rust//cargo/private/cargo_build_script_runner:test
just fmt-check
just bazel-lock-check
```

The analysis test requires the configured MSVC toolchain's static-runtime flag
in the build-script action. The executable tests require both static and dynamic
runtime choices to reach real build scripts through encoded Rust flags; they
run on Linux and Windows. macOS excludes these runtime-specific fixtures.
The full native Windows build remains required to verify compiler commands and
the absence of runtime-option warnings in dependency builds. The source carries
removable `WORKAROUND` comments; no warning diagnostic is suppressed.

## CI MSVC runtime license propagation

`zzzz-0007-ci-msvc-runtime-license.patch` supplies the accepted MSVC runtime
license to every Bazel CI job. Linux analysis tests also resolve Windows runtime
headers, so the setting applies across the CI matrix. Ordinary local builds
continue to require the operator's license acceptance.

The declared base is `rust-v0.163.0`
(`83086188ae0614bd882818ff1aa23f3194962012`). This patch changes only the Bazel
workflow and applies independently of the compiler repair series. The focused
CI check uses the LLVM module supplied by the preceding issue-61 dependency
patches. The canonical tracking issue is
https://github.com/holdmyspot-com/codex-unleashed/issues/61.

Verification uses `bazel build --nobuild
@llvm//runtimes/msvc:msvc_include` with the workflow's
`BAZEL_MSVC_RUNTIME_VISUAL_STUDIO_EULA=1` environment. It resolves and analyzes
the runtime headers without a license error. With that environment absent,
the same command fails at the MSVC runtime license check. The workflow runs this
check on Linux before the full test build. Required queue checks remain
`just fmt-check` and `just bazel-lock-check`; the native platform matrix verifies
the complete builds.

## Large Darwin unwind sections

`zzzz-0008-darwin-large-unwind.patch` selects Rust's bundled LLD on macOS to
avoid Apple's oversized unwind-section warning without disabling diagnostics.
Its removable `WORKAROUND` comment references
https://github.com/rust-lang/rust/issues/159105. LLD preserves valid DWARF fallback
offsets as described in https://reviews.llvm.org/D147505.
The configuration uses the stable `ld64.lld` linker flavor documented in
https://doc.rust-lang.org/rustc/codegen-options/index.html#linker-flavor.
The native regression retains more
than 16 MiB of DWARF call-frame information, links with fatal warnings, and
catches a Rust panic that unwinds through an assembly frame beyond the compact
unwind offset limit. Both Apple Silicon and Intel macOS run the check.

The declared base is `rust-v0.163.0`
(`83086188ae0614bd882818ff1aa23f3194962012`) with the issue-61 fatal-warning
policy. Run `bash tools/build-warnings/check-darwin-linker.sh` from the patched
checkout with owned `TMPDIR` and `CARGO_TARGET_DIR` directories. Success requires
both linking and panic unwinding. Non-macOS hosts return 2 rather than claiming
native coverage. The script removes its temporary source and package outputs.
Required queue checks are `just fmt-check` and `just bazel-lock-check`.
The full macOS Cargo release build remains a separate required gate.

## Short regression test resource declarations

`zzzz-0009-regression-test-sizes.patch` declares eleven short native build
regressions as small Bazel tests. It covers the assembly fixture checks,
AWS-LC and Cargo C-standard checks, the Make C-standard check, the Ninja elapsed
time check, the two V8 Windows action checks, and the two XZ driver checks. Verbose timeout diagnostics remain
active.

The declared base is `rust-v0.163.0`
(`83086188ae0614bd882818ff1aa23f3194962012`) with the preceding issue-61 patches
that introduce these test targets, including `zzzz-0005-make-c-standard.patch`.
The tracking issue is https://github.com/holdmyspot-com/codex-unleashed/issues/61.
Run the following focused check with the MSVC runtime license environment set:

```sh
bazel test --test_verbose_timeout_warnings --test_output=errors \
  //tools/build-warnings/assembly:native_fixture_selection_test \
  //tools/build-warnings/assembly/native:flags_test \
  //tools/build-warnings/aws-lc:crypto_standard_test \
  //tools/build-warnings/aws-lc:ssl_standard_test \
  //tools/build-warnings/cargo-c-standard:flags_test \
  //tools/build-warnings/make:standard_test \
  //tools/build-warnings/ninja:elapsed_time_test \
  //tools/build-warnings/v8:windows_pointer_flags_test \
  //tools/build-warnings/v8:windows_library_flags_test \
  //tools/build-warnings/xz:gnu_flags_test \
  //tools/build-warnings/xz:msvc_flags_test
```

All eleven checks pass without timing-range advisories. Required queue checks
remain `just fmt-check` and `just bazel-lock-check`.

## Explicit Python zip applications

`zzzz-0010-explicit-python-zipapps.patch` disables rules_python's implicit zip
outputs. Explicit `py_zipapp_binary` generators keep their declared outputs.
Windows hosts otherwise enable the legacy default for Linux remote-execution
tools, including Bazel's SHA-256 tool. The native Bazel 9 `build_python_zip`
option is a deprecated no-op, so the patch uses its supported Starlark
replacement. This changes the output selection rather than filtering a warning.

The declared base is `rust-v0.163.0`
(`83086188ae0614bd882818ff1aa23f3194962012`) with the preceding issue-61 queue,
including the explicit initializer test. The tracking issue is
https://github.com/holdmyspot-com/codex-unleashed/issues/61; the migration guide is
https://github.com/bazel-contrib/rules_python/issues/3567.

```sh
bazel test --test_output=errors \
  //tools/build-warnings/python:hash_initializers_test \
  //tools/build-warnings/python:hash_zipapp_test \
  //tools/build-warnings/python:hash_runtime_test
just fmt-check
just bazel-lock-check
```

The analysis test requires the Linux-execution hash tool's default outputs to
exclude legacy zip files. The runtime check executes the actual hash tool with
its declared runtime files and compares the SHA-256 digest of `abc` with the
known value. Run these checks with the maintained Linux and Windows CI
configurations; local Linux execution does not verify the native Windows
launcher. Adding
`--@rules_python//python/config_settings:build_python_zip=true` reproduces the
Windows-host default locally: the analysis assertion fails and rules_python
reports the legacy zipapp diagnostic. The project configuration requires all
three focused tests to pass without that diagnostic.

## Windows remote execution platform ownership

`zzzz-0011-windows-execution-platform-owner.patch` gives Windows cross CI one
execution-platform selection. The BuildBuddy wrappers select the Windows
variant of the generic or OpenAI remote configuration when
`--config=ci-windows-cross` appears before the command's `--` separator.
That CI configuration supplies both Linux remote workers and the native Windows
MSVC platform. Other platforms retain the existing remote configuration.
Cache endpoints, authentication, remote strategy, and job limits remain enabled.
The duplicate-option diagnostic is corrected through configuration ownership;
no diagnostic is filtered.

The declared base is `rust-v0.163.0`
(`83086188ae0614bd882818ff1aa23f3194962012`) with the preceding issue-61 queue.
The tracking issue is https://github.com/holdmyspot-com/codex-unleashed/issues/61.
From the patched checkout, run:

```sh
just test-github-scripts
bazel build --nobuild --config=buildbuddy-generic-rbe-windows-cross \
  --config=ci-windows-cross @llvm//runtimes/msvc:msvc_include
just fmt-check
just bazel-lock-check
```

Set the accepted MSVC runtime license and the normal CI BuildBuddy credentials.
The script suite verifies generic and OpenAI host selection, separator handling,
and the local fallback without credentials. Analysis must complete without
duplicate `extra_execution_platforms` expansion. Local analysis with remote
endpoints disabled verifies option expansion; the native Windows CI build and
its cache statistics remain separate required acceptance gates.
The fork's Maven wrapper implements the same selection; `tooling/mvnw verify`
verifies it through the project tooling's full gate.

## Libffi configure diagnostics

`zzzz-0012-libffi-configure-diagnostics.patch` preserves Libffi's configure
context in a failed Bazel native-prefix action. If a Libffi configure, build, or
install step fails, the action prints the failing step's output and the existing
`build/libffi/config.log` before removing its temporary directory. The original
exception and failure status remain intact, including failures caused by warnings.
An absent configure log does not replace the original failure.

The declared base is `rust-v0.163.0`
(`83086188ae0614bd882818ff1aa23f3194962012`) with the preceding native voice
warning-policy patches. The tracking issue is
https://github.com/holdmyspot-com/codex-unleashed/issues/61. From the patched
checkout, run:

```sh
python3 -m unittest discover -s third_party/voice -p test_build_native.py
just fmt-check
just bazel-lock-check
```

The regression exercises real failing child commands and a successful command
that emits a fatal warning. It requires configure context for Libffi failures,
preserves exit status 23, and covers unrelated steps and missing configure logs.
This patch provides diagnosis; the native macOS build remains required to
establish that the underlying Libffi warning is fixed.

## Generated Rust test timeouts

`zzzz-0013-rust-test-timeouts.patch` gives generated Rust unit, binary-unit,
and integration tests a short timeout by default. The measured short suites
avoid Bazel's oversized-timeout advisories. Explicit crate timeout settings
remain authoritative, including the long core/login unit suites and
core/app-server integration suites. Native, sharded, Windows-cross, and Wine
wrappers inherit their corresponding crate setting. Supplying `None` retains
Bazel's size-based default. Verbose timeout diagnostics remain enabled.

The declared base is `rust-v0.163.0`
(`83086188ae0614bd882818ff1aa23f3194962012`) with the preceding issue-61 queue.
The tracking issue is https://github.com/holdmyspot-com/codex-unleashed/issues/61.
From a Linux checkout with the MSVC runtime license environment set, run:

```sh
bazel test --platforms=//:rbe --extra_execution_platforms=//:rbe \
  --test_verbose_timeout_warnings --test_output=errors \
  //tools/build-warnings/test-timeouts:timeouts_tests \
  //codex-rs/ansi-escape:ansi-escape-unit-tests
just fmt-check
just bazel-lock-check
```

Six analysis regressions inspect the real generated targets: unit, binary-unit,
and integration defaults, plus explicit long unit, integration, and Wine
overrides. They live in the owning packages so production target visibility
remains unchanged. Platform-restricted fixtures retain their targets' existing
compatibility constraints. The executable ANSI suite verifies an actual short
test action. The complete native platform suites remain required acceptance
gates; one Linux suite does not establish timing headroom on Windows or macOS.

## Windows V8 registry linkage

`zzzz-0014-v8-windows-registry-link.patch` supplies the Windows registry library
required by V8's native archive to Rust consumers. Windows GNU links otherwise
fail on registry imports such as `RegOpenKeyExW` and `RegQueryValueExW`. The
archive's C++ rules already declare `advapi32`; the crate's build script must
also emit that dependency for Rust links. Linux and macOS metadata stay free
of Windows system libraries.

The declared base is `rust-v0.163.0`
(`83086188ae0614bd882818ff1aa23f3194962012`) with the preceding issue-61 queue.
The tracking issue is https://github.com/holdmyspot-com/codex-unleashed/issues/61.
Rusty V8 150.4.0 and 152.2.0 both omit this dependency. Remove the workaround
when the selected crate release emits its required Windows registry library.

```sh
bazel test --platforms=//:rbe --extra_execution_platforms=//:rbe \
  --test_output=errors --test_verbose_timeout_warnings \
  //tools/build-warnings/v8-link-metadata:link_metadata_test
just fmt-check
just bazel-lock-check
```

The regression executes the actual compiled build script in its supported
`DENO_TRYBUILD` mode for four Windows triples and Linux/macOS controls. It
requires each Windows system library exactly once, without downloading or
compiling a V8 archive. The complete Windows V8 link remains a required hosted
acceptance gate.

## Windows Ninja bootstrap parallelism

`zzzz-0015-nmake-bootstrap-parallelism.patch` keeps the native Windows Ninja
bootstrap free of CMake's unsupported-parallelism warning. NMake runs serially;
the subsequent Ninja builds and Unix Makefiles bootstrap retain the configured
parallel job count. Warning diagnostics remain fatal.

The declared base is `rust-v0.163.0`
(`83086188ae0614bd882818ff1aa23f3194962012`) with the preceding issue-61 queue.
The tracking issue is https://github.com/holdmyspot-com/codex-unleashed/issues/61.
With CMake and a native C/C++ toolchain available, run:

```sh
python3 -m unittest discover -v -s third_party/voice -p test_build_native.py
just fmt-check
just bazel-lock-check
```

The generator-support regression checks Windows NMake, Windows Ninja, Unix
Makefiles, and Unix Ninja commands. The complete native Windows voice build
remains a required hosted acceptance gate.

## V8 Windows assembly selection

`zzzz-0016-v8-windows-assembly-abi.patch` gives the Windows GNU stack-scanning
assembly its own `.S` source and restores the original MASM implementation.
The configured Windows ABI selects the source for both V8's production
library and its existing callback regression. Both sources preserve Windows
unwind metadata. V8 declares the existing LLVM constraint dependency at the
same selected version; no compiler warning check is disabled.

The declared base is `rust-v0.163.0`
(`83086188ae0614bd882818ff1aa23f3194962012`) with the preceding issue-61
V8 and LLVM build patches. The tracking issue is
https://github.com/holdmyspot-com/codex-unleashed/issues/61.
With the MSVC runtime license environment set, run:

```sh
bazel test --test_output=errors --test_verbose_timeout_warnings //tools/build-warnings/v8-assembly:all
bazel build --platforms=//:windows_x86_64_gnullvm @v8//:codex_windows_stack_assembly_library_icu
just fmt-check
just bazel-lock-check
```

The analysis regressions require the corresponding source in each ABI's
actual compiler action. The GNU build compiles the selected assembly and
ICU-definition fixture. Native Windows CI must also compile and run
`//tools/build-warnings/v8:windows_stack_assembly_test` on GNU and MSVC
platforms. Linux analysis and cross-compilation do not establish those native
assembler and runtime outcomes.

## Libtool compile-only probes

`zzzz-0017-libffi-compile-only-driver.patch` keeps Darwin's system-linker
selection out of Libtool's compile-only invocations. Libtool uses `LTCC`
for both capability probes and partial links, whose command omits
`LDFLAGS`. A private compiler driver selects the system linker only for
linking. Project warnings remain fatal; dependency warnings remain visible and nonfatal.

The declared base is `rust-v0.163.0`
(`83086188ae0614bd882818ff1aa23f3194962012`) with the preceding issue-61
queue through `zzzz-0016`. The tracking issue is
https://github.com/holdmyspot-com/codex-unleashed/issues/61.
Run with Clang 23 available on `PATH`:

```sh
python3 -m unittest discover -v -s third_party/voice -p test_build_native.py
just fmt-check
just bazel-lock-check
```

The linker-selection regression compiles through both `CC` and `LTCC`,
executes the linked program, and checks normal and partial-link driver
traces for the declared system linker. Native macOS CI must additionally
confirm the real `-force_load` probe succeeds and build the complete voice
prefix without warning diagnostics.

## Native test runfiles

`zzzz-0018-native-test-runfiles.patch` resolves configuration-schema fixtures
and the Windows sandbox setup executable through the maintained Cargo/Bazel
resource and binary helpers. It supports manifest-only runfiles without
assuming logical runfile names are filesystem paths. Schema byte and value
comparisons and Windows manifest resource assertions retain their meaning.

The later canonical schema patch registers the schema test's helper dependency
and uses the same resolvers in its generated CLI regression. Patch 0018 does
not modify files or manifest sections introduced by that later patch. Verify
application order with `scripts/apply-patches.sh` on a clean release checkout;
applying an individual patch to an already-combined tree does not establish
that the maintained queue applies.

The declared base is `rust-v0.163.0`
(`83086188ae0614bd882818ff1aa23f3194962012`) with the preceding issue-61
queue through `zzzz-0017`. The tracking issue is
https://github.com/holdmyspot-com/codex-unleashed/issues/61.
Apply the complete queue before running the normal release package-version
preparation, then verify:

```sh
(cd codex-rs && cargo metadata --format-version 1 > /dev/null)
just fmt-check
just bazel-lock-check
bazel test --test_output=errors --test_verbose_timeout_warnings //codex-rs/config-schema:config-schema-published_schema-test //codex-rs/config-schema:config-schema-codex_unleashed-test
```

On native Windows, also run the schema tests and
`//codex-rs/windows-sandbox-rs:windows-sandbox-rs-helper_manifest-test`
using CI's `ci-windows-msvc` configuration and MSVC platform. The sandbox
regression must locate the real executable and read its embedded manifest
through the Windows resource API. Linux schema results and MSVC analysis
alone do not establish the native resource outcome.

## Windows executable discovery environment

`zzzz-0019-windows-test-executable-extensions.patch` forwards the native
client's `PATHEXT` into Bazel tests alongside the other Windows environment
inputs. Extensionless executable lookup retains the client's extension
policy, including discovery of the doctor's `.cmd` fixture. The safety
regression continues to require that startup and doctor never execute the
fixture helpers; lookup failures include the extension policy in diagnostics.

The declared base is `rust-v0.163.0`
(`83086188ae0614bd882818ff1aa23f3194962012`) with the preceding issue-61
queue through `zzzz-0018`. The tracking issue is
https://github.com/holdmyspot-com/codex-unleashed/issues/61.
On native Windows, run:

```sh
just fmt-check
bazel test --config=ci-windows-msvc --platforms=//:windows_x86_64_msvc --test_output=errors --test_verbose_timeout_warnings //codex-rs/cli:cli-doctor_path_safety-test
```

All doctor safety cases must pass, including extensionless discovery of the
fixture Git helper and the no-execution assertions. Linux execution cannot
establish Windows extension lookup or environment inheritance.

## MSVC C++ language-version reporting

`zzzz-0020-v8-msvc-language-version.patch` makes CL drivers report the selected
C++20 language version to V8 headers with `/Zc:__cplusplus`. It covers both
the pointer-format regression and the V8 library compile actions. It enables
the correct language-version macro without disabling V8's C++20 requirement
or suppressing a compiler diagnostic.

The declared base is `rust-v0.163.0`
(`83086188ae0614bd882818ff1aa23f3194962012`) with the preceding issue-61
queue through `zzzz-0019`. The tracking issue is
https://github.com/holdmyspot-com/codex-unleashed/issues/61.

```sh
just fmt-check
just bazel-lock-check
bazel test --test_output=errors --test_verbose_timeout_warnings //tools/build-warnings/v8:windows_pointer_flags_test //tools/build-warnings/v8:windows_library_flags_test
bazel test --config=ci-windows-msvc --platforms=//:windows_x86_64_msvc --test_output=errors --test_verbose_timeout_warnings //tools/build-warnings/v8:pointer_formats_test //tools/build-warnings/v8:windows_stack_assembly_test
```

The action tests require the language-version reporting flag. Native Windows
execution must also compile the real V8 headers and pass both executable
regressions. The fork's Windows GNU shard 1 additionally builds the small stack
callback probe through RBE and executes its downloaded binary directly on the
Windows runner. Analysis and cross-compilation alone do not verify that callback.

## Native Windows launcher line endings

`zzzz-0021-windows-launcher-line-endings.patch` emits workspace-root batch
launchers with CRLF after expanding every placeholder. Native CMD reliably
finds helper labels even when the launcher exports multiple helper binaries.
The generation boundary normalizes existing CRLF before adding native line
endings, so multiline substitutions cannot introduce doubled carriage returns.
Bash launcher expansion retains its existing behavior.

The declared base is `rust-v0.163.0`
(`83086188ae0614bd882818ff1aa23f3194962012`) with the preceding issue-61
queue through `zzzz-0020`. The tracking issue is
https://github.com/holdmyspot-com/codex-unleashed/issues/61.

```sh
just fmt-check
just bazel-lock-check
bazel test --test_output=errors --test_verbose_timeout_warnings //tools/build-warnings/windows-launcher:all
bazel test --config=ci-windows-msvc --platforms=//:windows_x86_64_msvc --test_output=errors --test_verbose_timeout_warnings //codex-rs/app-server:app-server-unit-tests
```

The Linux generation regressions require CRLF throughout launchers containing
LF and CRLF helper substitutions, with no doubled carriage returns. Native
Windows execution must also resolve the app-server's helper binaries and run
the actual tests; generation checks alone do not establish that outcome.

## Shared Java Bazel entry point

`zzzz-0022-bazel-java-tooling-entry-point.patch` routes the upstream helper's
command-line entry point through the configured `CODEX_UNLEASHED_TOOLING`
launcher. This includes the maintained module-lock check. These callers use
the same cache policy and exact Bazel JVM-warning workaround as the fork's
other CI commands. The existing upstream fallback remains available when
the Java tooling launcher is not configured.

The declared base is `rust-v0.163.0`
(`83086188ae0614bd882818ff1aa23f3194962012`) with the preceding issue-61
queue through `zzzz-0021`. The tracking issue is
https://github.com/holdmyspot-com/codex-unleashed/issues/61.
The external JVM-launcher defect is
https://github.com/bazelbuild/bazel/issues/31786; the workaround remains
owned by the Java launcher and filters only its exact obsolete-option message.

With `CODEX_UNLEASHED_TOOLING` set to the bundled launcher, run:

```sh
just fmt-check
just bazel-lock-check
(cd .github/scripts && python3 -m unittest test_run_bazel_with_buildbuddy)
```

The module-lock check must pass without the obsolete JVM-option message.
All helper tests must pass, including exact argument forwarding, a tooling
path containing spaces, diagnostic forwarding, the configured launcher's
exit status, and the existing fallback behavior.

## Meson compiler-failure context

`zzzz-0023-meson-compiler-failure-context.patch` includes the failing native
voice component's `meson-log.txt` in Bazel's action diagnostics before
temporary build cleanup. Compiler commands and their errors remain available
for configure, build, and install failures. The original step exception and
the existing libffi configure diagnostics remain available.

The declared base is `rust-v0.163.0`
(`83086188ae0614bd882818ff1aa23f3194962012`) with the preceding issue-61
queue through `zzzz-0022`. The tracking issue is
https://github.com/holdmyspot-com/codex-unleashed/issues/61.

With Clang 23 and CMake 4.4.4 available on `PATH`, run:

```sh
python3 -m unittest discover -v -s third_party/voice -p test_build_native.py
just fmt-check
just bazel-lock-check
```

All native-builder tests must pass. Configure-context cases require the
Meson diagnostic, the original exception, and no completion artifacts,
including warnings raised after a zero child exit status. Missing logs and
unrelated component logs must retain their existing behavior. On native
macOS, build `//third_party/voice:native_prefix_macos_aarch64`; a failed
compiler probe must include its detailed Meson log. This diagnostic patch
does not establish that the underlying native compiler failure is fixed.

## Transport reconnect test timeout

`zzzz-0024-transport-retry-test-timeout.patch` gives the transport unit suite
an explicit moderate timeout. Its conflict-recovery regressions exercise
consecutive real reconnect waits of up to 30 seconds. The short default is
insufficient for that workload. Assertions, reconnect behavior, and verbose
timeout diagnostics remain enabled.

The declared base is `rust-v0.163.0`
(`83086188ae0614bd882818ff1aa23f3194962012`) with the preceding issue-61
queue, including `zzzz-0013-rust-test-timeouts.patch`. The tracking issue is
https://github.com/holdmyspot-com/codex-unleashed/issues/61.

```sh
just fmt-check
just bazel-lock-check
bazel test --test_output=errors --test_verbose_timeout_warnings //tools/build-warnings/test-timeouts:all
bazel test --test_output=errors --test_verbose_timeout_warnings //codex-rs/app-server-transport:app-server-transport-unit-tests
```

The analysis regression requires the real generated transport test's explicit
timeout. The actual transport suite must complete with every assertion passing
on each supported native platform; an analysis pass alone does not establish
that execution gate.

## Native voice Meson sanity flags

`zzzz-0025-native-voice-meson-sanity-flags.patch` selects the checksum-pinned
[Meson 1.12.1 release](https://github.com/mesonbuild/meson/releases/tag/1.12.1)
for native voice builds. This release fixes duplicated environment compiler
and linker arguments in Meson's compiler sanity checks. Darwin links retain
fatal warnings and receive the native recipe's runtime path exactly once.

The declared base is `rust-v0.163.0`
(`83086188ae0614bd882818ff1aa23f3194962012`) with the preceding issue-61
native voice patches, including `native-voice-meson-resource-directory.patch`
and `native-voice-z-symbol-tools.patch`. The tracking issue is
https://github.com/holdmyspot-com/codex-unleashed/issues/61.

With the generated native-prefix configuration, declared tool inputs, and a
project-owned `TMPDIR`, run:

```sh
python3 tools/build-warnings/check-native-meson.py <native-prefix-json> <execroot> <meson-source> <ninja>
just fmt-check
just bazel-lock-check
```

The probe requires exactly one runtime-path argument in each actual compiler
sanity command, then executes its linked C/C++ fixture. Meson 1.12.0 fails the
argument check; 1.12.1 passes. The probe resolves the declared symbol tools
alongside its other executable inputs and removes its scratch outputs on exit.
Native macOS acceptance additionally requires the complete
`//third_party/voice:native_prefix_macos_aarch64` build; Linux probe execution
does not establish Darwin linker behavior.

## Windows Bash executable resolution

`zzzz-0026-windows-bash-path-resolution.patch` resolves Bash through `PATH`
before the upstream Python bridge invokes the configured Java tooling launcher.
Native Windows process creation searches the system directory before `PATH`
for a bare executable name, which can select the WSL launcher rather than Git
Bash. The bridge uses the resolved executable and retains the tooling command's
arguments, output, and exit status. Missing Bash produces an explicit error.

The declared base is `rust-v0.163.0`
(`83086188ae0614bd882818ff1aa23f3194962012`) with
`zzzz-0022-bazel-python-jvm-warning-bridge.patch` and its prerequisites. The
tracking issue is https://github.com/holdmyspot-com/codex-unleashed/issues/61.

```sh
python3 .github/scripts/test_run_bazel_with_buildbuddy.py
just fmt-check
just bazel-lock-check
```

All 16 wrapper regressions must pass. The configured-tooling regression launches
real child processes from a path containing spaces and requires exact arguments,
stdout, stderr, and exit status 37. Its failure diagnostic includes both output
streams. Run it on native Windows with Git Bash on `PATH` and no installed WSL
distribution, as well as Linux. Linux execution alone does not establish Windows
executable resolution. The Windows Bazel shard and Clippy jobs remain separate
acceptance gates for the complete launcher chain.

## Serialized exec-server unit-test timeout

`zzzz-0027-serialized-exec-server-timeout.patch` gives exec-server's serialized
unit suite an explicit moderate timeout. Its hundreds of tests include real
network retry waits and require more than the short budget on native macOS.
Integration-test budgets and verbose timeout diagnostics retain their settings.

The declared base is `rust-v0.163.0`
(`83086188ae0614bd882818ff1aa23f3194962012`) with the preceding issue-61
queue, including `zzzz-0013-rust-test-timeouts.patch`. The tracking issue is
https://github.com/holdmyspot-com/codex-unleashed/issues/61.

```sh
bazel test //tools/build-warnings/test-timeouts:timeouts_tests
bazel test --test_output=errors --test_verbose_timeout_warnings //codex-rs/exec-server:exec-server-unit-tests
just fmt-check
just bazel-lock-check
```

The analysis regression requires the real generated unit target's moderate
timeout. The actual suite must complete with every assertion passing on each
supported native platform; analysis alone does not establish that execution gate.

## Exec-server compatibility test deadlines

`zzzz-0028-exec-server-compatibility-timeouts.patch` gives the current-version,
minimum-release, and stable-release compatibility checks short deadlines.
These Linux-only checks complete in seconds. Verbose timeout diagnostics remain
enabled, and the compatibility assertions retain their behavior.

The declared base is `rust-v0.163.0`
(`83086188ae0614bd882818ff1aa23f3194962012`) with the preceding issue-61
queue through `zzzz-0027`. The tracking issue is
https://github.com/holdmyspot-com/codex-unleashed/issues/61.

```sh
bazel test --test_output=errors --test_verbose_timeout_warnings //tools/build-warnings/test-timeouts:timeouts_tests //codex-rs/exec-server:exec-server-current-version-test //codex-rs/exec-server:exec-server-minimum-release-test //codex-rs/exec-server:exec-server-stable-release-test
just fmt-check
just bazel-lock-check
```

All timeout analysis regressions and the three actual compatibility
checks must pass without timing-range advisories. The focused runtime command
requires a Linux x86-64 execution host and the pinned comparison releases.


## Sharded exec integration deadline

`zzzz-0029-exec-integration-deadline.patch` gives exec integration tests an
explicit moderate timeout. The eight-shard suite can exceed the short budget
on native macOS. Unit-test defaults, sharding, assertions, and verbose timeout
diagnostics retain their behavior.

The declared base is `rust-v0.163.0`
(`83086188ae0614bd882818ff1aa23f3194962012`) with the preceding issue-61
queue through `zzzz-0028`. The tracking issue is
https://github.com/holdmyspot-com/codex-unleashed/issues/61.

```sh
bazel test //tools/build-warnings/test-timeouts:timeouts_tests
bazel test --test_output=errors --test_verbose_timeout_warnings //codex-rs/exec:exec-all-test
just fmt-check
just bazel-lock-check
```

The analysis regression requires the actual generated integration target's
moderate timeout. All eight runtime shards must complete with every assertion
passing on each supported native platform. An analysis pass alone does not
establish that execution gate.


## V8 compiler feature guards

`zzzz-0030-v8-warning-feature-guards.patch` nests Clang warning-feature
checks so GCC and MSVC never parse unavailable `__has_warning` expressions.
The existing pointer-format regression also exercises both diagnostic scopes.
Compiler warnings remain fatal.

The base is `rust-v0.163.0`
(`83086188ae0614bd882818ff1aa23f3194962012`) with the preceding issue-61
V8 patches. Run `bazel test //tools/build-warnings/v8:pointer_formats_test`,
`just fmt-check`, and `just bazel-lock-check`. Native Windows verification
compiles and executes the same fixture with `/std:c++20 /Zc:__cplusplus /W4 /WX`.
GCC and MSVC must compile and run it without preprocessor diagnostics.

## Daemon integration deadline

`zzzz-0031-daemon-integration-deadline.patch` gives CLI integration tests a
moderate timeout. The daemon suite includes real startup and shutdown waits
that exceed the short default on native macOS. Assertions and verbose timing
diagnostics remain enabled.

The base and prerequisites match patch 0030. Run
`bazel test //tools/build-warnings/test-timeouts:timeouts_tests`,
`bazel test --test_output=errors --test_verbose_timeout_warnings //codex-rs/cli:cli-app_server_daemon-test`,
`just fmt-check`, and `just bazel-lock-check`. The analysis regression requires
the real generated daemon target's moderate deadline; native execution must
also complete successfully on each supported platform.

## Native CMake installation paths

`zzzz-0032-cmake-install-prefix-path.patch` supplies the installation prefix
with forward slashes. CMake's generated Windows installation script therefore
preserves the path rather than interpreting backslashes as escape sequences.
Other initial-cache values retain their literal handling.

The base and prerequisites match patch 0030, including the native CMake
initial-cache patch. On native Windows with CMake 4.4.4 and Ninja 1.13.2 on
`PATH`, run `python -m unittest discover -s third_party/voice -p test_build_native.py`.
The new regression must install its marker beneath a prefix containing spaces.
The baseline fails with `Invalid character escape`. Required queue checks are
`just fmt-check` and `just bazel-lock-check`.

## Native Meson Darwin linker selection

`zzzz-0035-native-meson-darwin-linker.patch` selects the native Darwin linker
for native voice Meson links and capability probes. The selector belongs only
in `LDFLAGS`; compile-only arguments retain their existing values. Other
platforms retain the declared LLVM linker. Fatal diagnostics remain enabled.

The declared base is `rust-v0.163.0`
(`83086188ae0614bd882818ff1aa23f3194962012`) with the preceding issue-61
native voice queue. With the declared CMake and C/C++ tools on `PATH`, run
`python3 -m unittest discover -s third_party/voice -p test_build_native.py`,
`just fmt-check`, and `just bazel-lock-check`. The regression requires the
native selector last on Darwin, preserves the Linux selector and fatal-linker
flag, and excludes linker selection from compile-only arguments. Native macOS
acceptance additionally requires the native Meson probe and complete voice
prefix. The tracking issue is
https://github.com/holdmyspot-com/codex-unleashed/issues/61.

## Rust 1.99 first-party Clippy compatibility

`zzzz-0037-first-party-clippy.patch` keeps handwritten Codex code compatible
with fatal Clippy diagnostics. Selection notes pass their wrapping closure by
value, and compaction tests iterate over array chunks. The exec-policy error
conversion uses an explicit implementation with field shorthand while
preserving its error variant and source. Dependency sources remain unchanged.

The generated RPC module alone permits `clippy::double_must_use` because
`async_trait` produces the redundant attribute. The exception has a WORKAROUND
comment linking [Clippy #17529](https://github.com/rust-lang/rust-clippy/issues/17529)
and a removal condition tied to the installed Clippy. The explicit error
conversion similarly identifies
[Clippy #17525](https://github.com/rust-lang/rust-clippy/issues/17525).

Apply this patch after the preceding issue-61 build patches on the declared
release base. Run `just fmt-check` and `just bazel-clippy` from the checkout
root; both commands must succeed. The Clippy check covers the generated RPC
module, the error conversion, the selection view, and the compaction tests.

## Native Windows workspace status

`zzzz-0038-windows-workspace-status.patch` expands host-specific build settings
after the unconditional build defaults. Native Windows selects the batch
workspace-status command, so native runtime receipts receive the source commit.
Automatic host configuration for other Bazel commands remains enabled.
Dependency source and receipt validation remain unchanged.

The declared base is `rust-v0.163.0`
(`83086188ae0614bd882818ff1aa23f3194962012`) with the preceding issue-61
build-configuration patches. The tracking issue is
https://github.com/holdmyspot-com/codex-unleashed/issues/61. Run:

```sh
bazel test --test_output=errors //tools/build-warnings/workspace-status:source_revision_test
just fmt-check
just bazel-lock-check
```

The regression requires exactly one 40-digit lowercase hexadecimal source
commit in Bazel's real workspace-status input. The declared input is embedded
at compilation, so the test supports manifest-only Windows runfiles. Native
Windows CI also requires the complete voice runtime receipt preparation to
succeed; a Linux configuration-ordering probe does not establish that gate.

### TUI unit test deadline

`zzzz-0039-tui-unit-test-timeout.patch` uses four TUI unit-test shards with a
moderate deadline. All test assertions remain enabled. The analysis regression
`//codex-rs/tui:unit_deadline_test` checks the declared deadline and participates
in `//tools/build-warnings/test-timeouts:tests`. Run the regression together with
`//codex-rs/tui:tui-unit-tests`; both targets are expected to pass.

## Windows launcher profile environment

`zzzz-0040-windows-test-profile.patch` passes the host `LOCALAPPDATA` into
Windows Bazel tests. MXC uses that directory for its host-owned launcher profile;
its runtime tests require a nonempty Unicode value. The dependency and launcher
implementations retain their existing behavior.

The declared base is `rust-v0.163.0`
(`83086188ae0614bd882818ff1aa23f3194962012`) with the preceding issue-61 queue.
The tracking issue is https://github.com/holdmyspot-com/codex-unleashed/issues/61.
On a native Windows runner with its normal profile environment, run:

```sh
bazel test --config=ci-windows --config=ci-windows-msvc \
  --host_platform=//:local_windows_msvc \
  //tools/build-warnings/windows-environment:launcher_profile_test \
  //codex-rs/mxc-sandbox:mxc-sandbox-unit-tests
```

Both targets pass. The manual environment probe checks delivery of the host
profile variable; the MXC unit target checks the Windows launcher behavior.
A Linux check with an explicit Windows configuration can establish environment
delivery but does not establish native Windows execution.

## Concurrent V8 initialization

`zzzz-0041-v8-concurrent-initialization.patch` completes initialization of V8's
shared ArrayBuffer allocator inside Codex's existing `OnceLock`, before other
threads create isolates. Dependency source remains unchanged. Its regression
synchronizes 32 first isolate constructors in a fresh process. Apply the preceding
issue-61 queue, including patch 0040, on the declared release base.

On native Windows with the accepted MSVC runtime license, run:

```sh
bazel test --config=ci-windows-msvc --platforms=//:windows_x86_64_msvc --nocache_test_results --test_output=all //codex-rs/code-mode-runtime:code-mode-runtime-concurrent_initialization-test //codex-rs/code-mode-runtime:code-mode-runtime-unit-tests //codex-rs/code-mode-runtime:code-mode-runtime-settled-test //codex-rs/code-mode-host:code-mode-host-grpc-test //codex-rs/code-mode-host:code-mode-host-unit-tests
just fmt-check
just bazel-lock-check
```

All five targets must pass without the native allocator initialization assertion.
Linux verification supplements this required Windows result. The diagnostic
branch runs focused native tests and does not establish the complete CI matrix.

## Windows test launcher startup

`zzzz-0042-windows-test-launcher-startup.patch` selects and batches tests in one
PowerShell process. Filtering, ordinal ordering, deterministic shard assignment,
long command-line batching, and child failure status retain their behavior.
The native fixture removes its temporary files and returns success after checking
an intentionally failing child. The native Bazel workflow runs that fixture.

The declared base is `rust-v0.163.0`
(`83086188ae0614bd882818ff1aa23f3194962012`) with the preceding issue-61 queue
through patch 0041. On Windows, run:

```powershell
./tools/build-warnings/windows-launcher/selected_tests.ps1 -TempRoot $env:TEMP
```

The fixture must succeed and remove its scratch directory. Run the five native
V8 targets listed under Concurrent V8 initialization with uncached test results;
all must pass within their declared deadlines. Required queue checks remain
`just fmt-check` and `just bazel-lock-check`. Linux formatting and source replay
alone do not establish Windows execution.

## macOS remote concurrency budget

`zzzz-0043-macos-remote-thread-budget.patch` caps authenticated macOS Bazel CI
commands at 64 concurrent actions. Bazel 9.3 sizes its native remote callback
pool from `--jobs`; the 800-action remote default can exhaust native threads
during disk-cache reads and writes on hosted macOS. Remote configuration,
cache authentication, cache keys, and child exit status retain their behavior.
The fork also retains its independent local compilation and test limits.

The declared base is `rust-v0.163.0`
(`83086188ae0614bd882818ff1aa23f3194962012`) with the preceding issue-61 queue
through patch 0042. The tracking issue is
https://github.com/holdmyspot-com/codex-unleashed/issues/61. The pool sizing is
defined in [Bazel's RemoteModule](https://github.com/bazelbuild/bazel/blob/9.3.0/src/main/java/com/google/devtools/build/lib/remote/RemoteModule.java).

With an owned `TMPDIR`, run:

```sh
bash .github/scripts/test-bazel-macos-concurrency.sh
just fmt-check
just bazel-lock-check
```

The process-boundary regression requires the cap on authenticated macOS calls,
preserves exit status 37, and checks Linux and credential-free controls. The
fork's `tooling/mvnw verify` checks the same invocation through its bundled Java
runtime. Native macOS tests, Clippy, and release verification must also finish
without thread exhaustion; wrapper tests alone do not establish that result.

## Per-suite test deadlines

`zzzz-0044-per-suite-test-deadlines.patch` scopes extended integration deadlines
to the daemon, core-all, app-server-all, and exec-all suites. Short sibling tests
use the short default. Binary unit tests have their own short default, independent
of the library unit-suite deadline. Wine and Windows-cross variants inherit the
selected native integration deadline. Existing slow-suite deadlines, sharding,
assertions, and verbose timeout diagnostics remain enabled.

The declared base is `rust-v0.163.0`
(`83086188ae0614bd882818ff1aa23f3194962012`) with the preceding issue-61 queue
through patch 0043. The tracking issue is
https://github.com/holdmyspot-com/codex-unleashed/issues/61. From the prepared
checkout, run:

```sh
bash .github/scripts/run-bazel-ci.sh --print-failed-test-logs -- test --test_verbose_timeout_warnings -- //tools/build-warnings/test-timeouts:timeouts_tests
just fmt-check
just bazel-lock-check
```

The analysis tests require short sibling and binary deadlines, short Wine
inheritance, and the retained extended suite deadlines. Native CI must also
execute the affected tests within those deadlines on each supported platform.
Fast shards of a suite can still receive timing advisories because every shard
shares the deadline required by its slowest supported execution.

## Build-info executable runfiles

`zzzz-0045-build-info-test-runfiles.patch` resolves the Codex executable through
the maintained Cargo/Bazel binary helper in both build-info regressions. A
logical runfile name is not a native filesystem path on manifest-only Windows.
The version, provider, and upstream identity assertions retain their behavior.

## Settlement test completion

`zzzz-0046-settlement-test-yields.patch` follows yielded cells until completion
in the settlement test helper and collects output from each response. Completion
has a 30-second bound. Its regression forces an initial yield; all existing
settlement-order assertions remain enabled.

## Guardian prewarm test listeners

`zzzz-0047-guardian-prewarm-mock-listeners.patch` gives each prewarmed connection
its own scripted mock listener. The mock serves one connection before accepting
the next; sharing it can deadlock prewarm before a request is sent. Assertions
inspect the combined request logs, including cancellation and routing failures.
Connection-log iterators use method references to satisfy the configured Clippy
lint without relaxing the routing assertions.

## Windows native test DLL directories

`zzzz-0048-windows-test-dll-paths.patch` adds declared DLL directories to the
Windows test process's `PATH` before starting its executable. Each directory is
resolved through an actual DLL runfile, preserving the inherited search path.
Voice integration tests use the workspace-root launcher to receive these paths
and absolute helper executable paths. This does not add shards or retries.

The declared base for patches 0045–0048 is `rust-v0.163.0`
(`83086188ae0614bd882818ff1aa23f3194962012`) with the preceding queue through
patch 0044, including the branding patch's build-info tests. Apply the complete
queue in its maintained order. The tracking issue is
https://github.com/holdmyspot-com/codex-unleashed/issues/61. Run:

```sh
just fmt-check
just bazel-lock-check
bash .github/scripts/run-bazel-ci.sh --print-failed-test-logs -- test --test_output=errors --test_verbose_timeout_warnings -- //tools/build-warnings/windows-launcher:all //codex-rs/cli:cli-build_info-test //codex-rs/code-mode-runtime:code-mode-runtime-settled-test //codex-rs/ext/guardian-v2:guardian-v2-unit-tests //codex-rs/voice-host:all
```

The DLL policy and line-ending regressions pass, both build-info checks pass,
and all settlement and guardian tests complete with their assertions enabled.
Run `just bazel-clippy` to verify the listener regressions against the maintained
lint configuration across the workspace.
Native Windows must also start the voice unit and integration executables
without missing DLLs. macOS must complete the settlement and guardian suites.
Local Linux checks do not establish those native outcomes; the full CI matrix
and Cargo release builds remain required acceptance gates.

## Native runtime search paths

`zzzz-0049-native-rpath-feature.patch` selects LLVM's runtime search path
feature for target and execution builds and disables Bazel's original feature.
The two features otherwise emit the same rpath, causing Darwin's linker to warn
about duplicate paths. The warning remains enabled; each requested path occurs
once. Linux retains its relative runtime search path.

The declared base is `rust-v0.163.0`
(`83086188ae0614bd882818ff1aa23f3194962012`) with the preceding maintained
queue through patch 0048. The tracking issue is
https://github.com/holdmyspot-com/codex-unleashed/issues/61. The configuration
workaround is removable when LLVM disables Bazel's original feature itself.
Run these checks from the patched checkout:

```sh
just fmt-check
just bazel-lock-check
bash .github/scripts/run-bazel-ci.sh --print-failed-test-logs -- test --test_output=errors --test_verbose_timeout_warnings -- //tools/build-warnings/rpaths:all
```

Both generated-link-argument regressions require exactly one runtime search
path, preserving Darwin's `@loader_path` and Linux's `$ORIGIN` spelling.
Native macOS CI must also link and execute the voice targets without duplicate
rpath diagnostics. Analysis checks alone do not establish that native gate.

## V8 analysis test size

`zzzz-0050-v8-analysis-test-size.patch` declares the V8 warning-policy analysis
test small. Its subsecond execution otherwise produces Bazel's test-size
advisory. Verbose timeout diagnostics remain enabled.

The declared base is `rust-v0.163.0`
(`83086188ae0614bd882818ff1aa23f3194962012`) with the maintained queue through
patch 0049. Run:

```sh
just fmt-check
just bazel-lock-check
bash .github/scripts/run-bazel-ci.sh --print-failed-test-logs -- test --test_output=errors --test_verbose_timeout_warnings -- //tools/build-warnings/dependencies:v8_warning_policy_test
```

The analysis test passes without a test-size advisory.

## Rust linker warning policy

`zzzz-0051-rust-linker-warning-policy.patch` explicitly denies Rust's
`linker_messages` lint for target and execution builds. That lint ignores the
umbrella `-Dwarnings` option, so a successful linker can otherwise emit warnings
without failing the Rust action. Native dependency compiler warnings retain
their existing visible, nonfatal policy.

The declared base is `rust-v0.163.0`
(`83086188ae0614bd882818ff1aa23f3194962012`) with the maintained queue through
patch 0050. Both patches track
https://github.com/holdmyspot-com/codex-unleashed/issues/61. Run:

```sh
just fmt-check
just bazel-lock-check
bash .github/scripts/run-bazel-ci.sh --print-failed-test-logs -- test --test_output=errors --test_verbose_timeout_warnings -- //tools/build-warnings/llvm-driver:test_linker_warnings_test //tools/build-warnings/llvm-driver:proc_macro_linker_warnings_test
```

Both analysis tests require the explicit fatal linker lint in the generated
Rust compiler actions. Full native CI remains the linking acceptance gate.

## Windows native regression fixtures

`zzzz-0052-windows-markdown-fixture.patch` writes the Markdown link destination
with forward slashes. A Windows backslash before an underscore is a Markdown
escape, so an unnormalized temporary path changes the destination the test
renders. The relative-link and citation assertions remain enabled.

`zzzz-0053-stale-updater-error-fixture.patch` expects the ownership rejection for
a stale predecessor before considering launch or elevation errors. Both
predecessor-record preservation assertions remain enabled.

`zzzz-0054-windows-voice-negative-fixture.patch` removes the required
`gstcoreelements.dll` plugin from the incomplete MSVC voice fixture. Windows
stores plugins beside startup libraries, so copying startup DLLs can otherwise
make this negative fixture complete. Helper handshakes remain possible, and
runtime initialization must fail.

## Windows sandbox integration deadline

`zzzz-0055-windows-sandbox-test-deadline.patch` gives the dedicated Windows
sandbox integration binary the same long deadline as the core integration
suite. Its 120-second event wait exceeds the short 60-second whole-binary
deadline. Every test and security assertion remains enabled under the queue's
existing narrowly scoped legacy-sandbox exclusion.

## Windows analysis host toolchain

`zzzz-0056-windows-analysis-host-toolchain.patch` retains the analysis-only MSVC
toolchain when XZ and OpenSSL analysis tests transition to a Windows GNU target.
The host VFS generator otherwise resolves through the LLVM toolchain being
analyzed and creates a dependency cycle. The fixture toolchain requires the
MSVC ABI, so it cannot replace the GNU target compiler.

The declared base for patches 0052–0056 is `rust-v0.163.0`
(`83086188ae0614bd882818ff1aa23f3194962012`) with the maintained queue through
patch 0051, applied in order. They track
https://github.com/holdmyspot-com/codex-unleashed/issues/61. From a fresh patched
checkout, run:

```sh
just fmt-check
just bazel-lock-check
bash .github/scripts/run-bazel-ci.sh --print-failed-test-logs -- test --test_output=errors --test_verbose_timeout_warnings -- //tools/build-warnings/xz:msvc_flags_test //tools/build-warnings/xz:gnu_flags_test //tools/build-warnings/openssl:windows_perlasm_test
```

Native Windows MSVC CI must also run the affected regressions:

```sh
bash .github/scripts/run-bazel-ci.sh --print-failed-test-logs -- test --test_output=errors --test_verbose_timeout_warnings -- //codex-rs/tui:tui-unit-tests //codex-rs/app-server-daemon:app-server-daemon-unit-tests //codex-rs/voice-host:voice-host-installed_client-test //codex-rs/core:core-windows_sandbox-test
```

The Markdown snapshot matches, stale handoffs preserve predecessor records,
incomplete voice runtimes reject initialization, and the sandbox suite
completes with its assertions enabled. The analysis targets resolve without a
host-toolchain cycle. Linux analysis and formatting checks alone do not
establish these native outcomes; full native CI remains required.

## MCP binding counter isolation

`zzzz-0057-mcp-binding-counter-isolation.patch` keeps an additional tracing
dispatcher alive while the MCP startup regression observes binding captures.
Tracing's single-dispatcher optimization can otherwise cache the shared
callsite as disabled when a concurrent test without a subscriber reaches it
first. The fixture uses the same dispatcher guard as upstream's retry telemetry
and deferred-tool-world-state tests. All startup, dormant-server, and binding
reuse assertions remain enabled.

The declared base is `rust-v0.163.0`
(`83086188ae0614bd882818ff1aa23f3194962012`) with the maintained queue through
patch 0056. The patch tracks
https://github.com/holdmyspot-com/codex-unleashed/issues/61. Run:

```sh
just fmt-check
just bazel-lock-check
bash .github/scripts/run-bazel-ci.sh --print-failed-test-logs -- test --test_output=errors --test_verbose_timeout_warnings --test_sharding_strategy=disabled --test_filter=cached_mcp_startup_is_eager_for_root_and_lazy_for_subagents -- //codex-rs/core:core-all-test
```

The focused regression observes one shared binding during the dormant turn and
additional captures when the server starts. Full native Windows CI also runs
the regression concurrently with the core integration suite; a focused Linux
run alone does not establish that native gate.

## Guardian whole-suite deadline

`zzzz-0058-guardian-suite-deadline.patch` gives the complete guardian unit-test
suite a moderate deadline. Native Intel Mac execution approaches the short
60-second limit, so the suite needs headroom for normal runtime variation.
Every test and assertion remains enabled.

Bazel's `timeout` attribute is nonconfigurable, so the suite uses one deadline
across platforms. Faster platforms can report a moderate-test sizing advisory;
that output remains visible. Compiler warning policy is unchanged.

The declared base is `rust-v0.163.0`
(`83086188ae0614bd882818ff1aa23f3194962012`) with the maintained queue through
patch 0057. The patch tracks
https://github.com/holdmyspot-com/codex-unleashed/issues/61. Run:

```sh
just fmt-check
just bazel-lock-check
bash .github/scripts/run-bazel-ci.sh --print-failed-test-logs -- test --test_output=errors --test_verbose_timeout_warnings -- //codex-rs/ext/guardian-v2:guardian-v2-unit-tests
```

The complete guardian suite passes with its existing assertions. Full native
CI verifies the configured deadline without a diagnostic command-line override.

## Selected-plugin process readiness

`zzzz-0059-selected-plugin-pid-deadline.patch` lets the selected-plugin startup
regression wait for its MCP process using the fixture's existing 20-second
read timeout. The shared PID-file helper retains its two-second default for
other callers. The optional-startup grace, explicit-mention barrier, tool
availability, and analytics assertions remain enabled.

The declared base is `rust-v0.163.0`
(`83086188ae0614bd882818ff1aa23f3194962012`) with the maintained queue through
patch 0058. The patch tracks
https://github.com/holdmyspot-com/codex-unleashed/issues/61. Run:

```sh
just fmt-check
just bazel-lock-check
bash .github/scripts/run-bazel-ci.sh --print-failed-test-logs -- test --test_output=errors --test_verbose_timeout_warnings --test_sharding_strategy=disabled --test_filter=selected_plugin_mcp_startup_respects_explicit_mentions -- //codex-rs/app-server:app-server-all-test
```

All six mention and discovery variants pass with their existing assertions.
Full native CI verifies the emitted helper change without diagnostic source
instrumentation. The focused run can report a sizing advisory because the
unfiltered integration suite keeps its long deadline.

## Sharded core integration deadline

`zzzz-0060-core-shard-deadline.patch` gives the sharded core integration suite
Bazel's moderate five-minute deadline. The separate Windows sandbox suite keeps
its long deadline. The core integration assertions and 16-shard layout remain
enabled, and Wine execution inherits the same integration deadline.

Bazel's `timeout` attribute is nonconfigurable, so native platforms share this
deadline. Faster shards can report a short-test sizing advisory; that output
remains visible. Compiler warning policy is unchanged.

The declared base is `rust-v0.163.0`
(`83086188ae0614bd882818ff1aa23f3194962012`) with the maintained queue through
patch 0059. The patch tracks
https://github.com/holdmyspot-com/codex-unleashed/issues/61. Run:

```sh
just fmt-check
just bazel-lock-check
bash .github/scripts/run-bazel-ci.sh --print-failed-test-logs -- test -- //tools/build-warnings/test-timeouts:timeouts_tests
bash .github/scripts/run-bazel-ci.sh --print-failed-test-logs -- test --test_output=errors --test_verbose_timeout_warnings -- //codex-rs/core:core-all-test
```

The timeout analysis suite verifies the generated deadline. Full native CI
runs every core integration shard within the configured deadline.

## GovCloud quit test

`zzzz-0061-gov-cloud-quit-pty.patch` handles the pseudo-terminal closing while
Codex quits the GovCloud guidance screen. Only the quit scenario accepts an
input `EIO`, and only after at least one successful quit-key write and a
successful child exit within the original interaction deadline. Other input
failures and unsuccessful or late child exits remain test failures. The acknowledgment, saved-configuration, and restart
assertions remain enabled. Product behavior is unchanged.

The unmodified upstream test can fail intermittently on Intel macOS with an
input `EIO`: repeated quit input can reach the closed terminal after a successful
child exit. The test requires the configuration to remain unchanged on quit.
The declared base is
`rust-v0.163.0` (`83086188ae0614bd882818ff1aa23f3194962012`) with the maintained
queue through patch 0060. The patch tracks
https://github.com/holdmyspot-com/codex-unleashed/issues/61. Run:

```sh
just fmt-check
just bazel-lock-check
just test -p codex-tui --test all gov_cloud_startup_persists_only_acknowledgment
bash .github/scripts/run-bazel-ci.sh --print-failed-test-logs -- test --test_output=all --runs_per_test=5 --nocache_test_results --test_arg=--exact --test_arg=suite::focus_palette::gov_cloud_guidance::gov_cloud_startup_persists_only_acknowledgment --test_arg=--nocapture -- //codex-rs/tui:tui-all-test
```

The focused regression verifies quit without saving, acknowledgment persistence,
and suppression on restart. The repeated native check executes only this
regression without diagnostic source instrumentation. Full native CI remains
the platform acceptance gate.

## SQLite startup failure cleanup

`zzzz-0062-sqlite-startup-close.patch` closes the private startup pool before
returning an initialization error. Corruption recovery can immediately move the
failed database, including on Windows. The original initialization error,
existing database settings, and error handling for later lazy connections remain
unchanged. SQLx itself is unchanged.

The native Windows thread-deletion regression can encounter a sharing violation
while moving a corrupt database after failed initialization. SQLx's hard-close
path drops the SQLite connection without awaiting its worker's termination.
The patch retains startup ownership until ordinary pool closure completes.

The declared base is `rust-v0.163.0`
(`83086188ae0614bd882818ff1aa23f3194962012`) with the maintained queue through
patch 0061. The patch tracks
https://github.com/holdmyspot-com/codex-unleashed/issues/61. Run:

```sh
just fmt-check
just bazel-lock-check
just test -p codex-state open_read_write_pool
bash .github/scripts/run-bazel-ci.sh --print-failed-test-logs --windows-msvc-host-platform -- test --config=ci-windows-msvc --platforms=//:windows_x86_64_msvc --repo_env=BAZEL_MSVC_RUNTIME_VISUAL_STUDIO_EULA=1 --test_sharding_strategy=disabled --nocache_test_results --test_output=all --test_arg=--exact --test_arg=suite::v2::thread_delete::thread_delete_recovers_only_corrupt_board_with_feature_disabled --test_arg=--nocapture -- //codex-rs/app-server:app-server-all-test
```

The four focused state tests verify fresh and existing database settings,
preservation of the original lock error, and immediate corruption-file release.
The native Windows integration check verifies corruption recovery through the
app-server protocol.

## Failed Bazel attempt logs

`zzzz-0063-bazel-failed-test-logs.patch` prints the tail of every retry log
reported for a failed test. It recognizes sharded error labels and uses the
selected configuration and platform when locating a fallback log. The original
Bazel failure status remains the command's exit status.

The regression covers retry paths containing spaces, Windows CRLF output,
shard annotations, fallback configuration, and preservation of failure status.
The declared base is `rust-v0.163.0`
(`83086188ae0614bd882818ff1aa23f3194962012`) with the maintained queue through
patch 0062. The patch tracks
https://github.com/holdmyspot-com/codex-unleashed/issues/61. Run on Linux:

```sh
just fmt-check
just bazel-lock-check
bash .github/scripts/run-bazel-ci.sh --print-failed-test-logs -- test --nocache_test_results --test_output=all -- //tools/build-warnings/bazel-ci-logs:failed_logs_test
```

For a nested local host whose Linux namespace denies access to `/dev/null`, add
`--spawn_strategy=processwrapper-sandbox` to the focused Bazel command. Native CI
uses its normal sandbox. All fixture checks must pass and the test must execute,
rather than only report a cached result.

### Bazel repository cache save preflight

`zzzz-0064-bazel-cache-save-preflight.patch` checks the exact repository cache
identity immediately before saving, using the cache action's download-free
lookup. A cache created by another producer skips archive creation. A missing
cache, or a lookup service error, retains the save attempt. This avoids redundant
compression exhausting a runner's remaining disk space after a long build.
It does not serialize simultaneous first writers.

Apply the earlier workflow prerequisites through
`zzzz-0063-bazel-failed-test-logs.patch` on the declared upstream base. Run:

```sh
bash .github/scripts/test-bazel-cache-save.sh
just fmt-check
```

The policy check requires every repository cache producer to retain a miss
save and guard it with a preceding lookup using the same key and path. Linux
Bazel CI runs this check. The hosted workflow has the same guard and its Maven
regression checks the YAML bindings.

`zzzz-0067-sdk-cache-save-preflight.patch` applies the same lookup and miss
guard to the SDK workflow, whose repository cache is shared with the Bazel
producers. It requires `zzzz-0064-bazel-cache-save-preflight.patch` for the
maintained policy regression. SDK CI runs that regression with its own workflow:

```sh
bash .github/scripts/test-bazel-cache-save.sh .github/workflows/sdk.yml
just fmt-check
```

The SDK check verifies one cache producer. Missing caches and lookup failures
still attempt a save; an exact cache created during SDK execution skips saving.
Simultaneous first-writer reservations remain best-effort cache operations.

## Timeout test logs

`zzzz-0065-bazel-timeout-test-logs.patch` prints log tails for Bazel `TIMEOUT`
records as well as `FAIL` records. Reported retry paths support CRLF and spaces;
summary-only timeouts use the configured test-output root. The wrapper preserves
Bazel's original failing exit status.

The declared base is `rust-v0.163.0`
(`83086188ae0614bd882818ff1aa23f3194962012`) with
`zzzz-0063-bazel-failed-test-logs.patch` and its documented prerequisites.
The patch tracks https://github.com/holdmyspot-com/codex-unleashed/issues/61.
Run from the patched checkout:

```sh
just fmt-check
bash .github/scripts/run-bazel-ci.sh --print-failed-test-logs -- test -- //tools/build-warnings/bazel-ci-logs:failed_logs_test
```

The regression expects all four failure and timeout scenarios to print their
fixture logs and return the original Bazel exit status. Full CI remains the
hosted acceptance gate.

## Feedback upload test deadline

`zzzz-0066-feedback-upload-deadline.patch` retains upstream's five-minute
(`moderate`) deadline for the feedback unit suite. Its large-payload upload
integrity tests keep their full payloads and assertions. Other unit suites keep
the shared short deadline unless they declare their own override.

The declared base is `rust-v0.163.0`
(`83086188ae0614bd882818ff1aa23f3194962012`) with the maintained queue through
patch 0065, including the shared timeout policy and its analysis-test support.
The patch tracks https://github.com/holdmyspot-com/codex-unleashed/issues/61.
Run from the patched checkout:

```sh
just fmt-check
just bazel-lock-check
bash .github/scripts/run-bazel-ci.sh --print-failed-test-logs -- test --nocache_test_results --test_output=errors -- //tools/build-warnings/test-timeouts:timeouts_tests
```

All 22 policy tests must execute and pass. The feedback regression verifies a
`moderate` timeout on the generated test target. Native macOS CI must also run
`//codex-rs/feedback:feedback-unit-tests` as part of the full release gate.
For a nested Linux host that denies the normal sandbox access to `/dev/null`,
add `--spawn_strategy=processwrapper-sandbox` to the focused policy command.

## macOS daemon fixture concurrency

`zzzz-0068-macos-daemon-fixture-concurrency.patch` runs CLI integration tests
one at a time on macOS. Packaged daemon fixtures each create independent copies
of the full CLI, so their combined disk use can exhaust a native runner. The
setting uses the generated Rust test arguments for both direct and wrapped
test targets. Other platforms use their normal integration-test concurrency.

The declared base is `rust-v0.163.0`
(`83086188ae0614bd882818ff1aa23f3194962012`) with the maintained queue through
patch 0067. The patch tracks
https://github.com/holdmyspot-com/codex-unleashed/issues/61. Run on each native
macOS architecture from the patched checkout. Cargo metadata reconciles local
package versions in the release lockfile, matching `prepare-bazel-ci`:

```sh
just fmt-check
(cd codex-rs && cargo metadata --format-version 1 > /dev/null)
just bazel-lock-check
bash .github/scripts/run-bazel-ci.sh --print-failed-test-logs -- test --test_sharding_strategy=disabled --flaky_test_attempts=1 --nocache_test_results --test_output=all -- //codex-rs/cli:cli-app_server_daemon-test //codex-rs/cli:cli-daemon_startup-test
```

The daemon lifecycle suite runs 14 tests and the startup suite runs 12 tests.
Both suites must execute and pass with their full payloads and assertions.
Full native CI remains the release acceptance gate. The fixture concurrency
setting does not require a production app-server change.
