package com.holdmyspot.codexunleashed.tooling;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.io.StringReader;
import java.math.BigInteger;
import java.nio.file.AccessDeniedException;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.StringJoiner;
import java.util.function.Consumer;
import static java.nio.charset.StandardCharsets.UTF_8;

/**
 * Assigns Windows Bazel test targets to shards by their estimated duration.
 * <p>
 * Targets are placed longest first on the lightest shard, so the shards finish at about the same time. A target
 * without an estimate weighs one second. This policy is independent of the package hashing in
 * {@link SelectBazelPackages}.
 */
public final class SelectWindowsBazelTargets
{
	/** Command name. */
	public static final String NAME = "select-windows-bazel-targets";
	/** Option that names the one-based shard to select. */
	private static final String SHARD = "--shard";
	/** Option that names the number of shards. */
	private static final String SHARD_COUNT = "--shard-count";
	/** Option that names the checked-in duration table. */
	private static final String DURATIONS = "--durations";
	/** Every option that the command accepts. */
	private static final Set<String> OPTIONS = Set.of(SHARD, SHARD_COUNT, DURATIONS);
	/** Usage line. */
	private static final String USAGE = "Usage: codex-tooling " + NAME + " --shard N --shard-count N --durations PATH\n";
	/** Complete help text. */
	private static final String HELP = USAGE +
		"Reads Bazel test target labels from standard input and writes the labels of one shard to standard output.\n" +
		"Status: 0 selected; 2 for invalid arguments, rejected input or a failed file access.\n";
	/** Estimated test-seconds of a target without a duration record. */
	private static final BigInteger DEFAULT_DURATION = BigInteger.ONE;

	/** Prevents construction. */
	private SelectWindowsBazelTargets()
	{
	}

	/**
	 * Selects the labels of one shard.
	 * <p>
	 * Relative paths resolve against the process working directory. The caller keeps ownership of every stream. On
	 * failure nothing is written to {@code out}.
	 *
	 * @param arguments command arguments after the command name
	 * @param in        the Bazel test target labels, one per line, as UTF-8
	 * @param out       receives the selected labels as UTF-8 with an LF after each, or the help text
	 * @param err       receives the shard summary on success, or the failure diagnostic
	 * @return 0 when a selection was written or help was printed, otherwise 2
	 * @throws NullPointerException if an argument is null
	 */
	public static int run(String[] arguments, InputStream in, PrintStream out, PrintStream err)
	{
		Objects.requireNonNull(arguments, "arguments");
		Objects.requireNonNull(in, "in");
		Objects.requireNonNull(out, "out");
		Objects.requireNonNull(err, "err");
		if (arguments.length == 1 && arguments[0].equals("--help"))
		{
			out.print(HELP);
			out.flush();
			return 0;
		}
		try
		{
			Options options = parseOptions(arguments);
			select(options, in, out, err);
			return 0;
		}
		catch (IOException | IllegalArgumentException failure)
		{
			err.print(USAGE + NAME + ": error: " + failure.getMessage() + "\n");
			err.flush();
			return 2;
		}
	}

	/**
	 * Parses the named options, each of which must occur exactly once.
	 *
	 * @param arguments command arguments after the command name
	 * @return the options
	 * @throws IllegalArgumentException if an option is unknown, repeated, missing or has an invalid value
	 */
	private static Options parseOptions(String[] arguments)
	{
		Map<String, String> values = new HashMap<>();
		for (int index = 0; index < arguments.length; index += 2)
		{
			String option = arguments[index];
			if (!OPTIONS.contains(option))
				throw new IllegalArgumentException("unrecognized argument " + option);
			if (index + 1 >= arguments.length)
				throw new IllegalArgumentException("argument " + option + " needs a value");
			if (values.putIfAbsent(option, arguments[index + 1]) != null)
				throw new IllegalArgumentException("argument " + option + " was given more than once");
		}
		for (String required : List.of(SHARD, SHARD_COUNT, DURATIONS))
		{
			if (!values.containsKey(required))
				throw new IllegalArgumentException("argument " + required + " is required");
		}
		String shown = pythonPath(values.get(DURATIONS));
		return new Options(parseInteger(SHARD, values.get(SHARD)), parseInteger(SHARD_COUNT, values.get(SHARD_COUNT)),
			Path.of(shown), shown);
	}

	/**
	 * Parses a signed decimal integer whose digits are the Java platform's decimal digits.
	 *
	 * @param option the option that supplied the text
	 * @param text   the option value
	 * @return the value
	 * @throws IllegalArgumentException if the text is not an optionally signed decimal integer that fits an
	 *                                  {@code int}
	 */
	private static int parseInteger(String option, String text)
	{
		int start = 0;
		if (text.startsWith("-") || text.startsWith("+"))
			start = 1;
		if (start == text.length())
			throw new IllegalArgumentException("argument " + option + ": invalid integer value: '" + text + "'");
		BigInteger magnitude = parseDecimal(text.substring(start));
		if (magnitude == null)
			throw new IllegalArgumentException("argument " + option + ": invalid integer value: '" + text + "'");
		BigInteger value = magnitude;
		if (text.startsWith("-"))
			value = magnitude.negate();
		if (value.bitLength() > Integer.SIZE - 1)
			throw new IllegalArgumentException("argument " + option + ": integer value out of range: '" + text + "'");
		return value.intValueExact();
	}

	/**
	 * Parses a non-empty run of decimal digits, which may come from any script that the Java platform classifies as
	 * a decimal digit, including supplementary-plane scripts.
	 *
	 * @param text the candidate digits
	 * @return the value, or null if the text is empty or contains any other character
	 */
	private static BigInteger parseDecimal(String text)
	{
		if (text.isEmpty())
			return null;
		BigInteger value = BigInteger.ZERO;
		for (int offset = 0; offset < text.length(); offset += Character.charCount(text.codePointAt(offset)))
		{
			int codePoint = text.codePointAt(offset);
			if (!Character.isDigit(codePoint))
				return null;
			value = value.multiply(BigInteger.TEN).add(BigInteger.valueOf(Character.digit(codePoint, 10)));
		}
		return value;
	}

	/**
	 * Validates the shard, reads the inputs, assigns the targets and writes the selected labels.
	 *
	 * @param options the parsed options
	 * @param in      the target labels
	 * @param out     receives the selected labels
	 * @param err     receives the summary
	 * @throws IOException              if an input cannot be read
	 * @throws IllegalArgumentException if the shard, the durations or the targets are rejected
	 */
	private static void select(Options options, InputStream in, PrintStream out, PrintStream err) throws IOException
	{
		int shard = options.shard();
		int shardCount = options.shardCount();
		if (shard < 1 || shard > shardCount)
			throw new IllegalArgumentException("shard must be between 1 and shard count");
		Map<String, BigInteger> durations = readDurations(options.durations(), options.shown());
		List<String> targets = new ArrayList<>();
		byte[] input;
		try
		{
			input = in.readAllBytes();
		}
		catch (IOException failure)
		{
			throw new IOException("cannot read standard input: " + describe(failure), failure);
		}
		forEachLine(new BufferedReader(new StringReader(decodeUtf8(input))), targets::add);
		Assignment assignment = assign(targets, durations, shardCount);
		List<String> selected = assignment.shards().get(shard - 1);
		if (selected.isEmpty())
			throw new IllegalArgumentException("no Bazel test targets selected for shard " + shard + "/" + shardCount);

		Set<String> targetSet = new HashSet<>(targets);
		int defaultWeights = 0;
		for (String target : targetSet)
		{
			if (!durations.containsKey(target))
				defaultWeights += 1;
		}
		int unusedWeights = 0;
		for (String target : durations.keySet())
		{
			if (!targetSet.contains(target))
				unusedWeights += 1;
		}
		StringJoiner counts = new StringJoiner(", ", "[", "]");
		for (List<String> labels : assignment.shards())
			counts.add(Integer.toString(labels.size()));
		StringJoiner loads = new StringJoiner(", ", "[", "]");
		for (BigInteger load : assignment.loads())
			loads.add(load.toString());
		err.print("Windows Bazel shards: targets=" + counts + ", estimated test-seconds=" + loads +
			", default weights=" + defaultWeights + ", unused weights=" + unusedWeights + ". Selected shard " + shard +
			"/" + shardCount + ".\n");
		err.flush();
		byte[] payload = (String.join("\n", selected) + "\n").getBytes(UTF_8);
		out.write(payload, 0, payload.length);
		out.flush();
	}

	/**
	 * Reads the duration table. The whole file is decoded before the first record is examined, as Python's
	 * {@code Path.read_text} does.
	 *
	 * @param file  the table, with one {@code target<TAB>seconds} record per line
	 * @param shown the table's path as the diagnostics show it
	 * @return the estimated test-seconds of each target
	 * @throws IOException              if the file cannot be read as UTF-8
	 * @throws IllegalArgumentException if a record is malformed, not positive or repeats a target
	 */
	private static Map<String, BigInteger> readDurations(Path file, String shown) throws IOException
	{
		Map<String, BigInteger> durations = new HashMap<>();
		int[] lineNumber = {0};
		forEachLine(new BufferedReader(new StringReader(decodeUtf8(readFile(file, shown)))), line ->
		{
			lineNumber[0] += 1;
			if (isBlank(line) || startsWithComment(line))
				return;
			String[] fields = line.split("\t", -1);
			BigInteger duration = null;
			if (fields.length == 2 && !fields[0].isEmpty())
				duration = parseDecimal(fields[1]);
			if (duration == null)
				throw new IllegalArgumentException(shown + ":" + lineNumber[0] + ": expected target<TAB>positive integer");
			if (duration.signum() < 1)
				throw new IllegalArgumentException(shown + ":" + lineNumber[0] + ": duration must be positive");
			if (durations.putIfAbsent(fields[0], duration) != null)
				throw new IllegalArgumentException(shown + ":" + lineNumber[0] + ": duplicate target " + fields[0]);
		});
		return durations;
	}

	/**
	 * Reads a whole file. Failures that Python reports as an {@code OSError} use its wording.
	 *
	 * @param file  the file
	 * @param shown the path as the diagnostics show it
	 * @return the file content
	 * @throws IOException if the file cannot be read
	 */
	private static byte[] readFile(Path file, String shown) throws IOException
	{
		if (Files.isDirectory(file))
			throw new IOException(osError(21, "Is a directory", shown));
		try
		{
			return Files.readAllBytes(file);
		}
		catch (NoSuchFileException failure)
		{
			throw new IOException(osError(2, "No such file or directory", shown), failure);
		}
		catch (AccessDeniedException failure)
		{
			throw new IOException(osError(13, "Permission denied", shown), failure);
		}
		catch (FileSystemException failure)
		{
			if ("Not a directory".equals(failure.getReason()))
				throw new IOException(osError(20, "Not a directory", shown), failure);
			if ("Too many levels of symbolic links".equals(failure.getReason()))
				throw new IOException(osError(40, "Too many levels of symbolic links", shown), failure);
			if ("File name too long".equals(failure.getReason()))
				throw new IOException(osError(36, "File name too long", shown), failure);
			throw new IOException("cannot read " + shown + ": " + describe(failure), failure);
		}
		catch (IOException failure)
		{
			throw new IOException("cannot read " + shown + ": " + describe(failure), failure);
		}
	}

	/**
	 * Formats an operating system error as Python's {@code OSError} does.
	 *
	 * @param number the error number
	 * @param reason the system's description of the error
	 * @param shown  the path as the diagnostics show it
	 * @return the message
	 */
	private static String osError(int number, String reason, String shown)
	{
		return "[Errno " + number + "] " + reason + ": " + pythonRepr(shown);
	}

	/**
	 * Formats a path as Python's {@code str(Path(text))} does: repeated separators and "." components are dropped,
	 * and exactly two leading separators are kept.
	 *
	 * @param text the path text
	 * @return the normalized text
	 */
	private static String pythonPath(String text)
	{
		int slashes = 0;
		while (slashes < text.length() && text.charAt(slashes) == '/')
			slashes += 1;
		String root = "";
		if (slashes == 2)
			root = "//";
		else if (slashes > 0)
			root = "/";
		StringJoiner parts = new StringJoiner("/");
		for (String part : text.substring(slashes).split("/"))
		{
			if (!part.isEmpty() && !part.equals("."))
				parts.add(part);
		}
		if (root.isEmpty() && parts.length() == 0)
			return ".";
		return root + parts;
	}

	/**
	 * Formats text as Python's {@code repr(str)} does.
	 *
	 * @param text the text
	 * @return the quoted and escaped text
	 */
	private static String pythonRepr(String text)
	{
		char quote = '\'';
		if (text.indexOf('\'') >= 0 && text.indexOf('"') < 0)
			quote = '"';
		StringBuilder result = new StringBuilder().append(quote);
		for (int offset = 0; offset < text.length(); offset += Character.charCount(text.codePointAt(offset)))
		{
			int codePoint = text.codePointAt(offset);
			if (codePoint == quote || codePoint == '\\')
				result.append('\\').append((char) codePoint);
			else if (codePoint == '\t')
				result.append("\\t");
			else if (codePoint == '\n')
				result.append("\\n");
			else if (codePoint == '\r')
				result.append("\\r");
			else if (isPrintable(codePoint))
				result.appendCodePoint(codePoint);
			else if (codePoint <= 0xff)
				result.append(String.format(Locale.ROOT, "\\x%02x", codePoint));
			else if (codePoint <= 0xffff)
				result.append(String.format(Locale.ROOT, "\\u%04x", codePoint));
			else
				result.append(String.format(Locale.ROOT, "\\U%08x", codePoint));
		}
		return result.append(quote).toString();
	}

	/**
	 * Tells whether Python's {@code str.isprintable()} accepts a character.
	 *
	 * @param codePoint the character
	 * @return true for a space and for every character outside the control, format, surrogate, private-use,
	 *         unassigned and separator categories
	 */
	private static boolean isPrintable(int codePoint)
	{
		if (codePoint == ' ')
			return true;
		return switch (Character.getType(codePoint))
		{
			case Character.CONTROL, Character.FORMAT, Character.SURROGATE, Character.PRIVATE_USE, Character.UNASSIGNED,
				Character.LINE_SEPARATOR, Character.PARAGRAPH_SEPARATOR, Character.SPACE_SEPARATOR -> false;
			default -> true;
		};
	}

	/**
	 * Decodes strict UTF-8 and reports the first error as Python's {@code UnicodeDecodeError} does.
	 *
	 * @param bytes the encoded text
	 * @return the text
	 * @throws IOException if the bytes are not valid UTF-8; the message is Python's
	 */
	private static String decodeUtf8(byte[] bytes) throws IOException
	{
		StringBuilder text = new StringBuilder(bytes.length);
		int position = 0;
		while (position < bytes.length)
		{
			int lead = bytes[position] & 0xff;
			int length = sequenceLength(lead);
			if (length == 1)
			{
				text.append((char) lead);
				position += 1;
				continue;
			}
			if (length == 0)
				throw malformed(bytes, position, position + 1, "invalid start byte");
			int codePoint = lead & (0x7f >> length);
			for (int offset = 1; offset < length; offset += 1)
			{
				int index = position + offset;
				if (index >= bytes.length)
					throw malformed(bytes, position, bytes.length, "unexpected end of data");
				int next = bytes[index] & 0xff;
				if (!isContinuation(lead, offset, next))
					throw malformed(bytes, position, index, "invalid continuation byte");
				codePoint = (codePoint << 6) | (next & 0x3f);
			}
			text.appendCodePoint(codePoint);
			position += length;
		}
		return text.toString();
	}

	/**
	 * Returns the length of the UTF-8 sequence that a lead byte starts.
	 *
	 * @param lead the byte, as an unsigned value
	 * @return 1 for ASCII, 2 to 4 for a lead byte, or 0 if the byte cannot start a sequence
	 */
	private static int sequenceLength(int lead)
	{
		if (lead < 0x80)
			return 1;
		if (lead < 0xc2)
			return 0;
		if (lead < 0xe0)
			return 2;
		if (lead < 0xf0)
			return 3;
		if (lead < 0xf5)
			return 4;
		return 0;
	}

	/**
	 * Tells whether a byte may follow a lead byte at an offset, excluding overlong forms, surrogates and values
	 * above U+10FFFF.
	 *
	 * @param lead   the lead byte, as an unsigned value
	 * @param offset the position of the byte within the sequence
	 * @param next   the byte, as an unsigned value
	 * @return true if the byte is acceptable
	 */
	private static boolean isContinuation(int lead, int offset, int next)
	{
		if ((next & 0xc0) != 0x80)
			return false;
		if (offset != 1)
			return true;
		return switch (lead)
		{
			case 0xe0 -> next >= 0xa0;
			case 0xed -> next <= 0x9f;
			case 0xf0 -> next >= 0x90;
			case 0xf4 -> next <= 0x8f;
			default -> true;
		};
	}

	/**
	 * Builds Python's {@code UnicodeDecodeError} message.
	 *
	 * @param bytes  the encoded text
	 * @param start  the offset of the first rejected byte
	 * @param end    the offset after the last rejected byte
	 * @param reason Python's reason
	 * @return the failure
	 */
	private static IOException malformed(byte[] bytes, int start, int end, String reason)
	{
		if (end - start == 1)
			return new IOException(String.format(Locale.ROOT, "'utf-8' codec can't decode byte 0x%02x in position %d: %s",
				bytes[start] & 0xff, start, reason));
		return new IOException("'utf-8' codec can't decode bytes in position " + start + "-" + (end - 1) + ": " + reason);
	}

	/**
	 * Describes an I/O failure.
	 *
	 * @param failure the failure
	 * @return the failure's type and, if present, its message
	 */
	private static String describe(IOException failure)
	{
		String message = failure.getMessage();
		if (message == null)
			return failure.getClass().getSimpleName();
		return failure.getClass().getSimpleName() + ": " + message;
	}

	/**
	 * Places the targets on shards.
	 *
	 * @param targets    the supplied labels
	 * @param durations  the estimated test-seconds by label
	 * @param shardCount the number of shards
	 * @return the sorted labels and the accumulated test-seconds of every shard
	 * @throws IllegalArgumentException if the shard count is not positive, no target was supplied, a label is blank or
	 *                                  a label repeats
	 */
	private static Assignment assign(List<String> targets, Map<String, BigInteger> durations, int shardCount)
	{
		if (shardCount < 1)
			throw new IllegalArgumentException("shard count must be positive");
		if (targets.isEmpty())
			throw new IllegalArgumentException("no Bazel test targets were provided");
		for (String target : targets)
		{
			if (isBlank(target))
				throw new IllegalArgumentException("Bazel test target labels must not be empty");
		}
		if (new HashSet<>(targets).size() != targets.size())
			throw new IllegalArgumentException("duplicate Bazel test target labels were provided");

		List<String> ordered = new ArrayList<>(targets);
		ordered.sort((left, right) ->
		{
			int comparison = durationOf(durations, right).compareTo(durationOf(durations, left));
			if (comparison != 0)
				return comparison;
			return compareCodePoints(left, right);
		});
		List<List<String>> shards = new ArrayList<>();
		List<BigInteger> loads = new ArrayList<>();
		for (int index = 0; index < shardCount; index += 1)
		{
			shards.add(new ArrayList<>());
			loads.add(BigInteger.ZERO);
		}
		for (String target : ordered)
		{
			int lightest = 0;
			for (int index = 1; index < shardCount; index += 1)
			{
				if (loads.get(index).compareTo(loads.get(lightest)) < 0)
					lightest = index;
			}
			shards.get(lightest).add(target);
			loads.set(lightest, loads.get(lightest).add(durationOf(durations, target)));
		}
		for (List<String> labels : shards)
			labels.sort(SelectWindowsBazelTargets::compareCodePoints);
		return new Assignment(shards, loads);
	}

	/**
	 * Looks up the estimated test-seconds of a target.
	 *
	 * @param durations the estimated test-seconds by label
	 * @param target    the label
	 * @return the estimate, or one second when the label has none
	 */
	private static BigInteger durationOf(Map<String, BigInteger> durations, String target)
	{
		return durations.getOrDefault(target, DEFAULT_DURATION);
	}

	/**
	 * Visits each line using the separators that Python's {@code str.splitlines()} recognizes: CR, LF, CR LF, VT, FF,
	 * the file, group and record separators, NEL, and the line and paragraph separators. A final line without a
	 * separator is visited; an empty remainder after the last separator is not.
	 *
	 * @param reader   the source of text
	 * @param consumer receives each line without its separator
	 * @throws IOException if the reader fails
	 */
	private static void forEachLine(BufferedReader reader, Consumer<String> consumer) throws IOException
	{
		StringBuilder line = new StringBuilder();
		boolean afterCarriageReturn = false;
		int unit = reader.read();
		while (unit >= 0)
		{
			char current = (char) unit;
			if (afterCarriageReturn && current == '\n')
				afterCarriageReturn = false;
			else if (isLineSeparator(current))
			{
				consumer.accept(line.toString());
				line.setLength(0);
				afterCarriageReturn = current == '\r';
			}
			else
			{
				afterCarriageReturn = false;
				line.append(current);
			}
			unit = reader.read();
		}
		if (!line.isEmpty())
			consumer.accept(line.toString());
	}

	/**
	 * Indicates whether a character ends a line in Python's {@code str.splitlines()}.
	 *
	 * @param character the character
	 * @return true for each splitlines separator, including CR
	 */
	private static boolean isLineSeparator(char character)
	{
		return character == '\n' || character == '\r' || character == '\u000b' || character == '\f' ||
			(character >= '\u001c' && character <= '\u001e') || character == '\u0085' || character == ' ' ||
			character == ' ';
	}

	/**
	 * Indicates whether a string consists only of characters that Python's {@code str.isspace()} accepts.
	 *
	 * @param value the string
	 * @return true for an empty or whitespace-only string
	 */
	private static boolean isBlank(String value)
	{
		return firstNonWhitespace(value) < 0;
	}

	/**
	 * Indicates whether the first non-whitespace character of a line is {@code #}.
	 *
	 * @param line the line
	 * @return true for a comment
	 */
	private static boolean startsWithComment(String line)
	{
		int index = firstNonWhitespace(line);
		return index >= 0 && line.charAt(index) == '#';
	}

	/**
	 * Finds the first character that Python's {@code str.isspace()} rejects.
	 *
	 * @param value the string
	 * @return the character's index, or -1 if there is none
	 */
	private static int firstNonWhitespace(String value)
	{
		for (int offset = 0; offset < value.length(); offset += Character.charCount(value.codePointAt(offset)))
		{
			if (!isPythonWhitespace(value.codePointAt(offset)))
				return offset;
		}
		return -1;
	}

	/**
	 * Indicates whether Python's {@code str.isspace()} accepts a code point.
	 *
	 * @param codePoint the code point
	 * @return true for Unicode whitespace and the ASCII separators
	 */
	private static boolean isPythonWhitespace(int codePoint)
	{
		return (codePoint >= 9 && codePoint <= 13) || (codePoint >= 0x1c && codePoint <= 0x1f) || codePoint == 0x85 ||
			Character.isSpaceChar(codePoint);
	}

	/**
	 * Compares strings by Unicode code point rather than by UTF-16 code unit.
	 *
	 * @param left  the first string
	 * @param right the second string
	 * @return a negative number, zero or a positive number as {@code left} sorts before, with or after {@code right}
	 */
	private static int compareCodePoints(String left, String right)
	{
		int leftIndex = 0;
		int rightIndex = 0;
		while (leftIndex < left.length() && rightIndex < right.length())
		{
			int leftCodePoint = left.codePointAt(leftIndex);
			int rightCodePoint = right.codePointAt(rightIndex);
			int comparison = Integer.compare(leftCodePoint, rightCodePoint);
			if (comparison != 0)
				return comparison;
			leftIndex += Character.charCount(leftCodePoint);
			rightIndex += Character.charCount(rightCodePoint);
		}
		return Integer.compare(left.length() - leftIndex, right.length() - rightIndex);
	}

	/**
	 * Parsed command options.
	 *
	 * @param shard      the one-based shard to select
	 * @param shardCount the number of shards
	 * @param durations  the duration table
	 * @param shown      the table's path as the diagnostics show it
	 */
	private record Options(int shard, int shardCount, Path durations, String shown)
	{
	}

	/**
	 * The result of placing every target.
	 *
	 * @param shards the sorted labels of each shard
	 * @param loads  the accumulated test-seconds of each shard
	 */
	private record Assignment(List<List<String>> shards, List<BigInteger> loads)
	{
	}
}
