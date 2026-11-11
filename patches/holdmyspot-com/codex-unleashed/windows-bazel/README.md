# Windows Bazel Opus headers

- Applies to: upstream `openai/codex` tag `rust-v0.167.0`, commit `086ccc833bc9cf01024ad2dd6af7dbd421b6bf9a`.
- Prerequisites: none. The patch changes the `opusic-sys` crate annotation in `MODULE.bazel` and adds a crate patch to `patches/`; no other patch in the queue touches either.
- Upstream status: backport of [openai/codex#59752](https://github.com/openai/codex/pull/59752) (the crate annotation, commit `09358089b`) and [openai/codex#59972](https://github.com/openai/codex/pull/59972) (the crate patch, commit `8842c09a7`). Neither is in a stable release yet (`rust-v0.167.2` lacks them); drop this patch when the queue's upstream base includes both. The failure also occurs on unpatched upstream `rust-v0.167.0` (Windows Bazel run `42953543241`).

## Intent

A native Windows Bazel build of `opusic-sys` finishes its CMake install with the Opus headers intact, so the crate's build script output tree validates and its dependents build.

## Cause

Bazel on Windows runs with `startup --windows_enable_symlinks`, which presents the crate's sources to the build script as symbolic links. CMake installs a symbolic link as a symbolic link, so each installed `include/opus/*.h` pointed at `\??\D:\o\execroot\_main\external\rules_rs++crate+crates__opusic-sys-0.7.7\opus\include\...`. Bazel failed the action with `_bs.out_dir: child include/opus/opus_types.h is a dangling symbolic link`. The retained evidence of run `42953543241` records all five installed headers as links.

An earlier version of this patch replaced the installed links after CMake's install step, using `file(REAL_PATH)`. A live session on the Windows runner (run `43075770116`) showed that `REAL_PATH` returns the link's own path for a target with the `\??\` prefix, so that version deleted each link and then failed to copy it.

## Scope

`opus-installed-headers.patch` adds upstream's `patches/opusic_sys_install_headers.patch`, exports it from `patches/BUILD.bazel`, and applies it to `opusic-sys` through the crate annotation (`patch_args = ["-p1"]`). The crate patch makes Opus's `CMakeLists.txt` copy each public header into the build directory with `configure_file(... COPYONLY)`, which reads through a link, and installs those copies, so no link reaches the install prefix. The crate patch applies unchanged to `opusic-sys` 0.7.7, the version the dependency upgrades select. The patch copies no dependency source and leaves `MODULE.bazel.lock` unchanged, as upstream's commits do.

## Verification

`python3 .github/scripts/test-opus-installed-headers.py` (with CMake 3.21 or later and a C compiler on the `PATH`) takes the crate patch's change out of this patch and applies it to a small project that installs a symbolic-linked header the way Opus does. Without the change the installed header is a link; with it the header is a regular file. In one-off run `43100801026` it failed against the previous version of this patch and passed against this one, on Ubuntu and on Windows, where the unpatched project installed an unreadable link as in the Bazel failure. In the live session on run `43075770116`, with this patch and upstream's toolchain file, `opusic-sys` built under Windows Bazel. The native check is the Windows Bazel workflow with this patch queued.
