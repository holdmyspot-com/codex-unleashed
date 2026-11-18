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
			if (executed.contains(key) && !failing.containsKey(key)) fixed.add(value);
		});
		recorded.keySet().forEach(key ->
		{
			if (failing.containsKey(key)) unchanged.add(failing.get(key));
		});
		own.forEach((key, value) ->
		{
			if (!executed.contains(key)) notRun.add(value);
		});
		Comparator<String> order = Comparator.naturalOrder();
		newItems.sort(order); fixed.sort(order); unchanged.sort(order); notRun.sort(order);
		return new Sections(newItems, fixed, unchanged, notRun);
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
					default ->
					{
						return usage(err);
					}
				}
			}
			else paths.add(argument);
		}
		if (platform == null || commit == null || paths.isEmpty() || (write && !relatedPlatforms.isEmpty()) ||
			(!write && output != null)) return usage(err);
		TestReports.Runner reportRunner;
		if (runner.equals("bazel"))
			reportRunner = TestReports.Runner.BAZEL;
		else
			reportRunner = TestReports.Runner.NEXTEST;
		TestReports.Report report = TestReports.collect(paths.stream().map(Path::of).toList(), reportRunner);
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
		Path primary = baselinePath(base, commit, platform);
		List<Path> related = new ArrayList<>();
		for (String value : relatedPlatforms)
			related.add(baselinePath(base, commit, value));
		for (Path path : concat(primary, related))
			if (!Files.isRegularFile(path))
			{
				err.println("No baseline at " + path + "; record one from an unpatched run with --write-baseline.");
				return 2;
			}
		Set<String> relatedFailures = new LinkedHashSet<>();
		for (Path path : related)
			relatedFailures.addAll(readFailures(scope, path));
		Sections sections = compare(readFailures(scope, primary), report, relatedFailures);
		List<String> lines = render(sections, report);
		lines.forEach(out::println);
		if (summary != null)
			appendSummary(summary, "Test baseline comparison", lines);
		if (sections.newFailures().isEmpty())
			return 0;
		return 1;
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
			"--upstream-commit C [--runner nextest|bazel] [--baseline-dir D] " +
			"[--write-baseline [--output FILE]] PATH...");
		return 2;
	}
}
