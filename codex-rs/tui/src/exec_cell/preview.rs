//! Shell-source previews that preserve argument boundaries and executable filenames.

use codex_shell_command::bash::try_parse_shell;
use ratatui::style::Style;
use ratatui::text::Line;
use ratatui::text::Span;
use unicode_segmentation::UnicodeSegmentation;
use unicode_width::UnicodeWidthStr;

/// Wrap a shell source preview, shortening only over-width path directory prefixes.
///
/// Shell syntax comes from Codex's parser. Invalid syntax remains literal text; display
/// abbreviations never modify the original command retained by the owning execution cell.
pub fn command_preview(source: &str, width: usize) -> Vec<Line<'static>> {
    let width = width.max(1);
    let mut atoms = Vec::new();
    if let Some(tree) = try_parse_shell(source)
        && !tree.root_node().has_error()
    {
        let mut cursor = tree.walk();
        let mut covered = 0;
        loop {
            let node = cursor.node();
            if node.start_byte() >= covered
                && matches!(
                    node.kind(),
                    "command_name" | "word" | "string" | "raw_string" | "concatenation"
                )
            {
                atoms.push((node.byte_range(), node.kind() == "command_name"));
                covered = node.end_byte();
            }
            if cursor.goto_first_child() {
                continue;
            }
            while !cursor.goto_next_sibling() {
                if !cursor.goto_parent() {
                    return preview_atoms(source, width, atoms);
                }
            }
        }
    }
    preview_atoms(source, width, atoms)
}

/// Lay out parser-selected atoms and untouched source between them in source order.
fn preview_atoms(
    source: &str,
    width: usize,
    atoms: Vec<(std::ops::Range<usize>, bool)>,
) -> Vec<Line<'static>> {
    let mut parts = Vec::new();
    let mut offset = 0;
    for (range, executable) in atoms {
        if range.start > offset {
            parts.push((&source[offset..range.start], false, false));
        }
        offset = range.end;
        parts.push((&source[range], executable, true));
    }
    if offset < source.len() {
        parts.push((&source[offset..], false, false));
    }
    let mut lines = Vec::new();
    let mut current = Line::default();
    for (part, executable, atom) in parts {
        let display = if atom {
            abbreviated_path(part, width)
        } else {
            part.to_owned()
        };
        for (index, logical) in display.split('\n').enumerate() {
            if index > 0 {
                lines.push(std::mem::take(&mut current));
            }
            if atom && current.width() > 0 && current.width() + logical.width() > width {
                lines.push(std::mem::take(&mut current));
            }
            let directory_end = if atom {
                logical.rfind(['/', '\\']).map(|end| end + 1)
            } else {
                None
            };
            let mut byte = 0;
            for grapheme in logical.graphemes(true) {
                if current.width() + grapheme.width() > width && current.width() > 0 {
                    lines.push(std::mem::take(&mut current));
                }
                let style = if directory_end.is_some_and(|end| byte < end) {
                    Style::default().dim()
                } else if executable {
                    Style::default().bold()
                } else {
                    Style::default()
                };
                current.push_span(Span::styled(grapheme.to_owned(), style));
                byte += grapheme.len();
            }
        }
    }
    if current.width() > 0 || lines.is_empty() {
        lines.push(current);
    }
    lines
}

/// Retain the filename, leading identity and as much trailing directory context as fits.
fn abbreviated_path(value: &str, width: usize) -> String {
    if value.width() <= width || value.contains('\n') {
        return value.to_owned();
    }
    let path = value.trim_matches(['\'', '"']);
    if !(path.starts_with('/')
        || path.starts_with("./")
        || path.starts_with("../")
        || path.starts_with("~/")
        || path.as_bytes().get(1) == Some(&b':'))
    {
        return value.to_owned();
    }
    let Some(last) = path.rfind(['/', '\\']) else {
        return value.to_owned();
    };
    let start = value.len() - value.trim_start_matches(['\'', '"']).len();
    let end = value.trim_end_matches(['\'', '"']).len();
    let prefix = &value[..start];
    let suffix = &value[end..];
    let mut tail = &path[last..];
    let first_end = path[1..]
        .find(['/', '\\'])
        .map(|index| index + 1)
        .unwrap_or(0);
    let head = &path[..first_end];
    let mut result = format!("{prefix}{head}/…{tail}{suffix}");
    if result.width() > width {
        result = format!("{prefix}…{tail}{suffix}");
    }
    for (index, _) in path[..last].rmatch_indices(['/', '\\']) {
        let candidate = format!("{prefix}{head}/…{}{suffix}", &path[index..]);
        if candidate.width() > width {
            break;
        }
        tail = &path[index..];
        result = format!("{prefix}{head}/…{tail}{suffix}");
    }
    result
}

#[cfg(test)]
mod tests {
    use super::*;
    use ratatui::style::Modifier;

    fn text(lines: &[Line<'_>]) -> String {
        lines
            .iter()
            .map(ToString::to_string)
            .collect::<Vec<_>>()
            .join("\n")
    }

    #[test]
    fn readable_command_preview_paths_preserve_filename_quotes_and_terminal_width() {
        for width in [24, 40, 80] {
            let source = "'/plugins/CAT path/very/long/cache/directory/bin/run-part' --output '/reports/very/long/directory/検証.txt' --verbose";
            let lines = command_preview(source, width);
            assert!(lines.iter().all(|line| line.width() <= width));
            let rendered = text(&lines);
            assert!(rendered.contains("run-part'"), "{rendered}");
            assert!(rendered.contains("検証.txt'"), "{rendered}");
            assert!(rendered.contains("--output"));
            assert!(rendered.contains("--verbose"));
        }
    }

    #[test]
    fn readable_command_preview_fitting_paths_and_invalid_syntax_remain_literal() {
        for source in ["/usr/bin/echo 'a b'", "printf '%s\\n' 'unterminated"] {
            assert_eq!(text(&command_preview(source, /*width*/ 100)), source);
        }
    }

    #[test]
    fn readable_command_preview_same_basename_keeps_leading_identity() {
        let first = text(&command_preview(
            "/alpha/really/long/cache/bin/codex --help",
            /*width*/ 28,
        ));
        let second = text(&command_preview(
            "/bravo/really/long/cache/bin/codex --help",
            /*width*/ 28,
        ));
        assert_ne!(first, second);
        assert!(first.contains("codex"));
        assert!(second.contains("codex"));
    }

    #[test]
    fn readable_command_preview_shell_structure_and_oversized_argument_remain_inspectable() {
        let source = "run-part <<'EOF'\nprintf '%s\\n' 'a b'\ngit diff --stat\nEOF";
        assert_eq!(text(&command_preview(source, /*width*/ 100)), source);
        let argument = "abcdefghijklmnopqrstuvwxyz0123456789";
        let rendered = text(&command_preview(
            &format!("echo {argument}"),
            /*width*/ 12,
        ));
        assert!(rendered.replace('\n', "").contains(argument));
    }

    #[test]
    fn readable_command_preview_styles_executable_and_directories_without_losing_plain_text() {
        let lines = command_preview("/usr/bin/echo --verbose", /*width*/ 80);
        assert_eq!(text(&lines), "/usr/bin/echo --verbose");
        assert!(
            lines
                .iter()
                .flat_map(|line| &line.spans)
                .any(|span| span.style.add_modifier.contains(Modifier::BOLD))
        );
        assert!(
            lines
                .iter()
                .flat_map(|line| &line.spans)
                .any(|span| span.style.add_modifier.contains(Modifier::DIM))
        );
    }
}
