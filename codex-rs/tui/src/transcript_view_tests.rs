//! Viewport behavior and cache lifetime across scrolling, selection, and redraws.

use super::*;
use crate::history_cell::new_user_prompt;
use crossterm::event::KeyCode;
use crossterm::event::KeyEvent;
use crossterm::event::KeyModifiers;
use crossterm::event::MouseButton;
use crossterm::event::MouseEvent;
use crossterm::event::MouseEventKind;
use pretty_assertions::assert_eq;
use std::sync::atomic::AtomicUsize;
use std::sync::atomic::Ordering;

#[derive(Debug)]
struct TestCell {
    text: String,
    renders: Arc<AtomicUsize>,
}

impl HistoryCell for TestCell {
    fn display_lines(&self, _width: u16) -> Vec<Line<'static>> {
        self.renders.fetch_add(/*val*/ 1, Ordering::Relaxed);
        self.text
            .lines()
            .map(|line| line.to_owned().into())
            .collect()
    }

    fn raw_lines(&self) -> Vec<Line<'static>> {
        self.display_lines(/*width*/ 80)
    }
}

pub(super) fn cell(text: impl Into<String>) -> Arc<dyn HistoryCell> {
    Arc::new(TestCell {
        text: text.into(),
        renders: Arc::default(),
    })
}

fn prompt(text: impl Into<String>) -> Arc<dyn HistoryCell> {
    Arc::new(new_user_prompt(
        text.into(),
        Vec::new(),
        Vec::new(),
        Vec::new(),
    ))
}

pub(super) fn render(
    view: &mut TranscriptView,
    cells: &[Arc<dyn HistoryCell>],
    width: u16,
    height: u16,
) -> Buffer {
    let area = Rect::new(/*x*/ 0, /*y*/ 0, width, height);
    let mut buffer = Buffer::empty(area);
    view.render(area, &mut buffer, cells);
    buffer
}

pub(super) fn text(buffer: &Buffer) -> String {
    (buffer.area.top()..buffer.area.bottom())
        .map(|y| {
            (buffer.area.left()..buffer.area.right())
                .map(|x| buffer[(x, y)].symbol())
                .collect::<String>()
                .trim_end()
                .to_owned()
        })
        .collect::<Vec<_>>()
        .join("\n")
}

#[test]
fn consecutive_user_prompts_share_one_background_block() {
    use crate::history_cell::AgentMessageCell;
    use crate::history_cell::new_spoken_user_prompt;
    use codex_protocol::user_input::TextElement;

    let cells: Vec<Arc<dyn HistoryCell>> = vec![
        prompt("First steering message"),
        prompt("Second steering message"),
        Arc::new(AgentMessageCell::new(
            vec!["Assistant response".into()],
            /*is_first_line*/ true,
        )),
        Arc::new(new_spoken_user_prompt(String::new())),
        Arc::new(new_user_prompt(
            "   ".into(),
            vec![TextElement::new((0..3).into(), Some("   ".into()))],
            Vec::new(),
            Vec::new(),
        )),
        prompt("Later user message"),
    ];
    crate::terminal_palette::with_test_default_colors(
        crate::terminal_probe::DefaultColors {
            fg: (235, 235, 235),
            bg: (18, 20, 30),
        },
        || {
            let mut view = TranscriptView::default();
            let buffer = render(&mut view, &cells, /*width*/ 32, /*height*/ 12);
            insta::assert_snapshot!(
                "consecutive_user_prompts_share_one_background_block",
                format!("{buffer:?}")
            );
        },
    );
}

#[test]
fn prompt_adjacency_uses_cached_retained_presentations() {
    let renders = Arc::new(AtomicUsize::new(0));
    let cells: Vec<Arc<dyn HistoryCell>> = vec![
        Arc::new(TestCell {
            text: "response".into(),
            renders: Arc::clone(&renders),
        }),
        prompt("steering"),
    ];
    let mut view = TranscriptView::default();

    view.current_layout(&cells, /*index*/ 1);
    view.current_layout(&cells, /*index*/ 1);

    assert_eq!(renders.load(Ordering::Relaxed), 1);
}

#[test]
fn joined_prompt_preserves_source_offsets_across_rewrap() {
    let cells = vec![prompt("older"), prompt("one\n\ntwo")];
    let mut view = TranscriptView::default();
    let unjoined = [Arc::clone(&cells[1])];
    let source = view
        .current_layout(&unjoined, /*index*/ 0)
        .unwrap()
        .text()
        .to_owned();
    let joined = view.current_layout(&cells, /*index*/ 1).unwrap();

    assert_eq!(
        (joined.text(), joined.rewrap(/*width*/ 8).text()),
        (source.as_str(), source.as_str())
    );
}

#[test]
fn detailed_only_reasoning_separates_user_prompts() {
    use crate::history_cell::ReasoningSummaryCell;
    use std::path::Path;

    let cells: Vec<Arc<dyn HistoryCell>> = vec![
        prompt("first"),
        Arc::new(ReasoningSummaryCell::new(
            "Reasoning".into(),
            "visible detail".into(),
            Path::new("."),
            /*transcript_only*/ true,
        )),
        prompt("second"),
    ];
    let mut detailed = TranscriptView {
        detailed: true,
        ..TranscriptView::default()
    };
    assert!(
        detailed
            .current_layout(&cells, /*index*/ 2)
            .unwrap()
            .separated
    );

    let mut compact = TranscriptView::default();
    assert!(
        !compact
            .entry_layout(&cells, /*index*/ 2, /*full_content*/ true)
            .unwrap()
            .separated
    );
}

#[test]
fn readable_command_preview_click_waits_for_release_and_expands_output() {
    let mut command = crate::exec_cell::new_active_exec_command(
        "readable-click".into(),
        vec!["echo".into(), "retained command".into()],
        Vec::new(),
        codex_app_server_protocol::CommandExecutionSource::Agent,
        /*interaction_input*/ None,
        /*animations_enabled*/ false,
    );
    command = command.with_readable_commands(/*enabled*/ true);
    assert!(command.complete_call(
        "readable-click",
        crate::exec_cell::CommandOutput::new(
            /*exit_code*/ 0,
            "first output\nsecond output\nthird output\nfourth output\nlast output\n".into()
        ),
        std::time::Duration::from_secs(1),
    ));
    let cells: Vec<Arc<dyn HistoryCell>> = vec![Arc::new(command)];
    let mut view = TranscriptView::default();
    let collapsed = render(&mut view, &cells, /*width*/ 80, /*height*/ 20);
    assert!(!text(&collapsed).contains("first output"));
    let mouse = |kind| MouseEvent {
        kind,
        column: 20,
        row: 0,
        modifiers: KeyModifiers::NONE,
    };

    view.handle_mouse(mouse(MouseEventKind::Down(MouseButton::Left)), &cells);
    let pressed = render(&mut view, &cells, /*width*/ 80, /*height*/ 20);
    assert!(!text(&pressed).contains("first output"));
    view.handle_mouse(mouse(MouseEventKind::Up(MouseButton::Left)), &cells);
    let expanded = render(&mut view, &cells, /*width*/ 80, /*height*/ 20);

    assert!(
        text(&expanded).contains("first output"),
        "{}",
        text(&expanded)
    );
}

#[test]
fn readable_command_preview_advertises_expansion() {
    let command = crate::exec_cell::new_active_exec_command(
        "readable-hint".into(),
        vec!["echo".into(), "hello".into()],
        Vec::new(),
        codex_app_server_protocol::CommandExecutionSource::Agent,
        /*interaction_input*/ None,
        /*animations_enabled*/ false,
    );
    let command = command.with_readable_commands(/*enabled*/ true);
    let cells: Vec<Arc<dyn HistoryCell>> = vec![Arc::new(command)];
    let mut view = TranscriptView::default();
    let collapsed = render(&mut view, &cells, /*width*/ 80, /*height*/ 20);

    assert!(text(&collapsed).contains("Click to expand command and output"));
}

/// A completed command with hidden output exercises disclosure rather than an empty control.
fn readable_test_command(id: &str, original: &str) -> crate::exec_cell::ExecCell {
    let mut command = crate::exec_cell::new_active_exec_command(
        id.into(),
        vec!["bash".into(), "-lc".into(), original.into()],
        Vec::new(),
        codex_app_server_protocol::CommandExecutionSource::Agent,
        /*interaction_input*/ None,
        /*animations_enabled*/ false,
    )
    .with_readable_commands(/*enabled*/ true);
    command.retain_command_source(id, original.into());
    assert!(command.complete_call(
        id,
        crate::exec_cell::CommandOutput::new(
            /*exit_code*/ 0,
            "first output\nsecond output\nthird output\nfourth output\nlast output\n".into()
        ),
        std::time::Duration::ZERO,
    ));
    command
}

#[test]
fn readable_command_preview_copy_preserves_original_source_in_both_presentations() {
    let original = "'/plugins/CAT path/bin/run-part' <<'EOF'\nprintf '%s\\n' 'a b' > '/reports/long path/result.txt'\nEOF\n";
    let cells: Vec<Arc<dyn HistoryCell>> = vec![Arc::new(readable_test_command("copy", original))];
    let mut view = TranscriptView::default();
    for expanded in [false, true] {
        render(&mut view, &cells, /*width*/ 48, /*height*/ 30);
        let layout = view.base_layout(&cells, /*index*/ 0).unwrap();
        let commands: Vec<_> = layout
            .copy_block_ranges()
            .into_iter()
            .filter(|(_, label, _)| *label == "Command")
            .map(|(_, _, payload)| payload.unwrap())
            .collect();
        assert_eq!(commands, vec![original.to_owned()]);
        if !expanded {
            view.handle_key(KeyCode::F(4).into(), &cells);
            view.handle_key(KeyCode::Right.into(), &cells);
        }
    }
}

#[test]
fn readable_command_preview_keyboard_and_mouse_share_state_after_resize() {
    let cells: Vec<Arc<dyn HistoryCell>> =
        vec![Arc::new(readable_test_command("keys", "echo 'a b'"))];
    let mut view = TranscriptView::default();
    render(&mut view, &cells, /*width*/ 80, /*height*/ 25);
    view.handle_key(KeyCode::F(4).into(), &cells);
    assert!(view.is_activity_focused());
    view.handle_key(KeyCode::Right.into(), &cells);
    assert!(
        text(&render(
            &mut view, &cells, /*width*/ 48, /*height*/ 25
        ))
        .contains("first output")
    );
    assert!(view.is_activity_focused());
    view.handle_key(KeyCode::Left.into(), &cells);
    assert!(
        !text(&render(
            &mut view, &cells, /*width*/ 80, /*height*/ 25
        ))
        .contains("first output")
    );
    view.handle_key(KeyCode::Enter.into(), &cells);
    let expanded = render(&mut view, &cells, /*width*/ 80, /*height*/ 25);
    assert!(text(&expanded).contains("first output"));
    view.handle_key(KeyCode::Esc.into(), &cells);
    assert!(!view.is_activity_focused());
    // Clicking an output row collapses the same activity expanded by the keyboard.
    let row = (0..expanded.area.height)
        .find(|row| {
            (0..expanded.area.width)
                .map(|column| expanded[(column, *row)].symbol())
                .collect::<String>()
                .contains("first output")
        })
        .unwrap();
    for kind in [
        MouseEventKind::Down(MouseButton::Left),
        MouseEventKind::Up(MouseButton::Left),
    ] {
        view.handle_mouse(
            MouseEvent {
                kind,
                column: 20,
                row,
                modifiers: KeyModifiers::NONE,
            },
            &cells,
        );
    }
    assert!(
        !text(&render(
            &mut view, &cells, /*width*/ 80, /*height*/ 25
        ))
        .contains("first output")
    );
}

#[test]
fn readable_command_preview_drag_selects_without_expanding() {
    let cells: Vec<Arc<dyn HistoryCell>> =
        vec![Arc::new(readable_test_command("drag", "echo hello"))];
    let mut view = TranscriptView::default();
    render(&mut view, &cells, /*width*/ 80, /*height*/ 25);
    for (kind, column) in [
        (MouseEventKind::Down(MouseButton::Left), 10),
        (MouseEventKind::Drag(MouseButton::Left), 20),
        (MouseEventKind::Up(MouseButton::Left), 20),
    ] {
        view.handle_mouse(
            MouseEvent {
                kind,
                column,
                row: 0,
                modifiers: KeyModifiers::NONE,
            },
            &cells,
        );
    }
    assert!(
        !text(&render(
            &mut view, &cells, /*width*/ 80, /*height*/ 25
        ))
        .contains("first output")
    );
    assert!(
        view.selection
            .as_ref()
            .is_some_and(|selection| selection.moved)
    );
}

#[test]
fn readable_command_preview_hover_explains_collapse() {
    let cells: Vec<Arc<dyn HistoryCell>> =
        vec![Arc::new(readable_test_command("hover", "echo hello"))];
    let mut view = TranscriptView::default();
    render(&mut view, &cells, /*width*/ 80, /*height*/ 25);
    view.handle_key(KeyCode::F(4).into(), &cells);
    view.handle_key(KeyCode::Right.into(), &cells);
    render(&mut view, &cells, /*width*/ 80, /*height*/ 25);
    view.handle_mouse(
        MouseEvent {
            kind: MouseEventKind::Moved,
            column: 20,
            row: 0,
            modifiers: KeyModifiers::NONE,
        },
        &cells,
    );
    let footer = view
        .footer(
            /*width*/ 80,
            crate::motion::MotionMode::from_animations_enabled(/*animations_enabled*/ false),
        )
        .unwrap();
    assert!(
        footer
            .text
            .to_string()
            .contains("Click anywhere in this section to collapse")
    );
}

#[test]
fn readable_command_preview_focus_hint_uses_configured_binding() {
    let cells: Vec<Arc<dyn HistoryCell>> =
        vec![Arc::new(readable_test_command("remap", "echo hello"))];
    let config =
        serde_json::from_value(serde_json::json!({"global": {"focus_activity": "f12"}})).unwrap();
    let keymap = crate::keymap::RuntimeKeymap::from_config(&config).unwrap();
    let mut view = TranscriptView::default();
    view.set_keymap_bindings(&keymap);
    let collapsed = text(&render(
        &mut view, &cells, /*width*/ 100, /*height*/ 25,
    ));
    assert!(collapsed.contains("f12 to select"), "{collapsed}");
    assert!(!collapsed.contains("f4"));
    view.handle_key(KeyCode::F(12).into(), &cells);
    assert!(view.is_activity_focused());
    view.handle_key(KeyCode::Right.into(), &cells);
    assert!(
        text(&render(
            &mut view, &cells, /*width*/ 100, /*height*/ 25
        ))
        .contains("first output")
    );
}

#[test]
fn readable_command_preview_disabled_preserves_legacy_disclosure() {
    let command = readable_test_command("disabled", "echo hello")
        .with_readable_commands(/*enabled*/ false);
    let cells: Vec<Arc<dyn HistoryCell>> = vec![Arc::new(command)];
    let mut view = TranscriptView::default();
    let collapsed = text(&render(
        &mut view, &cells, /*width*/ 80, /*height*/ 25,
    ));
    assert!(!collapsed.contains("Click to expand"));
    assert!(collapsed.contains("+ 2 lines"));
}

#[test]
fn page_keys_follow_pager_scroll_bindings() {
    let cells = [cell(
        (1..=30)
            .map(|row| format!("row {row}\n"))
            .collect::<String>(),
    )];
    let mut frames = Vec::new();
    for (label, pager, rows) in [
        ("default", serde_json::json!({}), 7),
        (
            "half page",
            serde_json::json!({
                "page_up": [], "page_down": [],
                "half_page_up": "page-up", "half_page_down": "page-down"
            }),
            4,
        ),
        (
            "single row",
            serde_json::json!({
                "page_up": [], "page_down": [],
                "scroll_up": "page-up", "scroll_down": "page-down"
            }),
            1,
        ),
        (
            "unbound",
            serde_json::json!({"page_up": [], "page_down": []}),
            0,
        ),
    ] {
        let config = serde_json::from_value(serde_json::json!({"pager": pager})).unwrap();
        let keymap = crate::keymap::RuntimeKeymap::from_config(&config).unwrap();
        let mut view = TranscriptView::default();
        view.set_keymap_bindings(&keymap);
        let pre_scroll = render(&mut view, &cells, /*width*/ 20, /*height*/ 8);
        assert_eq!(
            view.handle_key(KeyCode::PageUp.into(), &cells).is_some(),
            rows != 0
        );
        let scrolled = render(&mut view, &cells, /*width*/ 20, /*height*/ 8);
        let mut expected = TranscriptView::default();
        render(&mut expected, &cells, /*width*/ 20, /*height*/ 8);
        expected.scroll(&cells, -rows);
        assert_eq!(
            scrolled,
            render(&mut expected, &cells, /*width*/ 20, /*height*/ 8)
        );
        frames.push(format!("{label}\n{}", text(&scrolled)));
        assert_eq!(
            view.handle_key(KeyCode::PageDown.into(), &cells).is_some(),
            rows != 0
        );
        assert_eq!(
            render(&mut view, &cells, /*width*/ 20, /*height*/ 8),
            pre_scroll
        );
        for key in [
            KeyCode::Char('j').into(),
            KeyEvent::new(KeyCode::Char('u'), KeyModifiers::CONTROL),
        ] {
            assert!(view.handle_key(key, &cells).is_none());
        }
    }
    insta::assert_snapshot!(
        "page_keys_follow_pager_scroll_bindings",
        frames.join("\n\n")
    );
}

#[test]
fn terminal_output_disclosure_follows_live_history_and_keymap() {
    use crate::exec_cell::CommandOutput;
    use crate::exec_cell::new_active_exec_command;
    use crate::keymap::RuntimeKeymap;
    use codex_app_server_protocol::CommandExecutionSource;

    let mut cell = new_active_exec_command(
        "output-test".into(),
        vec!["echo".into()],
        Vec::new(),
        CommandExecutionSource::Agent,
        /*interaction_input*/ None,
        /*animations_enabled*/ false,
    );
    cell.complete_call(
        "output-test",
        CommandOutput::new(/*exit_code*/ 0, "1\n2\n3\n4\n5\n6\n7\n8\n".into()),
        std::time::Duration::ZERO,
    );
    let cell: Arc<dyn HistoryCell> = Arc::new(cell);
    let mut view = TranscriptView::default();
    view.sync_live_activity(&[], cell.activity_ids());
    view.sync_live_activity_tail(
        /*width*/ 72,
        /*key*/ None,
        /*expanded*/ false,
        |width| {
            Some(ActivityTranscriptLines {
                activity: cell.compact_hyperlink_lines(width),
                auxiliary: Vec::new(),
                disclosure: cell.activity_disclosure(width),
            })
        },
    );
    let live = text(&render(
        &mut view,
        &[],
        /*width*/ 72,
        /*height*/ 6,
    ));
    let cells = [cell];
    view.sync_live_activity(&cells, Vec::new());
    view.sync_live_tail(/*width*/ 72, /*key*/ None, |_| None);
    let committed = text(&render(
        &mut view, &cells, /*width*/ 72, /*height*/ 6,
    ));
    assert_eq!(live, committed);
    let hint = " (⌃t to expand)";
    assert!(live.contains(&format!("+ 5 lines{hint}")));
    // Check where the hint is visible, so the copy/search exclusion cannot pass vacuously.
    assert!(
        !view
            .layout(&cells, /*index*/ 0)
            .unwrap()
            .text()
            .contains(hint)
    );
    // Warm layouts must follow config changes, including chords and disabling the action.
    for (configured, expected) in [
        (serde_json::json!("f12"), " (f12 to expand)"),
        (serde_json::json!("ctrl-x t"), " (⌃x t to expand)"),
        (serde_json::json!([]), ""),
    ] {
        let config = serde_json::from_value(serde_json::json!({
            "global": {"open_transcript": configured}
        }))
        .unwrap();
        let keymap = RuntimeKeymap::from_config(&config).unwrap();
        view.set_keymap_bindings(&keymap);
        assert_eq!(
            text(&render(
                &mut view, &cells, /*width*/ 72, /*height*/ 6,
            )),
            live.replace(hint, expected),
        );
    }
}

#[test]
fn terminal_output_disclosure_counts_only_revealable_lines() {
    use crate::exec_cell::CommandOutput;
    use crate::exec_cell::new_active_exec_command;
    use codex_app_server_protocol::CommandExecutionSource;

    let mut frames = Vec::new();
    for (name, command, output, width, streamed) in [
        ("three visible", "echo", "a\nb\nc\n".to_owned(), 64, false),
        ("one hidden", "echo", "a\nb\nc\nd\n".to_owned(), 64, false),
        (
            "clipped line",
            "echo",
            "abcdefghijklmnopqrstuvwxyz\n".to_owned(),
            20,
            false,
        ),
        ("command only", "echo a\necho b", String::new(), 64, false),
        (
            "storage truncated",
            "echo",
            "x\n".repeat(/*n*/ 530_000),
            64,
            true,
        ),
    ] {
        let mut cell = new_active_exec_command(
            "output-test".into(),
            vec!["sh".into(), "-c".into(), command.into()],
            Vec::new(),
            CommandExecutionSource::Agent,
            /*interaction_input*/ None,
            /*animations_enabled*/ false,
        );
        if streamed {
            cell.append_output("output-test", &output);
        } else {
            cell.complete_call(
                "output-test",
                CommandOutput::new(/*exit_code*/ 0, output),
                std::time::Duration::from_secs(/*secs*/ 1),
            );
        }
        let cells: [Arc<dyn HistoryCell>; 1] = [Arc::new(cell)];
        frames.push(format!(
            "{name}\n{}",
            text(&render(
                &mut TranscriptView::default(),
                &cells,
                width,
                /*height*/ 6,
            ))
            .trim_end()
        ));
    }
    insta::assert_snapshot!(frames.join("\n\n"));
}

#[test]
fn startup_warning_keeps_first_visible_header_in_place() {
    let header: Arc<dyn HistoryCell> =
        Arc::new(crate::history_cell::SessionHeaderHistoryCell::new(
            "gpt-test".into(),
            /*reasoning_effort*/ None,
            crate::test_support::test_path_buf("/project"),
            "test",
        ));
    let mut view = TranscriptView::default();
    view.sync_live_tail(/*width*/ 80, /*key*/ None, |width| {
        Some(header.compact_hyperlink_lines(width))
    });
    let mut cells = Vec::new();
    let before = render(&mut view, &cells, /*width*/ 80, /*height*/ 24);
    cells.push(Arc::new(crate::history_cell::StartupWarningsCell::new(vec![
        "Skill manifest is invalid.".into(),
    ])) as Arc<dyn HistoryCell>);
    assert_eq!(
        render(&mut view, &cells, /*width*/ 80, /*height*/ 24),
        before
    );
    view.sync_live_tail(/*width*/ 80, /*key*/ None, |_| None);
    cells.insert(/*index*/ 0, header);
    assert_eq!(
        render(&mut view, &cells, /*width*/ 80, /*height*/ 24),
        before
    );
    assert_eq!(crate::history_cell::warning_count(&cells), 1);
}

#[test]
fn hidden_initial_entries_do_not_add_a_separator_but_visible_entries_do() {
    let cells = vec![cell(""), cell(""), cell("First"), cell(""), cell("Second")];
    let mut view = TranscriptView::default();
    view.sync_live_tail(
        /*width*/ 20,
        /*key*/ None,
        |_| Some(vec![HyperlinkLine::from("Live")]),
    );
    let frame = render(&mut view, &cells, /*width*/ 20, /*height*/ 5);
    insta::assert_snapshot!(text(&frame), @"
    First

    Second

    Live
    ");
    // Scrolling past the first visible entry must stop at its content, not its separator.
    view.scroll(&cells, /*rows*/ -100);
    assert_eq!(render(&mut view, &cells, /*width*/ 20, /*height*/ 5), frame);
}

#[test]
fn reading_survives_prepend_and_new_output_then_returns_to_latest() {
    let mut cells = vec![cell("older\nline two"), cell("current\nlast line")];
    let mut view = TranscriptView::default();
    render(&mut view, &cells, /*width*/ 20, /*height*/ 3);
    view.scroll(&cells, /*rows*/ -2);
    let before = text(&render(
        &mut view, &cells, /*width*/ 20, /*height*/ 3,
    ));
    cells.insert(/*index*/ 0, cell("loaded from history"));
    cells.push(cell("new output"));
    assert_eq!(
        text(&render(
            &mut view, &cells, /*width*/ 20, /*height*/ 3
        )),
        before
    );
    view.jump_to_latest();
    insta::assert_snapshot!(text(&render(&mut view, &cells, /*width*/ 20, /*height*/ 3)), @"
    last line

    new output
    ");
}

#[test]
fn prepending_history_updates_pagination_position_before_the_next_frame() {
    let mut cells = vec![cell("current first"), cell("current last")];
    let mut view = TranscriptView::default();
    view.jump_to_entry(&cells, /*index*/ 0);
    assert!(view.near_start(&cells));
    cells.remove(/*index*/ 0);
    view.history_loaded(&cells, 0..0);
    let earlier = (0..100)
        .map(|index| cell(format!("earlier {index}")))
        .collect::<Vec<_>>();
    cells.splice(0..0, earlier);
    view.history_loaded(&cells, 0..100);
    assert!(!view.needs_history(&cells));
    let Position::Reading(anchor) = view.position else {
        panic!("reading anchor");
    };
    assert_eq!(
        (anchor.key, anchor.index),
        (EntryKey::cell(&cells[100]), 100)
    );
}

#[test]
fn beginning_wait_keeps_live_content_through_new_output_prepend_and_resize() {
    let mut cells = vec![cell("previous answer")];
    let live = cell("live zero\nlive one\nlive two\nlive three");
    let mut view = TranscriptView {
        history: TranscriptHistoryState::Partial,
        ..TranscriptView::default()
    };
    view.sync_live_tail(
        /*width*/ 20,
        /*key*/ None,
        |width| Some(live.display_hyperlink_lines(width)),
    );
    let before = render(&mut view, &cells, /*width*/ 20, /*height*/ 2);
    view.jump_to_beginning(&cells);
    cells.insert(/*index*/ 0, cell("older page"));
    view.history_loaded(&cells, 0..1);
    view.sync_live_tail(/*width*/ 20, /*key*/ None, |_| {
        Some(vec![HyperlinkLine::from("new streaming output")])
    });
    view.jump_to_beginning(&cells);
    assert_eq!(
        render(&mut view, &cells, /*width*/ 20, /*height*/ 2),
        before
    );
    let reading = view.position;
    render(&mut view, &cells, /*width*/ 12, /*height*/ 3);
    assert_eq!(
        (view.position, view.history),
        (reading, TranscriptHistoryState::LoadingBeginning)
    );
    assert_eq!(
        render(&mut view, &cells, /*width*/ 20, /*height*/ 2),
        before
    );
}

#[test]
fn transcript_interaction_cancels_pending_beginning_without_losing_received_history() {
    for key in [
        None,
        Some(KeyEvent::new(KeyCode::PageUp, KeyModifiers::NONE)),
        Some(KeyEvent::new(KeyCode::F(3), KeyModifiers::NONE)),
        Some(KeyEvent::new(KeyCode::Char(' '), KeyModifiers::CONTROL)),
    ] {
        let mut cells = (0..20)
            .map(|index| cell(format!("line {index}")))
            .collect::<Vec<_>>();
        let mut view = TranscriptView {
            history: TranscriptHistoryState::Partial,
            ..TranscriptView::default()
        };
        render(&mut view, &cells, /*width*/ 24, /*height*/ 3);
        view.jump_to_beginning(&cells);
        match key {
            Some(key) if key.code == KeyCode::F(3) => view.begin_search(),
            Some(key) => {
                view.handle_key(key, &cells);
            }
            None => {
                view.handle_mouse(
                    MouseEvent {
                        kind: MouseEventKind::ScrollUp,
                        column: 0,
                        row: 0,
                        modifiers: KeyModifiers::NONE,
                    },
                    &cells,
                );
            }
        }
        assert_eq!(view.history, TranscriptHistoryState::LoadingOlder);
        let before = render(&mut view, &cells, /*width*/ 24, /*height*/ 3);
        cells.insert(/*index*/ 0, cell("received older history"));
        view.history_loaded(&cells, 0..1);
        view.history = TranscriptHistoryState::Complete;
        assert_eq!(
            render(&mut view, &cells, /*width*/ 24, /*height*/ 3),
            before
        );
    }
}

#[test]
fn returning_to_latest_cancels_beginning_intent_while_retaining_the_loaded_page() {
    for (beginning, latest) in [
        (
            KeyEvent::new(KeyCode::Home, KeyModifiers::CONTROL),
            KeyEvent::new(KeyCode::End, KeyModifiers::CONTROL),
        ),
        (
            KeyEvent::new(KeyCode::Char('<'), KeyModifiers::ALT),
            KeyEvent::new(KeyCode::Char('>'), KeyModifiers::ALT),
        ),
        (
            KeyEvent::new(KeyCode::Char('<'), KeyModifiers::ALT | KeyModifiers::SHIFT),
            KeyEvent::new(KeyCode::Char('>'), KeyModifiers::ALT | KeyModifiers::SHIFT),
        ),
        (
            KeyEvent::new(KeyCode::Char(','), KeyModifiers::ALT | KeyModifiers::SHIFT),
            KeyEvent::new(KeyCode::Char('.'), KeyModifiers::ALT | KeyModifiers::SHIFT),
        ),
    ] {
        let mut cells = vec![cell("newest answer\nlast line")];
        let mut view = TranscriptView {
            history: TranscriptHistoryState::Partial,
            ..TranscriptView::default()
        };
        render(&mut view, &cells, /*width*/ 20, /*height*/ 2);
        view.handle_key(beginning, &cells);
        assert_eq!(view.history, TranscriptHistoryState::LoadingBeginning);
        view.handle_key(latest, &cells);
        assert_eq!(
            (view.history, view.position),
            (TranscriptHistoryState::LoadingOlder, Position::Latest),
        );
        cells.insert(
            /*index*/ 0,
            cell("older page first line\nsecond line\nthird line"),
        );
        view.history = TranscriptHistoryState::Partial;
        view.history_loaded(&cells, 0..1);
        assert!(!view.needs_history(&cells));
        insta::allow_duplicates! {
            insta::assert_snapshot!(text(&render(&mut view, &cells, /*width*/ 20, /*height*/ 2)), @"
            newest answer
            last line
            ");
        }
        view.handle_key(beginning, &cells);
        assert_eq!(view.history, TranscriptHistoryState::LoadingBeginning);
    }
}

#[test]
fn transcript_jumps_leave_typing_and_effort_shortcuts_to_the_composer() {
    let cells = vec![cell("current answer")];
    let mut view = TranscriptView::default();
    for key in [
        KeyEvent::new(KeyCode::Char('<'), KeyModifiers::NONE),
        KeyEvent::new(KeyCode::Char('>'), KeyModifiers::SHIFT),
        KeyEvent::new(KeyCode::Char(','), KeyModifiers::ALT),
        KeyEvent::new(KeyCode::Char('.'), KeyModifiers::ALT),
        KeyEvent::new(
            KeyCode::Char('<'),
            KeyModifiers::ALT | KeyModifiers::CONTROL,
        ),
        KeyEvent::new(KeyCode::Char('>'), KeyModifiers::ALT | KeyModifiers::SUPER),
        KeyEvent::new_with_kind(
            KeyCode::Char('<'),
            KeyModifiers::ALT,
            crossterm::event::KeyEventKind::Release,
        ),
    ] {
        assert!(view.handle_key(key, &cells).is_none(), "{key:?}");
        assert_eq!(view.position, Position::Latest);
    }
}

#[test]
fn scrolling_to_latest_cancels_beginning_and_keeps_selection_when_the_final_page_arrives() {
    for wheel in [false, true] {
        for select in [false, true] {
            let mut cells = vec![cell("older entry"), cell("latest answer")];
            let mut view = TranscriptView {
                history: TranscriptHistoryState::Partial,
                ..TranscriptView::default()
            };
            view.handle_key(KeyEvent::new(KeyCode::Home, KeyModifiers::CONTROL), &cells);
            render(&mut view, &cells, /*width*/ 20, /*height*/ 2);
            if select {
                view.begin_selection(&cells, /*column*/ 0, /*row*/ 0, /*clicks*/ 3);
            }
            let selected = view.selected_text(&cells);
            if wheel {
                view.handle_mouse(
                    MouseEvent {
                        kind: MouseEventKind::ScrollDown,
                        column: 0,
                        row: 0,
                        modifiers: KeyModifiers::NONE,
                    },
                    &cells,
                );
            } else {
                view.handle_key(KeyEvent::new(KeyCode::PageDown, KeyModifiers::NONE), &cells);
            }
            assert_eq!(
                (view.history, view.position, view.selected_text(&cells)),
                (
                    TranscriptHistoryState::LoadingOlder,
                    Position::Latest,
                    selected.clone()
                ),
            );
            cells.insert(/*index*/ 0, cell("final older page"));
            cells[1] = cell("revised older entry");
            view.history = TranscriptHistoryState::Complete;
            view.history_loaded(&cells, 0..1);
            assert_eq!(
                (view.position, view.selected_text(&cells)),
                (Position::Latest, selected),
            );
            assert!(
                text(&render(
                    &mut view, &cells, /*width*/ 20, /*height*/ 2
                ))
                .contains("latest answer")
            );
        }
    }
}

#[test]
fn single_row_scrolling_crosses_an_entry_separator_in_both_directions() {
    let cells = vec![cell("first"), cell("second")];
    let mut view = TranscriptView::default();
    render(&mut view, &cells, /*width*/ 20, /*height*/ 1);
    let mut frames = Vec::new();
    for rows in [-1, -1, 1, 1] {
        view.scroll(&cells, rows);
        frames.push(text(&render(
            &mut view, &cells, /*width*/ 20, /*height*/ 1,
        )));
    }
    assert_eq!(frames, ["", "first", "", "second"]);
}

#[test]
fn selecting_within_an_edge_row_does_not_start_selection_autoscroll() {
    let cells = vec![cell(
        (0..40)
            .map(|row| format!("row {row:02}\n"))
            .collect::<String>(),
    )];
    for edge in [0, 5] {
        let mut view = TranscriptView::default();
        render(&mut view, &cells, /*width*/ 20, /*height*/ 6);
        view.scroll(&cells, /*rows*/ -12);
        let before = render(&mut view, &cells, /*width*/ 20, /*height*/ 6);
        view.handle_mouse(
            MouseEvent {
                kind: MouseEventKind::Down(MouseButton::Left),
                column: 0,
                row: edge,
                modifiers: KeyModifiers::NONE,
            },
            &cells,
        );
        for _ in 0..10 {
            assert!(!view.tick_selection(&cells));
            assert_eq!(
                render(&mut view, &cells, /*width*/ 20, /*height*/ 6),
                before
            );
        }
        view.handle_mouse(
            MouseEvent {
                kind: MouseEventKind::Drag(MouseButton::Left),
                column: 3,
                row: edge,
                modifiers: KeyModifiers::NONE,
            },
            &cells,
        );
        let selected = render(&mut view, &cells, /*width*/ 20, /*height*/ 6);
        assert_eq!(text(&selected), text(&before));
        assert_eq!(view.selected_text(&cells).as_deref(), Some("row"));
        for _ in 0..10 {
            assert!(!view.tick_selection(&cells));
            assert_eq!(
                render(&mut view, &cells, /*width*/ 20, /*height*/ 6),
                selected
            );
            assert_eq!(view.selected_text(&cells).as_deref(), Some("row"));
        }
        if edge == 0 {
            insta::assert_snapshot!("horizontal_selection_at_edge_0", text(&selected));
        }
        let drag = MouseEvent {
            kind: MouseEventKind::Drag(MouseButton::Left),
            column: 3,
            row: edge,
            modifiers: KeyModifiers::NONE,
        };
        view.handle_mouse(
            MouseEvent {
                kind: if edge == 0 {
                    MouseEventKind::ScrollUp
                } else {
                    MouseEventKind::ScrollDown
                },
                ..drag
            },
            &cells,
        );
        render(&mut view, &cells, /*width*/ 20, /*height*/ 6);
        view.handle_mouse(drag, &cells);
        assert!(!view.tick_selection(&cells));
        // A deliberate vertical drag can return to its starting edge and still autoscroll.
        for row in [2, edge] {
            view.handle_mouse(
                MouseEvent {
                    kind: MouseEventKind::Drag(MouseButton::Left),
                    column: 3,
                    row,
                    modifiers: KeyModifiers::NONE,
                },
                &cells,
            );
            render(&mut view, &cells, /*width*/ 20, /*height*/ 6);
        }
        assert!(view.tick_selection(&cells));
    }
}

#[test]
fn wheel_scrolling_pauses_selection_autoscroll_until_the_next_drag() {
    let cells = vec![cell(
        (0..40)
            .map(|row| format!("row {row:02}\n"))
            .collect::<String>(),
    )];
    let mut frames = Vec::new();
    for (kind, edge) in [
        (MouseEventKind::ScrollUp, 0),
        (MouseEventKind::ScrollDown, 5),
    ] {
        let mut view = TranscriptView::default();
        render(&mut view, &cells, /*width*/ 20, /*height*/ 6);
        view.scroll(&cells, /*rows*/ -12);
        render(&mut view, &cells, /*width*/ 20, /*height*/ 6);
        view.begin_selection(&cells, /*column*/ 0, /*row*/ 2, /*clicks*/ 1);
        let drag = MouseEvent {
            kind: MouseEventKind::Drag(MouseButton::Left),
            column: 3,
            row: edge,
            modifiers: KeyModifiers::NONE,
        };
        // Wheel input before the first drag must not lose the original physical row.
        view.handle_mouse(MouseEvent { kind, ..drag }, &cells);
        assert!(!view.tick_selection(&cells));
        render(&mut view, &cells, /*width*/ 20, /*height*/ 6);
        view.handle_mouse(drag, &cells);
        assert!(view.tick_selection(&cells));
        render(&mut view, &cells, /*width*/ 20, /*height*/ 6);
        let selected = view.selected_text(&cells);
        view.handle_mouse(MouseEvent { kind, ..drag }, &cells);
        let after = render(&mut view, &cells, /*width*/ 20, /*height*/ 6);
        for _ in 0..10 {
            assert!(!view.tick_selection(&cells));
            assert_eq!(render(&mut view, &cells, /*width*/ 20, /*height*/ 6), after);
            assert_eq!(view.selected_text(&cells), selected);
        }
        frames.push(text(&after));
        view.handle_mouse(drag, &cells);
        assert!(view.tick_selection(&cells));
        render(&mut view, &cells, /*width*/ 20, /*height*/ 6);
        let selected = view.selected_text(&cells);
        view.handle_mouse(MouseEvent { kind, ..drag }, &cells);
        render(&mut view, &cells, /*width*/ 20, /*height*/ 6);
        view.handle_mouse(
            MouseEvent {
                kind: MouseEventKind::Up(MouseButton::Left),
                ..drag
            },
            &cells,
        );
        assert!(!view.tick_selection(&cells));
        assert_eq!(view.selected_text(&cells), selected);
        let click = MouseEvent {
            kind: MouseEventKind::Down(MouseButton::Left),
            row: 2,
            ..drag
        };
        view.handle_mouse(click, &cells);
        view.handle_mouse(MouseEvent { kind, ..click }, &cells);
        render(&mut view, &cells, /*width*/ 20, /*height*/ 6);
        view.handle_mouse(click, &cells);
        assert_eq!(view.selected_text(&cells), None);
    }
    insta::assert_snapshot!("wheel_stops_selection_autoscroll", frames.join("\n\n"));
}

#[test]
fn only_visible_history_is_laid_out_and_repeated_frames_reuse_it() {
    let renders = Arc::new(AtomicUsize::new(/*v*/ 0));
    let cells = (0..10_000)
        .map(|index| {
            Arc::new(TestCell {
                text: format!("message {index}"),
                renders: Arc::clone(&renders),
            }) as Arc<dyn HistoryCell>
        })
        .collect::<Vec<_>>();
    let mut view = TranscriptView::default();
    render(&mut view, &cells, /*width*/ 40, /*height*/ 20);
    let initial = renders.load(Ordering::Relaxed);
    assert!(
        initial <= 12,
        "laid out {initial} history entries for a 20-row viewport"
    );
    render(&mut view, &cells, /*width*/ 40, /*height*/ 20);
    assert_eq!(renders.load(Ordering::Relaxed), initial);
    for highlight in [Some(9_999), Some(9_998), Some(9_998), None] {
        view.set_highlight(highlight);
        render(&mut view, &cells, /*width*/ 40, /*height*/ 20);
        assert_eq!(renders.load(Ordering::Relaxed), initial);
    }
}

#[test]
fn selection_copies_source_across_wraps_and_survives_prepend() {
    let mut cells = vec![cell("alpha beta gamma delta")];
    let mut view = TranscriptView::default();
    render(&mut view, &cells, /*width*/ 10, /*height*/ 5);
    view.begin_selection(&cells, /*column*/ 0, /*row*/ 0, /*clicks*/ 1);
    view.extend_selection(/*column*/ 5, /*row*/ 2);
    assert_eq!(
        view.selected_text(&cells),
        Some("alpha beta gamma delta".to_string())
    );
    cells.insert(/*index*/ 0, cell("earlier page"));
    render(&mut view, &cells, /*width*/ 20, /*height*/ 5);
    assert_eq!(
        view.selected_text(&cells),
        Some("alpha beta gamma delta".to_string())
    );
}

#[test]
fn resize_keeps_the_logical_reading_anchor() {
    let mut cells = vec![cell(
        "alpha beta gamma delta epsilon zeta eta theta iota kappa lambda",
    )];
    let mut view = TranscriptView::default();
    render(&mut view, &cells, /*width*/ 10, /*height*/ 2);
    view.scroll(&cells, /*rows*/ -2);
    let Position::Reading(anchor) = view.position else {
        panic!("reading anchor");
    };
    render(&mut view, &cells, /*width*/ 20, /*height*/ 2);
    assert_eq!(view.position, Position::Reading(anchor));
    let replacement = cell("final answer");
    view.replace_range(&cells, 0..1, &replacement);
    cells.splice(0..1, [replacement]);
    assert_eq!(
        text(&render(
            &mut view, &cells, /*width*/ 20, /*height*/ 2
        )),
        "final answer\n"
    );
    assert!(!view.is_following());
    assert!(!view.unseen_activity);
    cells.push(cell("new output"));
    render(&mut view, &cells, /*width*/ 20, /*height*/ 2);
    assert!(view.unseen_activity);
}

#[test]
fn zero_sized_viewport_is_safe_and_empty_history_accepts_navigation() {
    let mut view = TranscriptView::default();
    render(&mut view, &[], /*width*/ 0, /*height*/ 0);
    view.scroll(&[], /*rows*/ -10);
    view.scroll(&[], /*rows*/ 10);
    assert_eq!(view.selected_text(&[]), None);
}

#[derive(Debug)]
struct MutableHistoryCell {
    version: Arc<AtomicUsize>,
    renders: Arc<AtomicUsize>,
}

impl HistoryCell for MutableHistoryCell {
    fn display_lines(&self, _width: u16) -> Vec<Line<'static>> {
        self.renders.fetch_add(/*val*/ 1, Ordering::Relaxed);
        let version = self.version.load(Ordering::Relaxed);
        (0..8)
            .map(|row| Line::from(format!("version {version} row {row}")))
            .collect()
    }

    fn raw_lines(&self) -> Vec<Line<'static>> {
        self.display_lines(/*width*/ 80)
    }

    fn has_stable_transcript_height(&self) -> bool {
        false
    }
}

#[test]
fn mutable_history_formats_once_per_frame_and_refreshes_the_next_frame() {
    let version = Arc::new(AtomicUsize::new(/*v*/ 1));
    let renders = Arc::new(AtomicUsize::new(/*v*/ 0));
    let cells: Vec<Arc<dyn HistoryCell>> = vec![Arc::new(MutableHistoryCell {
        version: Arc::clone(&version),
        renders: Arc::clone(&renders),
    })];
    let mut view = TranscriptView::default();
    let first = render(&mut view, &cells, /*width*/ 24, /*height*/ 5);
    assert_eq!(renders.load(Ordering::Relaxed), 1);
    version.store(/*val*/ 2, Ordering::Relaxed);
    let second = render(&mut view, &cells, /*width*/ 24, /*height*/ 5);
    assert_eq!(renders.load(Ordering::Relaxed), 2);
    assert!(text(&first).contains("version 1 row 3"));
    insta::assert_snapshot!(text(&second), @"
    version 2 row 3
    version 2 row 4
    version 2 row 5
    version 2 row 6
    version 2 row 7
    ");
    view.begin_search();
    view.paste_search("version 2");
    assert!(!view.advance_search(&cells));
    let matched = text(&render(
        &mut view, &cells, /*width*/ 24, /*height*/ 5,
    ));
    view.begin_selection(&cells, /*column*/ 0, /*row*/ 0, /*clicks*/ 3);
    version.store(/*val*/ 3, Ordering::Relaxed);
    view.end_selection(&cells);
    assert_eq!(
        text(&render(
            &mut view, &cells, /*width*/ 24, /*height*/ 5
        )),
        matched
    );
    view.handle_key(
        KeyEvent::new(KeyCode::Char('n'), KeyModifiers::CONTROL),
        &cells,
    );
    assert!(!view.advance_search(&cells));
    assert!(
        text(&render(
            &mut view, &cells, /*width*/ 24, /*height*/ 5
        ))
        .contains("version 3")
    );
}

#[test]
fn selected_latest_content_keeps_new_activity_visible() {
    let mut cells = vec![cell("selected")];
    let mut view = TranscriptView::default();
    render(&mut view, &cells, /*width*/ 24, /*height*/ 3);
    view.begin_selection(&cells, /*column*/ 0, /*row*/ 0, /*clicks*/ 3);
    view.scroll(&cells, /*rows*/ 10);
    cells.push(cell("new output"));
    render(&mut view, &cells, /*width*/ 24, /*height*/ 3);
    assert!(!view.is_following());
    insta::assert_snapshot!(view.status_line_with_navigation("Enter/Ctrl+C copy · Esc clear selection", crate::motion::MotionMode::Reduced).to_string(), @"New activity · Enter/Ctrl+C copy · Esc clear selection");
}

#[test]
fn live_reader_survives_commit_new_tail_resize_and_selection_then_rejoins_latest() {
    let mut cells = vec![cell("before")];
    let original = cell("live zero\nlive one\nlive two\nlive three\nlive four\nlive five");
    let mut view = TranscriptView::default();
    view.sync_live_tail(/*width*/ 20, /*key*/ None, |width| {
        Some(original.display_hyperlink_lines(width))
    });
    render(&mut view, &cells, /*width*/ 20, /*height*/ 2);
    view.scroll(&cells, /*rows*/ -2);
    let before = text(&render(
        &mut view, &cells, /*width*/ 20, /*height*/ 2,
    ));
    let reading = view.position;
    cells.push(Arc::clone(&original));
    view.sync_live_tail(/*width*/ 20, /*key*/ None, |_| None);
    assert_eq!(
        text(&render(
            &mut view, &cells, /*width*/ 20, /*height*/ 2
        )),
        before
    );
    view.sync_live_tail(
        /*width*/ 20,
        /*key*/ None,
        |_| Some(vec![HyperlinkLine::from("new tail")]),
    );
    assert_eq!(
        text(&render(
            &mut view, &cells, /*width*/ 20, /*height*/ 2
        )),
        before
    );
    render(&mut view, &cells, /*width*/ 9, /*height*/ 2);
    assert_eq!(view.position, reading);
    assert!(
        view.status_line_with_navigation("", crate::motion::MotionMode::Reduced)
            .to_string()
            .contains("New activity")
    );
    view.begin_selection(&cells, /*column*/ 0, /*row*/ 0, /*clicks*/ 3);
    assert_eq!(view.selected_text(&cells), Some("live two\n".to_string()));
    view.begin_search();
    assert!(!view.advance_search(&cells));
    view.handle_key(KeyCode::Esc.into(), &cells);
    assert_eq!(
        text(&render(
            &mut view, &cells, /*width*/ 20, /*height*/ 2
        )),
        before
    );
    view.jump_to_latest();
    assert!(view.snapshot().is_none());
    insta::assert_snapshot!(text(&render(&mut view, &cells, /*width*/ 20, /*height*/ 2)), @"

    new tail
    ");
}

#[test]
fn scrolling_out_of_held_live_content_releases_its_revision() {
    let mut cells = vec![cell("before")];
    let original = cell("zero\none\ntwo\nthree\nfour\nfive");
    let mut view = TranscriptView::default();
    view.sync_live_tail(/*width*/ 20, /*key*/ None, |width| {
        Some(original.display_hyperlink_lines(width))
    });
    render(&mut view, &cells, /*width*/ 20, /*height*/ 2);
    view.scroll(&cells, /*rows*/ -2);
    assert!(view.held_reading.is_some());
    cells.push(Arc::clone(&original));
    view.sync_live_tail(/*width*/ 20, /*key*/ None, |_| None);
    view.scroll(&cells, /*rows*/ -100);
    assert!(view.held_reading.is_none());
    view.scroll(&cells, /*rows*/ 100);
    assert!(view.is_following());
    assert!(
        text(&render(
            &mut view, &cells, /*width*/ 20, /*height*/ 2
        ))
        .contains("five")
    );
}

#[test]
fn repeated_resize_preserves_plain_and_markdown_passages_before_the_next_draw() {
    let passage = (0..40)
        .map(|index| format!("café{index:02}"))
        .collect::<Vec<_>>()
        .join(" ");
    let entries = [
        cell(passage.clone()),
        Arc::new(crate::history_cell::AgentMarkdownCell::new(
            passage,
            std::path::Path::new("/workspace"),
        )) as Arc<dyn HistoryCell>,
    ];
    for entry in entries {
        let cells = vec![cell("older entry"), entry];
        let mut view = TranscriptView::default();
        render(&mut view, &cells, /*width*/ 18, /*height*/ 3);
        view.scroll(&cells, /*rows*/ -5);
        // Resize can arrive before the first frame at this new reading position.
        let before_resize = view.position;
        let wide = text(&render(
            &mut view, &cells, /*width*/ 34, /*height*/ 3,
        ));
        let original = render(&mut view, &cells, /*width*/ 18, /*height*/ 3);
        assert_eq!(view.position, before_resize);
        let original_text = text(&original);
        let first_word = original_text
            .split_whitespace()
            .find(|word| word.starts_with("café"))
            .expect("visible Unicode passage");
        assert!(wide.contains(first_word));
        let narrow = render(&mut view, &cells, /*width*/ 12, /*height*/ 3);
        assert!(text(&narrow).contains(first_word));
        assert_eq!(
            render(&mut view, &cells, /*width*/ 18, /*height*/ 3),
            original
        );
        assert!(!view.is_following());
    }
}

#[test]
fn resizing_repeated_lines_keeps_the_same_occurrence() {
    let duplicate = "alpha beta gamma delta";
    let mut cells = vec![cell(format!(
        "{duplicate}\n{duplicate}\nsecond occurrence ends\ntrailing one\ntrailing two\ntrailing three"
    ))];
    let mut view = TranscriptView::default();
    view.jump_to_entry(&cells, /*index*/ 0);
    render(&mut view, &cells, /*width*/ 12, /*height*/ 2);
    view.scroll(&cells, /*rows*/ 3);
    let original = cells[0]
        .raw_lines()
        .iter()
        .map(ToString::to_string)
        .collect::<Vec<_>>()
        .join("\n");
    cells.insert(/*index*/ 0, cell("older report"));
    view.history_loaded(&cells, 0..1);
    let replacement = cell(format!("older report\n\n{original}"));
    view.replace_group(&cells, 0..2, &replacement);
    cells.splice(0..2, [replacement]);
    let wide = render(&mut view, &cells, /*width*/ 24, /*height*/ 2);
    insta::assert_snapshot!(text(&wide), @"
    alpha beta gamma delta
    second occurrence ends
    ");
    let narrow = render(&mut view, &cells, /*width*/ 12, /*height*/ 2);
    insta::assert_snapshot!(text(&narrow), @"
    gamma delta
    second
    ");
}

#[test]
fn resize_uses_the_passage_from_the_current_live_or_replaced_entry() {
    for entry_kind in ["live", "replaced"] {
        let initial = (0..40)
            .map(|index| format!("old{index:02}"))
            .collect::<Vec<_>>()
            .join(" ");
        let revised = (0..40)
            .map(|index| format!("café{index:02}"))
            .collect::<Vec<_>>()
            .join(" ");
        let mut view = TranscriptView::default();
        let mut cells = Vec::new();
        if entry_kind == "live" {
            view.sync_live_tail(/*width*/ 18, /*key*/ None, |_| {
                Some(crate::terminal_hyperlinks::plain_hyperlink_lines(vec![
                    initial.into(),
                ]))
            });
        } else {
            cells.push(cell(initial));
        }
        render(&mut view, &cells, /*width*/ 18, /*height*/ 3);
        view.scroll(&cells, /*rows*/ -5);
        if entry_kind == "live" {
            view.sync_live_tail(/*width*/ 18, /*key*/ None, |_| {
                Some(crate::terminal_hyperlinks::plain_hyperlink_lines(vec![
                    revised.clone().into(),
                ]))
            });
        } else {
            cells[0] = cell(revised.clone());
        }
        let current = render(&mut view, &cells, /*width*/ 18, /*height*/ 3);
        let current_text = text(&current);
        let first_word = current_text.split_whitespace().next().unwrap();
        if entry_kind == "live" {
            view.sync_live_tail(/*width*/ 34, /*key*/ None, |_| {
                Some(crate::terminal_hyperlinks::plain_hyperlink_lines(vec![
                    revised.clone().into(),
                ]))
            });
        }
        let wide = render(&mut view, &cells, /*width*/ 34, /*height*/ 3);
        assert!(text(&wide).contains(first_word), "{entry_kind}");
        if entry_kind == "live" {
            view.sync_live_tail(/*width*/ 18, /*key*/ None, |_| {
                Some(crate::terminal_hyperlinks::plain_hyperlink_lines(vec![
                    revised.into(),
                ]))
            });
        }
        assert_eq!(
            render(&mut view, &cells, /*width*/ 18, /*height*/ 3),
            current,
            "{entry_kind}"
        );
    }
}

#[test]
fn history_prefetch_counts_rows_in_preceding_entries() {
    for preceding_rows in [1, 100] {
        let cells = vec![
            cell(
                (0..preceding_rows)
                    .map(|row| format!("older {row}\n"))
                    .collect::<String>(),
            ),
            cell("current first\ncurrent last"),
        ];
        let mut view = TranscriptView::default();
        render(&mut view, &cells, /*width*/ 24, /*height*/ 4);
        assert_eq!(view.near_start(&cells), preceding_rows == 1);
        view.jump_to_entry(&cells, /*index*/ 1);
        assert_eq!(view.near_start(&cells), preceding_rows == 1);
        view.jump_to_entry(&cells, /*index*/ 0);
        assert!(view.near_start(&cells));
    }
}

#[test]
fn user_prompt_has_a_background_without_a_terminal_palette() {
    use crate::history_cell::new_user_prompt;
    use codex_protocol::user_input::TextElement;
    use std::path::PathBuf;

    let message = "First steering message";
    let cells: Vec<Arc<dyn HistoryCell>> = vec![Arc::new(new_user_prompt(
        message.into(),
        vec![TextElement::new((0..5).into(), Some("First".into()))],
        vec![PathBuf::from("screenshot.png")],
        Vec::new(),
    ))];
    crate::terminal_palette::with_test_default_colors(
        /*colors*/ None::<crate::terminal_probe::DefaultColors>,
        || {
            let mut view = TranscriptView::default();
            let buffer = render(&mut view, &cells, /*width*/ 32, /*height*/ 8);
            insta::assert_snapshot!(
                "user_prompt_has_a_background_without_a_terminal_palette",
                format!("{buffer:?}")
            );
        },
    );
}

#[test]
fn spoken_prompt_selection_keeps_its_fallback_contrast() {
    use crate::history_cell::new_spoken_user_prompt;
    use ratatui::style::Color;
    use ratatui::style::Modifier;

    crate::terminal_palette::with_test_default_colors(
        /*colors*/ None::<crate::terminal_probe::DefaultColors>,
        || {
            let cells: Vec<Arc<dyn HistoryCell>> =
                vec![Arc::new(new_spoken_user_prompt("one\nnext".into()))];
            for detailed in [false, true] {
                let mut view = TranscriptView {
                    detailed,
                    ..TranscriptView::default()
                };
                let before = render(&mut view, &cells, /*width*/ 20, /*height*/ 5);
                let rendered = text(&before);
                let first = rendered
                    .lines()
                    .position(|row| row.contains("› one"))
                    .unwrap() as u16;
                let second = rendered
                    .lines()
                    .position(|row| row.contains("next"))
                    .unwrap() as u16;
                view.begin_selection(&cells, /*column*/ 2, first, /*clicks*/ 1);
                view.extend_selection(/*column*/ 2, second);
                let selected = render(&mut view, &cells, /*width*/ 20, /*height*/ 5);
                let newline = &selected[(5, first)];
                assert_eq!(
                    (
                        view.selected_text(&cells).as_deref(),
                        selected[(0, first)].fg,
                        newline.fg,
                        newline.bg,
                        newline.modifier.contains(Modifier::REVERSED),
                    ),
                    (
                        Some("one\n"),
                        Color::Red,
                        Color::Reset,
                        Color::DarkGray,
                        true
                    )
                );
            }
        },
    );
}

#[test]
fn unleashed_collapsed_tool_activity_hover_and_click_toggle_complete_section() {
    use crate::exec_cell::CommandOutput;
    use crate::exec_cell::new_active_exec_command;
    use codex_app_server_protocol::CommandExecutionSource;
    use ratatui::style::Color;
    use std::time::Duration;

    let mut command = new_active_exec_command(
        "interactive".into(),
        vec!["echo visible output".into()],
        Vec::new(),
        CommandExecutionSource::Agent,
        /*interaction_input*/ None,
        /*animations_enabled*/ false,
    )
    .with_collapsed_tool_activity(/*enabled*/ true);
    assert!(command.complete_call(
        "interactive",
        CommandOutput::new(/*exit_code*/ 0, "first output\nsecond output\n".into()),
        Duration::from_secs(1)
    ));
    let cells: Vec<Arc<dyn HistoryCell>> = vec![Arc::new(command), cell("normal terminal output")];
    let mut view = TranscriptView::default();
    let collapsed = render(&mut view, &cells, /*width*/ 80, /*height*/ 20);
    assert!(text(&collapsed).contains("Ran 1 shell command"));
    assert!(!text(&collapsed).contains("first output"));
    assert_eq!(collapsed[(2, 0)].fg, Color::DarkGray);
    let mouse = |kind, column, row| MouseEvent {
        kind,
        column,
        row,
        modifiers: KeyModifiers::NONE,
    };
    assert!(
        view.handle_mouse(mouse(MouseEventKind::Moved, 60, 0), &cells)
            .is_some()
    );
    let hover = render(&mut view, &cells, /*width*/ 80, /*height*/ 20);
    assert_eq!(hover[(2, 0)].fg, Color::Gray);
    assert!(
        view.handle_mouse(
            mouse(MouseEventKind::Down(MouseButton::Left), 60, 0),
            &cells
        )
        .is_some()
    );
    let expanded = render(&mut view, &cells, /*width*/ 80, /*height*/ 20);
    assert!(text(&expanded).contains("first output"));
    let output_row = (0..20)
        .find(|y| {
            (0..80)
                .map(|x| expanded[(x, *y)].symbol())
                .collect::<String>()
                .contains("first output")
        })
        .unwrap();
    assert_eq!(expanded[(60, output_row)].bg, Color::Gray);
    let normal_row = (0..20)
        .find(|y| {
            (0..80)
                .map(|x| expanded[(x, *y)].symbol())
                .collect::<String>()
                .contains("normal terminal output")
        })
        .unwrap();
    assert_ne!(expanded[(60, normal_row)].bg, Color::Gray);
    assert!(
        view.handle_mouse(
            mouse(MouseEventKind::Down(MouseButton::Left), 60, output_row),
            &cells
        )
        .is_some()
    );
    let collapsed_again = render(&mut view, &cells, /*width*/ 80, /*height*/ 20);
    assert!(!text(&collapsed_again).contains("first output"));
    view.handle_mouse(mouse(MouseEventKind::Moved, 79, 19), &cells);
    let unhovered = render(&mut view, &cells, /*width*/ 80, /*height*/ 20);
    assert_eq!(unhovered[(2, 0)].fg, Color::DarkGray);
}

#[test]
fn unleashed_collapsed_tool_activity_summary_click_expands_without_footer() {
    use crate::exec_cell::CommandOutput;
    use crate::exec_cell::new_active_exec_command;
    use codex_app_server_protocol::CommandExecutionSource;
    let mut command = new_active_exec_command(
        "click".into(),
        vec!["echo details".into()],
        Vec::new(),
        CommandExecutionSource::Agent,
        /*interaction_input*/ None,
        /*animations_enabled*/ false,
    )
    .with_collapsed_tool_activity(/*enabled*/ true);
    command.complete_call(
        "click",
        CommandOutput::new(/*exit_code*/ 0, "retained details\n".into()),
        std::time::Duration::from_secs(1),
    );
    let cells: Vec<Arc<dyn HistoryCell>> = vec![Arc::new(command)];
    let mut view = TranscriptView::default();
    render(&mut view, &cells, /*width*/ 80, /*height*/ 20);
    view.handle_mouse(
        MouseEvent {
            kind: MouseEventKind::Down(MouseButton::Left),
            column: 60,
            row: 0,
            modifiers: KeyModifiers::NONE,
        },
        &cells,
    );
    assert!(
        text(&render(
            &mut view, &cells, /*width*/ 80, /*height*/ 20
        ))
        .contains("retained details")
    );
}

#[test]
fn unleashed_collapsed_tool_activity_auxiliary_output_stays_outside_section() {
    use crate::history_cell::ActivityDisclosure;
    let mut view = TranscriptView::default();
    view.sync_live_activity(&[], vec!["exec:auxiliary".into()]);
    view.sync_live_activity_tail(
        /*width*/ 80,
        Some(ActiveCellTranscriptKey {
            revision: 1,
            animation_tick: None,
            is_stream_continuation: false,
            cacheable: true,
        }),
        /*expanded*/ true,
        |_| {
            Some(ActivityTranscriptLines {
                activity: vec![HyperlinkLine::from("expanded command output")],
                auxiliary: vec![HyperlinkLine::from("normal auxiliary output")],
                disclosure: Some(ActivityDisclosure::ToolActivity),
            })
        },
    );
    let buffer = render(&mut view, &[], /*width*/ 80, /*height*/ 20);
    assert_eq!(buffer[(60, 0)].bg, ratatui::style::Color::Gray);
    assert_ne!(buffer[(60, 1)].bg, ratatui::style::Color::Gray);
}

#[test]
fn unleashed_collapsed_tool_activity_live_output_count_and_narrow_failure_stay_visible() {
    use crate::exec_cell::CommandOutput;
    use crate::exec_cell::new_active_exec_command;
    use codex_app_server_protocol::CommandExecutionSource;
    let script = format!("echo first\necho {}", "long command ".repeat(100));
    let mut command = new_active_exec_command(
        "stream".into(),
        vec!["bash".into(), "-lc".into(), script.clone()],
        Vec::new(),
        CommandExecutionSource::Agent,
        /*interaction_input*/ None,
        /*animations_enabled*/ false,
    )
    .with_collapsed_tool_activity(/*enabled*/ true);
    assert!(command.append_output("stream", "one output line\n"));
    let live = command
        .display_lines(/*width*/ 80)
        .iter()
        .map(ToString::to_string)
        .collect::<Vec<_>>()
        .join("\n");
    assert!(live.contains("1 line)"), "{live}");
    command.complete_call(
        "stream",
        CommandOutput::new(/*exit_code*/ 1, "failure details\n".into()),
        std::time::Duration::from_secs(1),
    );
    let compact = command
        .display_lines(/*width*/ 20)
        .iter()
        .map(ToString::to_string)
        .collect::<Vec<_>>()
        .join("\n");
    assert!(compact.contains("1 failed"), "{compact}");
    let raw = command
        .raw_lines()
        .iter()
        .map(ToString::to_string)
        .collect::<Vec<_>>()
        .join("\n");
    assert!(
        script.lines().all(|line| raw.contains(line.trim_end())),
        "{raw}"
    );
    assert!(raw.contains("failure details"));
}

#[test]
fn readable_command_preview_streaming_completion_and_resize_preserve_focus() {
    let mut command = crate::exec_cell::new_active_exec_command(
        "stream".into(),
        vec!["echo".into(), "hello".into()],
        Vec::new(),
        codex_app_server_protocol::CommandExecutionSource::Agent,
        /*interaction_input*/ None,
        /*animations_enabled*/ false,
    )
    .with_readable_commands(/*enabled*/ true);
    command.retain_command_source("stream", "echo 'hello'".into());
    assert!(command.append_output(
        "stream",
        "first output\nsecond output\nthird output\nfourth output\n"
    ));
    let mut view = TranscriptView::default();
    let sync = |view: &mut TranscriptView, command: &crate::exec_cell::ExecCell, width| {
        let expanded = view.sync_live_activity(&[], command.activity_ids());
        view.sync_live_activity_tail(width, /*key*/ None, expanded, |width| {
            Some(ActivityTranscriptLines {
                activity: if expanded {
                    command.expanded_hyperlink_lines(width)
                } else {
                    command.compact_hyperlink_lines(width)
                },
                auxiliary: Vec::new(),
                disclosure: command.activity_disclosure(width),
            })
        });
    };
    sync(&mut view, &command, 80);
    assert!(
        !text(&render(
            &mut view,
            &[],
            /*width*/ 80,
            /*height*/ 25
        ))
        .contains("first output")
    );
    view.handle_key(KeyCode::F(4).into(), &[]);
    view.handle_key(KeyCode::Right.into(), &[]);
    sync(&mut view, &command, 80);
    assert!(
        text(&render(
            &mut view,
            &[],
            /*width*/ 80,
            /*height*/ 25
        ))
        .contains("first output")
    );
    assert!(command.append_output("stream", "arriving output\n"));
    sync(&mut view, &command, 48);
    let streamed = text(&render(
        &mut view,
        &[],
        /*width*/ 48,
        /*height*/ 25,
    ));
    assert!(streamed.contains("first output"));
    assert!(streamed.contains("arriving output"));
    assert!(view.is_activity_focused());
    assert!(command.complete_call(
        "stream",
        crate::exec_cell::CommandOutput::new(
            /*exit_code*/ 0,
            "first output\nsecond output\nthird output\nfourth output\narriving output\n".into(),
        ),
        std::time::Duration::ZERO
    ));
    let cells: Vec<Arc<dyn HistoryCell>> = vec![Arc::new(command)];
    view.sync_live_activity(&cells, Vec::new());
    view.sync_live_tail(/*width*/ 48, /*key*/ None, |_| None);
    let completed = text(&render(
        &mut view, &cells, /*width*/ 48, /*height*/ 25,
    ));
    assert!(completed.contains("first output"));
    assert!(completed.contains("arriving output"));
    assert!(view.is_activity_focused());
    view.handle_key(KeyCode::Left.into(), &cells);
    assert!(
        !text(&render(
            &mut view, &cells, /*width*/ 48, /*height*/ 25
        ))
        .contains("first output")
    );
}

#[test]
fn readable_command_preview_up_down_select_independent_sections() {
    let cells: Vec<Arc<dyn HistoryCell>> = vec![
        Arc::new(readable_test_command("first", "echo first")),
        Arc::new(readable_test_command("second", "echo second")),
    ];
    let mut view = TranscriptView::default();
    render(&mut view, &cells, /*width*/ 80, /*height*/ 30);
    view.handle_key(KeyCode::F(4).into(), &cells);
    assert_eq!(view.disclosure.focused, Some(cells[1].activity_ids()));
    view.handle_key(KeyCode::Up.into(), &cells);
    assert_eq!(view.disclosure.focused, Some(cells[0].activity_ids()));
    view.handle_key(KeyCode::Right.into(), &cells);
    assert!(view.disclosure.is_expanded(&cells[0].activity_ids()));
    assert!(!view.disclosure.is_expanded(&cells[1].activity_ids()));
    view.handle_key(KeyCode::Down.into(), &cells);
    assert_eq!(view.disclosure.focused, Some(cells[1].activity_ids()));
    view.handle_key(KeyCode::Enter.into(), &cells);
    assert!(view.disclosure.is_expanded(&cells[0].activity_ids()));
    assert!(view.disclosure.is_expanded(&cells[1].activity_ids()));
    view.handle_key(KeyCode::Up.into(), &cells);
    view.handle_key(KeyCode::Left.into(), &cells);
    assert!(!view.disclosure.is_expanded(&cells[0].activity_ids()));
    assert!(view.disclosure.is_expanded(&cells[1].activity_ids()));
}
