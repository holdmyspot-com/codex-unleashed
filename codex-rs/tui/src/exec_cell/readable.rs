//! Readable command previews share exact copy payloads with their expanded presentation.

use super::model::ExecCell;
use super::preview::command_preview;
use crate::history_cell::activity_preview::DETAIL_PREVIEW_LINES;
use crate::history_cell::activity_preview::clipped_line;
use crate::terminal_hyperlinks::HyperlinkLine;
use crate::terminal_hyperlinks::LogicalLineSource;
use codex_ansi_escape::ansi_escape_line;
use codex_shell_command::bash::extract_bash_command;
use ratatui::style::Stylize;
use ratatui::text::Line;
use std::sync::Arc;

impl ExecCell {
    /// Render every grouped call in order, retaining only the existing output-tail budget.
    pub(super) fn readable_command_lines(&self, width: u16) -> Vec<HyperlinkLine> {
        let mut lines = Vec::new();
        for call in self.iter_calls() {
            if call.is_unified_exec_interaction() {
                continue;
            }
            let failed = call.output.as_ref().filter(|output| output.exit_code != 0);
            let title = if let Some(output) = failed {
                format!("Failed (exit {})", output.exit_code)
            } else if call.duration.is_none() {
                "Running".to_owned()
            } else if call.is_user_shell_command() {
                "You ran".to_owned()
            } else {
                "Ran".to_owned()
            };
            let marker = if failed.is_some() {
                "•".red()
            } else {
                "•".dim()
            };
            let header = Line::from(vec![marker, " ".into(), title.bold(), " ".into()]);
            let indent = header.width().min(usize::from(width).saturating_sub(1));
            let available = usize::from(width).saturating_sub(indent).max(1);
            let original: Arc<str> = self.command_source(call).into_owned().into();
            let preview = if let Some((_, script)) = extract_bash_command(&call.command) {
                let wrapper = crate::exec_command::escape_command(
                    &call.command[..call.command.len().saturating_sub(1)],
                );
                let mut preview = command_preview(&wrapper, available);
                preview.extend(command_preview(script, available));
                preview
            } else {
                command_preview(&original, available)
            };
            for (index, mut line) in preview.into_iter().enumerate() {
                line.spans.splice(
                    0..0,
                    if index == 0 && header.width() < usize::from(width) {
                        header.spans.clone()
                    } else {
                        vec![" ".repeat(indent).into()]
                    },
                );
                let mut source = LogicalLineSource::from_line(&line);
                source.command = Some(Arc::clone(&original));
                let mut rendered = HyperlinkLine::from(line);
                rendered.source = Some(source);
                lines.push(rendered);
            }
            if let Some(output) = &call.output {
                let mut tail: Vec<_> = output.lines().rev().take(DETAIL_PREVIEW_LINES).collect();
                tail.reverse();
                for (index, raw) in tail.into_iter().enumerate() {
                    let mut line = ansi_escape_line(raw.as_ref());
                    line.spans
                        .insert(0, if index == 0 { "  └ " } else { "    " }.dim());
                    lines.push(clipped_line(line, width));
                }
            }
        }
        lines
    }
}
