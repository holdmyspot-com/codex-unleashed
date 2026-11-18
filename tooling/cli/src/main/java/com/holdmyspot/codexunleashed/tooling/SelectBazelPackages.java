package com.holdmyspot.codexunleashed.tooling;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.PrintStream;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.SortedSet;
import java.util.StringJoiner;
import java.util.TreeSet;
import java.util.function.Consumer;
import static java.nio.charset.StandardCharsets.UTF_8;

/**
 * Selects the Bazel packages that one test shard or one Clippy shard builds.
 * <p>
 * A package is a candidate label without its final {@code :} component. Packages are placed in shards by the SHA-256
 * digest of their UTF-8 bytes, so a package keeps its shard whatever else the candidate list contains.
 */
public final class SelectBazelPackages
{
	/** Command name. */
	public static final String NAME = "select-bazel-packages";
	/** Option that names the candidate label list. */
	private static final String CANDIDATES = "--candidates";
	/** Option that names the existing directory that receives the three output files. */
	private static final String OUTPUT_DIRECTORY = "--output-directory";
	/** Option that names the maintained Clippy pattern list and selects the Clippy variant. */
	private static final String CLIPPY_TARGETS = "--clippy-targets";
	/** Option that names the one-based shard to select. */
	private static final String SHARD = "--shard";
	/** Option that names the number of shards. */
	private static final String SHARD_COUNT = "--shard-count";
	/** Every option that the command accepts. */
	private static final Set<String> OPTIONS = Set.of(CANDIDATES, OUTPUT_DIRECTORY, CLIPPY_TARGETS, SHARD, SHARD_COUNT);
	/** Usage line. */
	private static final String USAGE = "Usage: codex-tooling " + NAME + " --candidates PATH --output-directory PATH " +
		"[--clippy-targets PATH] --shard N --shard-count N\n";
	/** Complete help text. */
	private static final String HELP = USAGE +
		"Writes selected-targets.txt, package-shards.json and all-package-patterns.query into the output directory.\n" +
		"With --clippy-targets the shard count must be 2.\n" +
		"Status: 0 selected; 1 rejected input or failed file access; 2 invalid arguments.\n";
	/** First maintained Clippy pattern: the whole workspace. */
	private static final String CLIPPY_WORKSPACE = "//codex-rs/...";
	/** Second maintained Clippy pattern, which the selection appends last. */
	private static final String CLIPPY_EXCLUSION = "-//codex-rs/v8-poc:all";
	/** Number of maintained Clippy patterns that precede the manual patterns. */
	private static final int MAINTAINED_PATTERNS = 2;
	/** Number of Clippy package shards. */
	private static final int CLIPPY_SHARD_COUNT = 2;

	/** Prevents construction. */
	private SelectBazelPackages()
	{
	}

	/**
	 * Selects the packages of one shard and writes the selection files.
	 * <p>
	 * Relative paths resolve against the process working directory. The caller creates the output directory and
	 * keeps ownership of every stream and of the scope. Nothing is written when the input is rejected.
	 *
	 * @param scope     borrowed shared values
	 * @param arguments command arguments after the command name
	 * @param out       receives the help text or the one-line selection summary
	 * @param err       receives usage or failure diagnostics
	 * @return the exit status: 0 for a selection or help, 1 for rejected input or a failed file access, 2 for invalid
	 *         arguments
	 * @throws NullPointerException if an argument is null
	 */
	public static int run(ToolingScope scope, String[] arguments, PrintStream out, PrintStream err)
	{
		Objects.requireNonNull(scope, "scope");
		Objects.requireNonNull(arguments, "arguments");
		Objects.requireNonNull(out, "out");
		Objects.requireNonNull(err, "err");
		if (arguments.length == 1 && arguments[0].equals("--help"))
		{
			out.print(HELP);
			out.flush();
			return 0;
		}

		Options options;
		try
		{
			options = parseOptions(arguments);
		}
		catch (IllegalArgumentException failure)
		{
			err.print(USAGE + NAME + ": " + failure.getMessage() + "\n");
			err.flush();
			return 2;
		}
		try
		{
			String summary = select(scope, options);
			out.print(summary);
			out.flush();
			return 0;
		}
		catch (IOException | IllegalArgumentException failure)
		{
			err.print(NAME + ": " + failure.getMessage() + "\n");
			err.flush();
			return 1;
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
				throw new IllegalArgumentException("Unknown option " + option);
			if (index + 1 >= arguments.length)
				throw new IllegalArgumentException("Option " + option + " needs a value");
			if (values.putIfAbsent(option, arguments[index + 1]) != null)
				throw new IllegalArgumentException("Option " + option + " was given more than once");
		}
		for (String required : List.of(CANDIDATES, OUTPUT_DIRECTORY, SHARD, SHARD_COUNT))
		{
			if (!values.containsKey(required))
				throw new IllegalArgumentException("Option " + required + " is required");
		}
		Optional<Path> clippyTargets = Optional.empty();
		if (values.containsKey(CLIPPY_TARGETS))
			clippyTargets = Optional.of(Path.of(values.get(CLIPPY_TARGETS)));
		return new Options(Path.of(values.get(CANDIDATES)), Path.of(values.get(OUTPUT_DIRECTORY)), clippyTargets,
			parseInteger(SHARD, values.get(SHARD)), parseInteger(SHARD_COUNT, values.get(SHARD_COUNT)));
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
		boolean negative = false;
		if (text.startsWith("-") || text.startsWith("+"))
		{
			negative = text.startsWith("-");
			start = 1;
		}
		long value = 0;
		for (int offset = start; offset < text.length(); offset += 1)
		{
			int digit = Character.digit(text.codePointAt(offset), 10);
			if (digit < 0)
				throw new IllegalArgumentException("Option " + option + " needs a decimal integer, not '" + text + "'");
			if (Character.isSupplementaryCodePoint(text.codePointAt(offset)))
				offset += 1;
			value = value * 10 + digit;
			if (value > Integer.MAX_VALUE + 1L)
				throw new IllegalArgumentException("Option " + option + " is outside the int range: '" + text + "'");
		}
		if (start == text.length())
			throw new IllegalArgumentException("Option " + option + " needs a decimal integer, not '" + text + "'");
		if (negative)
			value = -value;
		if (value > Integer.MAX_VALUE || value < Integer.MIN_VALUE)
			throw new IllegalArgumentException("Option " + option + " is outside the int range: '" + text + "'");
		return (int) value;
	}

	/**
	 * Validates the shard, reads the inputs, selects the packages and writes the output files.
	 *
	 * @param scope   borrowed shared values
	 * @param options the parsed options
	 * @return the summary line, with its line terminator
	 * @throws IOException              if an input cannot be read or an output cannot be written
	 * @throws IllegalArgumentException if the shard, the candidates or the Clippy patterns are rejected
	 */
	private static String select(ToolingScope scope, Options options) throws IOException
	{
		int shard = options.shard();
		int shardCount = options.shardCount();
		if (shardCount < 1)
			throw new IllegalArgumentException("Shard count must be positive, not " + shardCount);
		if (shard < 1 || shard > shardCount)
			throw new IllegalArgumentException("Shard " + shard + " must be between 1 and the shard count " + shardCount);
		boolean clippy = options.clippyTargets().isPresent();
		if (clippy && shardCount != CLIPPY_SHARD_COUNT)
			throw new IllegalArgumentException("Clippy selection requires shard count " + CLIPPY_SHARD_COUNT + ", not " +
				shardCount);

		List<String> manualPatterns = List.of();
		if (clippy)
			manualPatterns = readManualPatterns(options.clippyTargets().get());
		SortedSet<String> packages = readPackages(options.candidates());
		Map<String, Integer> assignments = assignShards(packages, shardCount);
		for (String pattern : manualPatterns)
		{
			if (!assignments.containsKey(packageOf(pattern)))
				throw new IllegalArgumentException("Manual Clippy target outside package selection: " + pattern);
		}

		List<String> selected = new ArrayList<>();
		for (Map.Entry<String, Integer> assignment : assignments.entrySet())
		{
			if (assignment.getValue() == shard)
				selected.add(assignment.getKey() + ":all");
		}
		if (selected.isEmpty() && clippy)
			throw new IllegalArgumentException("No Clippy packages selected for shard " + shard);
		if (selected.isEmpty())
			throw new IllegalArgumentException("No packages selected for shard " + shard);

		String summary = "Selected " + selected.size() + " of " + packages.size() + " packages for shard " + shard + "/" +
			shardCount + "\n";
		if (clippy)
		{
			for (String pattern : manualPatterns)
			{
				if (assignments.get(packageOf(pattern)) == shard)
					selected.add(pattern);
			}
			selected.add(CLIPPY_EXCLUSION);
			summary = "Selected " + selected.size() + " Clippy patterns for package shard " + shard + "/" + shardCount +
				"\n";
		}
		writeOutputs(scope, options.outputDirectory(), selected, assignments);
		return summary;
	}

	/**
	 * Reads the maintained Clippy patterns and returns the manual patterns that follow the two maintained ones.
	 *
	 * @param file the Clippy pattern list
	 * @return the manual patterns in file order, with duplicates preserved
	 * @throws IOException              if the file cannot be read as UTF-8
	 * @throws IllegalArgumentException if the maintained patterns changed or a manual pattern is malformed
	 */
	private static List<String> readManualPatterns(Path file) throws IOException
	{
		List<String> patterns = new ArrayList<>();
		readLines(file, patterns::add);
		if (patterns.size() < MAINTAINED_PATTERNS || !patterns.get(0).equals(CLIPPY_WORKSPACE) ||
			!patterns.get(1).equals(CLIPPY_EXCLUSION))
		{
			throw new IllegalArgumentException("Maintained Clippy scope changed: " + file + " must begin with " +
				CLIPPY_WORKSPACE + " and " + CLIPPY_EXCLUSION);
		}
		List<String> manual = patterns.subList(MAINTAINED_PATTERNS, patterns.size());
		for (String pattern : manual)
		{
			if (!pattern.startsWith("//") || pattern.indexOf(':') < 0)
				throw new IllegalArgumentException("Unexpected manual Clippy pattern: " + pattern);
		}
		return manual;
	}

	/**
	 * Reads the candidate labels and collects their distinct packages in code point order.
	 *
	 * @param file the candidate label list
	 * @return the packages
	 * @throws IOException if the file cannot be read as UTF-8
	 */
	private static SortedSet<String> readPackages(Path file) throws IOException
	{
		SortedSet<String> packages = new TreeSet<>(SelectBazelPackages::compareCodePoints);
		readLines(file, label -> packages.add(packageOf(label)));
		return packages;
	}

	/**
	 * Reads a strict UTF-8 file line by line.
	 *
	 * @param file     the file to read
	 * @param consumer receives each line without its separator
	 * @throws IOException if the file cannot be opened, read or decoded; the message names the file
	 */
	private static void readLines(Path file, Consumer<String> consumer) throws IOException
	{
		try (BufferedReader reader = Files.newBufferedReader(file))
		{
			forEachLine(reader, consumer);
		}
		catch (IOException failure)
		{
			throw new IOException("Cannot read " + file + ": " + describe(failure), failure);
		}
	}

	/**
	 * Describes an I/O failure without repeating the file name that the caller already adds.
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
	 * Removes the final {@code :} component of a label, whatever the label's syntax.
	 *
	 * @param label a candidate label or manual pattern
	 * @return the text before the last colon, or the whole text when it has no colon
	 */
	private static String packageOf(String label)
	{
		int colon = label.lastIndexOf(':');
		if (colon < 0)
			return label;
		return label.substring(0, colon);
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
	 * Assigns each package to a one-based shard by its digest.
	 *
	 * @param packages   the packages in code point order
	 * @param shardCount the number of shards
	 * @return the shard of each package, in the order of {@code packages}
	 */
	private static Map<String, Integer> assignShards(Set<String> packages, int shardCount)
	{
		MessageDigest digest;
		try
		{
			digest = MessageDigest.getInstance("SHA-256");
		}
		catch (NoSuchAlgorithmException failure)
		{
			throw new IllegalStateException("Java platform does not supply SHA-256", failure);
		}
		BigInteger count = BigInteger.valueOf(shardCount);
		Map<String, Integer> assignments = new LinkedHashMap<>();
		for (String candidate : packages)
		{
			BigInteger value = new BigInteger(1, digest.digest(candidate.getBytes(UTF_8)));
			assignments.put(candidate, value.mod(count).intValue() + 1);
		}
		return assignments;
	}

	/**
	 * Writes the selected patterns, the package-to-shard document and the query over every package.
	 *
	 * @param scope       borrowed shared values
	 * @param directory   the existing output directory
	 * @param selected    the patterns of the requested shard
	 * @param assignments the shard of every package, in code point order
	 * @throws IOException if a file cannot be written
	 */
	private static void writeOutputs(ToolingScope scope, Path directory, List<String> selected,
		Map<String, Integer> assignments) throws IOException
	{
		write(directory.resolve("selected-targets.txt"), String.join("\n", selected) + "\n");
		// The shared formatter leaves U+007F raw, whereas the Python writer this replaces escapes every character
		// outside the printable ASCII range.
		String formatted = ArtifactJson.formatPreservingKeyOrder(scope, assignments);
		String document = formatted.replace(String.valueOf((char) 0x7f), "\\u007f");
		write(directory.resolve("package-shards.json"), document);
		StringJoiner query = new StringJoiner(" ", "set(", ")\n");
		for (String candidate : assignments.keySet())
			query.add(candidate + ":all");
		write(directory.resolve("all-package-patterns.query"), query.toString());
	}

	/**
	 * Writes a UTF-8 file, replacing any existing content.
	 *
	 * @param file    the file to write
	 * @param content the complete content
	 * @throws IOException if the file cannot be written; the message names the file
	 */
	private static void write(Path file, String content) throws IOException
	{
		try
		{
			Files.writeString(file, content, UTF_8);
		}
		catch (IOException failure)
		{
			throw new IOException("Cannot write " + file + ": " + describe(failure), failure);
		}
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
	 * @param candidates      the candidate label list
	 * @param outputDirectory the existing directory that receives the output files
	 * @param clippyTargets   the maintained Clippy pattern list, or empty for baseline selection
	 * @param shard           the one-based shard to select
	 * @param shardCount      the number of shards
	 */
	private record Options(Path candidates, Path outputDirectory, Optional<Path> clippyTargets, int shard,
		int shardCount)
	{
	}
}
