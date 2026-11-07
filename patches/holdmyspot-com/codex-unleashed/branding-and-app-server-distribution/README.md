# Codex Unleashed branding and build information

- Applies to: upstream `openai/codex` main, commit `dddc3c0a79528c4de8d9a20d51dd3bd89b02a3e4`.
- Prerequisites: the release queue through `issue-70/readable-commands.patch`. The CLI hunks share `MultitoolCli` with the earlier queue patches.
- Upstream status: fork-specific; not proposed upstream.

## Intent

Show the Codex Unleashed build number in `codex --version` and the session
header. Identify Codex Unleashed in the session header as an unofficial fork
of OpenAI Codex, with the complete project URL on its own line. The fork label
and URL align with the title, and the label has no trailing colon. The `/status`
header keeps the OpenAI Codex title and also displays the fork label and project
URL. The URL wraps on narrow terminals in both headers. Let users run
`codex --build-info` to see the upstream version and the provider.

## Feature configuration

None. Branding and build information are always available, and this patch adds
no `config.toml` property.

## Reproduction

Run `codex --version` and `codex --build-info`, then start an interactive
session and run `/status`. The version includes `+<build number>`; build
information identifies OpenAI Codex as upstream and Codex Unleashed as provider;
the session header shows the build number and identifies the unofficial fork.
The `/status` header shows the same fork label and complete project URL beneath
its title. Source builds without `CODEX_UNLEASHED_BUILD_NUMBER` show `dev`.

## Verification

```sh
CODEX_UNLEASHED_BUILD_NUMBER=15 just test -p codex-cli -p codex-tui \
  -E 'test(version_identifies_codex_unleashed_build) | test(build_info_reports_upstream_and_provider) | test(session_header_) | test(status::tests::)'
just fmt-check
```

Run the CLI build-information checks without `CODEX_UNLEASHED_BUILD_NUMBER` to
check the `+dev` fallback.

## Server distribution warning

- Patch: `warn-on-openai-app-server.patch`.
- Prerequisites: `add-branding-and-build-metadata.patch`.

The server user agent carries the Unleashed build number. The TUI warns when
the connected app-server identifies itself as OpenAI Codex, and compares
numeric Unleashed versions by release precedence, then build number, so a bare
equal release is older and a higher release stays newer.

```sh
just test -p codex-tui -p codex-login -p codex-app-server \
  -E 'test(update_versions) | test(status::remote_connection) | test(unleashed_openai_server_warning_snapshot) | test(user_agent_identifies_unleashed_server_build) | test(initialize_uses_client_info_name_as_originator)'
```

## Public daemon releases

- Patch: `daemon-public-releases.patch`.
- Prerequisites: `add-branding-and-build-metadata.patch`; the installers expect
  the published release layout (`rust-v<version>` tags, `install.sh`,
  `install.ps1`, `codex-package-<target>.tar.gz` and `SHA256SUMS`).

Daemon installs and updates fetch installers and packages only from
`holdmyspot-com/codex-unleashed` releases. Stable latest-channel selection
accepts numeric build suffixes such as `0.163.0+4`, development and prerelease
builds stay pinned, and failed installs keep the previous selection. The patch
drops the `releases.openai.com` mirror path and its tests, together with the
legacy-package fixture only those tests used.

```sh
python3 -m unittest discover -s scripts/install -p 'test_install_sh.py'
pwsh -NoProfile -File scripts/install/test_install_ps1.ps1
just test -p codex-app-server-daemon
```
