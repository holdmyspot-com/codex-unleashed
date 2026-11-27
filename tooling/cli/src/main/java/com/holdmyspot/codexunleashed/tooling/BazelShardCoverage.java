package com.holdmyspot.codexunleashed.tooling;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Stream;
import tools.jackson.databind.JsonNode;

/**
 * Records Bazel targets skipped as incompatible and checks that the shards of a Bazel workflow together test exactly
 * the candidate targets.
 * <p>
 * Each shard artifact is a directory holding {@code group.txt}, {@code shard.txt}, {@code shard-count.txt},
 * {@code candidates.txt}, {@code selected.txt} and, when the run skips incompatible targets, {@code skipped.txt}.
 */
public final class BazelShardCoverage
{
	/** Command name. */
	public static final String NAME = "bazel-shard-coverage";
	/** Usage text. */
	private static final String USAGE = "Usage: codex-tooling " + NAME + " record --bep PATH --executed PATH --skipped PATH\n" +
		"       codex-tooling " + NAME + " check --directory PATH --expect GROUP=SHARD_COUNT... [--allowlist PATH]\n";

	/** Prevents construction. */
	private BazelShardCoverage()
	{
	}

	/**
	 * Runs the command.
	 *
	 * @param scope shared mapper owner
	 * @param arguments the operation followed by its options
	 * @param out result output
	 * @param err diagnostics
	 * @return 0 on success, 1 when coverage or skips are rejected, 2 for invalid arguments
	 * @throws IOException if a file cannot be read or written
	 */
	public static int run(ToolingScope scope, String[] arguments, PrintStream out, PrintStream err) throws IOException
	{
		Objects.requireNonNull(scope, "scope");
		Objects.requireNonNull(arguments, "arguments");
		if (arguments.length == 0)
		{
			err.print(USAGE);
			return 2;
		}
		Map<String, List<String>> options = new LinkedHashMap<>();
		for (int index = 1; index < arguments.length; index += 2)
		{
			if (!arguments[index].startsWith("--") || index + 1 >= arguments.length)
			{
				err.print(USAGE);
				return 2;
			}
			options.computeIfAbsent(arguments[index], _ -> new ArrayList<>()).add(arguments[index + 1]);
		}
		switch (arguments[0])
		{
			case "record" ->
			{
				if (!options.keySet().equals(Set.of("--bep", "--executed", "--skipped")) ||
					options.values().stream().anyMatch(values -> values.size() != 1))
				{
					err.print(USAGE);
					return 2;
				}
				Events events = events(scope, Path.of(options.get("--bep").getFirst()));
				Files.writeString(Path.of(options.get("--executed").getFirst()), new SortedLines(events.executed).text());
				Files.writeString(Path.of(options.get("--skipped").getFirst()), new SortedLines(events.skipped).text());
				out.println("Recorded " + events.executed.size() + " executed and " + events.skipped.size() +
					" skipped incompatible target(s).");
				return 0;
			}
			case "check" ->
			{
				if (!Set.of("--directory", "--expect", "--allowlist").containsAll(options.keySet()) ||
					!options.containsKey("--directory") || !options.containsKey("--expect") ||
					options.get("--directory").size() != 1 ||
					options.getOrDefault("--allowlist", List.of()).size() > 1)
				{
					err.print(USAGE);
					return 2;
				}
				Map<String, Integer> expected = new TreeMap<>();
				for (String value : options.get("--expect"))
				{
					int equals = value.lastIndexOf('=');
					if (equals < 1 || expected.containsKey(value.substring(0, equals)))
					{
						err.print(USAGE);
						return 2;
					}
					try
					{
						int count = Integer.parseInt(value.substring(equals + 1));
						if (count < 1) throw new NumberFormatException(value);
						expected.put(value.substring(0, equals), count);
					}
					catch (NumberFormatException _)
					{
						err.print(USAGE);
						return 2;
					}
				}
				Set<String> allowlist = new TreeSet<>();
				if (options.containsKey("--allowlist"))
					allowlist.addAll(readTargets(Path.of(options.get("--allowlist").getFirst())));
				List<String> failures = check(Path.of(options.get("--directory").getFirst()), expected, allowlist, out);
				failures.forEach(err::println);
				return failures.isEmpty() ? 0 : 1;
			}
			default ->
			{
				err.print(USAGE);
				return 2;
			}
		}
	}

	/** A sorted, duplicate-free set of lines. */
	private static final class SortedLines
	{
		/** The lines. */
		private final TreeSet<String> values;

		/**
		 * Creates the set.
		 *
		 * @param values the lines
		 */
		private SortedLines(Set<String> values)
		{
			this.values = new TreeSet<>(values);
		}

		/**
		 * Renders the lines, one per line.
		 *
		 * @return the file text
		 */
		private String text()
		{
			if (values.isEmpty()) return "";
			return String.join("\n", values) + "\n";
		}
	}

	/**
	 * The test targets that a Bazel invocation executed and skipped.
	 *
	 * @param executed targets with a test summary
	 * @param skipped targets skipped as incompatible
	 */
	public record Events(Set<String> executed, Set<String> skipped)
	{
	}

	/**
	 * Finds the targets Bazel executed and the targets it skipped as incompatible in a build event protocol file.
	 *
	 * @param scope shared mapper owner
	 * @param bep newline-delimited JSON build events
	 * @return the executed and skipped target labels
	 * @throws IOException if the file cannot be read or contains malformed JSON
	 */
	public static Events events(ToolingScope scope, Path bep) throws IOException
	{
		Set<String> executed = new TreeSet<>();
		Set<String> skipped = new TreeSet<>();
		try (Stream<String> lines = Files.lines(bep, StandardCharsets.UTF_8))
		{
			for (String line : (Iterable<String>) lines::iterator)
			{
				if (line.isBlank()) continue;
				JsonNode event;
				try
				{
					event = scope.getJsonMapper().readTree(line);
				}
				catch (RuntimeException e)
				{
					throw new IOException("Malformed build event in " + bep + ": " + e.getMessage(), e);
				}
				JsonNode id = event.path("id");
				String summary = id.path("testSummary").path("label").asString("");
				if (!summary.isEmpty())
					executed.add(summary);
				if (!"SKIPPED".equals(event.path("aborted").path("reason").asString("")))
					continue;
				for (String kind : List.of("targetCompleted", "targetConfigured", "testSummary", "testResult"))
				{
					String label = id.path(kind).path("label").asString("");
					if (!label.isEmpty())
					{
						skipped.add(label);
						break;
					}
				}
			}
		}
		executed.removeAll(skipped);
		return new Events(executed, skipped);
	}

	/**
	 * Reads a target list, ignoring blank lines and comment lines.
	 *
	 * @param file the list
	 * @return the targets
	 * @throws IOException if the file cannot be read
	 */
	private static Set<String> readTargets(Path file) throws IOException
	{
		Set<String> result = new TreeSet<>();
		for (String line : Files.readAllLines(file, StandardCharsets.UTF_8))
		{
			String trimmed = line.strip();
			if (!trimmed.isEmpty() && !trimmed.startsWith("#")) result.add(trimmed);
		}
		return result;
	}

	/**
	 * Reads a target list that may be absent.
	 *
	 * @param file the list
	 * @return the targets, empty if the file does not exist
	 * @throws IOException if the file cannot be read
	 */
	private static Set<String> optionalTargets(Path file) throws IOException
	{
		if (!Files.isRegularFile(file)) return Set.of();
		return readTargets(file);
	}

	/**
	 * Describes a possibly long target set.
	 *
	 * @param targets the targets
	 * @return up to twenty targets, then the remaining count
	 */
	private static String preview(Set<String> targets)
	{
		List<String> shown = targets.stream().limit(20).toList();
		String text = String.join(", ", shown);
		if (targets.size() > shown.size()) text += ", and " + (targets.size() - shown.size()) + " more";
		return text;
	}

	/**
	 * Reads a single-line value file.
	 *
	 * @param file the file
	 * @return the stripped text
	 * @throws IOException if the file cannot be read
	 */
	private static String readValue(Path file) throws IOException
	{
		return Files.readString(file, StandardCharsets.UTF_8).strip();
	}

	/** One downloaded shard artifact. */
	private record Shard(Path directory, int number, int count, Set<String> candidates, Set<String> selected,
		Set<String> executed, Set<String> skipped)
	{
	}

	/**
	 * Checks shard coverage.
	 *
	 * @param directory directory of downloaded shard artifacts, one subdirectory each
	 * @param expected the expected shard count of every group
	 * @param allowlist targets that may be skipped
	 * @param out where the skipped targets are printed
	 * @return the failure messages, empty when coverage is complete
	 * @throws IOException if a present artifact cannot be read
	 */
	public static List<String> check(Path directory, Map<String, Integer> expected, Set<String> allowlist,
		PrintStream out) throws IOException
	{
		List<String> failures = new ArrayList<>();
		Map<String, List<Shard>> groups = new HashMap<>();
		List<Path> artifacts;
		try (Stream<Path> children = Files.isDirectory(directory) ? Files.list(directory) : Stream.<Path>empty())
		{
			artifacts = children.filter(Files::isDirectory).sorted().toList();
		}
		for (Path artifact : artifacts)
		{
			if (!Files.isRegularFile(artifact.resolve("group.txt")))
			{
				failures.add("Artifact " + artifact.getFileName() + " has no group.txt.");
				continue;
			}
			try
			{
				String group = readValue(artifact.resolve("group.txt"));
				Shard shard = new Shard(artifact, Integer.parseInt(readValue(artifact.resolve("shard.txt"))),
					Integer.parseInt(readValue(artifact.resolve("shard-count.txt"))),
					readTargets(artifact.resolve("candidates.txt")), readTargets(artifact.resolve("selected.txt")),
					optionalTargets(artifact.resolve("executed.txt")), optionalTargets(artifact.resolve("skipped.txt")));
				groups.computeIfAbsent(group, _ -> new ArrayList<>()).add(shard);
			}
			catch (IOException | NumberFormatException e)
			{
				failures.add("Artifact " + artifact.getFileName() + " is unreadable: " + e.getMessage());
			}
		}
		for (String group : groups.keySet())
			if (!expected.containsKey(group))
				failures.add("Unexpected shard group " + group + ".");
		Set<String> allSkipped = new TreeSet<>();
		for (Map.Entry<String, Integer> entry : expected.entrySet())
		{
			String group = entry.getKey();
			int count = entry.getValue();
			List<Shard> shards = groups.getOrDefault(group, List.of());
			Map<Integer, Shard> byNumber = new TreeMap<>();
			for (Shard shard : shards)
			{
				if (shard.count != count)
					failures.add(group + " shard " + shard.number + " reports shard count " + shard.count + ", expected " +
						count + ".");
				if (byNumber.putIfAbsent(shard.number, shard) != null)
					failures.add(group + " shard " + shard.number + " was uploaded more than once.");
				if (shard.number < 1 || shard.number > count)
					failures.add(group + " shard " + shard.number + " is outside 1.." + count + ".");
			}
			for (int number = 1; number <= count; ++number)
				if (!byNumber.containsKey(number))
					failures.add(group + " shard " + number + " of " + count + " did not upload its coverage.");
			if (byNumber.isEmpty()) continue;
			Shard first = byNumber.values().iterator().next();
			Set<String> union = new TreeSet<>();
			Map<String, Integer> owner = new HashMap<>();
			for (Shard shard : byNumber.values())
			{
				if (!shard.candidates.equals(first.candidates))
					failures.add(group + " shard " + shard.number + " saw a different candidate list than shard " +
						first.number + " (" + shard.candidates.size() + " vs " + first.candidates.size() + " targets).");
				for (String target : shard.selected)
				{
					Integer previous = owner.putIfAbsent(target, shard.number);
					if (previous != null && previous != shard.number)
						failures.add(group + " target " + target + " was selected by shards " + previous + " and " +
							shard.number + ".");
				}
				union.addAll(shard.selected);
				for (String target : shard.skipped)
					allSkipped.add(group + " " + target);
			}
			Set<String> missing = new TreeSet<>(first.candidates);
			missing.removeAll(union);
			Set<String> extra = new TreeSet<>(union);
			extra.removeAll(first.candidates);
			if (!missing.isEmpty())
				failures.add(group + ": " + missing.size() + " candidate(s) selected by no shard: " + preview(missing));
			if (!extra.isEmpty())
				failures.add(group + ": " + extra.size() + " selected target(s) are not candidates: " + preview(extra));
			for (Shard shard : byNumber.values())
			{
				Set<String> unrun = new TreeSet<>(shard.selected);
				unrun.removeAll(shard.executed);
				unrun.removeAll(shard.skipped);
				if (!unrun.isEmpty())
					failures.add(group + " shard " + shard.number + ": " + unrun.size() +
						" selected target(s) neither executed nor skipped: " + preview(unrun));
			}
			out.println(group + ": " + byNumber.size() + "/" + count + " shards, " + first.candidates.size() +
				" candidate(s), " + union.size() + " selected.");
		}
		out.println("Skipped incompatible targets: " + allSkipped.size());
		allSkipped.forEach(value -> out.println("    " + value));
		for (String value : allSkipped)
		{
			String target = value.substring(value.indexOf(' ') + 1);
			if (!allowlist.contains(target))
				failures.add("Skipped target " + target + " (" + value.substring(0, value.indexOf(' ')) +
					") is not on the skip allowlist.");
		}
		return failures;
	}
}
