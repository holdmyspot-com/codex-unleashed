# Codex Unleashed

A patched build of [OpenAI Codex](https://github.com/openai/codex). Each release applies this repository's [patch queue](patches/README.md) to one stable upstream release.

> **Unofficial:** This distribution is not affiliated with or endorsed by OpenAI.

## Install

Linux and macOS:

```sh
curl -fsSL https://github.com/holdmyspot-com/codex-unleashed/releases/latest/download/install.sh | bash
```

Windows:

```powershell
powershell -ExecutionPolicy ByPass -c "irm https://github.com/holdmyspot-com/codex-unleashed/releases/latest/download/install.ps1 | iex"
```

To install a specific release, replace `latest/download` with `download/<tag>`, for example `https://github.com/holdmyspot-com/codex-unleashed/releases/download/rust-v0.167.0%2B1/install.sh`. Each release's notes link its upstream release and applied patches.

## Releases

Releases are named `<upstream version>+<build>` and tagged `rust-v<upstream version>+<build>`. The [automatic release workflow](.github/workflows/upstream-auto-release.yml) detects each new stable upstream release, applies the patch queue, runs the lint, test and release-build gates, and publishes only when all of them pass.

## License and support

Company-owned additions are licensed under the [HoldMySpot Source-Available License](LICENSE.md) ([HTML edition](docs/LICENSE.html)); upstream OpenAI Codex remains under its [Apache License 2.0](licenses/openai-codex/LICENSE-APACHE-2.0). Distributions must include the License Terms and the Explanation. See the [Terms of Service](docs/terms.html), [Privacy Notice](docs/privacy.html), [commercial terms](docs/COMMERCIAL_TERMS.md), [support policy](SUPPORT.md), [security policy](SECURITY.md), [code of conduct](CODE_OF_CONDUCT.md) and [maintainer guide](docs/MAINTAINER.md).
