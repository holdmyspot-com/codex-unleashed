# Readable command previews

- Issue: [codex-unleashed#70](https://github.com/holdmyspot-com/codex-unleashed/issues/70)
- Upstream base: `openai/codex` `rust-v0.163.0`, commit
  `83086188ae0614bd882818ff1aa23f3194962012`.

## Intent

Keep executable filenames and arguments visible when commands contain long
paths. Collapsed previews shorten directory prefixes only when a path exceeds
the available width, wrap arguments, emphasize executable names, and retain
directory context where space permits. Shell wrappers and multiline scripts
remain inspectable. Invalid shell syntax displays literally.

Click a command or its output preview to expand the full command and retained
output. The collapsed section advertises this action; hovering an expanded
section explains that clicking anywhere in it collapses it. Dragging selects
text without toggling the section. Existing keyboard disclosure controls apply:
F4 selects activities, Up/Down changes selection, Right/Left expands/collapses,
Enter toggles, and Escape leaves activity selection. Hints use configured key
bindings. Expansion and focus persist through streaming, completion, and resize.

Expanded commands have no path abbreviations. The existing command-block copy
control copies the original protocol source, including quotes, redirections,
and newlines. Output remains subject to the existing retention and omission
limits. This presentation does not change execution or make output editable.

## Feature configuration

### Codex feature flag

`unleashed_readable_commands` is stable and enabled by default.

```sh
codex features enable unleashed_readable_commands
codex features disable unleashed_readable_commands
```

The equivalent configuration is:

```toml
[features]
unleashed_readable_commands = true
```

Disabling the flag preserves the existing command presentation and disclosure.

### Project-specific `config.toml` property

None. This feature adds no `[codex_unleashed]` properties. Its feature flag alone
controls the presentation.

## Patch variants and application order

`readable-commands.patch` integrates with this repository's sorted patch queue.
Its queue-context base is downstream commit
`64897f4247469148d5ea88261696536e06cef539` (upstream queue tree
`f58dd0bb7863ff8e83ef0353a5cf4e278b25b431`).
The shared schema patch supplies the combined configuration schema after all
feature patches. Existing collapsed-tool-activity hunks overlap these rendering
entry points, so this variant uses their context and includes an interaction
test for the two features.

`readable-commands.upstream.mbox` is the self-contained upstream contribution
variant. Apply it directly to the clean upstream base with `git apply` or
`git am`; it includes its own feature registration, schema, and test support.
It requires no other downstream patch or installed plugin. Its `.mbox` suffix
keeps the queue application script from applying both variants. The runtime
behavior is the same in both variants.

## Reproduction

Display a command whose executable resides under a deeply nested plugin cache,
with a quoted output path and several arguments, in a narrow terminal. Check
that the executable basename and arguments remain visible, then expand its
section and copy the command. Exercise a heredoc or shell wrapper and a command
that produces more output than the collapsed preview retains.

## Verification

Run from the patched upstream checkout root:

```sh
just fmt-check
cargo build --manifest-path codex-rs/Cargo.toml -p codex-cli --bin codex
just test -p codex-tui -p codex-features -E 'test(readable_command_preview) | test(unleashed_readable_commands)'
just test -p codex-tui -p codex-features
just clippy -p codex-tui -p codex-features -- --no-deps
```

Focused checks cover terminal width, Unicode and quoted paths, arguments,
wrapper/script previews, exact copying, mouse hints and drag selection, keyboard
remapping, streaming transitions, protocol replay, and flag opt-out. The full
suite also verifies compatibility with existing rendering. Build the CLI first
because the TUI's provider-default tests invoke that executable. Test fixture
homes must be isolated from project configuration; terminal-color environment
variables must match the upstream test environment. The Clippy command scopes
linting to the changed crates; dependency compiler diagnostics still apply.
Real-CLI socket tests require access to the protected daemon socket directory.

For the complete queue, also verify the shared schema using its
[README.md](../schema/README.md). The standalone contribution includes its
generated schema directly.

## Upstream status

This is a downstream feature proposal against
[upstream 0.163.0](https://github.com/openai/codex/releases/tag/rust-v0.163.0).
Its renderer reuses Codex's shell parser and transcript disclosure interfaces.
