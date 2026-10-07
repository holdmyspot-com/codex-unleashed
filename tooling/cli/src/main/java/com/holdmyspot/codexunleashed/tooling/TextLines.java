package com.holdmyspot.codexunleashed.tooling;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Splits release text at the retained ASCII and Unicode line boundaries without an extra final empty line.
 */
public final class TextLines
{
	private static final Pattern LINE_BREAK =
		Pattern.compile("\\r\\n|[\\n\\r\\u000b\\f\\u001c-\\u001e\\u0085\\u2028\\u2029]");

	/**
	 * Prevents construction.
	 */
	private TextLines()
	{
	}

	/**
	 * Returns lines without their terminators, retaining interior empty lines and the unit-separator character.
	 *
	 * @param text the release input text
	 * @return immutable lines, or an empty list for empty input
	 * @throws NullPointerException if {@code text} is null
	 */
	public static List<String> split(String text)
	{
		Objects.requireNonNull(text, "text");
		if (text.isEmpty())
			return List.of();
		List<String> lines = new ArrayList<>(Arrays.asList(LINE_BREAK.split(text, -1)));
		if (lines.getLast().isEmpty())
			lines.removeLast();
		return List.copyOf(lines);
	}
}
