# Windows legacy sandbox deletion regression

## Intent

Windows CI excludes only the known failing legacy sandbox deletion regression.
The sandbox security defect remains present; this patch does not fix it.
The test source, assertions, and explicit reproduction command remain available.

## Reference and upstream base

- [Canonical upstream issue](https://github.com/openai/codex/issues/32915).
- Base: `rust-v0.163.0`, commit `83086188ae0614bd882818ff1aa23f3194962012`.
- Apply the preceding issue-61 Windows CI skip-filter configuration first.

## Reproduction

A legacy restricted token can delete an outside file or protected directory when
its ordinary user permissions grant deletion through the parent directory.
Ordinary file writes can remain denied while this deletion path succeeds.

On a native Windows host, run the retained regression explicitly:

```sh
bazel test --config=ci-windows-msvc --platforms=//:windows_x86_64_msvc --nocache_test_results --test_filter=unified_exec::tests::legacy_workspace_write_delete_is_limited_to_writable_roots //codex-rs/windows-sandbox-rs:windows-sandbox-rs-unit-tests
```

The explicit test filter bypasses the normal CI skip list and reproduces the
security assertion failure on affected hosts. A failure to compile or start the
test does not establish reproduction.

## Scope and verification

`legacy-delete-ci-exclusion.patch` adds the fully qualified regression name to
both the native Windows and Windows-cross CI skip lists. It preserves every
existing filter and all other tests. Ordinary local runs retain the regression.

Run `just fmt-check`, `just bazel-lock-check`, and the Windows unit-test target
with the normal CI configuration and no explicit test filter. The suite must
pass with the named regression excluded. Run the explicit reproduction command
separately when checking an upstream repair.

## Upstream status

The issue tracks the parent `FILE_DELETE_CHILD` deletion path. Each CI exclusion
has a `WORKAROUND` comment linking it. Remove the exclusion when the retained
regression passes without it on the selected upstream release and affected
native Windows ACL configurations.
