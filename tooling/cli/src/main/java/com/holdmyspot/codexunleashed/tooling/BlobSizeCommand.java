package com.holdmyspot.codexunleashed.tooling;

import java.io.IOException;
import java.io.PrintStream;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.MathContext;
import java.math.RoundingMode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import picocli.CommandLine;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Model.OptionSpec;

/** Checks committed added and modified Git blobs against an explicit allowlist and size limit. */
public final class BlobSizeCommand
{
	/** Names the repository blob-size command. */
	public static final String NAME = "check-blob-size";
	private static final BigInteger DEFAULT_MAX_BYTES = BigInteger.valueOf(500 * 1024);
	private static final BigDecimal BYTES_PER_KIB = BigDecimal.valueOf(1024);

	/** Prevents construction. */
	private BlobSizeCommand()
	{
	}

	/**
	 * Checks blobs using the process environment for optional GitHub summary output.
	 *
	 * @param args the policy options
	 * @param out the policy output
	 * @param err the parser diagnostics
	 * @return zero when accepted or help is requested, one for violations, or two for parser failures
	 * @throws IOException if Git, input, reporting, or temporary cleanup fails
	 * @throws NullPointerException if an argument is null
	 */
	public static int run(String[] args, PrintStream out, PrintStream err) throws IOException
	{
		return run(args, out, err, System.getenv());
	}

	/**
	 * Checks committed object sizes and writes reports without changing repository sources or the index.
	 *
	 * @param args the policy options
	 * @param out the policy output
	 * @param err the parser diagnostics
	 * @param environment the explicit caller environment
	 * @return zero when accepted or help is requested, one for violations, or two for parser failures
	 * @throws IOException if Git, input, reporting, or temporary cleanup fails
	 * @throws NullPointerException if an argument or environment entry is null
	 */
	public static int run(String[] args, PrintStream out, PrintStream err, Map<String, String> environment)
		throws IOException
	{
		Objects.requireNonNull(args, "args");
		Objects.requireNonNull(out, "out");
		Objects.requireNonNull(err, "err");
		Map<String, String> variables = Map.copyOf(environment);
		CommandLine parser = parser();
		CommandLine.ParseResult options;
		try
		{
			options = parser.parseArgs(args);
		}
		catch (CommandLine.ParameterException failure)
		{
			err.println("ERROR: " + failure.getMessage());
			return 2;
		}
		if (options.isUsageHelpRequested())
		{
			parser.usage(out);
			return 0;
		}

		Path repository = options.matchedOptionValue("--repo", Path.of("."));
		Path allowlist = options.matchedOptionValue("--allowlist", null);
		BigInteger maximum = options.matchedOptionValue("--max-bytes", DEFAULT_MAX_BYTES);
		List<Blob> blobs = collect(repository, options.matchedOptionValue("--base", null),
			options.matchedOptionValue("--head", null), loadAllowlist(allowlist), variables);
		List<Blob> violations = blobs.stream().filter(blob -> blob.blocked(maximum)).toList();
		String summary = variables.get("GITHUB_STEP_SUMMARY");
		if (summary != null && !summary.isEmpty())
			writeSummary(Path.of(summary), maximum, blobs, violations);
		printReport(out, maximum, blobs, violations);
		if (!violations.isEmpty())
			return 1;
		return 0;
	}

	/**
	 * Reads exact allowlist paths with comments and retained Unicode whitespace trimming.
	 *
	 * @param path the UTF-8 allowlist
	 * @return the exact permitted paths
	 * @throws IOException if input access or UTF-8 decoding fails
	 */
	private static Set<String> loadAllowlist(Path path) throws IOException
	{
		Set<String> paths = new HashSet<>();
		for (String raw : TextLines.split(Files.readString(path)))
		{
			String line = strip(raw.split("#", 2)[0]);
			if (!line.isEmpty())
				paths.add(line);
		}
		return paths;
	}

	/**
	 * Collects NUL-delimited changed paths and each committed object's size and Git binary classification.
	 *
	 * @param repository the inspected repository
	 * @param base the base revision
	 * @param head the head revision
	 * @param allowlist the exact permitted paths
	 * @param environment the explicit Git environment overrides
	 * @return blobs in Git's maintained order
	 * @throws IOException if Git or integer output decoding fails
	 */
	private static List<Blob> collect(Path repository, String base, String head, Set<String> allowlist,
		Map<String, String> environment) throws IOException
	{
		String changed = git(repository, List.of("diff", "--name-only", "--diff-filter=AM", "--no-renames", "-z",
			base, head), environment);
		List<Blob> blobs = new ArrayList<>();
		for (String path : changed.split("\u0000", -1))
		{
			if (path.isEmpty())
				continue;
			BigInteger size = DecimalNumberText.parseInteger(git(repository, List.of("cat-file", "-s", head + ":" + path),
				environment));
			String stat = strip(git(repository, List.of("diff", "--numstat", "--diff-filter=AM", "--no-renames", base,
				head, "--", path), environment));
			boolean binary = stat.startsWith("-\t-\t");
			blobs.add(new Blob(path, size, allowlist.contains(path), binary));
		}
		return List.copyOf(blobs);
	}

	/**
	 * Executes Git with owned output captures and retains operational diagnostics.
	 *
	 * @param repository the Git cwd
	 * @param arguments the literal Git arguments
	 * @param environment the explicit caller environment overrides
	 * @return strict UTF-8 stdout
	 * @throws IOException if command execution, Git status, decoding, or cleanup fails
	 */
	private static String git(Path repository, List<String> arguments, Map<String, String> environment) throws IOException
	{
		List<String> command = new ArrayList<>(List.of("git"));
		command.addAll(arguments);
		SystemCommands.Result result = SystemCommands.capture(command, repository,
			Path.of(System.getProperty("java.io.tmpdir")), environment);
		if (result.status() != 0)
			throw new IOException("Git " + arguments.getFirst() + " failed with status " + result.status() + ": " +
				result.stderr());
		return result.stdout();
	}

	/**
	 * Writes the maintained summary only after complete collection and formatting.
	 *
	 * @param path the caller summary destination
	 * @param maximum the configured limit
	 * @param blobs the complete changed blobs
	 * @param violations the blocked blobs
	 * @throws IOException if formatting or writing fails
	 */
	private static void writeSummary(Path path, BigInteger maximum, List<Blob> blobs, List<Blob> violations)
		throws IOException
	{
		StringBuilder text = new StringBuilder(256).append("## Blob Size Policy\n\nDefault max: `").append(maximum).
			append("` bytes (").append(kib(maximum)).append(")\nChanged files checked: `").append(blobs.size()).
			append("`\nViolations: `").append(violations.size()).append("`\n\n");
		if (blobs.isEmpty())
			text.append("No changed files were detected.\n");
		else
		{
			text.append("| Path | Kind | Size | Status |\n| --- | --- | ---: | --- |\n");
			for (Blob blob : blobs)
				text.append("| `").append(blob.path()).append("` | ").append(blob.kind()).append(" | `").append(blob.size()).
					append("` bytes (").append(kib(blob.size())).append(") | ").append(blob.status(maximum)).append(" |\n");
		}
		Files.writeString(path, text);
	}

	/**
	 * Prints the maintained console report and policy guidance.
	 *
	 * @param out the output stream
	 * @param maximum the configured limit
	 * @param blobs the collected blobs
	 * @param violations the blocked blobs
	 * @throws IOException if numeric report formatting fails
	 */
	private static void printReport(PrintStream out, BigInteger maximum, List<Blob> blobs, List<Blob> violations)
		throws IOException
	{
		if (blobs.isEmpty())
		{
			out.println("No changed files were detected.");
			return;
		}
		out.println("Checked " + blobs.size() + " changed file(s) against the " + maximum + "-byte limit.");
		for (Blob blob : blobs)
			out.println("- " + blob.path() + ": " + blob.size() + " bytes (" + kib(blob.size()) + ") [" + blob.kind() +
				", " + blob.status(maximum) + "]");
		if (!violations.isEmpty())
		{
			out.println("\nFile(s) exceed the configured limit:");
			for (Blob blob : violations)
				out.println("- " + blob.path() + ": " + blob.size() + " bytes > " + maximum + " bytes");
			out.println("\nIf one of these is a real checked-in asset we want to keep, add its repo-relative path to " +
				".github/blob-size-allowlist.txt. Otherwise, shrink it or keep it out of git.");
		}
	}

	/**
	 * Formats binary floating-point KiB with the retained one-decimal ties-to-even rounding.
	 *
	 * @param size the integer byte value
	 * @return the maintained KiB text
	 * @throws IOException if integer division exceeds finite binary floating-point range
	 */
	private static String kib(BigInteger size) throws IOException
	{
		double value = new BigDecimal(size).divide(BYTES_PER_KIB).doubleValue();
		if (!Double.isFinite(value))
			throw new IOException("Blob-size report integer division is outside the floating-point range");
		BigDecimal rounded = new BigDecimal(value, MathContext.UNLIMITED).setScale(1, RoundingMode.HALF_EVEN);
		String text = rounded.toPlainString();
		if (value < 0 && rounded.signum() == 0)
			text = "-" + text;
		return text + " KiB";
	}

	/**
	 * Removes the Unicode whitespace supported by allowlist line trimming.
	 *
	 * @param text the allowlist or Git output text
	 * @return the trimmed text
	 */
	private static String strip(String text)
	{
		int first = 0;
		int last = text.length();
		while (first < last && whitespace(text.codePointAt(first)))
			first += Character.charCount(text.codePointAt(first));
		while (last > first && whitespace(text.codePointBefore(last)))
			last -= Character.charCount(text.codePointBefore(last));
		return text.substring(first, last);
	}

	/**
	 * Classifies whitespace without applying numeric-input restrictions to allowlist text.
	 *
	 * @param codePoint the candidate character
	 * @return whether line trimming removes it
	 */
	private static boolean whitespace(int codePoint)
	{
		return Character.isWhitespace(codePoint) || Character.isSpaceChar(codePoint) || codePoint == 0x85;
	}

	/**
	 * Defines required revisions and allowlist, optional checkout, and retained integer size grammar.
	 *
	 * @return the configured parser
	 */
	private static CommandLine parser()
	{
		CommandSpec specification = CommandSpec.create().name(NAME);
		for (String option : List.of("--base", "--head"))
			specification.addOption(OptionSpec.builder(option).type(String.class).required(true).build());
		specification.addOption(OptionSpec.builder("--allowlist").type(Path.class).required(true).build());
		specification.addOption(OptionSpec.builder("--repo").type(Path.class).build());
		specification.addOption(OptionSpec.builder("--max-bytes").type(BigInteger.class).
			converters(DecimalNumberText::parseInteger).build());
		specification.addOption(OptionSpec.builder("-h", "--help").usageHelp(true).build());
		return new CommandLine(specification).setOverwrittenOptionsAllowed(true).setExpandAtFiles(false).
			setAbbreviatedOptionsAllowed(true);
	}

	/**
	 * Retains one committed blob's exact path, size, allowlist decision, and binary classification.
	 *
	 * @param path the repository-relative path
	 * @param size the object size
	 * @param allowlisted whether the exact path is permitted
	 * @param binary whether Git classifies the change as binary
	 */
	private record Blob(String path, BigInteger size, boolean allowlisted, boolean binary)
	{
		/**
		 * Evaluates the configured threshold after the explicit allowlist decision.
		 *
		 * @param maximum the byte limit
		 * @return whether this object violates the policy
		 */
		private boolean blocked(BigInteger maximum)
		{
			return size.compareTo(maximum) > 0 && !allowlisted;
		}

		/**
		 * Identifies the maintained report kind.
		 *
		 * @return binary or non-binary
		 */
		private String kind()
		{
			if (binary)
				return "binary";
			return "non-binary";
		}

		/**
		 * Identifies the report status under the supplied policy.
		 *
		 * @param maximum the byte limit
		 * @return blocked, allowlisted, or ok
		 */
		private String status(BigInteger maximum)
		{
			if (blocked(maximum))
				return "blocked";
			if (allowlisted)
				return "allowlisted";
			return "ok";
		}
	}
}
