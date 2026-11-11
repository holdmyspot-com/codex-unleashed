# Create the WinGet daemon fixture's CODEX_HOME directory

- Issue: none filed; this repairs the deferred CP1 Windows fixture failure recorded in [windows-native-runtime-results.json](../../../../docs/evidence/windows-native-runtime-results.json).
- Applies to: upstream `openai/codex` tag `rust-v0.167.0`, commit `086ccc833bc9cf01024ad2dd6af7dbd421b6bf9a`. No downstream prerequisites.

## Intent

`winget_package_starts_and_reselects_daemon` sets `CODEX_HOME` to a path inside its temporary directory but never creates it. A set `CODEX_HOME` must name an existing directory, so every daemon command fails with `failed to resolve CODEX_HOME` on a non-elevated runner before the daemon behavior runs. Elevated runners return before the assertions. The patch creates the directory; the daemon behavior under test is unchanged.

## Verification

Red: unpatched runs `40758471648` (x86-64) and its ARM64 counterpart in the evidence file fail with that error as a standard user.

Green, from the patched upstream `codex-rs` directory on Windows as a non-elevated user:

```sh
cargo nextest run -p codex-cli --test app_server_daemon_windows -E 'test(=winget_package_starts_and_reselects_daemon)'
```

The test is expected to pass on x86-64 and ARM64.
