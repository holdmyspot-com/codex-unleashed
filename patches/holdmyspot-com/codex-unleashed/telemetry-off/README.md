# Turn feedback and analytics off by default and point the update check at our releases

- Applies to: upstream `openai/codex` tag `rust-v0.167.0`, commit `086ccc833bc9cf01024ad2dd6af7dbd421b6bf9a`. It is
  queue entry 96 and is listed last. It changes `codex-rs/core`, `config`, `cli`, `tui` and the `app-server`, `cli`
  and `core` tests, and no earlier entry's changes conflict with it.

## Intent

Upstream already has the switches this build needs, and every sender honours them, so the patch changes their
defaults instead of adding gates.

- `feedback.enabled` defaults to `false`. The app-server upload request, the log-write warning and the TUI `/feedback`
  handler already read it. `/feedback` is also neither listed nor resolved (`SlashCommand::Feedback => false` in
  `is_visible`), because the command list cannot read the configuration. The text "Conversation interrupted - use
  /feedback" is unchanged.
- An unset `analytics.enabled` resolves to `false` when the configuration loads, so product analytics and the built-in
  Statsig metrics exporter send nothing unless the user sets `analytics.enabled = true`. As a result
  `--analytics-default-enabled` no longer enables analytics; the flag's help text says so.
- The update check stays on by default. It queries
  `https://api.github.com/repos/holdmyspot-com/codex-unleashed/releases/latest`; the release notes and "installation
  options" links, and the standalone installer commands, name `holdmyspot-com/codex-unleashed` releases. A client
  installed with npm, bun, pnpm, Vite+ or Homebrew gets no upgrade action, because those commands would install
  OpenAI's package over this build. The unreachable npm and Homebrew branches stay in place to keep the patch small.
- Our release tags look like `rust-v0.167.0+<build>`, so the version comparison reads a numeric build after `+`; a
  version without a build compares as build 0.
- `codex doctor` and the OpenAI-side `otel/README.md` text are unchanged.

## Verification

```sh
cargo test -p codex-core --lib config::
cargo test -p codex-tui --lib slash_command update_ terminal_size feedback
cargo test -p codex-app-server --test all feedback analytics plugin_install plugin_uninstall
```

The TUI snapshot tests need the workspace version at `0.0.0`; the repository holds `0.167.0` until the release step.
The new and changed tests cover the three defaults (`feedback_and_analytics_default_to_off`), the unlisted
`/feedback`, the build-number comparison, and the update actions for each install method. Tests that observed
feedback uploads or analytics events now enable them explicitly.

On a Linux machine without the V8 artifact, the same suites fail on upstream plus entries 1-95 and on the tree with
this patch, with the same failing tests apart from timing flakes: `codex-tui` library 27 failures before and after,
`codex-core` library 9 and 9, `codex-core` integration tests 295 and 293, and `codex-app-server` integration tests
46 and 46. Those failures need the `codex-code-mode-host` binary, which cannot be built here, or are
terminal-dependent snapshots. `codex-config` and the `codex-app-server` library pass. `cargo check`,
`cargo clippy -D warnings` and `cargo fmt` pass for `codex-core`, `codex-config`, `codex-tui`, `codex-app-server` and
`codex-cli`, and `cargo check -p codex-tui` also passes with debug assertions off, which compiles `updates.rs`.
