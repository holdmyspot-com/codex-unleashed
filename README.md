# Codex Unleashed

A public patch queue and patched binary distribution for OpenAI Codex.

> Warning: Unofficial, not affiliated with OpenAI.

[![License](https://img.shields.io/github/license/holdmyspot-com/codex-unleashed)](https://github.com/holdmyspot-com/codex-unleashed/blob/main/LICENSE)
[![Public patches](https://img.shields.io/endpoint?url=https://raw.githubusercontent.com/holdmyspot-com/codex-unleashed/main/docs/badges/public-patches.json)](https://github.com/holdmyspot-com/codex-unleashed/tree/main/patches)
[![Early-access patches](https://img.shields.io/endpoint?url=https://raw.githubusercontent.com/holdmyspot-com/codex-unleashed/main/docs/badges/early-access.json)](https://holdmyspot.com/codex-unleashed/)

**[Get early access to Codex fixes and features →](https://holdmyspot.com/codex-unleashed/)**

## Quickstart

### Install and run Codex CLI

Download the latest [public release](https://github.com/holdmyspot-com/codex-unleashed/releases/latest) and choose the archive for your platform:

- macOS Apple Silicon: `codex-package-aarch64-apple-darwin.tar.gz`
- macOS Intel: `codex-package-x86_64-apple-darwin.tar.gz`
- Linux ARM64: `codex-package-aarch64-unknown-linux-musl.tar.gz`
- Linux x86_64: `codex-package-x86_64-unknown-linux-musl.tar.gz`
- Windows ARM64: `codex-package-aarch64-pc-windows-msvc.tar.gz`
- Windows x86_64: `codex-package-x86_64-pc-windows-msvc.tar.gz`

Extract the archive, then run the included `codex` executable:

```shell
tar -xzf codex-package-<platform>.tar.gz
./codex
```

On Windows, extract the archive and run `codex.exe` from PowerShell:

```powershell
.\codex.exe
```

Then sign in with ChatGPT when prompted. Codex Unleashed uses the same CLI workflow and authentication as upstream Codex.

### What is Codex Unleashed?

- Important user-facing issues can take months to be fixed upstream, even when they are time-sensitive for real users and teams.
- This project bridges that gap by shipping small, targeted fixes for issues that matter to users now, not months later.
- Patches stay public, auditable, and temporary: they are carried here until the equivalent fix lands upstream, then removed.
- Subscribers get priority consideration for requested fixes and early access to completed patched builds before those fixes roll into the public release.

Public releases are available from the [latest GitHub Release](https://github.com/holdmyspot-com/codex-unleashed/releases/latest). To request a fix, [open an issue](https://github.com/holdmyspot-com/codex-unleashed/issues) with the affected version, reproduction steps, scope, and business impact.

## What To Expect

- Reviewed fixes only.
- No pull requests.
- No unpaid support queue.

## Early Access Channel

Subscribers pay US$12 per named developer per month, month-to-month, for priority consideration of requested fixes and early access to completed patched builds.

- A request does not guarantee that a fix will be implemented, or establish a delivery date.
- You only pay for months when early-release fixes are available. No fixes? Your subscription will be credited.
- Early-release fixes become eligible for public release at least 30 days after first customer delivery. Fixes that require substantial investment may remain in early access longer.
- Full terms: [Commercial Terms](docs/COMMERCIAL_TERMS.md)
- Subscribe: https://holdmyspot.com/codex-unleashed/

## How Releases Work

For every upstream Codex release, this repository publishes a corresponding patched release after applying the active patch queue, running the relevant checks, and rebuilding release artifacts.

Every release notes page lists:

- Upstream Codex version/tag
- Patch files applied
- Upstream commit SHA
- Build date
- Supported platforms
- Checksums
- A release manifest containing the upstream commit and patch hashes
- A reproduction script for independently rebuilding and checking an artifact

Release assets are published once. A later rebuild receives a new build number
and release tag instead of replacing an existing release.

### Independent verification

Download `release-manifest.json`, `reproduce-release.sh`, and the package for
your target from the same release. After installing the build prerequisites,
run:

```shell
bash reproduce-release.sh release-manifest.json \
  --target <rust-target> \
  --output-dir reproduced
```

The script fetches the exact upstream commit, checks every patch hash, applies
the patches from the recorded repository commit, rebuilds the package, and
compares its SHA-256 digest with the published manifest.

## Versioning

Patched releases add a vendor build number to the upstream version:

- Upstream `1.3.6` -> patched `1.3.6+25`

The `+25` suffix identifies the vendor build. Build numbers are assigned by the release workflow and distinguish later patched builds based on the same upstream version.

### Build information

Invoke `codex --build-info` to see the provider and upstream details for an installed build:

```text
Codex Unleashed build information

Codex CLI version: 0.155.0+25
Build number:      25

Upstream project:  OpenAI Codex
Upstream version:  0.155.0
Upstream source:   https://github.com/openai/codex

Provided by:       Codex Unleashed
Project:           https://github.com/holdmyspot-com/codex-unleashed
```

## Policies

- Commercial terms: [docs/COMMERCIAL_TERMS.md](docs/COMMERCIAL_TERMS.md)
- Support policy: [SUPPORT.md](SUPPORT.md)
- Security policy: [SECURITY.md](SECURITY.md)
- Patch queue rules: [patches/README.md](patches/README.md)

## License

This project uses the same license as upstream Codex. See [LICENSE](LICENSE).

## Contact

- GitHub: [@cowwoc](https://github.com/cowwoc)
- Email: `cowwoc2020@gmail.com`
