# Voice runtime packaging

Apply these patches to upstream `openai/codex` tag `rust-v0.172.0`, commit `3d86e43b075adeba2193ec5c22ac92f55afd82e6`, after queue entries 1–64, in the order listed in the parent [README.md](../../../README.md).

`producer.patch` adds `.github/scripts/codex-voice-runtime.sh` and its focused fixture test to the selected upstream source. The producer builds the helper, native runtime, and both runtime test binaries for one supported application target, stages and seals the runtime through upstream voice tooling, then writes a completed result directory with identity and hash evidence.

The producer accepts only a fresh result directory and requires the patched source commit and runner target explicitly. Windows builds also require the verified native voice tool repository and host environment prepared by the release workflow. On failure, incomplete release and verification outputs are removed while evidence remains available for diagnosis.

Run the focused shell checks from the patched upstream checkout:

```sh
bash -n .github/scripts/codex-voice-runtime.sh
bash -n .github/scripts/test-codex-voice-runtime.sh
bash .github/scripts/test-codex-voice-runtime.sh
shellcheck .github/scripts/codex-voice-runtime.sh .github/scripts/test-codex-voice-runtime.sh
```

The fixture test validates command construction and failure cleanup with controlled tool boundaries; it does not establish native runtime acceptance. The six-target native acceptance matrix remains a separate release gate.

`package-integration.patch` extends the existing shared package dispatcher and composite action. The `voice-release-dir`, `voice-build-commit` and `tooling-launcher` inputs must be either all absent or all supplied. With all supplied, the dispatcher stages voice into the primary canonical package before writing its gzip and zstd archives. The app-server package retains its ordinary resource set. With all absent, generic package callers retain ordinary packaging. A partial triple fails before building; a failed voice stage or archive write removes only newly emitted primary archives and leaves evidence. Preexisting primary archive outputs are rejected and preserved.

The voice route requires the Java tooling launcher's `voice-runtime stage` operation. Native production also requires both verification-only runtime test binaries, including `voice-host-installed_package_runtime-test-bin`; these binaries are retained as verification artifacts and are not shipped in product archives. Registration of these packaging patches does not establish that those dependent implementations or the six-target native gates are complete.

`voice-non-rtp-ingress.patch` preserves decoded non-RTP packets on the application read queue, forwarding each once and in order with its packet metadata. The focused regression also checks that RTP conversion and outbound handling remain intact.

`voice-capture-timestamp.patch` carries the encoded frame's capture time into the emitted sample while preserving its payload and duration. Its focused regression checks all three fields; native platform acceptance remains a separate gate.

`query-stdout.patch` makes the voice producer's Bazel executable queries return only the executable path on standard output. Runner and build diagnostics go to standard error, both streams remain in the console log, and query failures retain their exit status and failure summary. The runner waits for both log writers before reading failure evidence. Other runner invocations retain their combined output.

Run the focused query and producer checks from the patched upstream checkout:

```sh
python3 .github/scripts/test_run_bazel_with_buildbuddy.py
bash .github/scripts/test-codex-voice-runtime.sh
bash -n .github/scripts/run-bazel-ci.sh .github/scripts/codex-voice-runtime.sh .github/scripts/test-codex-voice-runtime.sh
shellcheck .github/scripts/run-bazel-ci.sh .github/scripts/codex-voice-runtime.sh .github/scripts/test-codex-voice-runtime.sh
```

These checks exercise the runner's stream routing and status propagation with a controlled child process. Real native production, staging, archive creation, and relocated runtime verification remain required release gates.

Run the composed dispatcher checks after applying both patches:

```sh
bash -n .github/scripts/codex-package-operations.sh
bash -n .github/scripts/test-codex-package-operations.sh
bash .github/scripts/test-codex-package-operations.sh
shellcheck .github/scripts/codex-package-operations.sh .github/scripts/test-codex-package-operations.sh
```

`loader-path.patch` follows `query-stdout.patch` and runs the producer helper identity check with its own staged libraries: `runtime/lib` on Linux and macOS, or `runtime/bin` on Windows. Inherited loader search directories do not supply third-party libraries for this check. The bounded identity output, exact build-commit check and failure cleanup remain required.

Run the producer fixture suite, Bash syntax check and ShellCheck commands above after applying this patch. These controlled fixtures do not establish native platform acceptance; the real producer and relocated package checks remain release gates.

## Rebase onto rust-v0.171.0

`query-stdout.patch`: upstream replaced `run_bazel_with_buildbuddy.py` with `run_bazel.py` and removed the BuildBuddy echo lines. The FIFO split that keeps the query path on stdout is ported to `run-bazel-ci.sh` as it is now, and its test moves to `.github/scripts/test_run_bazel.py` without the BuildBuddy assertions. `test_run_bazel` (8 tests) and `test-codex-voice-runtime.sh` pass.
