package com.holdmyspot.codexunleashed.tooling;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;

/**
 * Refuses direct {@code codex-core} dependencies and imports in the {@code codex-tui} crate.
 */
final class VerifyTuiCoreBoundary
{
	/** Command name that selects this check. */
	static final String NAME = "verify-tui-core-boundary";
	/** Repository-relative location of the checked crate, using forward slashes. */
	private static final String TUI_DIRECTORY = "codex-rs/tui";
	/** Repository-relative location of the crate manifest, using forward slashes. */
	private static final String MANIFEST_PATH = TUI_DIRECTORY + "/Cargo.toml";
	/** Package whose direct use is refused. */
	private static final String FORBIDDEN_PACKAGE = "codex-core";
	/** Identifier whose direct import is refused. */
	private static final String FORBIDDEN_IDENTIFIER = "codex_core";
	/** Dependency sections in the order in which they are checked. */
	private static final List<String> DEPENDENCY_SECTIONS = List.of("dependencies", "dev-dependencies",
		"build-dependencies");
	/**
	 * Words that precede the identifier in a {@code use} declaration.
	 */
	private static final List<String> USE_WORDS = List.of("use");
	/**
	 * Words that precede the identifier in an {@code extern crate} declaration.
	 */
	private static final List<String> EXTERN_WORDS = List.of("extern", "crate");
	/** Name suffix of the Rust sources that are scanned. */
	private static final String RUST_SUFFIX = ".rs";
	/** Policy statement printed before the findings. */
	private static final List<String> POLICY_HEADER = List.of(
		"codex-tui must not depend on or import codex-core directly.",
		"Use the app-server protocol/client boundary instead; temporary embedded startup gaps belong behind " +
			"codex_app_server_client::legacy_core.",
		"");

	/** Prevents construction. */
	private VerifyTuiCoreBoundary()
	{
	}

	/**
	 * Checks a repository checkout without modifying it.
	 *
	 * @param scope borrowed shared parsers
	 * @param arguments exactly one repository root path
	 * @param out borrowed policy findings; written only when a finding exists
	 * @param err borrowed usage and operational diagnostics
	 * @return zero when no finding exists, one for policy findings or an unreadable or malformed input, and two for
	 *         a missing or extra argument
	 * @throws NullPointerException if any argument is null
	 */
	static int run(ToolingScope scope, String[] arguments, PrintStream out, PrintStream err)
	{
		if (arguments.length != 1)
		{
			err.print("Usage: codex-tooling " + NAME + " <repository-root>\n");
			return 2;
		}
		Path root = Path.of(arguments[0]);
		List<String> findings = new ArrayList<>();
		String current = MANIFEST_PATH;
		try
		{
			findings.addAll(manifestFindings(scope, root.resolve(MANIFEST_PATH)));
			for (Path source : rustSources(root.resolve(TUI_DIRECTORY)))
			{
				current = display(root, source);
				findings.addAll(sourceFindings(root, source));
			}
		}
		catch (IOException failure)
		{
			err.print("ERROR: " + current + ": " + failure.getMessage() + "\n");
			return 1;
		}
		if (findings.isEmpty())
			return 0;
		for (String line : POLICY_HEADER)
			out.print(line + "\n");
		for (String finding : findings)
			out.print("- " + finding + "\n");
		return 1;
	}

	/**
	 * Finds dependency tables that declare the forbidden package, in the legacy checker's order.
	 *
	 * @param scope borrowed TOML parser
	 * @param manifest crate manifest path
	 * @return one finding for each offending dependency table
	 * @throws IOException if the manifest cannot be read, is not valid TOML, or has a malformed target table
	 */
	private static List<String> manifestFindings(ToolingScope scope, Path manifest) throws IOException
	{
		JsonNode parsed;
		try
		{
			parsed = scope.getTomlMapper().readTree(readText(manifest));
		}
		catch (JacksonException failure)
		{
			throw new IOException("invalid TOML: " + failure.getOriginalMessage(), failure);
		}
		List<String> findings = new ArrayList<>();
		collect(parsed, "", findings);
		JsonNode targets = parsed.get("target");
		if (targets == null)
			return findings;
		if (!targets.isObject())
			throw new IOException("`target` must be a table");
		for (Map.Entry<String, JsonNode> target : targets.properties())
		{
			if (target.getValue().isObject())
				collect(target.getValue(), "target." + target.getKey() + ".", findings);
		}
		return findings;
	}

	/**
	 * Appends a finding for each dependency section of a table that has the forbidden package as an exact key.
	 *
	 * @param table root manifest or one target table
	 * @param prefix section-name prefix, empty for root sections
	 * @param findings receives the findings
	 */
	private static void collect(JsonNode table, String prefix, List<String> findings)
	{
		for (String section : DEPENDENCY_SECTIONS)
		{
			JsonNode dependencies = table.get(section);
			if (dependencies != null && dependencies.isObject() && dependencies.has(FORBIDDEN_PACKAGE))
			{
				findings.add(MANIFEST_PATH + " declares `" + FORBIDDEN_PACKAGE + "` in `[" + prefix + section + "]`");
			}
		}
	}

	/**
	 * Lists the Rust sources below the crate directory in sorted relative-path order.
	 *
	 * @param tuiRoot crate directory
	 * @return source paths; directory symbolic links are not traversed
	 * @throws IOException if the tree cannot be traversed
	 */
	private static List<Path> rustSources(Path tuiRoot) throws IOException
	{
		List<Path> sources = new ArrayList<>();
		Files.walkFileTree(tuiRoot, new SimpleFileVisitor<>()
		{
			@Override
			public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes)
			{
				if (!directory.equals(tuiRoot) && isRustName(directory))
					sources.add(directory);
				return FileVisitResult.CONTINUE;
			}

			@Override
			public FileVisitResult visitFile(Path file, BasicFileAttributes attributes)
			{
				if (isRustName(file))
					sources.add(file);
				return FileVisitResult.CONTINUE;
			}
		});
		sources.sort((left, right) -> compareComponents(tuiRoot.relativize(left), tuiRoot.relativize(right)));
		return sources;
	}

	/**
	 * Indicates whether an entry is selected by the legacy {@code **}{@code /*.rs} pattern.
	 *
	 * @param path entry path
	 * @return true if the final name ends in {@code .rs}
	 */
	private static boolean isRustName(Path path)
	{
		return path.getFileName().toString().endsWith(RUST_SUFFIX);
	}

	/**
	 * Orders relative paths component by component, comparing code points, as the legacy checker did.
	 *
	 * @param left first relative path
	 * @param right second relative path
	 * @return a negative, zero or positive value as {@code left} sorts before, with or after {@code right}
	 */
	private static int compareComponents(Path left, Path right)
	{
		int shared = Math.min(left.getNameCount(), right.getNameCount());
		for (int index = 0; index < shared; ++index)
		{
			int order = compareCodePoints(left.getName(index).toString(), right.getName(index).toString());
			if (order != 0)
				return order;
		}
		return Integer.compare(left.getNameCount(), right.getNameCount());
	}

	/**
	 * Compares strings by Unicode code point rather than by UTF-16 code unit.
	 *
	 * @param left first string
	 * @param right second string
	 * @return a negative, zero or positive value as {@code left} sorts before, equal to or after {@code right}
	 */
	private static int compareCodePoints(String left, String right)
	{
		int leftIndex = 0;
		int rightIndex = 0;
		while (leftIndex < left.length() && rightIndex < right.length())
		{
			int leftPoint = left.codePointAt(leftIndex);
			int rightPoint = right.codePointAt(rightIndex);
			if (leftPoint != rightPoint)
				return Integer.compare(leftPoint, rightPoint);
			leftIndex += Character.charCount(leftPoint);
			rightIndex += Character.charCount(rightPoint);
		}
		return Integer.compare(left.length() - leftIndex, right.length() - rightIndex);
	}

	/**
	 * Finds the lines of one Rust source that import the forbidden crate.
	 *
	 * @param root repository root
	 * @param source Rust source path
	 * @return one finding for each matching line, in line order
	 * @throws IOException if the source cannot be read as UTF-8
	 */
	private static List<String> sourceFindings(Path root, Path source) throws IOException
	{
		List<String> findings = new ArrayList<>();
		int lineNumber = 0;
		for (String line : splitLines(readText(source)))
		{
			++lineNumber;
			if (importsForbiddenCrate(line))
				findings.add(display(root, source) + ":" + lineNumber + " imports `" + FORBIDDEN_IDENTIFIER + "`");
		}
		return findings;
	}

	/**
	 * Formats a path relative to the repository root with forward slashes.
	 *
	 * @param root repository root
	 * @param path path below the root
	 * @return the repository-relative path
	 */
	private static String display(Path root, Path path)
	{
		List<String> names = new ArrayList<>();
		for (Path name : root.relativize(path))
			names.add(name.toString());
		return String.join("/", names);
	}

	/**
	 * Reads a whole file as strict UTF-8 text.
	 *
	 * @param path file to read
	 * @return the decoded text with line terminators unchanged
	 * @throws IOException if the file cannot be read or contains malformed UTF-8
	 */
	private static String readText(Path path) throws IOException
	{
		byte[] bytes;
		try
		{
			bytes = Files.readAllBytes(path);
		}
		catch (IOException failure)
		{
			throw new IOException("cannot read file (" + failure.getClass().getSimpleName() + ")", failure);
		}
		try
		{
			return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).
				onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
		}
		catch (CharacterCodingException failure)
		{
			throw new IOException("file is not valid UTF-8", failure);
		}
	}

	/**
	 * Splits text into lines at every separator that Python's {@code str.splitlines()} recognizes, treating a
	 * carriage return and line feed pair as one separator.
	 *
	 * @param text decoded source text
	 * @return the lines without their separators; a trailing separator does not start another line
	 */
	private static List<String> splitLines(String text)
	{
		List<String> lines = new ArrayList<>();
		int start = 0;
		int index = 0;
		while (index < text.length())
		{
			char character = text.charAt(index);
			if (!isLineSeparator(character))
			{
				++index;
				continue;
			}
			lines.add(text.substring(start, index));
			if (character == '\r' && index + 1 < text.length() && text.charAt(index + 1) == '\n')
				++index;
			++index;
			start = index;
		}
		if (start < text.length())
			lines.add(text.substring(start));
		return lines;
	}

	/**
	 * Indicates whether a character ends a line for Python's {@code str.splitlines()}.
	 *
	 * @param character UTF-16 code unit
	 * @return true for line feed, carriage return, vertical tab, form feed, the three information separators, next
	 *         line, line separator and paragraph separator
	 */
	private static boolean isLineSeparator(char character)
	{
		return (character >= '\n' && character <= '\r') || (character >= '\u001c' && character <= '\u001e') ||
			character == '\u0085' || character == ' ' || character == ' ';
	}

	/**
	 * Applies the legacy forbidden-import patterns to one line.
	 *
	 * @param line one source line
	 * @return true if the line contains {@code codex_core::}, {@code use codex_core} or
	 *         {@code extern crate codex_core}, each starting and ending at a word boundary where one is required
	 */
	private static boolean importsForbiddenCrate(String line)
	{
		for (int index = 0; index < line.length(); ++index)
		{
			if (!startsAtBoundary(line, index))
				continue;
			if (line.startsWith(FORBIDDEN_IDENTIFIER + "::", index) || matchesPhrase(line, index, USE_WORDS) ||
				matchesPhrase(line, index, EXTERN_WORDS))
			{
				return true;
			}
		}
		return false;
	}

	/**
	 * Matches words that are each followed by whitespace, then the forbidden identifier ending at a word boundary.
	 *
	 * @param line source line
	 * @param start position of the first word
	 * @param words words that must appear in order
	 * @return true if the phrase starts at {@code start}
	 */
	private static boolean matchesPhrase(String line, int start, List<String> words)
	{
		int position = start;
		for (String word : words)
		{
			if (!line.startsWith(word, position))
				return false;
			int afterWord = position + word.length();
			position = skipWhitespace(line, afterWord);
			if (position == afterWord)
				return false;
		}
		if (!line.startsWith(FORBIDDEN_IDENTIFIER, position))
			return false;
		int end = position + FORBIDDEN_IDENTIFIER.length();
		return end == line.length() || !isWordCharacter(line.codePointAt(end));
	}

	/**
	 * Skips a run of whitespace characters.
	 *
	 * @param line source line
	 * @param start position at which the run may begin
	 * @return the position after the run, which equals {@code start} when none exists
	 */
	private static int skipWhitespace(String line, int start)
	{
		int position = start;
		while (position < line.length() && isWhitespace(line.codePointAt(position)))
			position += Character.charCount(line.codePointAt(position));
		return position;
	}

	/**
	 * Indicates whether a word boundary precedes an index.
	 *
	 * @param line source line
	 * @param index position in the line
	 * @return true if the index is at the start of the line or the preceding character is not a word character
	 */
	private static boolean startsAtBoundary(String line, int index)
	{
		return index == 0 || !isWordCharacter(line.codePointBefore(index));
	}

	/**
	 * Matches Python's Unicode {@code \w}: alphanumeric characters and underscore.
	 *
	 * @param codePoint character to classify
	 * @return true for a letter, a decimal digit, a letter number, another number, or underscore
	 */
	private static boolean isWordCharacter(int codePoint)
	{
		if (codePoint == '_' || Character.isLetter(codePoint))
			return true;
		int type = Character.getType(codePoint);
		return type == Character.DECIMAL_DIGIT_NUMBER || type == Character.LETTER_NUMBER ||
			type == Character.OTHER_NUMBER;
	}

	/**
	 * Matches Python's Unicode {@code \s}.
	 *
	 * @param codePoint character to classify
	 * @return true for the characters for which {@code str.isspace()} is true
	 */
	private static boolean isWhitespace(int codePoint)
	{
		return (codePoint >= '\t' && codePoint <= '\r') || (codePoint >= 0x1c && codePoint <= ' ') ||
			codePoint == 0x85 || codePoint == 0xa0 || codePoint == 0x1680 ||
			(codePoint >= 0x2000 && codePoint <= 0x200a) || codePoint == 0x2028 || codePoint == 0x2029 ||
			codePoint == 0x202f || codePoint == 0x205f || codePoint == 0x3000;
	}
}
