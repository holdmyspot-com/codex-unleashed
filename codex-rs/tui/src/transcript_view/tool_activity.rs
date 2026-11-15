//! Tool section surfaces follow current viewport geometry, including wrapping and scrolling.

use super::*;
use ratatui::style::Color;
use ratatui::style::Modifier;
use ratatui::style::Style;

#[derive(Clone, Copy)]
pub(super) enum ToolActivityPresentation {
    Collapsed { source_end: usize },
    Expanded { source_end: usize },
}

impl TranscriptView {
    pub(super) fn render_tool_activity(&self, buf: &mut Buffer) {
        let hovered = self.disclosure.pointer.and_then(|(x, y)| {
            if !self.area.contains(ratatui::layout::Position::new(x, y)) {
                return None;
            }
            let row = self.visible.get(usize::from(y - self.area.y))?;
            if row.layout.separated && row.row == 0 {
                return None;
            }
            let presentation = row.layout.tool_activity?;
            if row.layout.position_at(row.row, /*column*/ 0) > presentation.source_end() {
                return None;
            }
            Some(&row.activity_ids)
        });
        for (screen_row, row) in self.visible.iter().enumerate() {
            if row.layout.separated && row.row == 0 {
                continue;
            }
            let Some(presentation) = row.layout.tool_activity else {
                continue;
            };
            if row.layout.position_at(row.row, /*column*/ 0) > presentation.source_end() {
                continue;
            }
            let style = match presentation {
                ToolActivityPresentation::Collapsed { .. } => {
                    let highlighted = hovered
                        .is_some_and(|ids| ids.iter().any(|id| row.activity_ids.contains(id)));
                    Style::default().fg(if highlighted {
                        Color::Gray
                    } else {
                        Color::DarkGray
                    })
                }
                ToolActivityPresentation::Expanded { .. } => {
                    Style::default().bg(Color::Gray).fg(Color::Black)
                }
            }
            .remove_modifier(Modifier::DIM);
            buf.set_style(
                Rect::new(
                    self.area.x,
                    self.area.y + screen_row as u16,
                    self.area.width,
                    1,
                ),
                style,
            );
        }
    }
}

impl ToolActivityPresentation {
    pub(super) fn source_end(self) -> usize {
        match self {
            Self::Collapsed { source_end } | Self::Expanded { source_end } => source_end,
        }
    }
}

impl TranscriptView {
    pub(crate) fn sync_tool_activity_turn(
        &mut self,
        cells: &[Arc<dyn HistoryCell>],
        running: bool,
    ) {
        if self.disclosure.turn_running && !running {
            let changed = self
                .disclosure
                .expanded
                .iter()
                .any(|id| self.disclosure.turn_tool_ids.contains(id));
            self.disclosure
                .expanded
                .retain(|id| !self.disclosure.turn_tool_ids.contains(id));
            self.disclosure.turn_tool_ids.clear();
            if changed {
                self.end_selection(cells);
                self.release_live_reading();
                self.cache.clear();
                self.live_key = None;
                self.live_separated = None;
                self.disclosure.focused = None;
                self.restart_search();
            }
        }
        self.disclosure.turn_running = running;
    }

    pub(crate) fn track_turn_tool_activity(&mut self, cell: &dyn HistoryCell) {
        if self.disclosure.turn_running
            && cell.activity_disclosure(self.area.width.max(1))
                == Some(crate::history_cell::ActivityDisclosure::ToolActivity)
        {
            self.disclosure.turn_tool_ids.extend(cell.activity_ids());
        }
    }
}
