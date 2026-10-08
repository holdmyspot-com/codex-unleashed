# Codex Unleashed branding and build information

- Branding patch applies to: upstream `openai/codex` `rust-v0.161.0`, commit `979011409de0a60b52f179721948e65531d26144`

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
and the raw transcript. Run the maintained checks from the patched checkout root:

```sh
CODEX_UNLEASHED_BUILD_NUMBER=15 just test -p codex-cli -p codex-tui \
  -E 'test(version_identifies_codex_unleashed_build) | test(build_info_reports_upstream_and_provider) | test(session_header_) | test(status::tests::) | test(exec_server_help_documents_remote_options) | binary(app_server_daemon)'
just fmt-check
```

The checks expect the build number, provider, and fork identity in their
respective outputs. Run the CLI build-information checks without
`CODEX_UNLEASHED_BUILD_NUMBER` to check the `+dev` fallback.
The daemon fixtures use a stable installer release and package metadata that
matches the branded executable, including its build suffix.
Startup, voice-caption, status-copy, and update fixtures also include the branded
header and version. Their snapshots use the default `dev` build number. Run these
checks without inherited terminal color overrides:

```sh
env -u CODEX_UNLEASHED_BUILD_NUMBER -u WT_SESSION -u NO_COLOR \
  just test -p codex-tui \
  -E 'test(startup_draft::) | test(startup_frame_tests::) | test(realtime_requests::) | test(history_cell::tests::) | test(update_prompt::tests::) | test(slash_copy_picker_copies_status_fields_and_preserves_source_after_copying) | test(status_snapshot_uses_default_reasoning_when_config_empty)'
```

## Package and executable version agreement

The JDK 27 release tooling records the full Unleashed executable version,
including the numeric build suffix or `+dev`. Package assembly and the
`get-codex-package-version <checkout>` command share the same version provider.
Explicit `--package-version` overrides retain their meaning.

`CODEX_UNLEASHED_BUILD_NUMBER` supplies the build number for compilation and
packaging. When absent, both use `dev`; an explicitly empty value remains empty.
The Windows packaging job supplies the same build number as compilation. The
packager reads the workspace selected by `CODEX_PACKAGE_WORKSPACE_ROOT` and
retains license bundling and archive timestamp handling.

Run the maintained checks from this repository root with JDK 27:

```sh
tooling/mvnw verify -Dtest=PackageVersionCommandTest,PackageVersionsTest,PackageCommandTest,PackageCommandRuntimeTest \
  -Dsurefire.failIfNoSpecifiedTests=false
```

The checks cover branded defaults, the development fallback, explicit version
overrides, the package-version query, and actual package assembly through a
fresh bundled runtime. The branding patch supplies the executable's matching
version; the package policy resides in this repository's Java tooling.

## Public daemon releases

[daemon-public-releases.patch](daemon-public-releases.patch) applies independently
to upstream `rust-v0.161.0`, commit
`979011409de0a60b52f179721948e65531d26144`.

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
tooling/mvnw verify
```

These checks execute installer staging, compare the staged bytes, and verify
that missing or unpatched inputs preserve the release directory.

### Upstream status

This is a distribution-specific change. Upstream Codex continues to use
OpenAI's public release channel.

## Connected OpenAI app-server warning

[warn-on-openai-app-server.patch](warn-on-openai-app-server.patch) applies to upstream `rust-v0.161.0`, commit
`979011409de0a60b52f179721948e65531d26144`. The branding patch in this directory
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
