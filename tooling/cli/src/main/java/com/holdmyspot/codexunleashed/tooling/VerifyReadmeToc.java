package com.holdmyspot.codexunleashed.tooling;

import com.github.difflib.DiffUtils;
import com.github.difflib.UnifiedDiffUtils;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CoderResult;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Verifies that one explicit README file has a current table of contents.
 */
public final class VerifyReadmeToc
{
	/**
	 * The command name that selects this verifier.
	 */
	static final String NAME = "verify-readme-toc";
	/**
	 * The marker line that opens the ToC block.
	 */
	private static final String BEGIN = "<!-- Begin ToC -->";
	/**
	 * The marker line that closes the ToC block.
	 */
	private static final String END = "<!-- End ToC -->";
	/**
	 * Matches a column-zero heading of level two to six and captures its hashes and text.
	 */
	private static final Pattern HEADING = Pattern.compile("^(#{2,6})(?:\\s|[\\x{1c}-\\x{1f}])+(.*)$",
		Pattern.UNICODE_CHARACTER_CLASS);

	/**
	 * Prevents construction.
	 */
	private VerifyReadmeToc()
	{
	}

	/**
	 * Checks the existing ToC against headings without modifying the file or closing caller streams.
	 *
	 * @param scope invocation-owned tooling scope
	 *
	 * @param arguments command arguments after the command name
	 *
	 * @param out caller-owned output
	 *
	 * @param err caller-owned diagnostics
	 *
	 * @return zero for a match or missing marker, one for a read failure or mismatch, or two for invalid syntax
	 */
	public static int run(ToolingScope scope, String[] arguments, PrintStream out, PrintStream err)
	{
		if (arguments.length != 1 || arguments[0].startsWith("-"))
		{
			err.println("Usage: codex-tooling verify-readme-toc <file>");
			return 2;
		}

		String displayPath = pythonPathString(arguments[0]);
		Path path = Path.of(arguments[0]);
		if (!Files.isRegularFile(path))
		{
			err.printf("Error: file not found: %s%n", displayPath);
			return 1;
		}

		byte[] bytes;
		try
		{
			bytes = Files.readAllBytes(path);
		}
		catch (IOException failure)
		{
			err.printf("Error: failed to read %s: %s%n", displayPath, failure.getMessage());
			return 1;
		}

		ByteBuffer input = ByteBuffer.wrap(bytes);
		CharBuffer decoded = CharBuffer.allocate(bytes.length);
		CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).
			onUnmappableCharacter(CodingErrorAction.REPORT);
		CoderResult result = decoder.decode(input, decoded, true);
		if (result.isError())
		{
			// The decoder leaves the input position at the first malformed byte, which locates the failure for the user.
			err.printf("Error: failed to decode %s: %s%n", displayPath,
				VerifyReadmeCharacters.describeMalformedInput(bytes, input.position(), result.length()));
			return 1;
		}
		decoder.flush(decoded);
		decoded.flip();
		String content = decoded.toString();

		List<String> lines = splitLines(normalizeNewlines(content));
		int beginIndex = firstMarker(lines, BEGIN);
		int endIndex = firstMarker(lines, END);
		if (beginIndex < 0 || endIndex < 0)
		{
			out.printf("Note: Skipping ToC check; no markers found in %s.%n", displayPath);
			return 0;
		}

		List<String> current = new ArrayList<>();
		for (int index = beginIndex + 1; index < endIndex; index += 1)
		{
			String line = lines.get(index);
			if (stripLeading(line).startsWith("- ["))
			{
				current.add(line);
			}
		}
		List<String> tocContent = new ArrayList<>(lines.subList(0, beginIndex));
		if (endIndex + 1 < lines.size())
		{
			tocContent.addAll(lines.subList(endIndex + 1, lines.size()));
		}
		List<String> expected = generateToc(tocContent);
		if (current.equals(expected))
		{
			return 0;
		}

		out.println("ERROR: README ToC is out of date. Diff between existing and generated ToC:");
		List<String> unifiedDiff = UnifiedDiffUtils.generateUnifiedDiff("existing ToC", "generated ToC", current,
			DiffUtils.diff(current, expected), 3);
		for (String line : unifiedDiff)
		{
			out.println(line);
		}
		return 1;
	}

	/**
	 * Generates ToC entries from selected raw Markdown heading lines.
	 *
	 * @param lines selected README lines
	 * @return generated ToC entries
	 */
	private static List<String> generateToc(List<String> lines)
	{
		List<String> toc = new ArrayList<>();
		boolean inCode = false;
		for (String line : lines)
		{
			if (strip(line).startsWith("```"))
			{
				inCode = !inCode;
				continue;
			}
			if (inCode)
			{
				continue;
			}
			Matcher heading = HEADING.matcher(line);
			if (!heading.matches())
			{
				continue;
			}
			int level = heading.group(1).length();
			String text = strip(heading.group(2));
			String indent = "  ".repeat(level - 2);
			toc.add(indent + "- [" + text + "](#" + slug(text) + ")");
		}
		return toc;
	}

	/**
	 * Applies the retained legacy slug normalization to heading text.
	 *
	 * @param text heading text
	 * @return normalized anchor slug
	 */
	private static String slug(String text)
	{
		String lowered = text.toLowerCase(Locale.ROOT).replace('\u00a0', ' ').replace('\u2011', '-').
			replace('\u2013', '-').replace('\u2014', '-');
		StringBuilder retained = new StringBuilder();
		for (int index = 0; index < lowered.length();)
		{
			int codePoint = lowered.codePointAt(index);
			if ((codePoint >= '0' && codePoint <= '9') || (codePoint >= 'a' && codePoint <= 'z') ||
				isPythonWhitespace(codePoint) || codePoint == '-')
			{
				retained.appendCodePoint(codePoint);
			}
			index += Character.charCount(codePoint);
		}
		return strip(retained.toString()).replace(' ', '-');
	}

	/**
	 * Normalizes CR and CRLF to LF for the legacy parser.
	 *
	 * @param text README text
	 * @return normalized text
	 */
	private static String normalizeNewlines(String text)
	{
		return text.replace("\r\n", "\n").replace('\r', '\n');
	}

	/**
	 * Splits text using the legacy Python-compatible line boundary set.
	 *
	 * @param text README text
	 * @return split lines
	 */
	private static List<String> splitLines(String text)
	{
		List<String> lines = new ArrayList<>();
		int start = 0;
		for (int index = 0; index < text.length();)
		{
			int codePoint = text.codePointAt(index);
			if (isSplitLineBoundary(codePoint))
			{
				lines.add(text.substring(start, index));
				index += Character.charCount(codePoint);
				start = index;
			}
			else
			{
				index += Character.charCount(codePoint);
			}
		}
		if (start < text.length())
		{
			lines.add(text.substring(start));
		}
		return lines;
	}

	/**
	 * Finds the first matching marker after legacy whitespace stripping.
	 *
	 * @param lines README lines
	 * @param marker marker spelling
	 * @return first matching line index, or {@code -1}
	 */
	private static int firstMarker(List<String> lines, String marker)
	{
		for (int index = 0; index < lines.size(); index += 1)
		{
			if (strip(lines.get(index)).equals(marker))
			{
				return index;
			}
		}
		return -1;
	}

	/**
	 * Removes Python-compatible whitespace from both ends of text.
	 *
	 * @param text input text
	 * @return stripped text
	 */
	private static String strip(String text)
	{
		int start = 0;
		int end = text.length();
		while (start < end)
		{
			int codePoint = text.codePointAt(start);
			if (!isPythonWhitespace(codePoint))
			{
				break;
			}
			start += Character.charCount(codePoint);
		}
		while (start < end)
		{
			int codePoint = text.codePointBefore(end);
			if (!isPythonWhitespace(codePoint))
			{
				break;
			}
			end -= Character.charCount(codePoint);
		}
		return text.substring(start, end);
	}

	/**
	 * Removes Python-compatible whitespace from the beginning of text.
	 *
	 * @param text input text
	 * @return left-stripped text
	 */
	private static String stripLeading(String text)
	{
		int start = 0;
		while (start < text.length())
		{
			int codePoint = text.codePointAt(start);
			if (!isPythonWhitespace(codePoint))
			{
				break;
			}
			start += Character.charCount(codePoint);
		}
		return text.substring(start);
	}

	/**
	 * Returns whether the code point belongs to Python whitespace.
	 *
	 * @param codePoint Unicode code point
	 * @return {@code true} when it is Python whitespace
	 */
	private static boolean isPythonWhitespace(int codePoint)
	{
		return Character.isWhitespace(codePoint) || Character.isSpaceChar(codePoint) || codePoint == 0x85;
	}

	/**
	 * Returns whether the code point terminates a Python split line.
	 *
	 * @param codePoint Unicode code point
	 * @return {@code true} when it terminates a line
	 */
	private static boolean isSplitLineBoundary(int codePoint)
	{
		return codePoint == '\n' || codePoint == '\r' || codePoint == '\u000b' || codePoint == '\f' ||
			(codePoint >= 0x1c && codePoint <= 0x1e) || codePoint == 0x85 || codePoint == 0x2028 || codePoint == 0x2029;
	}

	/**
	 * Produces Python pathlib-style lexical spelling without filesystem resolution.
	 *
	 * @param value lexical path spelling
	 * @return normalized pathlib-style spelling
	 */
	private static String pythonPathString(String value)
	{
		if (value.isEmpty())
		{
			return ".";
		}
		boolean doubleSlashRoot = value.startsWith("//") && !value.startsWith("///");
		boolean absolute = value.startsWith("/");
		String[] components = value.split("/+", -1);
		List<String> retained = new ArrayList<>();
		for (String component : components)
		{
			if (!component.isEmpty() && !component.equals("."))
			{
				retained.add(component);
			}
		}
		String prefix;
		if (doubleSlashRoot)
		{
			prefix = "//";
		}
		else if (absolute)
		{
			prefix = "/";
		}
		else
		{
			prefix = "";
		}
		if (retained.isEmpty())
		{
			if (prefix.isEmpty())
			{
				return ".";
			}
			return prefix;
		}
		return prefix + String.join("/", retained);
	}
}
