# MITM path-policy fixture without DNS

## Intent

The repository-path policy regression test runs without depending on public DNS
availability.

## Issue and scope

- Tracking: https://github.com/holdmyspot-com/codex-unleashed/issues/65
- Base: `rust-v0.162.1`, commit `092d3acd6bec3e3a14bdc7e7a2810ab628ab759d`.

The patch applies independently and changes only the path-policy fixture host.
A public IP literal reaches the MITM hook policy without resolving a hostname.
The fixture makes no outbound connection. Every allowed-path, denied-path,
response-reason, and telemetry assertion remains in place. Production network
checks are unchanged. There is no feature flag or configuration property.

## Reproduction

Run the focused test with DNS unavailable in an isolated mount namespace.
Hostname resolution rejects `github.com` before the hook policy executes, so
the response reports `blocked-by-allowlist` instead of `blocked-by-mitm-hook`.
Do not change the host's resolver configuration: bind a temporary resolver
file only inside the verification process's mount namespace.

## Verification

Run from the patched checkout through `tooling/bin/codex-tooling run-local-rust`
with absolute project and checkout paths and separate compiler outputs for the
baseline and patched checkouts:

```sh
just test -p codex-network-proxy --lib
just test -p codex-network-proxy --lib mitm_policy_blocks_encoded_path_traversal_for_repository_allowlist
just fmt-check
```

Run the full unit suite with its normal resolver configuration first, so Cargo
can prepare dependency metadata; other upstream tests also exercise hostname
policies. The focused test must then pass with DNS unavailable. On the declared
base, that same isolated check must fail at the path-policy assertion. The full
unit suite and maintained formatting check must pass. An unavailable resolver
mount or compilation failure does not
establish the regression or its correction.
