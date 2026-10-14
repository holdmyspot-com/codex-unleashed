# Build warning enforcement

## Intent

Rust compilation, documentation, and native compiler/linker actions treat warnings as errors. Formatting checks also reject unexpected warning diagnostics, including warnings from commands that exit successfully. Stable rustfmt remains supported through a narrow exception for its unsupported `imports_granularity=Item` option, matching upstream's formatting behavior.

## Reference and upstream base

- [Canonical issue](https://github.com/holdmyspot-com/codex-unleashed/issues/61).
- Base: `rust-v0.162.1`, commit `092d3acd6bec3e3a14bdc7e7a2810ab628ab759d`.
- [Upstream import-formatting tracking issue](https://github.com/rust-lang/rustfmt/issues/4991).

## Scope and dependencies

`formatter-warning-policy.patch` updates the existing upstream formatter wrapper and its CI callers. It requires no other downstream patch. It includes process-boundary tests and runs them in repository checks. This patch covers formatter diagnostics; the issue's other build-tool enforcement gates are separate work.

`native-and-rust-warning-policy.patch` enables Cargo's warning policy, strict rustdoc diagnostics, and strict Bazel Rust target and execution-platform actions. It enables `treat_warnings_as_errors` for native target and host toolchains. Hermetic LLVM supplies compiler and fatal-linker flags; native MSVC uses the existing rules_cc feature with `/WX` for compilation and linking. Apply the current-toolchain patch from issue #60 first, including its linker-argument compatibility adaptation.

The native probe first requires a clean C program to build, then requires a deprecated-function warning to fail at the compiler diagnostic. It rejects an unrelated failure as evidence of warning enforcement. Remaining build-tool and hosted-platform acceptance gates are tracked separately in the issue.

`vendored-native-build-script-modes.patch` explicitly selects the existing vendored build scripts for `tikv-jemalloc-sys` and `libgit2-sys`. This resolves rules_rs's well-known-annotation advisories without changing native library providers. It applies after the issue #60 toolchain patch. Cold module-extension evaluation must complete without either advisory.

`libcxx-explicit-runtime-configuration.patch` defines the custom libc++ runtime's minimum availability-header version and LLVM-libc selection as zero. These match the existing preprocessor branches and remove undefined-macro warnings. The patch includes a native configuration regression test and applies independently of the other patches.

`icu-mutex-thread-safety.patch` gives ICU's mutex implementation, C wrappers, and scoped guard explicit Clang lock contracts, including the default global mutex. It releases the currency-name cache mutex on allocation failure and represents the transliterator's conditional ownership with `std::unique_lock`. Thread-safety analysis remains enabled. A focused native test exercises direct locking, C wrappers, explicit scoped guards, and the default global guard; a manual negative target verifies that unprotected writes still fail compilation. Apply the issue #60 toolchain patch and this issue's native warning policy before verification.

`upgrade-llvm-23-icu-regression-values.patch` makes that fixture read and check
each guarded value while holding its mutex. The positive test verifies the values
without LLVM 23's unused-global errors. The negative target still fails for
unguarded access. Apply it after `icu-mutex-thread-safety.patch`.

`native-mingw-function-guard.patch` corrects the native x86 function macro's `#ifndef` spelling in the pinned MinGW headers and enables fatal diagnostics in import-definition preprocessing. Its regression tests compile the real header for Windows x86-64 and ARM64 and assert that native symbols remain enabled only on the corresponding architecture. Apply it after the issue #60 toolchain patch and `native-and-rust-warning-policy.patch`, which provides its test directory.

`native-bindgen-preprocessor-policy.patch` preserves the compiler's optimization and warning-policy arguments when generating Rust bindings. It translates MSVC's `/WX` and `/WX-` options to their Clang equivalents and retains argument boundaries and the existing toolchain's deterministic macro-redaction policy. Its regression suite participates in `just bazel-test`. Apply it after the issue #60 toolchain patch and `native-and-rust-warning-policy.patch`.

`native-aws-lc-windows-tls.patch` backports AWS-LC's MinGW TLS-callback fix, preserving thread-exit cleanup under LLVM 22. It uses the upstream section attributes and `_tls_used` reference while retaining the MSVC path. A focused thread-exit cleanup regression participates in `just bazel-test`. Apply it after the issue #60 toolchain patch and `native-and-rust-warning-policy.patch`.

`native-make-bootstrap-policy.patch` follows Autoconf's supported probe contract: global `-Werror` takes effect after feature detection and remains active for GNU Make compilation, linking, and installation. It preserves the other toolchain flags and fatal linker diagnostics. It backports GNU Make's string-comparison fix and adds a test that builds the actual bootstrap tool and executes a Makefile. Apply it after the issue #60 toolchain patch and `native-and-rust-warning-policy.patch`.

`upgrade-llvm-23-windows-driver.patch` selects hermetic LLVM 0.8.24
(LLVM 23.1.2) and aligns its direct Bazel dependencies. LLVM 23 honors Rust's
Windows GNU `-nolibc` option under `-Werror`. The custom libc++, ARM64 MinGW,
and warning-policy patches use this module's toolchain layout. Windows MSVC
compiles use `/WX`; links also pass `/WX` to lld-link. GNU and macOS
toolchains retain their fatal-warning flags. The merged link resource tree
includes the selected compiler's builtin headers for foreign builds that
combine compilation and linking flags. GNU Make's buffer counter exists
only when its consuming assertion is enabled; release builds retain the
computed statistics and avoid unused-counter warnings. Apply it after the issue #60
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

## V8 legacy callback compatibility

`native-v8-callback-compatibility.patch` keeps the pinned Rust callback ABI while
V8's transition constructors invoke deprecated internal converters. It scopes
V8's diagnostic push/pop macros to the named and indexed compatibility
constructor sections. `WORKAROUND` comments link the
[callback-retirement issue](https://crbug.com/348660658) and the
[coordinated Rusty V8 migration](https://github.com/denoland/rusty_v8/pull/2037).
Remove the guards when the pinned Rust callbacks migrate with V8.

Apply the native warning policy, LLVM 23, simdutf, and Abseil prerequisites, then
run the focused compiler check:

```sh
tools/build-warnings/v8/check.sh --keep_going --output_groups=compilation_outputs
```

The real binding compiles, and an unrelated deprecated call in an independent
translation unit fails compilation. The independent probe checks restoration of
the header's diagnostic state without inheriting the upstream binding source's
existing suppression. The negative target is manual. This compile-only check
does not establish V8 runtime or release-artifact correctness; the full build and
runtime tests remain required.

## Abseil lifetime attributes

`native-abseil-lifetime-attribute.patch` backports
[Abseil's lifetime-capture migration](https://github.com/abseil/abseil-cpp/commit/0784689a65b4ea1329fbc5174b8a57c88ec796a9)
to the selected 20250814.1 dependency. It preserves the annotations, selects
`lifetime_capture_by_this` when supported, and retains upstream's fallback for
older compilers. Deprecated-attribute warnings remain fatal.

With the native warning policy and LLVM 23 toolchain prerequisites applied:

```sh
bazel test //tools/build-warnings/abseil:lifetime_attribute_test --keep_going
```

The test compiles the public container, status, and mutex headers and executes
exclusive, shared, and writer lock guards. It passes without deprecated-attribute
diagnostics. It does not establish lock behavior under concurrent contention.

## SIMD UTF conversion

`native-simdutf-constexpr.patch` backports compile-time chunk selection from
[simdutf PR #943](https://github.com/simdutf/simdutf/pull/943) to V8's pinned
simdutf 7.7.0 dependency. Its Bazel build requires C++20, so the backport uses
`if constexpr` directly. LLVM 23 discards array accesses in unreachable branches
while array-bounds warnings remain fatal.

With the native warning policy and LLVM 23 toolchain prerequisites applied:

```sh
bazel test @v8//tests/codex_simdutf:transcoder_test --keep_going
```

The test passes for every implementation supported by the running processor. It
checks multibyte UTF-8 conversion, UTF-16 contents, malformed input rejection,
and the reported error offset. It does not execute unsupported processor kernels.

## OpenSSL initialization and provider backports

`native-openssl-unused-globals.patch` backports OpenSSL's library-only fixes from
[PR #32821](https://github.com/openssl/openssl/pull/32821),
[PR #30420](https://github.com/openssl/openssl/pull/30420), and
[PR #31508](https://github.com/openssl/openssl/pull/31508) to the pinned 3.5.4
source. It removes unused initialization state and keeps each provider's used
core callback local to its initialization. Compiler warnings remain fatal.

Apply the native warning policy and LLVM 23 toolchain patch before verification:

```sh
bazel test //tools/build-warnings/openssl:ssl_init_test --keep_going
```

The test passes after repeated TLS initialization and loading and unloading the
base and default providers. Without the backports, LLVM 23 rejects unused globals
in the pinned library. The test covers initialization and provider availability;
it does not establish concurrent provider initialization behavior.

`native-openssl-unused-options.patch` follows the library backports and carries
the command-line fixes from PR #32821. It limits sendfile state to kernel-TLS
builds and removes an unused argument-count global. The enabled kernel-TLS path
and option parsing retain their behavior.

```sh
bazel build //tools/build-warnings/openssl:cli_probe --keep_going
```

The probe builds the CLI with fatal warnings, then runs its version and server
help commands. Its output contains the pinned OpenSSL version and server usage
text. It does not open a listener or establish a TLS connection.

## AWS-LC fork-detection state

`native-aws-lc-windows-unused-fork-state.patch` backports
[AWS-LC PR #3645](https://github.com/aws/aws-lc/pull/3645) to 5.1.0.bcr.1.
Fork-detection test state exists only on platforms that support it; the test
hooks remain callable elsewhere. Warning checks stay enabled. Apply it after
`native-aws-lc-windows-tls.patch`.

```sh
bazel build @aws-lc//:crypto --platforms=//:windows_x86_64_gnullvm --output_groups=compilation_outputs --keep_going
bazel test //tools/build-warnings/aws-lc:fork_detection_test --keep_going
```

The first check compiles the actual Windows crypto objects under LLVM 23's
fatal warning policy. The runtime regression checks random generation before
and after calling both test hooks. A Linux pass does not establish Windows
runtime behavior; run the same test on a Windows execution host for that gate.

## MinGW ABI alias compatibility

`upgrade-llvm-23-windows-mingw-aliases.patch` backports MinGW's
[LLVM 23 alias compatibility fix](https://github.com/mingw-w64/mingw-w64/commit/b43f781b916e8170b66bc4c6784123e53b283f8a)
and its two diagnostic-macro prerequisites to v14.0.0. The upstream scoped
push/pop guards preserve intentional aliases between ABI-compatible types.
The shared guard carries a `WORKAROUND` reference to this issue, the upstream
fix, and the removal condition. Other alias warnings remain fatal.

Apply the native warning policy, MinGW function-guard, and LLVM 23 prerequisites:

```sh
tools/build-warnings/mingw/check.sh --platforms=//:windows_x86_64_gnullvm --keep_going
```

The check compiles the actual MinGW runtime objects and requires an unrelated
alias mismatch to fail at `-Wattribute-alias`. It runs on Linux and does not
establish Windows runtime behavior or a successful full Windows release build.

## GoogleTest Windows paths

`upgrade-llvm-23-windows-test-paths.patch` removes an unreferenced private
separator string from GoogleTest 1.17.0.bcr.2. It preserves path normalization
and splitting while unused-constant warnings remain fatal. Apply it after the
Abseil lifetime-attribute and LLVM 23 prerequisites.

```sh
bazel build @googletest//:gtest --platforms=//:windows_x86_64_gnullvm --output_groups=compilation_outputs --keep_going
bazel test //tools/build-warnings/googletest:path_separator_test --keep_going
```

The compiler check builds the actual Windows library objects. The runtime test
checks separator normalization and directory/basename splitting, with Windows
expectations selected on Windows execution hosts. A Linux pass covers its POSIX
branch; Windows runtime acceptance remains a separate gate.

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
CLI executable. A `WORKAROUND` comment uses this issue to track rules_foreign_cc
0.15.1 overwriting explicit `target_args["compile"]`; its supported `build_args`
property selects the executable until that argument override is corrected.

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

`native-voice-warning-diagnostics.patch` follows that patch. Successful native
commands fail if their logs contain compiler, linker, CMake, Meson, or GNU
configure warning diagnostics. The receipt retains multiline warning context
and the original command status. Commands that already fail retain their
nonzero status.

```sh
python3 -m unittest discover -s third_party/voice -p 'test_*.py'
just fmt-check
bazel build //third_party/voice:native_prefix_linux_x86_64 --keep_going
```

The regressions exercise real child processes, build C-only and C++-only CMake
fixtures, preserve literal cache values, and require an unused project-specific
option to remain fatal. Use the declared build tools on `PATH` and a
project-owned `TMPDIR`. The complete native prefix and hosted platform builds
remain separate acceptance gates.

## Native Ninja bundled lexers

`native-voice-ninja-bundled-lexers.patch` gives the checksum-verified Ninja
1.13.2 source an explicit `NINJA_REGENERATE_LEXERS` build option. It defaults
to `ON`, preserving lexer regeneration and its missing-tool diagnostic. The
native voice recipe sets it to `OFF` and compiles the bundled generated lexers.
Missing bundled files fail configuration. The `WORKAROUND` comment links this
issue and gives the upstream removal condition.

With the CMake initial-cache and native diagnostic policy prerequisites applied:

```sh
python3 tools/build-warnings/check-native-ninja.py /path/to/native_prefix_linux_x86_64.json /path/to/bazel-execroot
```

Use a project-owned `TMPDIR` and an execroot whose Bazel command has finished.
The probe builds Ninja with the real declared compiler flags and executes a
build manifest. Its negative checks require the missing-generator warning in
regeneration mode and a configuration error for a missing bundled lexer.
It prints the logs and removes its extracted sources and outputs on exit.
The complete native prefix and Windows/macOS builds remain separate gates.

## Native GLib printf portability

`native-voice-warning-glib-1-printf.patch` repairs GLib 2.88.3's bundled gnulib printf
fallback. Its fall-through annotation uses GLib's compiler capability check.
The two remaining signed return-count comparisons use unsigned counts after
the existing negative-result error handling. Compiler warning checks remain
enabled. The casts follow gnulib's
[unsigned-count comparison fix](https://github.com/coreutils/gnulib/commit/857227b38e1953a9f42be3a1deb5b7b6e0c720b3)
in the remaining fallback branches.

```sh
bazel test //tools/build-warnings/glib:printf_test --keep_going
```

The check builds the complete native prefix with fatal warnings and exercises
GLib's public printf API with signed and unsigned 64-bit values, negative field
width, and a large padded result. The runtime assertions cover the public API;
compilation of the actual gnulib source establishes the fallback warning gate.

## Native GLib log writer

`native-voice-warning-glib-2-writer.patch` backports
[GLib's log-writer cleanup fix](https://github.com/GNOME/glib/commit/e7592a3c4ed4f3ef97f9efb804ad9637a3d4ca1c).
The setter retains its single-call contract and accounts for the stored cleanup
callback. Apply the native GLib printf prerequisite before verification.

```sh
bazel test //tools/build-warnings/glib:log_writer_test --keep_going
```

The check builds the complete native prefix with fatal warnings, installs a
custom log writer, verifies its structured message and caller data, and checks
that caller data remains owned while the writer is installed. Hosted platform
builds remain separate gates. Full-prefix verification requires the following
GLib and GStreamer compatibility patches in filename order.

## Native GLib headers and optional tracing

`native-voice-warning-glib-3-headers.patch` backports GLib's
[sanitizer predicate conversion fix](https://github.com/GNOME/glib/commit/997154cecc465bd399b467cc5c6cb7c88c43559d)
and [optional generated parent-class annotation](https://github.com/GNOME/glib/commit/1567250294e9666747ee63f5c6364bbceadd300b).
The predicate returns a boolean without narrowing a long integer. Generated
types can omit parent-class access without an unused-variable diagnostic.

`native-voice-warning-glib-4-tracing.patch` backports GLib's
[optional trace-counter annotations](https://github.com/GNOME/glib/commit/168e378526a590439faa9ce7b607203fd434ff1d).
The counters remain available to profiling builds and do not produce unused
variable diagnostics when profiling is disabled. Each source adaptation names
its upstream fix and removal condition; compiler checks remain enabled.

```sh
bazel test //tools/build-warnings/glib:type_test //tools/build-warnings/glib:task_test --keep_going
```

The checks compile the actual native prefix, construct and destroy a generated
GObject type, and execute a GTask through its thread pool with a checked result.
Compilation of GObject exercises the private sanitizer header.

## Native GLib query compatibility

`native-voice-warning-glib-5-query.patch` adapts GLib's
[unused spacing-storage fix](https://github.com/GNOME/glib/commit/11de1d730e58c1f501e3a5d4c65b283fb8a26e0f).
The pinned release continues accepting `-s` and documenting it in help. Its
existing argument conversion remains, while unused global storage is removed.

After building the native prefix, run:

```sh
python3 tools/build-warnings/check-native-glib-query.py bazel-bin/third_party/voice/native_prefix_linux_x86_64/prefix.tar
```

The Linux check executes the built utility, compares its type tree with several
accepted spacing values, checks help, and removes its extracted prefix on exit.

## Native GStreamer pad quarks and option parsing

`native-voice-warning-gstreamer-1-quarks.patch` removes unused pad-quark storage
from GStreamer 1.28.6 and preserves the three string-interning calls.
`native-voice-warning-gstreamer-2-preload.patch` compiles the plugin preload
callback under the same option-parsing feature guard as its only caller.
Neither change disables a compiler diagnostic.

```sh
bazel test //tools/build-warnings/gstreamer:pad_quarks_test --keep_going
```

The check builds the native prefix, constructs a source pad, and verifies that
the buffer, buffer-list, and event quark strings remain interned.

## Native GStreamer Orc selection

`native-voice-warning-orc-choice.patch` reports the explicitly selected C
fallback as a status message in both `gst-plugins-base` and `gst-plugins-good`.
Automatic discovery that fails to find Orc
continues emitting its warning, which the native recipe rejects.

With the declared Meson source and Ninja executable, run:

```sh
python3 tools/build-warnings/check-native-orc-choice.py <native-prefix-json> <execroot> <meson-source> <ninja>
```

The check extracts the checksum-verified GStreamer dependency block and uses
the native Meson recipe to exercise both choices. Explicit disablement succeeds;
automatic discovery without Orc fails at the warning. The probe declares its
minimum Meson version, overrides the recipe's global automatic-feature default
for this comparison, prints the logs, and removes its temporary files.

## Native GStreamer optional Linux allocator

`native-voice-warning-udmabuf-guard.patch` gives the allocator's performance
category the same `memfd_create` and Linux udmabuf-header guard as its only
logging call. Type initialization calls a helper that initializes the category
when those capabilities are available. The unsupported allocator path does
not compile unused category storage. Compiler diagnostics remain enabled.

The native-prefix build in the GStreamer regression command verifies this
boundary with the declared kernel headers. Hosted platform builds remain
separate acceptance gates.

```sh
bazel test //tools/build-warnings/gstreamer:allocator_type_test --keep_going
```

The Linux check initializes the allocator's type class without requiring an
udmabuf device.

## Native GStreamer audio/video metadata and enum generation

`native-voice-warning-video-1-quarks.patch` removes unused audio and video encoder
and decoder quark caches while preserving their metadata-tag string interning.
`native-voice-warning-video-2-enums.patch` keeps two enum expressions on single
lines so the pinned `glib-mkenums` generator parses every value. The C tokens
and enum values remain unchanged.

```sh
bazel test //tools/build-warnings/gstreamer:video_quarks_test //tools/build-warnings/gstreamer:enum_values_test --keep_going
```

The checks initialize all four audio and video type classes, verify both metadata
tags remain interned, and verify that the generated RTP send/receive and navigation-mask
flag entries match their public enum values. The complete native build rejects
generator warnings as well as compiler and linker warnings.

## Native GStreamer audio conversion category

`native-voice-warning-z-audioconvert.patch` removes an unused performance logging
category from the pinned audio converter. The category has no logging callers;
the plugin's normal debug category remains initialized. Apply it after the native
GLib and GStreamer compatibility patches above.

```sh
bazel test //tools/build-warnings/gstreamer:audio_convert_test --keep_going
```

The Linux check builds the native prefix, loads its `audioconvert` plugin, and
creates and destroys an audio converter element. It requires no audio hardware.
The check covers plugin initialization; it does not exercise sample conversion.

## Native GStreamer documentation selection

`native-voice-warning-z-documentation.patch` reports a missing documentation cache
generator as a status message when documentation is explicitly disabled. If the
generator is available, its cache target remains available. Automatic discovery
without the generator retains its warning, which the native recipe rejects.

```sh
python3 tools/build-warnings/check-native-doc-choice.py <native-prefix-json> <execroot> <meson-source> <ninja>
```

With a project-owned `TMPDIR`, the check exercises the checksum-verified
documentation dependency block through the native Meson recipe. Disabled
documentation succeeds; missing automatic discovery fails at its warning.
The fixture supplies absolute GStreamer package metadata, prints its logs, and
removes its temporary files. The complete native prefix remains a separate gate.

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

## libffi Java raw implementation

`native-libffi-java-raw-implementation.patch` gives libffi 3.8.0's Java raw
entry points private implementations and preserves their public wrappers.
Internal calls use those helpers instead of deprecated public declarations.
The public ABI and deprecation attributes remain available. The source repair
applies only to the checksum-verified pinned version and rejects unexpected
function definitions. Its `WORKAROUND` comment links this issue and states the
upstream removal condition.

With a project-owned `TMPDIR`, run the focused Linux check using the pinned
libffi archive and the selected compiler and Make executable:

```sh
tools/build-warnings/check-libffi.sh /path/to/libffi-3.8.0.tar /path/to/cc /path/to/make
bazel build //third_party/voice:native_prefix_linux_x86_64 --keep_going
```

The focused check builds the actual shared library with fatal warnings, checks
its exported Java raw symbols, and executes an argument-conversion round trip.
An independent consumer must fail compilation at its public deprecation
diagnostic. Configure and build logs must contain no warning diagnostics.
The check removes its extracted sources and outputs on exit. Native Windows
and macOS builds remain separate acceptance gates.

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
- Source whitespace checks pass. Nested patch context retains the space prefix required by Git patch syntax.
- After Cargo resolves the workspace lockfile versions, `just bazel-lock-check` passes without crate-annotation advisories, including on a cold module-extension evaluation.
- `just check-build-warnings` passes on Unix: the clean fixture builds and the warning fixture fails at its expected diagnostic.
- `bazel test //third_party/v8/libcxx_config:config_test` passes with both runtime configuration values explicitly defined as zero.
- `bazel test //third_party/v8:icu_mutex_analysis_test` passes with Clang thread-safety analysis enabled, including compilation of ICU's common and internationalization libraries.
- With the hermetic Clang toolchain, `bazel build //third_party/v8:icu_mutex_analysis_error` fails specifically because the two guarded writes require their respective mutexes. An unrelated failure does not establish this check.
- `bazel test //tools/build-warnings:mingw_function_guard_x86_64_test //tools/build-warnings:mingw_function_guard_aarch64_test` passes without preprocessor warnings and preserves both architectures' macro expansion.
- `bazel test //tools/build-warnings/bindgen:preprocessor_policy_test` passes all six argument-preservation and MSVC normalization regressions.
- `bazel build //tools/build-warnings/bindgen:warning` fails specifically at `expected_bindgen_warning`, proving that a header warning is fatal during binding generation.
- `bazel test //tools/build-warnings/make-bootstrap:make_bootstrap_test` builds GNU Make with strict compiler/linker flags and verifies its Makefile execution. The successful bootstrap log contains no warning diagnostics.
- `bazel test //tools/build-warnings/aws-lc:thread_test` verifies that worker-thread exit invokes its registered destructor exactly once and an unused thread does not invoke it again.
- `bazel build //tools/build-warnings/aws-lc:thread_test --platforms=//:windows_x86_64_gnullvm` compiles and links the Windows regression executable without warnings. On a Windows execution host, run the same target with `bazel test` to verify thread-exit cleanup; cross-compilation alone does not establish that runtime outcome.
- `bazel test //tools/build-warnings/llvm-driver:all` verifies warning-free Windows GNU linking with `-nolibc` and requires the MSVC linker to reject an intentionally triggered warning when fatal-linker warnings are enabled. These Linux-hosted driver checks do not establish Windows runtime behavior.
- On Windows with the installed MSVC/SDK environment, `bash tools/build-warnings/check.sh --config=ci-windows-msvc` checks native MSVC warning enforcement.

Local verification uses the project's maintained `run-local-rust` helper with checkout-specific outputs and dependency caches beneath `.cat/work/temp/build-caches`. The same commands apply after applying the emitted patch to a fresh checkout of the declared upstream base.

## Upstream status

The declared upstream release permits ignoring the stable-rustfmt import-layout warning and does not reject other successful formatter warnings. Rustfmt's import-granularity option remains unstable in its tracking issue.

AWS-LC [commit `8cca33b97eb64be944a3d90406230676e8a7b931`](https://github.com/aws/aws-lc/commit/8cca33b97eb64be944a3d90406230676e8a7b931) fixes MinGW TLS callback registration. The queue retains the backport while its AWS-LC dependency lacks that fix.

GNU Make [commit `63961d5d0905f69c59b44652fd33ac932f7d1c23`](https://gnu.googlesource.com/make/+/63961d5d0905f69c59b44652fd33ac932f7d1c23) removes string-pointer comparisons that trigger compiler warnings. [Autoconf's supported configuration contract](https://lists.gnu.org/archive/html/autoconf/2020-12/msg00002.html) requires applying global warning promotion after its feature probes.
