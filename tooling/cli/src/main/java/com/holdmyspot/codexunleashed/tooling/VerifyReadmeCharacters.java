package com.holdmyspot.codexunleashed.tooling;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CoderResult;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Verifies that one explicit README file contains only the maintained character set.
 */
public final class VerifyReadmeCharacters
{
	/**
	 * The command name that selects this verifier.
	 */
	static final String NAME = "verify-readme-characters";

	/**
	 * Prevents construction.
	 */
	private VerifyReadmeCharacters()
	{
	}

	/**
	 * Checks one file without modifying it or closing the caller's streams.
	 *
	 * @param scope invocation-owned tooling scope
	 *
	 * @param arguments command arguments after the command name
	 *
	 * @param out caller-owned output
	 *
	 * @param err caller-owned diagnostics
	 *
	 * @return zero for valid input, one for a file or policy failure, or two for invalid syntax
	 */
	public static int run(ToolingScope scope, String[] arguments, PrintStream out, PrintStream err)
	{
		if (arguments.length != 1 || arguments[0].startsWith("-"))
		{
			err.println("Usage: codex-tooling verify-readme-characters <file>");
			return 2;
		}

		Path path = Path.of(arguments[0]);
		byte[] bytes;
		try
		{
			bytes = Files.readAllBytes(path);
		}
		catch (IOException failure)
		{
			err.printf("Error: failed to read %s: %s%n", arguments[0], failure.getMessage());
			return 1;
		}

		ByteBuffer input = ByteBuffer.wrap(bytes);
		CharBuffer decoded = CharBuffer.allocate(bytes.length);
		CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).
			onUnmappableCharacter(CodingErrorAction.REPORT);
		CoderResult result = decoder.decode(input, decoded, true);
		if (result.isError())
		{
			int offset = input.position();
			int line = 1;
			int previousLineFeed = -1;
			for (int i = 0; i < offset; i += 1)
			{
				if (bytes[i] == '\n')
				{
					line += 1;
					previousLineFeed = i;
				}
			}
			int column = offset - previousLineFeed;
			out.println("UTF-8 decoding error:");
			out.printf("  byte offset: %d%n", offset);
			out.printf("  reason: %s%n", decodeReason(bytes, offset));
			out.printf("  location: line %d, column %d%n", line, column);
			return 1;
		}
		result = decoder.flush(decoded);
		if (result.isError())
		{
			err.printf("Error: failed to decode %s: %s%n", arguments[0], result);
			return 1;
		}
		decoded.flip();
		String text = decoded.toString();
		int line = 1;
		int column = 1;
		boolean hasErrors = false;
		for (int index = 0; index < text.length();)
		{
			int codePoint = text.codePointAt(index);
			if (!isAllowed(codePoint))
			{
				hasErrors = true;
				out.printf("Invalid character at line %d, column %d: U+%04X (%s)%n", line, column, codePoint,
					pythonRepr(codePoint));
			}
			int nextIndex = index + Character.charCount(codePoint);
			if (codePoint == '\r' && nextIndex < text.length() && text.codePointAt(nextIndex) == '\n')
			{
				column += 1;
			}
			else if (isSplitLineBoundary(codePoint))
			{
				line += 1;
				column = 1;
			}
			else
			{
				column += 1;
			}
			index = nextIndex;
		}
		if (hasErrors)
		{
			return 1;
		}
		return 0;
	}

	/**
	 * Returns whether a code point is accepted by the README character policy.
	 *
	 * @param codePoint the Unicode code point to test
	 * @return {@code true} if the README may contain the code point
	 */
	private static boolean isAllowed(int codePoint)
	{
		return (codePoint >= 0x20 && codePoint <= 0x7e) || codePoint == 0x2728 || codePoint == '\n';
	}

	/**
	 * Returns whether a code point ends a Python-compatible split line.
	 *
	 * @param codePoint the Unicode code point to test
	 * @return {@code true} if the code point ends a line
	 */
	private static boolean isSplitLineBoundary(int codePoint)
	{
		return codePoint == '\n' || codePoint == '\r' || codePoint == '\u000b' || codePoint == '\f' ||
			(codePoint >= 0x1c && codePoint <= 0x1e) || codePoint == 0x85 || codePoint == 0x2028 || codePoint == 0x2029;
	}

	/**
	 * Describes a malformed UTF-8 sequence in the wording of CPython's {@code UnicodeDecodeError}, without the
	 * exception class name: the byte or byte range, its zero-based position and the reason.
	 *
	 * @param bytes  the complete input that failed to decode
	 * @param offset the zero-based position of the first byte of the malformed sequence
	 * @param length the number of bytes that the decoder reported as malformed
	 * @return the cause, such as {@code 'utf-8' codec can't decode byte 0xff in position 24: invalid start byte}
	 */
	static String describeMalformedInput(byte[] bytes, int offset, int length)
	{
		String reason = decodeReason(bytes, offset);
		if (length == 1)
		{
			return String.format("'utf-8' codec can't decode byte 0x%02x in position %d: %s", bytes[offset] & 0xff,
				offset, reason);
		}
		return String.format("'utf-8' codec can't decode bytes in position %d-%d: %s", offset, offset + length - 1,
			reason);
	}

	/**
	 * Maps a malformed UTF-8 sequence to CPython-compatible reason text.
	 *
	 * @param bytes  the complete input that failed to decode
	 * @param offset the zero-based position of the first byte of the malformed sequence
	 * @return the reason, such as {@code invalid start byte}
	 */
	private static String decodeReason(byte[] bytes, int offset)
	{
		int first = bytes[offset] & 0xff;
		if (first < 0xc2 || first > 0xf4)
		{
			return "invalid start byte";
		}
		int remaining = bytes.length - offset;
		if (remaining > 1)
		{
			int second = bytes[offset + 1] & 0xff;
			if ((second & 0xc0) != 0x80 || (first == 0xe0 && second < 0xa0) ||
				(first == 0xed && second >= 0xa0) || (first == 0xf0 && second < 0x90) ||
				(first == 0xf4 && second >= 0x90))
			{
				return "invalid continuation byte";
			}
		}
		int expectedLength;
		if (first <= 0xdf)
		{
			expectedLength = 2;
		}
		else if (first <= 0xef)
		{
			expectedLength = 3;
		}
		else
		{
			expectedLength = 4;
		}
		for (int index = 2; index < Math.min(remaining, expectedLength); index += 1)
		{
			if ((bytes[offset + index] & 0xc0) != 0x80)
			{
				return "invalid continuation byte";
			}
		}
		if (remaining < expectedLength)
		{
			return "unexpected end of data";
		}
		return "invalid continuation byte";
	}

	/**
	 * Returns Python {@code repr()} content without its surrounding quotes.
	 *
	 * @param codePoint the Unicode code point to describe
	 * @return the escaped or printable spelling of the code point
	 */
	private static String pythonRepr(int codePoint)
	{
		return switch (codePoint)
		{
			case '\\' -> "\\\\";
			case '\t' -> "\\t";
			case '\n' -> "\\n";
			case '\r' -> "\\r";
			default -> printableRepr(codePoint);
		};
	}

	/**
	 * Chooses printable Unicode or an escaped representation for diagnostics.
	 *
	 * @param codePoint the Unicode code point to describe
	 * @return the code point itself if Python considers it printable, otherwise its escape
	 */
	private static String printableRepr(int codePoint)
	{
		if (isPythonPrintable(codePoint))
		{
			return new String(Character.toChars(codePoint));
		}
		return unicodeEscape(codePoint);
	}

	/**
	 * Returns whether Python's {@code str.isprintable()} accepts a code point.
	 *
	 * @param codePoint the Unicode code point to test
	 * @return {@code true} if the code point is printable
	 */
	private static boolean isPythonPrintable(int codePoint)
	{
		int type = Character.getType(codePoint);
		return type != Character.UNASSIGNED && type != Character.CONTROL && type != Character.FORMAT &&
			type != Character.SURROGATE && type != Character.PRIVATE_USE && type != Character.LINE_SEPARATOR &&
			type != Character.PARAGRAPH_SEPARATOR && (type != Character.SPACE_SEPARATOR || codePoint == ' ');
	}

	/**
	 * Escapes a non-printable code point using Python hexadecimal forms.
	 *
	 * @param codePoint the Unicode code point to escape
	 * @return the two-, four- or eight-digit hexadecimal escape of the code point
	 */
	private static String unicodeEscape(int codePoint)
	{
		if (codePoint <= 0xff)
		{
			return String.format("\\x%02x", codePoint);
		}
		if (codePoint <= 0xffff)
		{
			return String.format("\\u%04x", codePoint);
		}
		return String.format("\\U%08x", codePoint);
	}
}
