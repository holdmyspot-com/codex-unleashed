# Windows Bazel Opus headers

- Applies to: upstream `openai/codex` tag `rust-v0.167.0`, commit `086ccc833bc9cf01024ad2dd6af7dbd421b6bf9a`.
- Prerequisites: none. The patch changes only `third_party/voice/opus-toolchain.cmake`, which no other patch in the queue touches.
- Upstream status: downstream fix; the failure also occurs on unpatched upstream (Windows Bazel run `42953543241`).

## Intent

A native Windows Bazel build of `opusic-sys` finishes its CMake install with the Opus headers intact, so the crate's build script output tree validates and its dependents build.

## Cause

Bazel on Windows runs with `startup --windows_enable_symlinks`, which presents the crate's sources to the build script as symbolic links. CMake installs a symbolic link as a symbolic link, so each installed `include/opus/*.h` pointed at `\\?\D:\o\execroot\_main\external\rules_rs++crate+crates__opusic-sys-0.7.5\opus\include\...`. Bazel resolves links in the output tree outside the execution root and failed the action with `_bs.out_dir: child include/opus/opus_types.h is a dangling symbolic link`. The retained evidence of run `42953543241` records all five installed headers as links whose targets do not exist (`opus-install/headers.json`).

## Scope

`opus-installed-headers.patch` appends a block to the toolchain file that the crate's build script already receives (`CMAKE_TOOLCHAIN_FILE` in `MODULE.bazel`). `cmake_language(DEFER ...)` schedules an `install(CODE ...)` step at the end of the project's top-level directory, after its own install rules, and that step replaces each symbolic link in `CMAKE_INSTALL_MANIFEST_FILES` with a copy of its target. Editing the toolchain file leaves `MODULE.bazel` and `MODULE.bazel.lock` unchanged, and the patch copies no dependency source.

## Verification

`python3 .github/scripts/test-opus-installed-headers.py` (with CMake 3.21 or later on the `PATH`) installs a symbolic-linked header through a small project. Without the patch's block the installed header is a link; with it the header is a regular file. The native check is the Windows Bazel workflow with this patch queued.
