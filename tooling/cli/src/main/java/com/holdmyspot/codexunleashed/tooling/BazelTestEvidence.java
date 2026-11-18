package com.holdmyspot.codexunleashed.tooling;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/** Validates invocation-bound Bazel test coverage evidence. */
public final class BazelTestEvidence
{
	/** Command name. */
	public static final String NAME = "validate-bazel-test-evidence";
	/** Exact supported nondefault tag filter. */
	private static final String TAG_FILTER = "-argument-comment-lint";
	/** Usage text and status meanings. */
	private static final String HELP = "Usage: codex-tooling " + NAME +
		" --bep PATH --expected-patterns PATH --bazel-exit-code INTEGER [--interrupted]\n" +
		"Status: 0 complete coverage (including completed test failures); 1 rejected evidence; 2 invalid arguments.\n";

	/** Prevents construction. */
	private BazelTestEvidence()
	{
	}

	/**
	 * Validates one Bazel invocation's BEP file against its exact target patterns and original exit status.
	 *
	 * @param scope shared mapper owner
	 * @param arguments command arguments
	 * @param out result output
	 * @param err diagnostics
	 * @return 0 for complete evidence, 1 for rejected evidence or 2 for invalid arguments
	 * @throws NullPointerException if an argument is null
	 */
	public static int run(ToolingScope scope, String[] arguments, PrintStream out, PrintStream err)
	{
		Objects.requireNonNull(scope, "scope");
		Objects.requireNonNull(arguments, "arguments");
		Objects.requireNonNull(out, "out");
		Objects.requireNonNull(err, "err");
		Arguments parsed;
		try
		{
			parsed = parseArguments(arguments);
		}
		catch (IllegalArgumentException _)
		{
			err.print(HELP);
			return 2;
		}
		if (parsed.help)
		{
			out.print(HELP);
			return 0;
		}
		if (parsed.invalid)
		{
			err.print(HELP);
			return 2;
		}
		Report report = new Report(parsed.exitCode);
		if (parsed.interrupted)
			report.reject("interrupted_execution", null, null);
		else
		{
			try
			{
				List<String> patterns = readPatterns(parsed.patterns);
				if (patterns.isEmpty())
					report.reject("selection_mismatch", null, null);
				else
					validate(scope, parsed, patterns, report);
			}
			catch (IOException | RuntimeException _)
			{
				if (report.failureCode == null)
					report.reject("input_error", null, null);
			}
		}
		writeResult(scope, report, out);
		if (report.failureCode != null)
		{
			err.print("Rejected Bazel test evidence: " + report.failureCode);
			if (report.eventKind != null)
				err.print(" at " + report.eventKind);
			if (report.line > 0)
				err.print(" line " + report.line);
			err.println();
			return 1;
		}
		return 0;
	}

	/**
	 * Parses the exact supported CLI grammar.
	 *
	 * @param arguments command-line arguments
	 * @return parsed arguments or invalid/help state
	 */
	private static Arguments parseArguments(String[] arguments)
	{
		Arguments parsed = new Arguments();
		if (arguments.length == 1 && arguments[0].equals("--help"))
		{
			parsed.help = true;
			return parsed;
		}
		if (List.of(arguments).contains("--help"))
		{
			parsed.invalid = true;
			return parsed;
		}
		for (int index = 0; index < arguments.length; index += 1)
		{
			String flag = arguments[index];
			if (flag.equals("--interrupted"))
			{
				if (parsed.interrupted)
					parsed.invalid = true;
				parsed.interrupted = true;
				continue;
			}
			if (!Set.of("--bep", "--expected-patterns", "--bazel-exit-code").contains(flag) ||
				index + 1 >= arguments.length || isOption(arguments[index + 1]))
			{
				parsed.invalid = true;
				continue;
			}
			index += 1;
			String value = arguments[index];
			switch (flag)
			{
				case "--bep" ->
				{
					if (parsed.bep != null)
						parsed.invalid = true;
					parsed.bep = Path.of(value);
				}
				case "--expected-patterns" ->
				{
					if (parsed.patterns != null)
						parsed.invalid = true;
					parsed.patterns = Path.of(value);
				}
				case "--bazel-exit-code" ->
				{
					if (parsed.exitCode != null)
						parsed.invalid = true;
					try
					{
						int code = Integer.parseInt(value);
						if (code < 0 || code > 255)
							parsed.invalid = true;
						parsed.exitCode = code;
					}
					catch (NumberFormatException _)
					{
						parsed.invalid = true;
					}
				}
				default -> throw new AssertionError(flag);
			}
		}
		parsed.invalid |= parsed.bep == null || parsed.patterns == null || parsed.exitCode == null;
		return parsed;
	}

	/**
	 * Checks whether a token is a recognized option rather than a value.
	 *
	 * @param token token following a value flag
	 * @return whether the token begins another recognized option
	 */
	private static boolean isOption(String token)
	{
		return Set.of("--bep", "--expected-patterns", "--bazel-exit-code", "--interrupted", "--help").contains(token);
	}

	/**
	 * Reads exact ordered target pattern lines.
	 *
	 * @param path expected pattern file
	 * @return exact pattern strings
	 * @throws IOException if the input cannot be read
	 */
	private static List<String> readPatterns(Path path) throws IOException
	{
		String contents = Files.readString(path, StandardCharsets.UTF_8);
		String normalized = contents.replace("\r\n", "\n");
		if (normalized.endsWith("\n"))
			normalized = normalized.substring(0, normalized.length() - 1);
		if (normalized.isEmpty())
			return List.of();
		List<String> result = new ArrayList<>(List.of(normalized.split("\n", -1)));
		if (result.stream().anyMatch(String::isEmpty))
			throw new IllegalArgumentException("empty pattern entry");
		return result;
	}

	/**
	 * Validates the parsed invocation and fills its result report.
	 *
	 * @param scope invocation-owned borrowed mappers
	 * @param arguments parsed invocation arguments
	 * @param expected expected target patterns
	 * @param report mutable result values
	 * @throws IOException if serialization of an event identity fails
	 */
	private static void validate(ToolingScope scope, Arguments arguments, List<String> expected, Report report)
		throws IOException
	{
		List<JsonNode> events = new ArrayList<>();
		String input = Files.readString(arguments.bep, StandardCharsets.UTF_8);
		String[] lines = input.split("\n", -1);
		for (int index = 0; index < lines.length; index += 1)
		{
			String line = lines[index];
			if (line.endsWith("\r"))
				line = line.substring(0, line.length() - 1);
			if (line.isEmpty() && index == lines.length - 1)
				continue;
			JsonNode event;
			try
			{
				event = scope.getJsonMapper().readTree(line);
			}
			catch (RuntimeException _)
			{
				report.reject("invalid_bep", "json", index + 1);
				return;
			}
			if (event == null || !event.isObject() || !event.path("id").isObject())
			{
				report.reject("invalid_bep", "event", index + 1);
				return;
			}
			events.add(event);
		}
		Map<String, JsonNode> byId = new HashMap<>();
		Map<String, Integer> lineById = new HashMap<>();
		for (int index = 0; index < events.size(); index += 1)
		{
			JsonNode event = events.get(index);
			String id = scope.getArtifactJsonMapper().writeValueAsString(event.path("id"));
			if (byId.putIfAbsent(id, event) != null)
			{
				report.reject("invalid_bep", "duplicate_id", index + 1);
				return;
			}
			lineById.put(id, index + 1);
		}
		List<JsonNode> roots = events.stream().filter(event -> event.path("id").has("started")).toList();
		List<JsonNode> finished = events.stream().filter(event -> event.path("id").has("buildFinished")).toList();
		if (roots.size() != 1 || finished.size() != 1)
		{
			report.reject("incomplete_graph", "root_or_terminal", null);
			return;
		}
		JsonNode root = roots.getFirst();
		JsonNode started = root.path("started");
		report.uuid = text(started, "uuid");
		if (!isUuid(report.uuid))
		{
			String rootId = scope.getArtifactJsonMapper().writeValueAsString(root.path("id"));
			Integer rootLine = lineById.get(rootId);
			report.reject("invalid_bep", "started", rootLine);
			return;
		}
		if (!patternsMatch(events, expected))
		{
			report.reject("selection_mismatch", "structuredCommandLine", null);
			return;
		}
		JsonNode canonical = events.stream().filter(event -> text(event.path("id").path("structuredCommandLine"),
			"commandLineLabel").equals("canonical")).findFirst().orElse(null);
		if (canonical == null || !supportedOptions(canonical))
		{
			report.reject("unsupported_selection", "structuredCommandLine", null);
			return;
		}
		for (JsonNode event : events)
		{
			if (!event.path("id").has("targetConfigured"))
				continue;
			JsonNode configured = event.path("configured");
			boolean testRule = text(configured, "targetKind").contains("_test rule");
			boolean hasTestSize = Set.of("SMALL", "MEDIUM", "LARGE", "ENORMOUS").contains(
				text(configured, "testSize"));
			if (testRule != hasTestSize)
			{
				report.reject("invalid_bep", "targetConfigured", null);
				return;
			}
		}
		Set<JsonNode> configuredTestSet = new HashSet<>();
		for (JsonNode pattern : events.stream().filter(event -> event.path("id").has("pattern")).toList())
			for (JsonNode child : childEvents(scope, pattern, byId))
				if (child.path("id").has("targetConfigured") && isTest(child.path("configured")))
					configuredTestSet.add(child);
		List<JsonNode> configuredTests = new ArrayList<>(configuredTestSet);
		Set<String> uniqueTests = new HashSet<>();
		Set<String> completedTests = new HashSet<>();
		int filtered = 0;
		int nonpassing = 0;
		for (JsonNode configured : configuredTests)
		{
			JsonNode identity = configured.path("id").path("targetConfigured");
			String label = text(identity, "label");
			boolean tagged = hasExactTag(configured.path("configured"), "argument-comment-lint");
			List<JsonNode> completions = childEvents(scope, configured, byId).stream().filter(child ->
				child.path("id").has("targetCompleted")).toList();
			if (completions.isEmpty())
			{
				if (tagged)
					filtered += 1;
				else
				{
					report.reject("incomplete_graph", "targetConfigured", null);
					return;
				}
				continue;
			}
			for (JsonNode completion : completions)
			{
				String config = text(completion.path("id").path("targetCompleted").path("configuration"), "id");
				String pair = label + "\u0000" + config;
				if (tagged)
				{
					filtered += 1;
					continue;
				}
				if (!uniqueTests.add(pair))
					continue;
				report.expected += 1;
				List<JsonNode> children = childEvents(scope, completion, byId);
				List<JsonNode> results = children.stream().filter(child -> child.path("id").has("testResult")).toList();
				List<JsonNode> summaries = children.stream().filter(child -> child.path("id").has("testSummary")).toList();
				if (results.isEmpty())
				{
					report.reject("incomplete_graph", "targetCompleted", null);
					return;
				}
				if (summaries.size() != 1)
					continue;
				JsonNode summary = summaries.getFirst();
				if (!hasCompleteExecution(scope, completion, label, config, byId, summary))
					continue;
				completedTests.add(pair);
				String status = text(summary.path("testSummary"), "overallStatus");
				if (status.equals("FAILED") || status.equals("FLAKY"))
					nonpassing += 1;
			}
		}
		report.filtered = filtered;
		report.completed = completedTests.size();
		report.nonpassing = nonpassing;
		if (report.expected == 0)
		{
			report.reject("unsupported_selection", "test_selection", null);
			return;
		}
		if (!isClosed(scope, events, byId, root))
		{
			report.reject("incomplete_graph", "children", null);
			return;
		}
		if (report.completed != report.expected)
		{
			report.reject("incomplete_execution", "testResult_or_testSummary", null);
			return;
		}
		int actualExit = finished.getFirst().path("finished").path("exitCode").path("code").asInt(-1);
		if (actualExit != arguments.exitCode)
		{
			report.reject("command_failed", "buildFinished", null);
			return;
		}
		if (actualExit != 0 && actualExit != 3)
			report.reject("command_failed", "buildFinished", null);
	}

	/**
	 * Binds canonical residual target arguments to the expected-pattern file.
	 *
	 * @param events invocation events
	 * @param expected ordered expected patterns
	 * @return whether both sequences match exactly
	 */
	private static boolean patternsMatch(List<JsonNode> events, List<String> expected)
	{
		List<JsonNode> canonical = events.stream().filter(event -> event.path("id").has("structuredCommandLine") &&
			text(event.path("id").path("structuredCommandLine"), "commandLineLabel").equals("canonical")).toList();
		if (canonical.size() != 1)
			return false;
		for (JsonNode section : canonical.getFirst().path("structuredCommandLine").path("sections"))
		{
			if (text(section, "sectionLabel").equals("residual"))
			{
				List<String> actual = new ArrayList<>();
				for (JsonNode chunk : section.path("chunkList").path("chunk"))
					actual.add(chunk.asString());
				return actual.equals(expected);
			}
		}
		return false;
	}

	/**
	 * Checks the bounded Bazel selection profile.
	 *
	 * @param event canonical structured command event
	 * @return whether all effective selection options are supported
	 */
	private static boolean supportedOptions(JsonNode event)
	{
		JsonNode sections = event.path("structuredCommandLine").path("sections");
		boolean test = false;
		Map<String, String> options = new HashMap<>();
		boolean duplicateOption = false;
		for (JsonNode section : sections)
		{
			String label = text(section, "sectionLabel");
			if (label.equals("command"))
			{
				JsonNode chunks = section.path("chunkList").path("chunk");
				test = chunks.size() == 1 && chunks.get(0).asString().equals("test");
			}
			if (label.equals("command options"))
				for (JsonNode option : section.path("optionList").path("option"))
					if (options.putIfAbsent(text(option, "optionName"), text(option, "optionValue")) != null)
						duplicateOption = true;
		}
		return test && !duplicateOption && TAG_FILTER.equals(options.get("test_tag_filters")) &&
			options.getOrDefault("test_size_filters", "").isEmpty() &&
			options.getOrDefault("test_timeout_filters", "").isEmpty() &&
			options.getOrDefault("test_lang_filters", "").isEmpty() &&
			options.getOrDefault("build_tag_filters", "").isEmpty() &&
			options.getOrDefault("build_manual_tests", "false").equals("false") &&
			options.getOrDefault("build_tests_only", "false").equals("false") &&
			options.getOrDefault("compile_one_dependency", "false").equals("false") &&
			options.getOrDefault("expand_test_suites", "true").equals("true") &&
			!options.containsKey("test_filter") && !options.containsKey("target_pattern_file");
	}

	/**
	 * Identifies a configured test using kind and size together.
	 *
	 * @param configured configured target payload
	 * @return whether it is a recognized test target
	 */
	private static boolean isTest(JsonNode configured)
	{
		String kind = text(configured, "targetKind");
		String size = text(configured, "testSize");
		return kind.contains("_test rule") && Set.of("SMALL", "MEDIUM", "LARGE", "ENORMOUS").contains(size);
	}

	/**
	 * Checks exact configured tag membership.
	 *
	 * @param configured configured target payload
	 * @param tag exact tag value
	 * @return whether the exact tag is present
	 */
	private static boolean hasExactTag(JsonNode configured, String tag)
	{
		for (JsonNode candidate : configured.path("tag"))
			if (candidate.asString().equals(tag))
				return true;
		return false;
	}

	/**
	 * Validates every announced attempt and its aggregate summary for one selected test configuration.
	 *
	 * @param scope mapper owner for stable child identity serialization
	 * @param completion target completion event
	 * @param label selected test label
	 * @param configuration selected configuration ID
	 * @param byId invocation event lookup
	 * @param summary announced test summary
	 * @return whether all attempts, identities, and aggregate counts agree
	 * @throws IOException if child identity serialization fails
	 */
	private static boolean hasCompleteExecution(ToolingScope scope, JsonNode completion, String label,
		String configuration, Map<String, JsonNode> byId, JsonNode summary) throws IOException
	{
		JsonNode summaryId = summary.path("id").path("testSummary");
		if (!label.equals(text(summaryId, "label")) ||
			!configuration.equals(text(summaryId.path("configuration"), "id")))
			return false;
		List<JsonNode> initialResults = childEvents(scope, completion, byId).stream().
			filter(child -> child.path("id").has("testResult")).toList();
		if (initialResults.isEmpty())
			return false;
		Map<String, JsonNode> attempts = new HashMap<>();
		for (JsonNode initialResult : initialResults)
			if (!collectAttemptChain(scope, initialResult, label, configuration, 1, byId, attempts))
				return false;
		List<JsonNode> results = new ArrayList<>(attempts.values());
		if (results.isEmpty() || results.stream().anyMatch(result ->
			!Set.of("PASSED", "FAILED").contains(text(result.path("testResult"), "status"))))
			return false;
		return summaryCountsMatch(results, summary.path("testSummary"));
	}

	/**
	 * Resolves a test-result attempt and recursively follows only its announced next-attempt edge.
	 *
	 * @param scope mapper owner for stable child identity serialization
	 * @param event current test-result event
	 * @param label selected test label
	 * @param configuration selected configuration ID
	 * @param expectedAttempt required attempt number
	 * @param byId invocation event lookup
	 * @param attempts collected result events indexed by run, shard, and attempt
	 * @return whether this attempt and every announced retry resolve with matching identity
	 * @throws IOException if child identity serialization fails
	 */
	private static boolean collectAttemptChain(ToolingScope scope, JsonNode event, String label, String configuration,
		int expectedAttempt, Map<String, JsonNode> byId, Map<String, JsonNode> attempts) throws IOException
	{
		JsonNode identity = event.path("id").path("testResult");
		int run = identity.path("run").asInt(-1);
		int shard = identity.path("shard").asInt(-1);
		int attempt = identity.path("attempt").asInt(-1);
		if (!label.equals(text(identity, "label")) ||
			!configuration.equals(text(identity.path("configuration"), "id")) ||
			run < 1 || shard < 1 || attempt != expectedAttempt)
			return false;
		String attemptKey = run + ":" + shard + ":" + attempt;
		if (attempts.putIfAbsent(attemptKey, event) != null)
			return false;
		List<JsonNode> children = new ArrayList<>();
		for (JsonNode child : event.path("children"))
		{
			if (!child.has("testResult"))
				return false;
			JsonNode retry = byId.get(scope.getArtifactJsonMapper().writeValueAsString(child));
			if (retry == null)
				return false;
			children.add(retry);
		}
		return children.size() <= 1 && (children.isEmpty() || collectAttemptChain(scope, children.getFirst(), label,
			configuration, attempt + 1, byId, attempts));
	}

	/**
	 * Compares result identities and statuses with the summary's run, shard, attempt, and overall counts.
	 *
	 * @param results all recursively announced attempt events
	 * @param summary aggregate test-summary payload
	 * @return whether identities are unique and the aggregate counts and status agree
	 */
	private static boolean summaryCountsMatch(List<JsonNode> results, JsonNode summary)
	{
		Set<Integer> runs = new HashSet<>();
		Set<Integer> shards = new HashSet<>();
		Map<Integer, Set<Integer>> shardsByRun = new HashMap<>();
		Map<String, Set<Integer>> attemptsByRunAndShard = new HashMap<>();
		Map<Integer, Integer> highestAttemptByRun = new HashMap<>();
		boolean hasPassed = false;
		boolean hasFailed = false;
		for (JsonNode result : results)
		{
			JsonNode identity = result.path("id").path("testResult");
			int run = identity.path("run").asInt(-1);
			int shard = identity.path("shard").asInt(-1);
			int attempt = identity.path("attempt").asInt(-1);
			runs.add(run);
			shards.add(shard);
			shardsByRun.computeIfAbsent(run, _ -> new HashSet<>()).add(shard);
			String runAndShard = run + ":" + shard;
			if (!attemptsByRunAndShard.computeIfAbsent(runAndShard, _ -> new HashSet<>()).add(attempt))
				return false;
			highestAttemptByRun.merge(run, attempt, Math::max);
			String status = text(result.path("testResult"), "status");
			hasPassed |= status.equals("PASSED");
			hasFailed |= status.equals("FAILED");
		}
		if (shardsByRun.values().stream().distinct().count() != 1 ||
			runs.stream().mapToInt(Integer::intValue).max().orElse(0) != runs.size() ||
			shards.stream().mapToInt(Integer::intValue).max().orElse(0) != shards.size() ||
			attemptsByRunAndShard.values().stream().anyMatch(attempts ->
				attempts.size() != attempts.stream().mapToInt(Integer::intValue).max().orElse(0)))
			return false;
		int announcedAttemptCount = highestAttemptByRun.values().stream().mapToInt(Integer::intValue).sum();
		String expectedStatus = "PASSED";
		if (hasFailed)
		{
			expectedStatus = "FAILED";
			if (hasPassed)
				expectedStatus = "FLAKY";
		}
		return summary.path("runCount").asInt(-1) == runs.size() &&
			summary.path("shardCount").asInt(-1) == shards.size() &&
			summary.path("attemptCount").asInt(-1) == announcedAttemptCount &&
			summary.path("totalRunCount").asInt(-1) == results.size() &&
			expectedStatus.equals(text(summary, "overallStatus"));
	}

	/**
	 * Resolves an event's announced child IDs.
	 *
	 * @param scope mapper owner for stable identity serialization
	 * @param event parent event
	 * @param byId event lookup
	 * @return resolved child events
	 * @throws IOException if identity serialization fails
	 */
	private static List<JsonNode> childEvents(ToolingScope scope, JsonNode event, Map<String, JsonNode> byId)
		throws IOException
	{
		List<JsonNode> result = new ArrayList<>();
		for (JsonNode child : event.path("children"))
		{
			String key = scope.getArtifactJsonMapper().writeValueAsString(child);
			JsonNode found = byId.get(key);
			if (found != null)
				result.add(found);
		}
		return result;
	}

	/**
	 * Requires every event to be reachable through declared child IDs.
	 *
	 * @param scope mapper owner for stable identity serialization
	 * @param events all parsed events
	 * @param byId event lookup
	 * @param root started event
	 * @return whether graph traversal resolves and visits every event
	 * @throws IOException if identity serialization fails
	 */
	private static boolean isClosed(ToolingScope scope, List<JsonNode> events, Map<String, JsonNode> byId,
		JsonNode root) throws IOException
	{
		Set<JsonNode> visited = new HashSet<>();
		List<JsonNode> pending = new ArrayList<>();
		pending.add(root);
		while (!pending.isEmpty())
		{
			JsonNode current = pending.removeLast();
			if (!visited.add(current))
				continue;
			for (JsonNode child : current.path("children"))
			{
				JsonNode target = byId.get(scope.getArtifactJsonMapper().writeValueAsString(child));
				if (target == null)
					return false;
				pending.add(target);
			}
		}
		return visited.size() == events.size();
	}

	/**
	 * Returns a string-valued field or an empty string when absent.
	 *
	 * @param node source object
	 * @param key field name
	 * @return string value or empty string
	 */
	private static String text(JsonNode node, String key)
	{
		JsonNode value = node.path(key);
		if (value.isMissingNode() || value.isNull())
			return "";
		return value.asString();
	}

	/**
	 * Checks canonical UUID text shape.
	 *
	 * @param value candidate text
	 * @return whether the value has the UUID shape
	 */
	private static boolean isUuid(String value)
	{
		return value != null && value.matches(
			"[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");
	}

	/**
	 * Emits the compact result object in the required key order.
	 *
	 * @param scope invocation-owned artifact mapper
	 * @param report result values
	 * @param out result stream
	 */
	private static void writeResult(ToolingScope scope, Report report, PrintStream out)
	{
		ObjectNode result = scope.getArtifactJsonMapper().createObjectNode();
		result.put("format_version", 1);
		if (report.failureCode == null)
			result.put("coverage", "complete");
		else
			result.put("coverage", "rejected");
		if (report.uuid == null)
			result.putNull("invocation_uuid");
		else
			result.put("invocation_uuid", report.uuid);
		result.put("bazel_exit_code", report.exitCode);
		result.put("expected_test_count", report.expected);
		result.put("filtered_test_count", report.filtered);
		result.put("completed_test_count", report.completed);
		result.put("nonpassing_test_count", report.nonpassing);
		if (report.failureCode == null)
			result.putNull("failure_code");
		else
			result.put("failure_code", report.failureCode);
		out.print(scope.getArtifactJsonMapper().writeValueAsString(result));
		out.print('\n');
	}

	/** Parsed command arguments and syntax state. */
	private static final class Arguments
	{
		/** BEP input path. */
		private Path bep;
		/** Expected-pattern input path. */
		private Path patterns;
		/** Original Bazel process status. */
		private Integer exitCode;
		/** Whether controller evidence states the invocation was interrupted. */
		private boolean interrupted;
		/** Whether help was requested alone. */
		private boolean help;
		/** Whether syntax is invalid. */
		private boolean invalid;

		/** Creates empty parsed-argument state. */
		private Arguments()
		{
		}
	}

	/** Ordered public evidence values and the first rejection. */
	private static final class Report
	{
		/** Supplied original process status. */
		private final int exitCode;
		/** Established invocation UUID. */
		private String uuid;
		/** Selected distinct test count. */
		private int expected;
		/** Exact filtered test count. */
		private int filtered;
		/** Tests with complete results and summaries. */
		private int completed;
		/** Completed failed or flaky tests. */
		private int nonpassing;
		/** First bounded failure code. */
		private String failureCode;
		/** Safe event-kind diagnostic. */
		private String eventKind;
		/** One-based BEP line for parsing diagnostics. */
		private int line;

		/**
		 * Creates a report with its original process status.
		 *
		 * @param exitCode original Bazel status
		 */
		private Report(int exitCode)
		{
			this.exitCode = exitCode;
		}

		/**
		 * Retains the first rejection and its safe location.
		 *
		 * @param code fixed failure code
		 * @param kind fixed event kind, if known
		 * @param inputLine one-based input line, if known
		 */
		private void reject(String code, String kind, Integer inputLine)
		{
			failureCode = code;
			eventKind = kind;
			if (inputLine == null)
				line = 0;
			else
				line = inputLine;
		}
	}
}
