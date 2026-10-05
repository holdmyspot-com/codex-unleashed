# Codex Unleashed branding and build information

- Branding patch applies to: upstream `openai/codex` `rust-v0.159.2`, commit `ff6aec96948b70d94983af2641a6b67c94faeff5`

## Intent

Show the Codex Unleashed build number in `codex --version` and the session
header. Identify Codex Unleashed in the session header as an unofficial fork
of OpenAI Codex, with the complete project URL on its own line. The fork label
and URL align with the title, and the label has no trailing colon. The `/status`
header keeps the OpenAI Codex title and also displays the fork label and project
URL. The URL wraps on narrow terminals in both headers. Let users run
`codex --build-info` to see the upstream version and the provider.

## Feature configuration

### Codex feature flag

None. Branding and build information are always available.

### Project-specific `config.toml` property

None. This patch adds no `config.toml` property or section.

## Reproduction

Run `codex --version` and `codex --build-info`, then start an interactive
session and run `/status`. The version includes `+<build number>`; build
information identifies OpenAI Codex as upstream and Codex Unleashed as provider; the session header
shows the build number and identifies the unofficial fork. The `/status` header
shows the same fork label and complete project URL beneath its title.
Source builds without a release build number show `dev` as the build number.

## Verification

The CLI tests check `--version` and `--build-info`. The TUI tests check the
session header and `/status` header, including the full URL at narrow widths
and the raw transcript. Run them from `codex-rs/` with a fixed build number as
the test input:

```sh
export RUST_MIN_STACK=16777216
CODEX_UNLEASHED_BUILD_NUMBER=15 cargo test -p codex-cli --test build_info
CODEX_UNLEASHED_BUILD_NUMBER=15 cargo test -p codex-tui --lib session_header_
CODEX_UNLEASHED_BUILD_NUMBER=15 cargo test -p codex-tui --lib status::tests::
```

The checks expect the build number, provider, and fork identity in their
respective outputs. Run the CLI tests without
`CODEX_UNLEASHED_BUILD_NUMBER` to check the `+dev` fallback.

## Formatting

Run `cargo fmt --package codex-cli --package codex-tui -- --check` from
`codex-rs/`.

## Package and executable version agreement

[match-package-and-executable-version.patch](match-package-and-executable-version.patch)
applies independently to upstream `rust-v0.160.0`, commit
`a956835d020762cb2b570053af06f643a11c0ecc`. Use it with the branding patch in
this directory so the executable and package metadata identify the same build.

### Intent

Packages record the full Unleashed executable version, including the numeric
build suffix or `+dev`. This lets the CLI install its own package as the daemon
without a package/executable version mismatch. Explicit `--package-version`
overrides retain their meaning. A version-query script lets this repository’s
release packager use the same version policy.

### Feature configuration

This packaging fix is always active and has no feature flag or project-specific
`config.toml` properties. `CODEX_UNLEASHED_BUILD_NUMBER` supplies the build number
for compilation and packaging; without it, both use `dev`.

### Reproduction

Build a branded executable with `CODEX_UNLEASHED_BUILD_NUMBER=41`, then assemble
its package with the same environment variable. The manifest version and
executable version both identify `0.160.0+41` at this upstream base.

### Verification

Run from the patched upstream checkout root:

```sh
CODEX_REPO_ROOT="$PWD" python3 -m unittest discover -s scripts/codex_package -p 'test_*.py'
just fmt-check
```

The checks cover branded defaults, the development fallback, explicit version
overrides, the version-query script, and the existing package layout and
archive behavior. Run this repository's entry-point checks separately:

```sh
python3 -m unittest discover -s scripts/codex_package -p test_upstream_package_version.py
```

This repository’s packager queries the version helper in
`CODEX_PACKAGE_WORKSPACE_ROOT` and retains its own license bundling and archive
timestamp handling.
The Windows packaging job supplies the same build number as compilation.

### Upstream status

This is a distribution-specific change for Codex Unleashed branding.

## Public daemon releases

[daemon-public-releases.patch](daemon-public-releases.patch) applies independently
to upstream `rust-v0.160.0`, commit
`a956835d020762cb2b570053af06f643a11c0ecc`.

The `/daemon` option **Install latest public stable** keeps its existing text
and installs the latest public release from
[holdmyspot-com/codex-unleashed](https://github.com/holdmyspot-com/codex-unleashed/releases/latest).
Manual and automatic daemon updates use the same repository. The installers
accept numeric vendor build suffixes such as `0.160.0+39` and use the release's
`SHA256SUMS` to verify platform packages. Download or validation failures leave
the selected installation unchanged. **Use this CLI build** still copies the
local CLI package and pins the daemon against automatic updates.

The release workflow publishes the patched `install.sh` and `install.ps1`
from the exact upstream commit with the active patch queue applied. It includes
both scripts in the release manifest and checksums. Existing immutable releases
are unchanged; the updater requires a release containing these installer assets.

### Feature configuration

This distribution patch is always active. It has no Codex feature flag and no
project-specific `config.toml` properties. Existing daemon automatic-update
settings and explicit version pins retain their meaning.

### Verification

Run from the patched upstream checkout root:

```sh
python3 -m unittest discover -s scripts/install -p 'test_*.py'
pwsh -NoProfile -File scripts/install/test_install_ps1.ps1
just test -p codex-app-server-daemon
just fmt-check
```

The Unix installer tests check repository requests, package checksums, vendor
versions, and preservation of the selected package after a failed download.
The PowerShell check exercises the installer's actual release selection and
version-validation functions with controlled metadata. The daemon suite covers
installer fetching, vendor eligibility, package seeding, pinning, cancellation,
restart, and update recovery.

Run the release packaging checks from this repository root:

```sh
python3 -m unittest discover -s scripts/codex_package -p test_release_installers.py
python3 -m unittest discover -s scripts/codex_package -p test_release_workflow.py
```

These checks execute installer staging, compare the staged bytes, and verify
that missing or unpatched inputs preserve the release directory.

### Upstream status

This is a distribution-specific change. Upstream Codex continues to use
OpenAI's public release channel.

## Connected OpenAI app-server warning

[warn-on-openai-app-server.patch](warn-on-openai-app-server.patch) applies to upstream `rust-v0.160.0`, commit
`a956835d020762cb2b570053af06f643a11c0ecc`. The branding patch in this directory
supplies the client vendor version and precedes this patch in the queue.

A numeric Codex Unleashed build connected to a server without build metadata
at the same or an older release warns that its app-server identifies itself
as OpenAI Codex and needs a Codex Unleashed app-server to use Unleashed server
features. Development builds retain their distribution warning for unmarked
servers. The warning covers local daemons
and explicit remote connections. Local daemon notices offer `/daemon`; remote
servers need updating on their host. The existing
`tui.show_server_version_notice` setting controls the notice, and reconnecting
to the same server version does not repeat it.

Unleashed servers report the numeric vendor build or `dev` in their existing
initialization user agent, matching the CLI package version. Matching client and server builds do not
show a version warning. Unknown or malformed versions do not produce a provider
warning. Older unmarked forks cannot be distinguished conclusively from upstream;
the notice describes the identity the server reports, rather than authenticating
its publisher. Older servers with numeric vendor metadata keep the ordinary
version notice. Numeric Unleashed clients compare release precedence first, then
the numeric build number. At the same release, a missing build suffix is older
than a numeric Unleashed build. A higher server release is newer even without a
build suffix and receives a newer-version notice without upgrade guidance.

### Feature configuration

This distribution warning has no new feature flag or project-specific setting.
The existing upstream notice setting retains its default and behavior.

### Verification

Run from the patched upstream checkout root:

```sh
just test -p codex-app-server -p codex-login -p codex-app-server-client -p codex-tui \
  --lib --test all \
  -E '(package(codex-app-server) & test(initialize_uses_client_info_name_as_originator)) | (package(codex-login) & test(default_client)) | (package(codex-app-server-client) & test(remote_)) | (package(codex-tui) & (test(update_versions) | test(status::remote_connection) | test(server_version) | test(unleashed_)))'
just fmt-check
```

These checks verify the producer identifier, its preservation across a WebSocket
initialization response, the real app-server initialization response, provider
classification, settings and repeat suppression, local update guidance, and the
rendered warning snapshot. Embedded sessions do not show connection notices.

### Upstream status

This is a distribution-specific warning and server identity marker.
