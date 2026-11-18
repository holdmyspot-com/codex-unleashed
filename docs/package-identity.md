# Verify package identity

Release maintainers use the Java `package-identity` command to check the upstream source version before building and
the compiled CLI identity after assembling a canonical package. In GitHub Actions, run
`.github/actions/setup-tooling` after checkout; it provides `CODEX_TOOLING` and its Bash launcher.

Before building, verify the source checkout without changing its Cargo manifest:

```sh
bash "$CODEX_TOOLING" package-identity source \
  --source-root /path/to/upstream-checkout --version 0.167.0 --build 17
```

The command requires `codex-rs/Cargo.toml` to contain exactly `0.167.0` in `workspace.package.version`. A different
version, missing field, malformed manifest, or build containing anything other than ASCII decimal digits fails with
exit status 1 and a diagnostic on standard error. The original build digits are preserved, including leading zeros.

After applying the patch queue and before building, record the workspace version in the lockfile and commit it:

```sh
bash "$CODEX_TOOLING" align-workspace-lock --source-root /path/to/upstream-checkout
```

Upstream's release commit sets `workspace.package.version` in `codex-rs/Cargo.toml` but leaves each workspace package in
`codex-rs/Cargo.lock` at `0.0.0`. Cargo would rewrite the lockfile on its first run, and `--locked` builds would fail.
The command sets the `version` line of every source-less lockfile package that is a workspace member or a
`workspace.dependencies` path package, and any `"name version"` dependency reference that names one, to the version
that package's manifest resolves to. It prints the number of changed packages, changes nothing when the lockfile
already matches (including when the manifest is at the development version `0.0.0`), and needs neither Cargo nor a
network. A glob in `workspace.members` or a malformed manifest fails with exit status 1 and leaves the lockfile
unchanged; invalid arguments exit with status 2.

When it changes the lockfile, the command commits `codex-rs/Cargo.lock` alone on top of HEAD, so the checkout is clean
again and the release records an ordinary source identity (`commit`, `tree`, `cargo_lock_sha256`). The commit is
reproducible: author and committer are Gili Tzabari <cowwoc2020@gmail.com>, both dates are the author date of the
HEAD it follows, and the message is `Align workspace package versions in Cargo.lock with the release version`. Two
runs on checkouts of the same HEAD therefore give the same commit and tree. When nothing changes, no commit is made,
so a release whose queue already aligned the lockfile keeps the HEAD it recorded. Other working-tree changes are not
staged, and the source identity still rejects them.

After assembling the package, verify its actual entrypoint:

```sh
bash "$CODEX_TOOLING" package-identity binary \
  --package-dir /path/to/extracted-package --version 0.167.0 --build 17 --output /path/to/version.txt
```

The package must have `codex-package.json` with integer `layoutVersion` equal to `1` and an existing CLI `entrypoint`
of `bin/codex` or `bin/codex.exe`. These fields follow the canonical upstream
[package layout](https://github.com/openai/codex/blob/086ccc833bc9cf01024ad2dd6af7dbd421b6bf9a/scripts/codex_package/layout.py).
The command runs that executable with `--version` and `--build-info`. Both processes must exit successfully.

For this example, `--version` must print exactly `codex-cli 0.167.0+17` followed by the line
`(See --build-info for more information)`, with at most one final newline. The patched CLI prints both lines (see
`codex-rs/cli/tests/build_info.rs` in the patch queue). The build information must contain each required field exactly once:

```text
Codex CLI version: 0.167.0+17
Build number:      17
Upstream version:  0.167.0
Provided by:       Codex Unleashed
```

Spacing around the build information labels and values may vary; other build information fields are allowed. The
version banner accepts either an LF or CRLF line separator and final newline. It does not accept a missing, different,
repeated or additional line, or extra spaces.

Only after every check succeeds does the command atomically replace `--output` with the version line (the first banner
line and its line terminator), which is what the release receipt's `observed_cli_version` records. Its
parent directory must already exist. Validation failure returns exit status 1, prints a diagnostic on standard error,
and leaves any existing output unchanged. A successful command returns exit status 0 without printing proof on standard
output. Run the binary command on a runner that can execute the package's target platform.
