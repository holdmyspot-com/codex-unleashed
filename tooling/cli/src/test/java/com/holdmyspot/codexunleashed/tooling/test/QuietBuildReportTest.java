package com.holdmyspot.codexunleashed.tooling.test;

import com.holdmyspot.codexunleashed.tooling.QuietBuildReport;
import com.holdmyspot.codexunleashed.tooling.QuietBuildReport.DumpTimeoutException;
import com.holdmyspot.codexunleashed.tooling.QuietBuildReport.Factory;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

/**
 * Verifies {@link QuietBuildReport} output and its interaction with a supplied dump runner.
 */
public final class QuietBuildReportTest
{
	/**
	 * Creates the TestNG test fixture.
	 */
	public QuietBuildReportTest()
	{
	}

	/**
	 * Appending twice writes one rounded UTC header before each runner's output.
	 *
	 * @throws IOException if the report file cannot be created, read, or deleted
	 */
	@Test
	public void appendsHeadersAndRunnerOutput() throws IOException
	{
		Path reportPath = Files.createTempFile("quiet-build-report-", ".txt");
		try
		{
			int[] invocation = {0};
			QuietBuildReport report = new QuietBuildReport(reportPath, (jcmd, output, timeout) ->
			{
				requireThat(jcmd, "jcmd").isEqualTo(Optional.empty());
				requireThat(timeout, "timeout").isEqualTo(Duration.ofSeconds(600));
				invocation[0] = invocation[0] + 1;
				output.write("dump-%d\n".formatted(invocation[0]));
			});
			Instant reportTime = Instant.parse("2026-05-06T07:08:09.999Z");

			report.append(Duration.ofMillis(1_500), reportTime, Optional.empty());
			report.append(Duration.ofMillis(2_500), reportTime, Optional.empty());

			requireThat(Files.readString(reportPath, StandardCharsets.UTF_8), "report").
				isEqualTo("=== quiet_seconds=2 time=2026-05-06T07:08:09Z\ndump-1\n" +
					"=== quiet_seconds=2 time=2026-05-06T07:08:09Z\ndump-2\n");
		}
		finally
		{
			Files.deleteIfExists(reportPath);
		}
	}

	/**
	 * The runner can read the appended header when append invokes it.
	 *
	 * @throws IOException if the report file cannot be created, read, or deleted
	 */
	@Test
	public void flushesHeaderBeforeRunningDump() throws IOException
	{
		Path reportPath = Files.createTempFile("quiet-build-report-", ".txt");
		try
		{
			Instant reportTime = Instant.parse("2026-05-06T07:08:09Z");
			QuietBuildReport report = new QuietBuildReport(reportPath, (jcmd, output, timeout) ->
			{
				requireThat(Files.readString(reportPath, StandardCharsets.UTF_8), "visibleHeader").
					isEqualTo("=== quiet_seconds=0 time=2026-05-06T07:08:09Z\n");
			});

			report.append(Duration.ZERO, reportTime, Optional.empty());
		}
		finally
		{
			Files.deleteIfExists(reportPath);
		}
	}

	/**
	 * Rounds signed quiet durations to seconds using ties-to-even and keeps a negative zero sign.
	 *
	 * @param quiet signed quiet duration
	 * @param expectedSeconds expected rounded second value in the header
	 * @throws IOException if the report file cannot be created, read, or deleted
	 */
	@Test(dataProvider = "roundedQuietSeconds")
	public void roundsQuietSeconds(Duration quiet, String expectedSeconds) throws IOException
	{
		Path reportPath = Files.createTempFile("quiet-build-report-", ".txt");
		try
		{
			QuietBuildReport report = new QuietBuildReport(reportPath, (jcmd, output, timeout) -> {});
			Instant reportTime = Instant.parse("2026-05-06T07:08:09.999Z");

			report.append(quiet, reportTime, Optional.empty());

			requireThat(Files.readString(reportPath, StandardCharsets.UTF_8), "report").
				isEqualTo("=== quiet_seconds=%s time=2026-05-06T07:08:09Z\n".formatted(expectedSeconds));
		}
		finally
		{
			Files.deleteIfExists(reportPath);
		}
	}

	/**
	 * Supplies nearby values around ties and positive and negative half-second ties.
	 *
	 * @return quiet durations and expected rounded header values
	 */
	@DataProvider(name = "roundedQuietSeconds")
	public Object[][] roundedQuietSeconds()
	{
		return new Object[][] {
			{Duration.ofNanos(499_999_999), "0"},
			{Duration.ofNanos(500_000_000), "0"},
			{Duration.ofNanos(500_000_001), "1"},
			{Duration.ofMillis(1_499), "1"},
			{Duration.ofMillis(1_500), "2"},
			{Duration.ofMillis(1_501), "2"},
			{Duration.ofMillis(2_499), "2"},
			{Duration.ofMillis(2_500), "2"},
			{Duration.ofMillis(2_501), "3"},
			{Duration.ofNanos(-499_999_999), "-0"},
			{Duration.ofNanos(-500_000_000), "-0"},
			{Duration.ofNanos(-500_000_001), "-1"},
			{Duration.ofMillis(-1_499), "-1"},
			{Duration.ofMillis(-1_500), "-2"},
			{Duration.ofMillis(-1_501), "-2"},
			{Duration.ofMillis(-2_499), "-2"},
			{Duration.ofMillis(-2_500), "-2"},
			{Duration.ofMillis(-2_501), "-3"}
		};
	}

	/**
	 * Appends the exact timeout marker after the header when the runner reports its deadline.
	 *
	 * @throws IOException if the report file cannot be created, read, or deleted
	 */
	@Test
	public void appendsTimeoutMarker() throws IOException
	{
		Path reportPath = Files.createTempFile("quiet-build-report-", ".txt");
		try
		{
			QuietBuildReport report = new QuietBuildReport(reportPath,
				(jcmd, output, timeout) ->
				{
					throw new DumpTimeoutException("deadline");
				});
			Instant reportTime = Instant.parse("2026-05-06T07:08:09.999Z");

			report.append(Duration.ZERO, reportTime, Optional.empty());

			requireThat(Files.readString(reportPath, StandardCharsets.UTF_8), "report").
				isEqualTo("=== quiet_seconds=0 time=2026-05-06T07:08:09Z\n<process report timed out>\n");
		}
		finally
		{
			Files.deleteIfExists(reportPath);
		}
	}

	/**
	 * Propagates an ordinary runner I/O failure as the same exception instance.
	 *
	 * @throws IOException if the report file cannot be created or deleted
	 */
	@Test
	public void propagatesRunnerIOException() throws IOException
	{
		Path reportPath = Files.createTempFile("quiet-build-report-", ".txt");
		try
		{
			IOException expected = new IOException("runner failed");
			QuietBuildReport report = new QuietBuildReport(reportPath, (jcmd, output, timeout) ->
				{
					throw expected;
				});

			IOException actual = TestRequirements.requireException(IOException.class,
				() -> report.append(Duration.ZERO, Instant.EPOCH, Optional.empty()));

			requireThat(actual, "actual").isReferenceEqualTo(expected, "expected");
		}
		finally
		{
			Files.deleteIfExists(reportPath);
		}
	}

	/**
	 * The factory creates an appender that passes the caller's jcmd path and the fixed timeout to its runner.
	 *
	 * @throws IOException if the report file cannot be created, read, or deleted
	 */
	@Test
	public void factoryPassesRunnerArguments() throws IOException
	{
		Path reportPath = Files.createTempFile("quiet-build-report-", ".txt");
		try
		{
			Optional<Path> jcmd = Optional.of(Path.of("build/jcmd"));
			Factory factory = QuietBuildReport.factory();
			QuietBuildReport report = factory.create(reportPath, (receivedJcmd, output, timeout) ->
			{
				requireThat(receivedJcmd, "jcmd").isEqualTo(jcmd);
				requireThat(timeout, "timeout").isEqualTo(Duration.ofSeconds(600));
				output.write("dump\n");
			});

			report.append(Duration.ZERO, Instant.EPOCH, jcmd);

			requireThat(Files.readString(reportPath, StandardCharsets.UTF_8), "report").
				isEqualTo("=== quiet_seconds=0 time=1970-01-01T00:00:00Z\ndump\n");
		}
		finally
		{
			Files.deleteIfExists(reportPath);
		}
	}

	/**
	 * Rejects a null report path at construction.
	 */
	@Test(expectedExceptions = NullPointerException.class)
	public void rejectsNullPath()
	{
		new QuietBuildReport(null, (jcmd, output, timeout) -> {});
	}

	/**
	 * Rejects a null dump runner at construction.
	 */
	@Test(expectedExceptions = NullPointerException.class)
	public void rejectsNullRunner()
	{
		new QuietBuildReport(Path.of("report"), null);
	}

	/**
	 * Rejects a null quiet duration before appending.
	 *
	 * @throws IOException if the report file cannot be created or deleted
	 */
	@Test(expectedExceptions = NullPointerException.class)
	public void rejectsNullQuietDuration() throws IOException
	{
		Path reportPath = Files.createTempFile("quiet-build-report-", ".txt");
		try
		{
			new QuietBuildReport(reportPath, (jcmd, output, timeout) -> {}).append(null, Instant.EPOCH, Optional.empty());
		}
		finally
		{
			Files.deleteIfExists(reportPath);
		}
	}

	/**
	 * Rejects a null report time before appending.
	 *
	 * @throws IOException if the report file cannot be created or deleted
	 */
	@Test(expectedExceptions = NullPointerException.class)
	public void rejectsNullReportTime() throws IOException
	{
		Path reportPath = Files.createTempFile("quiet-build-report-", ".txt");
		try
		{
			new QuietBuildReport(reportPath, (jcmd, output, timeout) -> {}).append(Duration.ZERO, null, Optional.empty());
		}
		finally
		{
			Files.deleteIfExists(reportPath);
		}
	}

	/**
	 * Rejects a null jcmd optional before appending.
	 *
	 * @throws IOException if the report file cannot be created or deleted
	 */
	@Test(expectedExceptions = NullPointerException.class)
	public void rejectsNullJcmdOptional() throws IOException
	{
		Path reportPath = Files.createTempFile("quiet-build-report-", ".txt");
		try
		{
			new QuietBuildReport(reportPath, (jcmd, output, timeout) -> {}).append(Duration.ZERO, Instant.EPOCH, null);
		}
		finally
		{
			Files.deleteIfExists(reportPath);
		}
	}
}
