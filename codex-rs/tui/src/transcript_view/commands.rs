//! Command disclosure uses stationary release clicks and the existing keyboard expansion state.

use super::*;
use crossterm::event::MouseButton;
use crossterm::event::MouseEvent;
use crossterm::event::MouseEventKind;
use ratatui::layout::Position as ScreenPosition;

impl TranscriptView {
    /// Resolve only displayed command rows, excluding separators and auxiliary live text.
    pub(super) fn command_at(&self, point: ScreenPosition) -> Option<(Vec<String>, bool)> {
        if self.detailed || self.mode != HistoryRenderMode::Rich || !self.area.contains(point) {
            return None;
        }
        let visible = self.visible.get(usize::from(point.y - self.area.y))?;
        let end = visible.layout.command_source_end?;
        if (visible.layout.separated && visible.row == 0)
            || visible.layout.position_at(visible.row, /*column*/ 0) > end
            || visible.activity_ids.is_empty()
        {
            return None;
        }
        Some((
            visible.activity_ids.to_vec(),
            visible.layout.command_expanded,
        ))
    }

    /// Observe gestures without intercepting selection until a stationary release is established.
    pub(super) fn handle_command_mouse(
        &mut self,
        event: MouseEvent,
        cells: &[Arc<dyn HistoryCell>],
        interactive: bool,
    ) -> Option<ViewAction> {
        let point = ScreenPosition::new(event.column, event.row);
        match event.kind {
            MouseEventKind::Moved if interactive => {
                let hover = self.command_at(point).map(|_| point);
                let changed = self.command_hover != hover;
                self.command_hover = hover;
                changed.then_some(ViewAction::Changed)
            }
            MouseEventKind::Down(MouseButton::Left) => {
                self.command_press = (interactive && event.modifiers.is_empty())
                    .then(|| self.command_at(point))
                    .flatten()
                    .map(|(ids, _)| (point, ids));
                None
            }
            MouseEventKind::Up(MouseButton::Left) => {
                let (pressed, ids) = self.command_press.take()?;
                let stationary = self.selection.as_ref().is_some_and(|selection| {
                    !selection.moved
                        && selection.pointer == Some(point)
                        && selection.start.key == selection.end.key
                        && selection.start.offset == selection.end.offset
                });
                if !interactive || !event.modifiers.is_empty() || point != pressed || !stationary {
                    return None;
                }
                let (current, _) = self.command_at(point)?;
                if current != ids {
                    return None;
                }
                let index = self.canonical_activity_index(cells, &ids)?;
                self.end_selection(cells);
                self.toggle_activity(cells, index);
                Some(ViewAction::Changed)
            }
            MouseEventKind::Drag(_)
            | MouseEventKind::ScrollUp
            | MouseEventKind::ScrollDown
            | MouseEventKind::ScrollLeft
            | MouseEventKind::ScrollRight => {
                self.command_press = None;
                None
            }
            _ => None,
        }
    }

    /// Present the hover action through the footer without inserting selectable output rows.
    pub(super) fn command_hover_hint(&self) -> Option<&'static str> {
        let (_, expanded) = self.command_at(self.command_hover?)?;
        Some(if expanded {
            "Click anywhere in this section to collapse"
        } else {
            "Click to expand command and output"
        })
    }
}
