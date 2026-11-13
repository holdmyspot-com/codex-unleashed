# Verify package identity

Release maintainers use `.github/scripts/package-identity.py` with Python 3.11 or newer to check the upstream source
version before building and the compiled CLI identity after assembling a canonical package.

Before building, verify the source checkout without changing its Cargo manifest:

```sh
python3 -I .github/scripts/package-identity.py source \
  --source-root /path/to/upstream-checkout --version 0.167.0 --build 17
```

The command requires `codex-rs/Cargo.toml` to contain exactly `0.167.0` in `workspace.package.version`. A different
version, missing field, malformed manifest, or build containing anything other than ASCII decimal digits fails with
exit status 1 and a diagnostic on standard error. The original build digits are preserved, including leading zeros.

After assembling the package, verify its actual entrypoint:

```sh
python3 -I .github/scripts/package-identity.py binary \
  --package-dir /path/to/extracted-package --version 0.167.0 --build 17 --output /path/to/version.txt
```

The package must have `codex-package.json` with integer `layoutVersion` equal to `1` and an existing CLI `entrypoint`
of `bin/codex` or `bin/codex.exe`. These fields follow the canonical upstream
[package layout](https://github.com/openai/codex/blob/086ccc833bc9cf01024ad2dd6af7dbd421b6bf9a/scripts/codex_package/layout.py).
The command runs that executable with `--version` and `--build-info`. Both processes must exit successfully.

For this example, `--version` must print exactly `codex-cli 0.167.0+17`, with at most one final newline. The build
information must contain each required field exactly once:

```text
Codex CLI version: 0.167.0+17
Build number:      17
Upstream version:  0.167.0
Provided by:       Codex Unleashed
```

Spacing around the build information labels and values may vary; other build information fields are allowed.
The version banner accepts either an LF or CRLF final newline. It does not accept additional lines or spaces.

Only after every check succeeds does the command atomically replace `--output` with the captured version stdout.
Its parent directory must already exist. Validation failure returns exit status 1, prints a diagnostic on standard
error, and leaves any existing output unchanged. A successful command returns exit status 0 without printing proof on
standard output. Run the binary command on a runner that can execute the package's target platform.
