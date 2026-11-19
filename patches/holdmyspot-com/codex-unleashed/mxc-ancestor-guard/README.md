# Keep protected paths in place inside writable MXC roots

- Applies to: upstream `openai/codex` tag `rust-v0.167.0`, commit `086ccc833bc9cf01024ad2dd6af7dbd421b6bf9a`. The
  patch changes only `codex-rs/mxc-sandbox/src/policy.rs` and `policy_tests.rs`, which no earlier queue entry changes,
  so it applies after any prefix of the queue. It is queue entry 99 and is listed last.

## Intent

The Windows MXC launcher grants every writable root recursively. When a read-only or denied path (for example `.git`
or `.codex`) lies inside a writable root, a sandboxed command could still rename or delete a directory that contains
the protected path and so replace it. `build_request` now finds each existing protected path inside a writable root
and replaces the grant on each ancestor directory between the root and the protected path with a read-only guard of
that directory plus a writable grant for each child that is neither protected nor itself an ancestor. It repeats this
for the ancestor children. The protected path stays where it is and its siblings stay writable.

- A guarded directory that cannot be listed fails the launch with `PolicyError::EnumerateGuardedDirectory`, which
  names the directory.
- Directories that are unreadable and lie above a writable root stay ungranted, as before.
- A protected path that does not exist yet guards nothing. This is an accepted limitation: such a path is created by
  the next launch's own preparation step, and until then its existing ancestors can be renamed.
- The guard also covers a writable root that is the direct parent of a protected path. New entries directly in a
  guarded directory are blocked by the read-only guard. The user accepted this consequence.

## Verification

The new and updated unit tests are in `codex-rs/mxc-sandbox/src/policy_tests.rs`:

```sh
cargo test -p codex-mxc-sandbox
```

On the pinned `mxc-sdk` revision `b21120168d70befe12356411423266bfef168446` on a hosted `windows-11-arm` runner (run
`44324069858`), the new tests with the unpatched `policy.rs` pass 19 and fail 6 (three updated existing expectations
and three new tests), and with this patch all 26 pass. The same session drove the product launcher through 1,059
sandboxed operations. Without the patch, the operations disagreed with the expected results, including successful
renames of ancestors of protected paths. With the patch, no operation differed from its expected result. The other
platforms' unit tests, including the Unix-only broken-link case, have not been run.
