# Local npm consumer verification

This maintainer check assembles `@holdmyspot/codex-unleashed` and its six native CLI packages from the current release's extracted `codex-package-<target>.tar.gz` archives. It publishes them to a fresh Verdaccio listening on an automatically assigned loopback port, installs the selector by its npm name and exact version in a new consumer, and executes the installed `codex --version` and `codex --help` through npm's executable registration.

The native package contains the complete canonical package tree, including its helpers and resources. The verifier compares every regular file under the selected source and installed `codex-resources/voice` trees, including executable roles on Unix, and rejects symbolic links in either voice tree. After that comparison and the CLI checks pass, it copies the complete installed canonical package to `--installed-package-output`. Supply a destination that does not already exist; the verifier rejects an existing file, directory or symbolic link before starting Verdaccio. On failure, it removes only an incomplete output directory it created. The caller owns the completed output and removes it after installed-package verification. The app-server archive remains a separate distribution: upstream has no app-server npm selector. The SDK, responses proxy and early-access npm families are excluded. The verification has no configurable publication registry; its Verdaccio has no upstream proxies.

Use Node 24 or later and the verification tool lock in `.github/npm-verification`, which selects **Verdaccio 7.1.0** and **npm 12.2.2**. To prepare a fresh tools directory named by `NPM_TOOLS`, copy that directory's `package.json` and `package-lock.json` into it and run `npm ci --prefix "$NPM_TOOLS" --ignore-scripts --no-audit --no-fund`. Set `NPM_CLI` to `$NPM_TOOLS/node_modules/npm/bin/npm-cli.js`. Downloading these tools is separate from publishing the product. Keep the checkout intact so the script can read the current release matrix, launcher and legal documents.

The release workflow hands the independently verified CLI archives to all six native installer jobs through one immutable run artifact. Each job checks its one-line installer and runs this npm consumer check before the release can be marked latest. It retains consumer receipts, the release manifest and checksums for three days. Tool installation falls back to the npm mirror with the same lockfile integrity checks; that fallback still requires a hosted verification with the primary unavailable.

Place each trusted release archive's extracted contents directly under `<packages>/<target>/`, for all six targets in `.github/release-matrix.json`. Each target directory must contain `codex-package.json`, `bin/`, `codex-resources/` and `codex-path/`. All metadata must identify the `codex` variant and the same stable version, optionally followed by a numeric vendor build such as `0.167.0+41`. npm receives that version as `0.167.0-41`.

On each supported native hosted runner, run the following command. The environment variables identify already supplied inputs: `PACKAGES` is the extracted package root; `NPM_TOOLS` contains pinned Verdaccio; `NPM_CLI` is npm's `bin/npm-cli.js`; `RUNNER_TEMP` is the runner's existing temporary directory; `NPM_EVIDENCE` names a new directory for this execution's receipts; `NPM_INSTALLED_PACKAGE` names a destination that does not yet exist for the installed canonical package.

```bash
node .github/scripts/verify-local-npm.mjs \
  --package-root "$PACKAGES" \
  --tools "$NPM_TOOLS" \
  --npm-cli "$NPM_CLI" \
  --scratch-root "$RUNNER_TEMP" \
  --evidence-dir "$NPM_EVIDENCE" \
  --installed-package-output "$NPM_INSTALLED_PACKAGE"
```

The check rejects incomplete or mismatched package identities, a different Verdaccio version, package URLs outside its local registry, an installed executable differing from the selected release binary, missing, added or changed voice files, changed Unix executable roles, an existing installed-package output, version output that is not exactly `codex-cli VERSION` followed by `(See --build-info for more information)`, or any failed publish, install or smoke command. It removes the registry, generated credentials, staged packages, npm cache and installed consumer when the command finishes. The caller retains the completed installed canonical package and removes it after verification. The caller also retains the evidence directory, including logs and `result.json`; a failed check leaves no passing result. The result includes `voice_resources: "verified"` after the complete voice-tree comparison and package copy. Use new evidence and installed-package output paths on retry.

Run the check on Linux x64 and ARM64, macOS x64 and ARM64, and Windows x64 and ARM64. A pass on one runner verifies only that runner's native package selection and executable. Retain the original release archive identities alongside the six result files. Native package building and these real release executable checks run on remote runners.

For local development, run the bounded unit and launcher fixture checks:

```bash
node --test .github/scripts/test-verify-local-npm.mjs
```

The fixture supplies a small Node program in place of Codex to check argument and exit-status forwarding, voice-resource preservation and complete-package handoff. It does not establish release acceptance, successful Verdaccio publication or a real installation. D4 acceptance requires the six actual native consumer results above; this document does not mark that gate passed.
