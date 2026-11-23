# Root metadata fixture identities

- Applies to: the queue through `regenerated-outputs/regenerate-bindings-and-config-schemas.patch`, on upstream `openai/codex` tag `rust-v0.171.0`, commit `ccf9be8cc458bd6b8336fe84d68d8893f2691a5e`.
- Prerequisites: none; the patch changes only `codex-rs/linux-sandbox/tests/suite/root_metadata_tests.rs`.
- Tracking: https://github.com/holdmyspot-com/codex-unleashed/issues/63

## Intent

`root_metadata_symlinks_cannot_reopen_approved_denials` builds its fake root with an outer bubblewrap that forces
namespace UID/GID 0, then runs `codex-linux-sandbox`, whose own bubblewrap needs a nested UID map. Hosted Ubuntu 24.04
runners refuse that map (`bwrap: setting up uid map: Operation not permitted`), and the test returned early, which the
test harness reports as a pass. Upstream's runners have no system `bwrap`, so there the test returns earlier still.

The patch runs every case twice, with one fresh fixture per identity:

1. As namespace UID 0, as upstream does. Launcher trust and root-owned metadata take root-specific paths there. When
   the host refuses the nested UID map, only this pass is skipped, with the message
   `skipping root metadata test as namespace UID 0: nested UID map refused: ...`.
2. As the caller's own identity, which those hosts permit. This pass always runs every assertion.

All fixture contents, permission profiles, scripts and assertions are unchanged. Any other namespace failure still
skips the whole test with the original message, which `check-test-skips` reports as missing setup.
`CheckTestSkips` allows only the UID 0 pass's message.

## Reproduction

On a hosted `ubuntu-24.04` runner with the apt `bubblewrap` installed, run
`cargo test -p codex-linux-sandbox --test all root_metadata`. Without the patch the test prints
`skipping root metadata test: bwrap: setting up uid map: Operation not permitted` and passes without running an
assertion. Old main's native run `40172691443` showed the caller-identity variant passing on the same runner type.
