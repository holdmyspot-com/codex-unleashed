package com.holdmyspot.codexunleashed.tooling;

import java.nio.file.Path;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Parses quiet-build watcher options and stores their validated values.
 */
public final class QuietBuildArguments
{
	/**
	 * Nanoseconds in one second for scaling the command's decimal seconds.
	 */
	private static final double NANOS_PER_SECOND = 1_000_000_000d;
	/**
	 * Inclusive lower nanosecond bound accepted by {@link Duration#ofNanos(long)}.
	 */
	private static final double MIN_DURATION_NANOS = -0x1.0p63;
	/**
	 * Exclusive upper nanosecond bound accepted by {@link Duration#ofNanos(long)}.
	 */
	private static final double MAX_DURATION_NANOS_EXCLUSIVE = 0x1.0p63;

	/**
	 * Prevents callers from constructing a parser instance.
	 */
	private QuietBuildArguments()
	{
	}

	/**
	 * Parses watcher options and the command that follows them.
	 *
	 * @param argv complete watcher and command arguments
	 * @param environment environment used for optional JAVA_HOME lookup
	 * @param windows whether Windows executable naming applies
	 * @return validated watcher settings and command
	 * @throws IllegalArgumentException if an option is missing, unknown, malformed, or required input is absent
	 * @throws NullPointerException if {@code argv}, an argument, or {@code environment} is {@code null}
	 */
	public static Values parse(String[] argv, Map<String, String> environment, boolean windows)
	{
		Objects.requireNonNull(argv, "argv");
		Objects.requireNonNull(environment, "environment");

		Path watchDirectory = null;
		Path reportPath = null;
		Path jcmd = null;
		Duration firstReport = Duration.ofSeconds(900);
		Duration reportInterval = Duration.ofSeconds(900);
		Duration stopAfter = Duration.ofSeconds(3600);
		Duration sampleInterval = Duration.ofSeconds(5);
		Duration cpuInterval = Duration.ofSeconds(60);
		Duration minimumCpu = Duration.ofSeconds(5);
		List<String> command = new ArrayList<>();
		boolean optionsHaveEnded = false;
		boolean commandStarted = false;

		for (int index = 0; index < argv.length; index += 1)
		{
			String argument = Objects.requireNonNull(argv[index], "argv[" + index + "]");
			if (optionsHaveEnded)
			{
				command.add(argument);
				continue;
			}
			if (argument.equals("--"))
			{
				optionsHaveEnded = true;
				continue;
			}
			if (argument.startsWith("-"))
			{
				if (commandStarted)
					throw new IllegalArgumentException("Option-like command token requires --: " + argument);

				String value = requiredOptionValue(argv, index, argument);
				index += 1;
				switch (argument)
				{
					case "--watch" -> watchDirectory = parsePath(value, argument);
					case "--report" -> reportPath = parsePath(value, argument);
					case "--jcmd" -> jcmd = parsePath(value, argument);
					case "--first-report" -> firstReport = parseSeconds(value, argument);
					case "--interval" -> reportInterval = parseSeconds(value, argument);
					case "--stop-after" -> stopAfter = parseSeconds(value, argument);
					case "--sample-interval" -> sampleInterval = parseSeconds(value, argument);
					case "--cpu-interval" -> cpuInterval = parseSeconds(value, argument);
					case "--min-cpu" -> minimumCpu = parseSeconds(value, argument);
					default -> throw new IllegalArgumentException("Unknown option: " + argument);
				}
				continue;
			}

			commandStarted = true;
			command.add(argument);
		}

		if (watchDirectory == null)
			throw new IllegalArgumentException("Required option --watch is missing");
		if (reportPath == null)
			throw new IllegalArgumentException("Required option --report is missing");
		if (command.isEmpty())
			throw new IllegalArgumentException("Required command is missing");

		if (jcmd == null)
			jcmd = findJcmd(environment, windows);

		return new Values(watchDirectory, reportPath, jcmd, firstReport, reportInterval, stopAfter, sampleInterval,
				cpuInterval, minimumCpu, command);
	}

	/**
	 * Returns the next value for a watcher option.
	 *
	 * @param argv watcher arguments
	 * @param optionIndex position of the option in {@code argv}
	 * @param option option whose value follows
	 * @return the option value
	 * @throws IllegalArgumentException if the value is missing
	 */
	private static String requiredOptionValue(String[] argv, int optionIndex, String option)
	{
		int valueIndex = optionIndex + 1;
		if (valueIndex >= argv.length)
			throw invalidOption(option, "requires a value");

		String value = argv[valueIndex];
		boolean valueIsMissing = value == null || value.startsWith("--");
		if (valueIsMissing)
			throw invalidOption(option, "requires a value before the next option");
		return value;
	}

	/**
	 * Converts a supplied path after keeping invalid-path details out of the error.
	 *
	 * @param pathText path supplied for a watcher option
	 * @param option option that supplied the path
	 * @return the supplied path
	 * @throws IllegalArgumentException if the path is invalid for this platform
	 */
	private static Path parsePath(String pathText, String option)
	{
		try
		{
			return Path.of(pathText);
		}
		catch (InvalidPathException _)
		{
			throw invalidOption(option, "requires a valid path");
		}
	}

	/**
	 * Converts finite decimal seconds to nanosecond precision within the Duration range.
	 *
	 * @param secondsText decimal seconds supplied for the option
	 * @param option option that supplied the seconds
	 * @return the corresponding duration
	 * @throws IllegalArgumentException if the seconds are malformed, non-finite, or out of range
	 */
	private static Duration parseSeconds(String secondsText, String option)
	{
		double seconds;
		try
		{
			seconds = Double.parseDouble(secondsText);
		}
		catch (NumberFormatException _)
		{
			throw invalidOption(option, "requires finite seconds within the supported range");
		}

		boolean secondsAreFinite = Double.isFinite(seconds);
		if (secondsAreFinite)
		{
			double scaledNanos = seconds * NANOS_PER_SECOND;
			boolean scaledValueIsInRange = scaledNanos >= MIN_DURATION_NANOS &&
					scaledNanos < MAX_DURATION_NANOS_EXCLUSIVE;
			if (scaledValueIsInRange)
				return Duration.ofNanos(Math.round(scaledNanos));
		}
		throw invalidOption(option, "requires finite seconds within the supported range");
	}

	/**
	 * Finds a regular jcmd executable under the supplied JAVA_HOME when one is available.
	 *
	 * @param environment environment supplied to the parser
	 * @param windows whether Windows executable naming applies
	 * @return the regular jcmd executable, or {@code null} when it is unavailable
	 */
	private static Path findJcmd(Map<String, String> environment, boolean windows)
	{
		String javaHome = environment.get("JAVA_HOME");
		if (javaHome == null)
			return null;

		String executableName;
		if (windows)
			executableName = "jcmd.exe";
		else
			executableName = "jcmd";

		Path javaHomePath;
		try
		{
			javaHomePath = Path.of(javaHome);
		}
		catch (InvalidPathException _)
		{
			return null;
		}
		Path binDirectory = javaHomePath.resolve("bin");
		Path candidate = binDirectory.resolve(executableName);
		if (Files.isRegularFile(candidate))
			return candidate;
		return null;
	}

	/**
	 * Creates an invalid-option error without including the supplied option value.
	 *
	 * @param option option associated with invalid input
	 * @param reason safe explanation of the invalid input
	 * @return the caller-facing argument error
	 */
	private static IllegalArgumentException invalidOption(String option, String reason)
	{
		return new IllegalArgumentException("Option " + option + " " + reason);
	}

	/**
	 * Holds validated watcher paths, timing thresholds, and an immutable command.
	 *
	 * @param watchDirectory directory whose changes the watcher monitors
	 * @param reportPath destination for process reports
	 * @param jcmd optional Java diagnostic command path, or {@code null} when absent
	 * @param firstReport quiet duration before the first report
	 * @param reportInterval quiet duration between reports
	 * @param stopAfter quiet duration before stopping the build
	 * @param sampleInterval interval between resource samples
	 * @param cpuInterval interval between CPU readings
	 * @param minimumCpu minimum CPU activity threshold
	 * @param command nonempty command passed to the build process
	 */
	public record Values(
			Path watchDirectory,
			Path reportPath,
			Path jcmd,
			Duration firstReport,
			Duration reportInterval,
			Duration stopAfter,
			Duration sampleInterval,
			Duration cpuInterval,
			Duration minimumCpu,
			List<String> command)
	{
		/**
		 * Validates required values and takes an immutable copy of the command.
		 *
		 * @param watchDirectory directory whose changes the watcher monitors
		 * @param reportPath destination for process reports
		 * @param jcmd optional Java diagnostic command path, or {@code null} when absent
		 * @param firstReport quiet duration before the first report
		 * @param reportInterval quiet duration between reports
		 * @param stopAfter quiet duration before stopping the build
		 * @param sampleInterval interval between resource samples
		 * @param cpuInterval interval between CPU readings
		 * @param minimumCpu minimum CPU activity threshold
		 * @param command nonempty command passed to the build process
		 * @throws NullPointerException if a required value or command entry is {@code null}
		 * @throws IllegalArgumentException if {@code command} is empty
		 */
		public Values
		{
			Objects.requireNonNull(watchDirectory, "watchDirectory");
			Objects.requireNonNull(reportPath, "reportPath");
			Objects.requireNonNull(firstReport, "firstReport");
			Objects.requireNonNull(reportInterval, "reportInterval");
			Objects.requireNonNull(stopAfter, "stopAfter");
			Objects.requireNonNull(sampleInterval, "sampleInterval");
			Objects.requireNonNull(cpuInterval, "cpuInterval");
			Objects.requireNonNull(minimumCpu, "minimumCpu");
			command = List.copyOf(Objects.requireNonNull(command, "command"));
			if (command.isEmpty())
				throw new IllegalArgumentException("command must not be empty");
		}
	}
}
