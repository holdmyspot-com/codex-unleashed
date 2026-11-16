//! Compact command activity retains complete command and output in the detailed transcript.

use super::model::ExecCell;
use crate::exec_command::strip_bash_lc_and_escape;
use crate::history_cell::activity_preview::clipped_line;
use crate::terminal_hyperlinks::HyperlinkLine;
use codex_protocol::parse_command::ParsedCommand;
use ratatui::style::Stylize;
use ratatui::text::Line;
use std::collections::HashSet;

impl ExecCell {
    pub(super) fn tool_activity_lines(&self, width: u16) -> Vec<HyperlinkLine> {
        let active = self.is_active();
        let reads = self
            .group
            .calls
            .iter()
            .flat_map(|call| &call.parsed)
            .filter_map(|parsed| match parsed {
                ParsedCommand::Read { path, .. } => Some(path),
                _ => None,
            })
            .collect::<HashSet<_>>()
            .len();
        let shells = self
            .group
            .calls
            .iter()
            .filter(|call| {
                call.parsed.is_empty()
                    || !call
                        .parsed
                        .iter()
                        .all(|parsed| matches!(parsed, ParsedCommand::Read { .. }))
            })
            .count();
        let mut parts = Vec::new();
        if reads > 0 {
            parts.push(format!(
                "{} {reads} {}",
                if active { "Reading" } else { "Read" },
                if reads == 1 { "file" } else { "files" }
            ));
        }
        if shells > 0 {
            parts.push(format!(
                "{} {shells} shell {}",
                if active {
                    if reads > 0 { "running" } else { "Running" }
                } else if reads > 0 {
                    "ran"
                } else {
                    "Ran"
                },
                if shells == 1 { "command" } else { "commands" }
            ));
        }
        let mut summary = parts.join(", ");
        if active {
            summary.push_str(&format!(
                " · {}s…",
                self.activity_started_at.elapsed().as_secs()
            ));
        }
        let failures = self
            .group
            .calls
            .iter()
            .filter(|call| {
                call.output
                    .as_ref()
                    .is_some_and(|output| output.exit_code != 0)
            })
            .count();
        let header = if failures > 0 {
            format!("! {failures} failed · {summary}")
        } else {
            format!("• {summary}")
        };
        let mut lines = vec![clipped_line(Line::from(header.dark_gray()), width)];
        if active
            && let Some(call) = self
                .group
                .calls
                .iter()
                .rev()
                .find(|call| call.duration.is_none())
        {
            let command = strip_bash_lc_and_escape(&call.command)
                .lines()
                .map(str::trim)
                .collect::<Vec<_>>()
                .join(" ");
            let elapsed = call
                .start_time
                .map(|start| start.elapsed().as_secs())
                .unwrap_or_default();
            let output_lines = call
                .output
                .as_ref()
                .map(|output| output.line_counts().0)
                .unwrap_or_default();
            let noun = if output_lines == 1 { "line" } else { "lines" };
            let suffix = format!(" ({elapsed}s · {output_lines} {noun})");
            let preview_width = width.saturating_sub(
                Line::from(suffix.clone())
                    .width()
                    .try_into()
                    .unwrap_or(u16::MAX),
            );
            let mut preview = clipped_line(
                Line::from(format!("  ⎿  $ {command}").dark_gray()),
                preview_width,
            );
            preview.line.spans.push(suffix.dark_gray());
            lines.push(clipped_line(preview.line, width));
        }
        lines
    }
}

#[cfg(test)]
mod tests {
    use crate::history_cell::HistoryCell;

    #[test]
    fn unleashed_collapsed_tool_activity_elapsed_time_invalidates_live_transcript() {
        let mut cell = crate::exec_cell::new_active_exec_command(
            "timer".to_string(),
            vec!["sleep".to_string(), "60".to_string()],
            Vec::new(),
            codex_app_server_protocol::CommandExecutionSource::Agent,
            /*interaction_input*/ None,
            /*animations_enabled*/ false,
        )
        .with_collapsed_tool_activity(/*enabled*/ true);
        cell.activity_started_at = std::time::Instant::now() - std::time::Duration::from_secs(12);
        assert_eq!(cell.transcript_animation_tick(), Some(12));
        cell.activity_started_at -= std::time::Duration::from_secs(1);
        assert_eq!(cell.transcript_animation_tick(), Some(13));
        cell.collapsed_tool_activity = false;
        assert_eq!(cell.transcript_animation_tick(), None);
        cell.collapsed_tool_activity = true;
        cell.complete_call(
            "timer",
            crate::exec_cell::CommandOutput::new(/*exit_code*/ 0, String::new()),
            std::time::Duration::from_secs(13),
        );
        assert_eq!(cell.transcript_animation_tick(), None);
    }
}
