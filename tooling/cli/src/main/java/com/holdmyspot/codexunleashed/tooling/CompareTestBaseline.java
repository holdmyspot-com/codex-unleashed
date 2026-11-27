package com.holdmyspot.codexunleashed.tooling;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.regex.Pattern;

/** Compares a patched test report with known upstream failures and writes canonical baseline records. */
public final class CompareTestBaseline
{
	/**
	 * The four report-comparison sections, retaining raw display IDs.
	 *
	 * @param newFailures failures absent from all known baselines
	 * @param fixed baseline failures that passed in this run
	 * @param unchanged known failures still present in this run
	 * @param notRun primary-baseline IDs absent from the execution set
	 */
	public record Sections(List<String> newFailures, List<String> fixed, List<String> unchanged, List<String> notRun)
	{
		/**
		 * Copies section values.
		 *
		 * @param newFailures failures absent from all known baselines
		 * @param fixed baseline failures that passed in this run
		 * @param unchanged known failures still present in this run
		 * @param notRun primary-baseline IDs absent from the execution set
		 */
		public Sections(List<String> newFailures, List<String> fixed, List<String> unchanged, List<String> notRun)
		{
			this.newFailures = List.copyOf(newFailures);
			this.fixed = List.copyOf(fixed);
			this.unchanged = List.copyOf(unchanged);
			this.notRun = List.copyOf(notRun);
		}
	}

	/** Removes generated case-number prefixes after each double colon. */
	private static final Pattern CASE_PREFIX = Pattern.compile("(?<=::)(?:test_case_[0-9]+_)?_*");
	/** Default location for upstream baseline files. */
	private static final String DEFAULT_BASELINE_DIRECTORY = "docs/test-baselines";
	/** Default minimum percentage of the baseline's executed tests that the patched run must execute. */
	public static final double DEFAULT_MIN_EXECUTED_PERCENT = 98.0;

	/** Prevents construction. */
	private CompareTestBaseline()
	{
	}

	/**
	 * Compares known baseline failures with the collected current report.
	 *
	 * @param baseline primary baseline IDs
	 * @param report current run
	 * @param related failures from same-run related baselines
	 * @return deterministic four-section comparison
	 */
	public static Sections compare(Collection<String> baseline, TestReports.Report report, Collection<String> related)
	{
		Objects.requireNonNull(baseline, "baseline");
		Objects.requireNonNull(report, "report");
		Objects.requireNonNull(related, "related");
		Map<String, String> failing = normalize(report.failing());
		Set<String> executed = normalizeKeys(report.executed());
		executed.addAll(normalizeKeys(report.passedCases()));
		Map<String, String> own = normalize(baseline);
		Map<String, String> recorded = normalize(related);
		recorded.putAll(own);
		List<String> newItems = new ArrayList<>();
		List<String> fixed = new ArrayList<>();
		List<String> unchanged = new ArrayList<>();
		List<String> notRun = new ArrayList<>();
		failing.forEach((key, value) ->
		{
			if (!recorded.containsKey(key)) newItems.add(value);
		});
		recorded.forEach((key, value) ->
		{
			if ((executed.contains(key) || targetRan(key, executed)) && !failing.containsKey(key)) fixed.add(value);
		});
		recorded.keySet().forEach(key ->
		{
			if (failing.containsKey(key)) unchanged.add(failing.get(key));
		});
		own.forEach((key, value) ->
		{
			if (!executed.contains(key) && !targetRan(key, executed) && !failing.containsKey(key)) notRun.add(value);
		});
		Comparator<String> order = Comparator.naturalOrder();
		newItems.sort(order); fixed.sort(order); unchanged.sort(order); notRun.sort(order);
		return new Sections(newItems, fixed, unchanged, notRun);
	}

	/**
	 * Tells whether a Bazel case ID {@code "<label> <case>"} belongs to a target that ran in some shard.
	 *
	 * @param key normalized baseline ID
	 * @param executed normalized executed IDs
	 * @return true for a Bazel case ID whose target label is executed
	 */
	private static boolean targetRan(String key, Set<String> executed)
	{
		int space = key.indexOf(' ');
		return key.startsWith("//") && space > 0 && executed.contains(key.substring(0, space));
	}

	/**
	 * Renders the current protocol without its initial scan line.
	 *
	 * @param sections comparison sections
	 * @return summary lines for all four sections
	 */
	public static List<String> renderSections(Sections sections)
	{
		List<String> lines = new ArrayList<>();
		appendSection(lines, "new (fail the run)", sections.newFailures());
		appendSection(lines, "fixed relative to the baseline", sections.fixed());
		appendSection(lines, "unchanged baseline failures", sections.unchanged());
		appendSection(lines, "in the baseline but not run", sections.notRun());
		return List.copyOf(lines);
	}

	/**
	 * Appends one named section and its IDs to the output lines.
	 *
	 * @param lines destination output lines
	 * @param title section heading
	 * @param values IDs to display
	 */
	private static void appendSection(List<String> lines, String title, List<String> values)
	{
		lines.add("[" + title + "] " + values.size());
		values.forEach(value -> lines.add("    " + value));
	}

	/**
	 * Produces the legacy summary text for a collected report.
	 *
	 * @param sections comparison result
	 * @param report collected current test report
	 * @return summary lines in command output order
	 */
	public static List<String> render(Sections sections, TestReports.Report report)
	{
		List<String> lines = new ArrayList<>();
		Set<String> failing = report.failing();
		lines.add("Scanned " + report.files().size() + " report file(s): " + report.executed().size() +
			" test(s) executed, " + failing.size() + " failing or timed out.");
		lines.addAll(renderSections(sections));
		return List.copyOf(lines);
	}

	/**
	 * Canonicalizes IDs in sorted raw-ID order, making normalized collisions deterministic.
	 *
	 * @param values raw report IDs
	 * @return normalized key to selected raw ID mapping
	 */
	private static Map<String, String> normalize(Collection<String> values)
	{
		Map<String, String> result = new LinkedHashMap<>();
		values.stream().sorted().forEach(value -> result.put(matchingKey(value), value));
		return result;
	}

	/**
	 * Normalizes IDs for membership checks.
	 *
	 * @param values raw IDs
	 * @return set of normalized keys
	 */
	private static Set<String> normalizeKeys(Collection<String> values)
	{
		Set<String> result = new LinkedHashSet<>();
		values.stream().sorted().map(CompareTestBaseline::matchingKey).forEach(result::add);
		return result;
	}

	/**
	 * Drops generated case numbers and leading underscores after every double colon.
	 *
	 * @param test raw test ID
	 * @return normalized ID used for baseline matching
	 */
	public static String matchingKey(String test)
	{
		return CASE_PREFIX.matcher(test).replaceAll("");
	}

	/**
	 * Reads a baseline's failure arrays, accepting legacy minimal documents.
	 *
	 * @param scope configured JSON mapper scope
	 * @param path baseline document
	 * @return union of failed and timed-out IDs
	 * @throws IOException if the document is malformed or unreadable
	 */
	public static Set<String> readFailures(ToolingScope scope, Path path) throws IOException
	{
		Object raw = scope.getJsonMapper().readValue(Files.readString(path), Object.class);
		if (!(raw instanceof Map<?, ?> document))
			throw new IOException("Invalid baseline at " + path);
		Set<String> result = new LinkedHashSet<>();
		for (String key : List.of("failed", "timed_out"))
		{
			Object values = document.get(key);
			if (values == null) continue;
			if (!(values instanceof List<?> list) ||
				list.stream().anyMatch(value -> !(value instanceof String)))
				throw new IOException("Invalid baseline field " + key + " at " + path);
			list.forEach(value -> result.add((String) value));
		}
		return result;
	}

	/**
	 * Reads the number of tests the baseline run executed.
	 *
	 * @param scope configured JSON mapper scope
	 * @param path baseline document
	 * @return the recorded count, or empty if the baseline predates the field
	 * @throws IOException if the document is malformed or unreadable
	 */
	public static OptionalLong readExecutedCount(ToolingScope scope, Path path) throws IOException
	{
		Object raw = scope.getJsonMapper().readValue(Files.readString(path), Object.class);
		if (!(raw instanceof Map<?, ?> document))
			throw new IOException("Invalid baseline at " + path);
		Object value = document.get("tests_executed");
		if (value == null) return OptionalLong.empty();
		if (!(value instanceof Number number))
			throw new IOException("Invalid baseline field tests_executed at " + path);
		return OptionalLong.of(number.longValue());
	}

	/**
	 * Checks that the patched run did not silently execute far fewer tests than the baseline.
	 *
	 * @param baselineCount tests executed by the baseline run
	 * @param executedCount tests executed by this run
	 * @param minPercent minimum percentage of the baseline count that this run must execute
	 * @return the failure message with both counts, or empty if the count is acceptable
	 */
	public static Optional<String> checkExecutedCount(long baselineCount, long executedCount, double minPercent)
	{
		if (executedCount * 100.0 >= baselineCount * minPercent) return Optional.empty();
		return Optional.of("Executed " + executedCount + " test(s), fewer than " + minPercent +
			"% of the baseline's " + baselineCount + " executed test(s).");
	}

	/**
	 * Writes the exact external schema using the project's canonical artifact JSON formatter.
	 *
	 * @param scope configured JSON formatter scope
	 * @param path destination file
	 * @param commit upstream commit ID
	 * @param platform platform key
	 * @param runner report runner key
	 * @param report collected test outcomes
	 * @throws IOException if the destination cannot be written
	 */
	public static void writeBaseline(ToolingScope scope, Path path, String commit, String platform, String runner,
		TestReports.Report report) throws IOException
	{
		Map<String, Object> data = new LinkedHashMap<>();
			data.put("upstream_commit", commit);
			data.put("platform", platform);
			data.put("runner", runner);
			data.put("tests_executed", report.executed().size());
			data.put("failed", report.failed().stream().sorted().toList());
		data.put("timed_out", report.timedOut().stream().sorted().toList());
		if (path.getParent() != null) Files.createDirectories(path.getParent());
		Files.writeString(path, ArtifactJson.format(scope, data));
	}

	/**
	 * Runs the command protocol; the optional summary path is supplied by the composition boundary.
	 *
	 * @param scope configured JSON mapper and formatter scope
	 * @param arguments command arguments
	 * @param summary optional workflow summary file
	 * @param out standard output stream
	 * @param err standard error stream
	 * @return command status
	 * @throws IOException if report, baseline, or summary IO fails
	 */
	public static int run(ToolingScope scope, String[] arguments, Path summary, PrintStream out, PrintStream err)
		throws IOException
	{
		String platform = null;
		String commit = null;
		String runner = "nextest";
		String baselineDirectory = DEFAULT_BASELINE_DIRECTORY;
		String output = null;
		String writeResults = null;
		String mergeResults = null;
		String baselinePrefix = null;
		double minExecutedPercent = DEFAULT_MIN_EXECUTED_PERCENT;
		List<String> relatedPlatforms = new ArrayList<>();
		List<String> paths = new ArrayList<>();
		boolean write = false;
		boolean positional = false;
		boolean seenRunner = false;
		boolean seenBaselineDirectory = false;
		for (int index = 0; index < arguments.length; ++index)
		{
			String argument = arguments[index];
			if (!positional && argument.equals("--"))
			{
				positional = true;
				continue;
			}
			if (!positional && argument.startsWith("--"))
			{
				if (argument.equals("--write-baseline"))
				{
					write = true;
					continue;
				}
				if (index + 1 == arguments.length) return usage(err);
				++index;
				String value = arguments[index];
				switch (argument)
				{
					case "--platform" -> platform = value;
					case "--related-platform" -> relatedPlatforms.add(value);
					case "--upstream-commit" -> commit = value;
					case "--runner" ->
					{
						if (seenRunner || !(value.equals("nextest") || value.equals("bazel"))) return usage(err);
						seenRunner = true;
						runner = value;
					}
					case "--baseline-dir" ->
					{
						if (seenBaselineDirectory) return usage(err);
						seenBaselineDirectory = true;
						baselineDirectory = value;
					}
					case "--output" -> output = value;
					case "--write-results" -> writeResults = value;
					case "--merge-results" -> mergeResults = value;
					case "--baseline-prefix" -> baselinePrefix = value;
					case "--min-executed-percent" ->
					{
						try
						{
							minExecutedPercent = Double.parseDouble(value);
						}
						catch (NumberFormatException _)
						{
							return usage(err);
						}
						if (!(minExecutedPercent >= 0.0 && minExecutedPercent <= 100.0)) return usage(err);
					}
					default ->
					{
						return usage(err);
					}
				}
			}
			else paths.add(argument);
		}
		TestReports.Runner reportRunner;
		if (runner.equals("bazel"))
			reportRunner = TestReports.Runner.BAZEL;
		else
			reportRunner = TestReports.Runner.NEXTEST;
		if (writeResults != null)
		{
			if (paths.isEmpty() || write || mergeResults != null) return usage(err);
			TestReports.Report collected = TestReports.collect(paths.stream().map(Path::of).toList(), reportRunner);
			if (collected.files().isEmpty() || collected.executed().isEmpty())
			{
				err.println("No test results were found; the tests must keep their reports.");
				return 1;
			}
			writeResults(scope, Path.of(writeResults), collected);
			out.println("Wrote " + writeResults + ": " + collected.executed().size() + " executed.");
			return 0;
		}
		boolean merge = mergeResults != null;
		if (merge)
		{
			if (commit == null || baselinePrefix == null || !paths.isEmpty() || write || output != null ||
				!relatedPlatforms.isEmpty()) return usage(err);
		}
		else if (platform == null || commit == null || paths.isEmpty() || (write && !relatedPlatforms.isEmpty()) ||
			(!write && output != null) || baselinePrefix != null) return usage(err);
		TestReports.Report report;
		if (merge)
			report = mergeResults(scope, Path.of(mergeResults));
		else
			report = TestReports.collect(paths.stream().map(Path::of).toList(), reportRunner);
		if (report.files().isEmpty() || report.executed().isEmpty())
		{
			err.println("No test results were found to compare; the tests must keep their reports.");
			return 1;
		}
		if (write)
		{
			Path target;
			if (output == null)
				target = baselinePath(Path.of(baselineDirectory), commit, platform);
			else
				target = Path.of(output);
			writeBaseline(scope, target, commit, platform, runner, report);
			out.println("Wrote " + target + ": " + report.executed().size() + " executed, " + report.failed().size() +
				" failed, " + report.timedOut().size() + " timed out.");
			return 0;
		}
		Path base = Path.of(baselineDirectory);
		List<Path> primaries = new ArrayList<>();
		List<Path> related = new ArrayList<>();
		if (merge)
		{
			primaries.addAll(baselineFiles(base.resolve(commit), baselinePrefix));
			if (primaries.isEmpty())
			{
				err.println("No baseline matching " + base.resolve(commit).resolve(baselinePrefix + "*.json") +
					"; record them from an unpatched run with --write-baseline.");
				return 2;
			}
		}
		else
		{
			primaries.add(baselinePath(base, commit, platform));
			for (String value : relatedPlatforms)
				related.add(baselinePath(base, commit, value));
		}
		for (Path path : concat(primaries.getFirst(), related))
			if (!Files.isRegularFile(path))
			{
				err.println("No baseline at " + path + "; record one from an unpatched run with --write-baseline.");
				return 2;
			}
		Set<String> relatedFailures = new LinkedHashSet<>();
		for (Path path : related)
			relatedFailures.addAll(readFailures(scope, path));
		Set<String> baselineFailures = new LinkedHashSet<>();
		long countTotal = 0;
		boolean countKnown = true;
		for (Path path : primaries)
		{
			baselineFailures.addAll(readFailures(scope, path));
			OptionalLong count = readExecutedCount(scope, path);
			if (count.isPresent())
				countTotal += count.getAsLong();
			else
				countKnown = false;
		}
		Sections sections = compare(baselineFailures, report, relatedFailures);
		List<String> lines = new ArrayList<>(render(sections, report));
		int status = 0;
		if (!sections.newFailures().isEmpty())
			status = 1;
		OptionalLong baselineCount = OptionalLong.empty();
		if (countKnown)
			baselineCount = OptionalLong.of(countTotal);
		Optional<String> shortfall = Optional.empty();
		if (baselineCount.isPresent())
		{
			lines.add("Executed tests: " + report.executed().size() + " in this run, " + baselineCount.getAsLong() +
				" in the baseline.");
			shortfall = checkExecutedCount(baselineCount.getAsLong(), report.executed().size(), minExecutedPercent);
			shortfall.ifPresent(lines::add);
			if (shortfall.isPresent())
				status = 1;
		}
		lines.forEach(out::println);
		if (summary != null)
			appendSummary(summary, "Test baseline comparison", lines);
		shortfall.ifPresent(err::println);
		return status;
	}

	/**
	 * Lists the baseline files of one commit whose names start with a prefix.
	 *
	 * @param directory the commit's baseline directory
	 * @param prefix the file name prefix
	 * @return the matching {@code .json} files in name order
	 * @throws IOException if the directory cannot be listed
	 */
	private static List<Path> baselineFiles(Path directory, String prefix) throws IOException
	{
		if (!Files.isDirectory(directory)) return List.of();
		try (java.util.stream.Stream<Path> children = Files.list(directory))
		{
			return children.filter(path -> path.getFileName().toString().startsWith(prefix) &&
				path.getFileName().toString().endsWith(".json")).sorted().toList();
		}
	}

	/**
	 * Writes one shard's collected results so that a later job can merge them.
	 *
	 * @param scope configured JSON formatter scope
	 * @param path destination file
	 * @param report the shard's collected outcomes
	 * @throws IOException if the file cannot be written
	 */
	public static void writeResults(ToolingScope scope, Path path, TestReports.Report report) throws IOException
	{
		Map<String, Object> data = new LinkedHashMap<>();
		data.put("executed", report.executed().stream().sorted().toList());
		data.put("failed", report.failed().stream().sorted().toList());
		data.put("timed_out", report.timedOut().stream().sorted().toList());
		data.put("passed_cases", report.passedCases().stream().sorted().toList());
		if (path.getParent() != null) Files.createDirectories(path.getParent());
		Files.writeString(path, ArtifactJson.format(scope, data));
	}

	/**
	 * Merges every {@code results.json} below a directory into one report.
	 *
	 * @param scope configured JSON mapper scope
	 * @param directory directory holding the shards' result files, at any depth
	 * @return the union of the shards' outcomes; its files are the merged result files
	 * @throws IOException if a result file is malformed or unreadable
	 */
	public static TestReports.Report mergeResults(ToolingScope scope, Path directory) throws IOException
	{
		List<Path> files;
		if (!Files.isDirectory(directory))
			files = List.of();
		else
			try (java.util.stream.Stream<Path> walk = Files.walk(directory))
			{
				files = walk.filter(path -> path.getFileName().toString().equals("results.json")).sorted().toList();
			}
		Set<String> executed = new LinkedHashSet<>();
		Set<String> failed = new LinkedHashSet<>();
		Set<String> timedOut = new LinkedHashSet<>();
		Set<String> passed = new LinkedHashSet<>();
		for (Path file : files)
		{
			Object raw = scope.getJsonMapper().readValue(Files.readString(file), Object.class);
			if (!(raw instanceof Map<?, ?> document))
				throw new IOException("Invalid results at " + file);
			readStrings(document, "executed", file, executed);
			readStrings(document, "failed", file, failed);
			readStrings(document, "timed_out", file, timedOut);
			readStrings(document, "passed_cases", file, passed);
		}
		return new TestReports.Report(files, executed, failed, timedOut, List.of(), passed);
	}

	/**
	 * Adds a string array of a results document to a destination.
	 *
	 * @param document the results document
	 * @param key the array's name
	 * @param file the document's path, for diagnostics
	 * @param destination receives the strings
	 * @throws IOException if the field is not an array of strings
	 */
	private static void readStrings(Map<?, ?> document, String key, Path file, Set<String> destination)
		throws IOException
	{
		Object values = document.get(key);
		if (values == null) return;
		if (!(values instanceof List<?> list) || list.stream().anyMatch(value -> !(value instanceof String)))
			throw new IOException("Invalid results field " + key + " at " + file);
		list.forEach(value -> destination.add((String) value));
	}

	/**
	 * Resolves a platform baseline path below its upstream commit directory.
	 *
	 * @param directory baseline directory
	 * @param commit upstream commit ID
	 * @param platform platform key
	 * @return platform baseline path
	 */
	private static Path baselinePath(Path directory, String commit, String platform)
	{
		return directory.resolve(commit).resolve(platform + ".json");
	}

	/**
	 * Prepends the primary baseline to the related baseline paths.
	 *
	 * @param first primary baseline
	 * @param rest related baselines
	 * @return combined baseline paths
	 */
	private static List<Path> concat(Path first, List<Path> rest)
	{
		List<Path> values = new ArrayList<>();
		values.add(first);
		values.addAll(rest);
		return values;
	}

	/**
	 * Appends a fenced command section to the explicit workflow summary file.
	 *
	 * @param summary destination workflow summary file
	 * @param heading section heading
	 * @param lines section contents
	 * @throws IOException if the summary file cannot be appended
	 */
	private static void appendSummary(Path summary, String heading, List<String> lines) throws IOException
	{
		Files.writeString(summary, "### " + heading + "\n\n```\n" + String.join("\n", lines) + "\n```\n",
			java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
	}

	/**
	 * Prints usage and returns the syntax-error status.
	 *
	 * @param err diagnostic output stream
	 * @return syntax-error status
	 */
	private static int usage(PrintStream err)
	{
		err.println("Usage: compare-test-baseline --platform P [--related-platform R]... " +
			"--upstream-commit C [--runner nextest|bazel] [--baseline-dir D] [--min-executed-percent P] [--write-results FILE | --merge-results DIR --baseline-prefix P] " +
			"[--write-baseline [--output FILE]] PATH...");
		return 2;
	}
}
