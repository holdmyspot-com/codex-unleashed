# Sandbox helper runfiles paths

## Intent

The managed-proxy regression grants the sandbox access to the helper executable's
real directory when Bazel exposes it through runfiles symlinks. The routed command
can re-execute the helper while the test continues checking direct-egress denial.
Production sandbox permissions remain unchanged.

## Reference and base

- [Issue #68](https://github.com/holdmyspot-com/codex-unleashed/issues/68).
- Base: `rust-v0.162.1`, commit `092d3acd6bec3e3a14bdc7e7a2810ab628ab759d`.
- `canonical-sandbox-helper-fixture.patch` applies independently and changes
  only the existing managed-proxy fixture. It adds no feature flag or setting.

## Reproduction and verification

The original fixture grants access to the symlink's parent. The helper resolves
its executable to the real Bazel output path and cannot re-execute that path inside
the restricted filesystem. The regression requires working bubblewrap and user
namespaces; a skipped test does not verify the fix.

Run from the patched checkout with the release queue's build environment:

```sh
bazel test //codex-rs/linux-sandbox:linux-sandbox-all-test --test_arg=suite::managed_proxy::managed_proxy_mode_routes_through_bridge_and_blocks_direct_egress --test_arg=--exact
just fmt-check
```

The focused test executes and passes its routed-command and direct-egress
assertions. Full queue acceptance also requires `just bazel-test`, including the
other Linux sandbox fixture repairs. Hosted-platform checks remain separate gates.

## Upstream status

The affected helper-path code matches the declared upstream base. The reproduced
failure uses the downstream release queue under Bazel; unrelated fixture changes
exist elsewhere in the file. Newer upstream development behavior is unverified.
