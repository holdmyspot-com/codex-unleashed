# Bazel integration compiler environment

## Intent

Bazel integration tests receive the generated compiler environment files supplied
to their library. The Linux sandbox's test and library agree on the bundled
bubblewrap digest, so its fallback fixture honors the existing release-build skip.
Production digest verification remains enabled.

## Reference and base

- [Issue #69](https://github.com/holdmyspot-com/codex-unleashed/issues/69).
- Base: `rust-v0.163.0`, commit `83086188ae0614bd882818ff1aa23f3194962012`.
- `integration-compiler-environment.patch` applies independently. It updates
  sharded, unsharded, and Windows-cross integration-test compilation and adds a
  Linux sandbox compiler-environment regression. It adds no feature configuration.

## Reproduction and verification

Without propagation, the library enforces its release digest while the integration
test sees no digest. The fallback fixture installs an unrelated system executable
and correctly fails the library's digest verification. The added regression fails
directly at the missing compiler setting when run through Bazel. Cargo builds
retain their existing optional compiler-environment behavior.

```sh
bazel test //codex-rs/linux-sandbox:linux-sandbox-all-test --test_arg=suite::managed_proxy::bazel_tests_receive_bundled_bwrap_digest --test_arg=--exact
bazel test //codex-rs/linux-sandbox:linux-sandbox-all-test
just fmt-check
```

The focused metadata assertion passes. Full Linux sandbox acceptance also needs
the release queue's other fixture repairs, including issue #68. The existing
release-digest guard still skips the system-binary substitution when a release
digest is enforced. Full `just bazel-test`, Windows-cross compilation, and hosted
platform acceptance remain required queue gates.

## Upstream status

The compiler-environment declarations match the declared upstream base. Behavioral
reproduction uses the downstream release queue, which also carries unrelated
dependency-alias adaptations in the macro. Newer upstream behavior is unverified.
