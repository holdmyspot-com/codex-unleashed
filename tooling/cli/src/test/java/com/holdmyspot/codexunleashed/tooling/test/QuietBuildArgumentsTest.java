package com.holdmyspot.codexunleashed.tooling.test;

import com.holdmyspot.codexunleashed.tooling.QuietBuildArguments;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.testng.Assert;
import org.testng.annotations.Test;

/**
 * Checks quiet-build command arguments at their parser boundary.
 */
public final class QuietBuildArgumentsTest
{
	/**
	 * Creates the parser test suite.
	 */
	public QuietBuildArgumentsTest()
	{
	}

	/**
	 * Uses parser calls to check defaults, validation, command handling, and Java-home selection.
	 *
	 * @throws IOException if a Java-home fixture cannot be created or removed
	 */
	@Test
	public void parsesDefaultValues() throws IOException
	{
		Path watchDirectory = Path.of("watch directory");
		Path reportPath = Path.of("report path");
		QuietBuildArguments.Values values = QuietBuildArguments.parse(
				new String[]{"--watch", watchDirectory.toString(), "--report", reportPath.toString(), "make", "all"},
				Map.of(), false);

		Assert.assertNotNull(values);
		Assert.assertEquals(values.watchDirectory(), watchDirectory);
		Assert.assertEquals(values.reportPath(), reportPath);
		Assert.assertNull(values.jcmd());
		Assert.assertEquals(values.firstReport(), Duration.ofSeconds(900));
		Assert.assertEquals(values.reportInterval(), Duration.ofSeconds(900));
		Assert.assertEquals(values.stopAfter(), Duration.ofSeconds(3600));
		Assert.assertEquals(values.sampleInterval(), Duration.ofSeconds(5));
		Assert.assertEquals(values.cpuInterval(), Duration.ofSeconds(60));
		Assert.assertEquals(values.minimumCpu(), Duration.ofSeconds(5));
		Assert.assertEquals(values.command(), List.of("make", "all"));
	}

	/**
	 * Rejects absent required options, absent option values, unknown options, and an empty command.
	 */
	@Test
	public void rejectsMissingAndUnknownArguments()
	{
		assertArgumentError(new String[]{"--report", "report", "make"}, "--watch");
		assertArgumentError(new String[]{"--watch", "watch", "make"}, "--report");
		assertArgumentError(new String[]{"--watch", "watch", "--report", "report"}, "command");
		assertArgumentError(new String[]{"--watch"}, "--watch");
		assertArgumentError(new String[]{"--watch", "watch", "--report"}, "--report");
		assertArgumentError(new String[]{"--watch", "watch", "--report", "report", "--unknown"}, "--unknown");
		assertArgumentError(new String[]{"--watch", "watch", "--report", "report", "make", "--verbose"},
				"--verbose");
	}

	/**
	 * Parses integral, fractional, and signed finite second values without clamping them.
	 */
	@Test
	public void parsesFiniteSeconds()
	{
		QuietBuildArguments.Values values = QuietBuildArguments.parse(new String[]{
				"--watch", "watch", "--report", "report", "--first-report", "1", "--interval", "1.25",
				"--stop-after", "-2.5", "--sample-interval", "0.0000000005", "--cpu-interval", "-0.125",
				"--min-cpu", "-3.75", "make"
		}, Map.of(), false);

		Assert.assertEquals(values.firstReport(), Duration.ofSeconds(1));
		Assert.assertEquals(values.reportInterval(), Duration.ofMillis(1250));
		Assert.assertEquals(values.stopAfter(), Duration.ofMillis(-2500));
		Assert.assertEquals(values.sampleInterval(), Duration.ofNanos(1));
		Assert.assertEquals(values.cpuInterval(), Duration.ofMillis(-125));
		Assert.assertEquals(values.minimumCpu(), Duration.ofMillis(-3750));
	}

	/**
	 * Rejects malformed, non-finite, and out-of-range scaled second values at both numeric bounds.
	 */
	@Test
	public void rejectsInvalidAndOutOfRangeSeconds()
	{
		assertInvalidSeconds("not-a-number");
		assertInvalidSeconds("NaN");
		assertInvalidSeconds("Infinity");
		assertInvalidSeconds("-Infinity");
		assertInvalidSeconds(Double.toString(Math.nextDown(-0x1.0p63 / 1_000_000_000d)));
		assertInvalidSeconds(Double.toString(0x1.0p63 / 1_000_000_000d));

		QuietBuildArguments.Values boundaryValues = QuietBuildArguments.parse(new String[]{
				"--watch", "watch", "--report", "report", "--interval",
				Double.toString(-0x1.0p63 / 1_000_000_000d), "--stop-after",
				Double.toString(Math.nextDown(0x1.0p63 / 1_000_000_000d)), "make"
		}, Map.of(), false);

		Assert.assertEquals(boundaryValues.reportInterval(), Duration.ofNanos(Long.MIN_VALUE));
		Assert.assertTrue(boundaryValues.stopAfter().toNanos() > 0);
	}

	/**
	 * Uses the last duplicate option and preserves command tokens after the literal separator.
	 */
	@Test
	public void lastOptionAndSeparatorAreHonored()
	{
		QuietBuildArguments.Values values = QuietBuildArguments.parse(new String[]{
				"--watch", "first watch", "--watch", "last watch", "--report", "report", "--interval", "2",
				"--interval", "3", "--", "--watch", "option-like command", "--verbose"
		}, Map.of(), false);

		Assert.assertEquals(values.watchDirectory(), Path.of("last watch"));
		Assert.assertEquals(values.reportInterval(), Duration.ofSeconds(3));
		Assert.assertEquals(values.command(), List.of("--watch", "option-like command", "--verbose"));
	}

	/**
	 * Selects regular jcmd files under JAVA_HOME and honors explicit and Windows-specific paths.
	 *
	 * @throws IOException if a Java-home fixture cannot be created or removed
	 */
	@Test
	public void selectsJcmdFromJavaHome() throws IOException
	{
		withTemporaryDirectory(home ->
		{
			Path binDirectory = home.resolve("bin");
			Files.createDirectories(binDirectory);
			Path unixJcmd = Files.createFile(binDirectory.resolve("jcmd"));
			Path windowsJcmd = Files.createFile(binDirectory.resolve("jcmd.exe"));
			Map<String, String> environment = Map.of("JAVA_HOME", home.toString());

			Assert.assertEquals(parseWithJcmd(environment, false).jcmd(), unixJcmd);
			Assert.assertEquals(parseWithJcmd(environment, true).jcmd(), windowsJcmd);
			Path explicitJcmd = home.resolve("explicit command that does not exist");
			Assert.assertEquals(parseWithJcmdAndExplicit(environment, explicitJcmd).jcmd(), explicitJcmd);

			Files.delete(unixJcmd);
			Files.delete(windowsJcmd);
			Files.createDirectory(binDirectory.resolve("jcmd"));
			Assert.assertNull(parseWithJcmd(environment, false).jcmd());
			Assert.assertNull(parseWithJcmd(environment, true).jcmd());
			Assert.assertNull(parseWithJcmd(Map.of(), false).jcmd());
		});
	}

	/**
	 * Does not expose environment values when parser errors identify a caller-correctable requirement.
	 */
	@Test
	public void errorsDoNotExposeEnvironmentValues()
	{
		String secretJavaHome = "private-java-home-marker";
		IllegalArgumentException error = argumentError(
				new String[]{"--report", "report", "make"}, Map.of("JAVA_HOME", secretJavaHome), false);

		Assert.assertTrue(error.getMessage().contains("--watch"));
		Assert.assertFalse(error.getMessage().contains(secretJavaHome));
	}

	/**
	 * Requires Values fields, rejects an empty command, and retains an immutable command copy.
	 */
	@Test
	public void valuesValidatesAndCopiesItsCommand()
	{
		List<String> command = new ArrayList<>(List.of("make", "all"));
		QuietBuildArguments.Values values = valuesWithCommand(command);
		command.add("changed later");

		Assert.assertEquals(values.command(), List.of("make", "all"));
		Assert.assertThrows(UnsupportedOperationException.class, () -> values.command().add("later"));
		Assert.assertThrows(IllegalArgumentException.class, () -> valuesWithCommand(List.of()));
		Assert.assertThrows(NullPointerException.class, () -> valuesWithWatchDirectory(null));
	}

	/**
	 * Rejects null for every required Values component while allowing nullable jcmd.
	 */
	@Test
	public void valuesRejectsNullRequiredValues()
	{
		Path watchDirectory = Path.of("watch");
		Path reportPath = Path.of("report");
		Duration duration = Duration.ZERO;
		List<String> command = List.of("make");
		List<Runnable> nullRequiredValues = List.of(
				() -> new QuietBuildArguments.Values(null, reportPath, null, duration, duration, duration, duration,
						duration, duration, command),
				() -> new QuietBuildArguments.Values(watchDirectory, null, null, duration, duration, duration,
						duration, duration, duration, command),
				() -> new QuietBuildArguments.Values(watchDirectory, reportPath, null, null, duration, duration,
						duration, duration, duration, command),
				() -> new QuietBuildArguments.Values(watchDirectory, reportPath, null, duration, null, duration,
						duration, duration, duration, command),
				() -> new QuietBuildArguments.Values(watchDirectory, reportPath, null, duration, duration, null,
						duration, duration, duration, command),
				() -> new QuietBuildArguments.Values(watchDirectory, reportPath, null, duration, duration, duration,
						null, duration, duration, command),
				() -> new QuietBuildArguments.Values(watchDirectory, reportPath, null, duration, duration, duration,
						duration, null, duration, command),
				() -> new QuietBuildArguments.Values(watchDirectory, reportPath, null, duration, duration, duration,
						duration, duration, null, command),
				() -> new QuietBuildArguments.Values(watchDirectory, reportPath, null, duration, duration, duration,
						duration, duration, duration, null));

		for (Runnable nullRequiredValue : nullRequiredValues)
			Assert.assertThrows(NullPointerException.class, nullRequiredValue::run);
	}

	/**
	 * Confirms that a parsed argument error names the requested option or requirement.
	 *
	 * @param argv invalid parser arguments
	 * @param expectedText expected option or requirement text in the error
	 */
	private static void assertArgumentError(String[] argv, String expectedText)
	{
		IllegalArgumentException error = argumentError(argv, Map.of(), false);
		Assert.assertTrue(error.getMessage().contains(expectedText), error.getMessage());
	}

	/**
	 * Confirms that a seconds option is rejected as invalid input.
	 *
	 * @param seconds invalid seconds text
	 */
	private static void assertInvalidSeconds(String seconds)
	{
		IllegalArgumentException error = argumentError(new String[]{
				"--watch", "watch", "--report", "report", "--interval", seconds, "make"
		}, Map.of(), false);

		Assert.assertTrue(error.getMessage().contains("--interval"), error.getMessage());
	}

	/**
	 * Returns the argument error produced for a parser invocation.
	 *
	 * @param argv parser arguments
	 * @param environment environment supplied to the parser
	 * @param windows whether the parser should use Windows jcmd naming
	 * @return the parser's invalid-argument error
	 */
	private static IllegalArgumentException argumentError(String[] argv, Map<String, String> environment,
			boolean windows)
	{
		try
		{
			QuietBuildArguments.parse(argv, environment, windows);
		}
		catch (IllegalArgumentException error)
		{
			return error;
		}
		throw new AssertionError("Expected invalid parser arguments");
	}

	/**
	 * Parses a complete argument set with JAVA_HOME to check automatic jcmd selection.
	 *
	 * @param environment environment supplied to the parser
	 * @param windows whether the parser should use Windows jcmd naming
	 * @return the parsed values
	 */
	private static QuietBuildArguments.Values parseWithJcmd(Map<String, String> environment, boolean windows)
	{
		return QuietBuildArguments.parse(new String[]{"--watch", "watch", "--report", "report", "make"},
				environment, windows);
	}

	/**
	 * Parses a complete argument set with an explicit jcmd path.
	 *
	 * @param environment environment supplied to the parser
	 * @param jcmd explicit jcmd path
	 * @return the parsed values
	 */
	private static QuietBuildArguments.Values parseWithJcmdAndExplicit(Map<String, String> environment, Path jcmd)
	{
		return QuietBuildArguments.parse(new String[]{"--watch", "watch", "--report", "report", "--jcmd",
				jcmd.toString(), "make"}, environment, false);
	}

	/**
	 * Constructs Values with the supplied command and otherwise valid representative fields.
	 *
	 * @param command command copied into the record
	 * @return validated argument values
	 */
	private static QuietBuildArguments.Values valuesWithCommand(List<String> command)
	{
		return new QuietBuildArguments.Values(Path.of("watch"), Path.of("report"), null, Duration.ZERO,
				Duration.ZERO, Duration.ZERO, Duration.ZERO, Duration.ZERO, Duration.ZERO, command);
	}

	/**
	 * Constructs Values with the supplied watch directory.
	 *
	 * @param watchDirectory required watch directory
	 * @return validated argument values
	 */
	private static QuietBuildArguments.Values valuesWithWatchDirectory(Path watchDirectory)
	{
		return new QuietBuildArguments.Values(watchDirectory, Path.of("report"), null, Duration.ZERO,
				Duration.ZERO, Duration.ZERO, Duration.ZERO, Duration.ZERO, Duration.ZERO, List.of("make"));
	}

	/**
	 * Runs a fixture action with a fresh directory that this test removes afterward.
	 *
	 * @param action action that uses the temporary directory
	 * @throws IOException if the directory cannot be created or removed
	 */
	private static void withTemporaryDirectory(TemporaryDirectoryAction action) throws IOException
	{
		Path temporaryDirectory = Files.createTempDirectory("quiet-build-arguments-");
		try
		{
			action.run(temporaryDirectory);
		}
		finally
		{
			try (Stream<Path> paths = Files.walk(temporaryDirectory))
			{
				for (Path path : paths.sorted(Comparator.reverseOrder()).toList())
					Files.deleteIfExists(path);
			}
		}
	}

	/**
	 * Performs a test fixture action inside a temporary directory.
	 */
	@FunctionalInterface
	private interface TemporaryDirectoryAction
	{
		/**
		 * Uses a temporary directory for one parser fixture.
		 *
		 * @param temporaryDirectory directory owned by the enclosing test
		 * @throws IOException if the fixture cannot be created
		 */
		void run(Path temporaryDirectory) throws IOException;
	}
}
