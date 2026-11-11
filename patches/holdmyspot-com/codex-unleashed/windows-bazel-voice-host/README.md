# Windows Bazel voice-host test runtime

- Applies to: upstream `openai/codex` tag `rust-v0.167.0`, commit `086ccc833bc9cf01024ad2dd6af7dbd421b6bf9a`.
- Prerequisites: `windows-bazel/opus-installed-headers.patch`. Without it, `opusic-sys` fails to build on Windows, so no voice-host test target builds and this patch has nothing to fix.
- Upstream status: backport of [openai/codex#59082](https://github.com/openai/codex/pull/59082) (commit `ac91c14443`), the voice-host part of [openai/codex#59231](https://github.com/openai/codex/pull/59231) (commit `8da8b0c7f7`), and the `defs.bzl` and `codex-rs/voice-host/BUILD.bazel` parts of [openai/codex#59369](https://github.com/openai/codex/pull/59369) (commit `021fccb04`). None is in a stable release yet (`rust-v0.167.2` lacks them); drop this patch when the queue's upstream base includes all three.

## Intent

Under Bazel on Windows (MSVC), the voice-host tests start `codex-voice-host` with the native runtime's DLLs on the `PATH`, so the helper starts and the tests exercise it.

## Cause

`codex-voice-host` links GStreamer (`gstreamer-1.0-0.dll` and others) and `opus.dll` dynamically. The Bazel tests started it without those DLLs on the `PATH`. In a live session on Windows Bazel run `43075770116`, with `opusic-sys` building, `voice-host-lifecycle-test` and `voice-host-installed_client-test` failed on `assert!(output.status.success())` when they started the helper, and `codex-voice-host-bin-unit-tests` exited with `0xC0000135` (`STATUS_DLL_NOT_FOUND`).

## Scope

- `codex-rs/voice-host/tests/common/mod.rs` starts the helper with the runtime's `bin` directory, found through Bazel runfiles, at the front of the `PATH`; the integration tests use it.
- `installed_client.rs` leaves `gstcoreelements.dll` out of its startup-only fixture on Windows, because Windows keeps plugins beside the startup DLLs and the fixture must still fail to initialize the audio runtime.
- `defs.bzl` gains `binary_unit_test_windows_runtime`, which puts a runtime's `bin` directory on the binary unit test's `PATH`; `codex-rs/voice-host/BUILD.bazel` sets it to `//third_party/voice:native_runtime` for MSVC.

## Verification

The voice-host test targets in the Windows Bazel workflow, with this patch queued after the Opus patch. They fail without it, as described under Cause.
