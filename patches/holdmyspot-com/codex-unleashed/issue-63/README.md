# Root metadata fixture launcher

## Intent

The root metadata sandbox test runs with a protected bubblewrap executable even
when the host installs it outside `/usr/bin` or `/bin`.

## Issue and scope

- Tracking: https://github.com/holdmyspot-com/codex-unleashed/issues/63
- Base: `rust-v0.163.0`, commit `83086188ae0614bd882818ff1aa23f3194962012`.

The patch applies independently and changes only the test fixture. It binds the
selected launcher at `/bwrap`, exposes its location on the fixture PATH, and
protects that dependency with a read-only policy entry. All metadata-denial,
approved-write, and reopened-subpath assertions remain in place. Production
launcher selection is unchanged. There is no feature flag or configuration.

## Reproduction

Put a usable bubblewrap executable on PATH outside the fixture's `/usr` and
`/bin` trees. The test selects it for the outer namespace, but the inner sandbox
cannot find it. Merely exposing a launcher does not suffice when namespace UID 0
and the approved filesystem policy permit replacing it: trusted-executable
lookup correctly rejects that candidate.

## Verification

Run from the patched checkout through `tooling/bin/codex-tooling run-local-rust`
with absolute project and checkout paths and separate compiler outputs for each
checkout:

On WSL, apply the existing issue-48 `wsl-explicit-pid-procfs.patch` before
running the full integration suite. That patch repairs the separate procfs
fallback assertion; the focused root-metadata regression applies and runs
independently on the declared base.

```sh
just test -p codex-linux-sandbox --test all root_metadata_symlinks_cannot_reopen_approved_denials
just test -p codex-linux-sandbox --test all
just fmt-check
```

The focused test must execute and pass with an available bubblewrap executable
and user-namespace support. The integration suite and formatting must pass;
platform-specific tests retain their own skip conditions. On the declared base,
the same focused command must fail because the fixture cannot find a usable
launcher. An unavailable host prerequisite or a skipped focused test does not
establish the regression or its correction.
