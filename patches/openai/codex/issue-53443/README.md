# Native Windows Bazel SDK environment

## Intent

Native Windows Bazel builds use the initialized Microsoft compiler and SDK environment.

## Reference and upstream base

- [Canonical reference](https://github.com/openai/codex/pull/53443).
- Base: rust-v0.162.1 (`092d3acd6bec3e3a14bdc7e7a2810ab628ab759d`).

## Scope

The `ci-windows-msvc` configuration registers both native MSVC toolchains and forwards the SDK environment to target and host actions. The downstream Windows Bazel workflow selects this configuration and accepts the Microsoft runtime license.

## Reproduction

Run the complete native MSVC Bazel test entry point on the declared upstream base. Native target and host actions lack the required compiler/SDK environment and fail with missing SystemRoot or SDK paths.

## Verification

- From the patched checkout root, `just fmt-check` passes.
- On a native Windows runner, initialize MSVC and run the maintained Bazel CI test entry point with `--config=ci-windows-msvc` and `--repo_env=BAZEL_MSVC_RUNTIME_VISUAL_STUDIO_EULA=1`; the complete suite passes without missing SystemRoot or SDK failures.

## Upstream status

This narrowly backports upstream commit `3ece80b784c7629230c38927b3a8f85fc059c66d`, which is absent from the declared stable base.
