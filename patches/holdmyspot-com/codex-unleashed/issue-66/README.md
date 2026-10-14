# Empty workspace roots fixture dependencies

## Intent

The empty-workspace-roots regression exercises the command's file denial,
rather than accepting a failed sandbox launcher as successful coverage.

## Issue and scope

- Tracking: https://github.com/holdmyspot-com/codex-unleashed/issues/66
- Base: `rust-v0.162.1`, commit `092d3acd6bec3e3a14bdc7e7a2810ab628ab759d`.

The patch applies independently and changes only the exec-process test fixture.
It preserves PATH for bubblewrap lookup and supplies minimal read-only runtime
directories for the sandbox helper and command. Workspace roots remain empty.
The regression requires `cat` to exit with status 1 and report the excluded
file, while excluded contents never reach stdout and process streams close.
The original two-second event timeout remains in place. Production sandbox
behavior is unchanged. There is no feature flag or configuration property.

## Reproduction

Run the focused test with a dynamically linked helper and a bubblewrap launcher
selected from PATH. Its empty child environment prevents launcher lookup.
Merely preserving PATH is insufficient: missing runtime dependencies prevent
bubblewrap from executing the helper. An assertion accepting any nonzero exit
cannot distinguish either launcher failure from the intended command denial.

## Verification

Run from the patched checkout through `tooling/bin/codex-tooling run-local-rust`
with absolute project and checkout paths and separate compiler outputs for the
baseline and patched checkouts:

Apply the existing issue-47 `bash-snapshot-replay-startup.patch` and issue-49
`descriptor-fallback-fixture.patch` before running the full integration suite.
They repair separate Bash startup and descriptor-fallback failures. The focused
empty-workspace-roots regression applies and runs independently.

```sh
just test -p codex-exec-server --test exec_process remote_process_preserves_empty_workspace_roots
just test -p codex-exec-server --test exec_process
just fmt-check
```

The focused regression, exec-process integration suite, and maintained
formatting check must pass. Bring only the strengthened assertions to the
unpatched base to establish the missing outcome; retain its original fixture
environment and runtime policy for that baseline. A diagnostic trace can
identify launcher failures, but a longer diagnostic wait does not establish
that the original test passes and is not part of this patch.
