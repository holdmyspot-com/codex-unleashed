package com.holdmyspot.codexunleashed.tooling;

import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import picocli.CommandLine;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Model.OptionSpec;

/**
 * Selects Windows Bazel targets using checked-in positive duration estimates and deterministic shard assignments.
 */
public final class WindowsBazelShards
{
	/**
	 * Prevents construction.
	 */
	private WindowsBazelShards()
	{
	}

	/**
	 * Prints one nonempty shard with LF delimiters and reports estimated loads on standard error.
	 *
	 * @param args required shard, shard count, and duration-file options
	 * @param in the complete target inventory, one label per line
	 * @param out the selected labels
	 * @param err usage and estimated-load diagnostics
	 * @return zero on success or help, or two for invalid arguments or unreadable inputs
	 */
	public static int run(String[] args, InputStream in, PrintStream out, PrintStream err)
	{
		CommandSpec spec = CommandSpec.create().name("select-windows-bazel-targets");
		spec.addOption(OptionSpec.builder("--shard").type(Integer.class).required(true).build());
		spec.addOption(OptionSpec.builder("--shard-count").type(Integer.class).required(true).build());
		spec.addOption(OptionSpec.builder("--durations").type(Path.class).required(true).build());
		spec.addOption(OptionSpec.builder("-h", "--help").usageHelp(true).build());
		CommandLine parser = new CommandLine(spec).setExpandAtFiles(false);
		try
		{
			CommandLine.ParseResult options = parser.parseArgs(args);
			if (options.isUsageHelpRequested())
			{
				parser.usage(out);
				return 0;
			}
			int shard = options.matchedOptionValue("--shard", 0);
			int count = options.matchedOptionValue("--shard-count", 0);
			if (count < 1 || shard < 1 || shard > count)
				throw new IllegalArgumentException("Shard must be between 1 and a positive shard count");

			Map<String, BigInteger> durations = readDurations(options.matchedOptionValue("--durations", null));
			List<String> targets = new String(in.readAllBytes(), StandardCharsets.UTF_8).lines().toList();
			if (targets.isEmpty() || targets.stream().anyMatch(String::isBlank))
				throw new IllegalArgumentException("Supply a nonempty inventory of nonblank Bazel target labels");
			if (new HashSet<>(targets).size() != targets.size())
				throw new IllegalArgumentException("Duplicate Bazel target labels were provided");
			List<List<String>> assignments = new ArrayList<>();
			List<BigInteger> loads = new ArrayList<>();
			for (int index = 0; index < count; ++index)
			{
				assignments.add(new ArrayList<>());
				loads.add(BigInteger.ZERO);
			}
			List<String> ordered = new ArrayList<>(targets);
			ordered.sort(Comparator.<String, BigInteger>comparing(target -> durations.getOrDefault(target, BigInteger.ONE)).
				reversed().thenComparing(Comparator.naturalOrder()));
			for (String target : ordered)
			{
				int selected = 0;
				for (int index = 1; index < count; ++index)
				{
					if (loads.get(index).compareTo(loads.get(selected)) < 0)
						selected = index;
				}
				assignments.get(selected).add(target);
				loads.set(selected, loads.get(selected).add(durations.getOrDefault(target, BigInteger.ONE)));
			}
			List<String> selected = assignments.get(shard - 1);
			if (selected.isEmpty())
				throw new IllegalArgumentException("No Bazel targets selected for shard " + shard + "/" + count);
			selected.sort(Comparator.naturalOrder());
			err.println("Windows Bazel shards: targets=" + assignments.stream().map(List::size).toList() +
				", estimated test-seconds=" + loads + ", default weights=" +
				targets.stream().filter(target -> !durations.containsKey(target)).count() + ". Selected shard " +
				shard + "/" + count + ".");
			out.print(String.join("\n", selected) + "\n");
			return 0;
		}
		catch (CommandLine.ParameterException | IOException | IllegalArgumentException failure)
		{
			err.println("ERROR: " + failure.getMessage());
			return 2;
		}
	}

	/**
	 * Reads unique target weights, ignoring comments and blank lines.
	 *
	 * @param path the UTF-8 tab-separated target and positive integer seconds file
	 * @return the duration of each explicitly weighted target
	 * @throws IOException if the file cannot be read
	 * @throws IllegalArgumentException if a row is malformed or duplicated
	 */
	private static Map<String, BigInteger> readDurations(Path path) throws IOException
	{
		Map<String, BigInteger> durations = new HashMap<>();
		int lineNumber = 0;
		for (String line : Files.readAllLines(path, StandardCharsets.UTF_8))
		{
			++lineNumber;
			if (line.isBlank() || line.stripLeading().startsWith("#"))
				continue;
			String[] fields = line.split("\t", -1);
			if (fields.length != 2 || fields[0].isBlank() || fields[1].isEmpty() ||
				!fields[1].codePoints().allMatch(Character::isDigit))
				throw new IllegalArgumentException(path + ":" + lineNumber + ": expected target<TAB>positive integer");
			BigInteger duration = new BigInteger(fields[1]);
			if (duration.signum() < 1)
				throw new IllegalArgumentException(path + ":" + lineNumber + ": duration must be positive");
			if (durations.putIfAbsent(fields[0], duration) != null)
				throw new IllegalArgumentException(path + ":" + lineNumber + ": duplicate target " + fields[0]);
		}
		return durations;
	}
}
