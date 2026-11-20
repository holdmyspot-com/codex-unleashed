package com.holdmyspot.codexunleashed.tooling.test;

import com.holdmyspot.codexunleashed.tooling.DefaultToolingScope;
import com.holdmyspot.codexunleashed.tooling.QuietBuildCapture;
import com.holdmyspot.codexunleashed.tooling.QuietBuildCpu;
import java.io.IOException;
import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import org.testng.annotations.Test;

import static com.holdmyspot.codexunleashed.tooling.test.TestRequirements.requireException;
import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

/** Verifies bounded, exact CPU observation from the accepted Unix and Windows process listings. */
public final class QuietBuildCpuTest
{
	/** Creates process CPU observation tests. */
	public QuietBuildCpuTest()
	{
	}

	/**
	 * Parses the supported Unix clocks exactly and excludes rows from other process groups.
	 *
	 * @throws IOException if the fixture cannot be read
	 */
	@Test
	public void readsExactUnixGroupClocks() throws IOException
	{
		String listing = "41 7 2:03.45\n42 7 01:02:03\n43 7 4:05:06.123456789\n" +
			"44 7 12-23:59:59\n99 8 00:00:09\n";
		Map<Long, Duration> expected = Map.of(41L, Duration.ofMillis(123_450), 42L, Duration.ofSeconds(3_723),
			43L, Duration.ofSeconds(14_706, 123_456_789), 44L, Duration.ofSeconds(1_123_199));
		Map<Long, Duration> actual = QuietBuildCpu.groupCpu(new StringReader(listing), 7);
		requireThat(actual, "cpuByProcess").isEqualTo(expected, "expectedGroupCpu");
		requireException(UnsupportedOperationException.class, () -> actual.put(99L, Duration.ZERO));
	}

	/**
	 * Treats a valid listing with no members as an immutable empty observation.
	 *
	 * @throws IOException if the fixture cannot be read
	 */
	@Test
	public void acceptsAnEmptySuccessfulUnixListing() throws IOException
	{
		Map<Long, Duration> actual = QuietBuildCpu.groupCpu(new StringReader(""), 5);
		requireThat(actual, "cpuByProcess").isEqualTo(Map.of(), "expectedEmptyObservation");
		requireException(UnsupportedOperationException.class, () -> actual.put(5L, Duration.ZERO));
	}

	/**
	 * Rejects malformed required Unix row data instead of returning a partial observation.
	 *
	 * @throws IOException if a fixture cannot be read
	 */
	@Test
	public void rejectsMalformedUnixRows() throws IOException
	{
		requireException(IOException.class,
			() -> QuietBuildCpu.groupCpu(new StringReader("2 5 not-a-clock\n"), 5));
		requireException(IOException.class,
			() -> QuietBuildCpu.groupCpu(new StringReader("2 5 00:00:01 extra\n"), 5));
	}

	/**
	 * Includes the root and descendants once across cycles and converts nullable counters exactly.
	 *
	 * @throws IOException if the temporary directory cannot be read or written
	 */
	@Test
	public void readsWindowsTreeAndCycles()
		throws IOException
	{
		String listing = "[" +
			"{\"ProcessId\":0,\"ParentProcessId\":0,\"KernelModeTime\":0,\"UserModeTime\":0}," +
			"{\"ProcessId\":10,\"ParentProcessId\":20,\"KernelModeTime\":null,\"UserModeTime\":20000000}," +
			"{\"ProcessId\":11,\"ParentProcessId\":10,\"KernelModeTime\":1000000,\"UserModeTime\":0}," +
			"{\"ProcessId\":20,\"ParentProcessId\":10,\"KernelModeTime\":10000000,\"UserModeTime\":null}," +
			"{\"ProcessId\":99,\"ParentProcessId\":1,\"KernelModeTime\":99999999,\"UserModeTime\":0}]";
		Path temporaryDirectory = Files.createTempDirectory("quiet-build-cpu-array-");
		try (DefaultToolingScope scope = new DefaultToolingScope())
		{
			Map<Long, Duration> actual = QuietBuildCpu.treeCpu(scope, new StringReader(listing), 10, temporaryDirectory);
			requireThat(actual, "cpuByProcess").isEqualTo(Map.of(10L, Duration.ofSeconds(2),
				11L, Duration.ofMillis(100), 20L, Duration.ofSeconds(1)), "expectedTreeCpu");
			try (Stream<Path> files = Files.list(temporaryDirectory))
			{
				requireThat(files.count(), "temporaryResidue").isEqualTo(0L, "expectedNoTemporaryResidue");
			}
		}
		finally
		{
			Files.delete(temporaryDirectory);
		}
	}

	/**
	 * Accepts the singleton-object form emitted by PowerShell for one process.
	 *
	 * @throws IOException if the temporary directory cannot be read or written
	 */
	@Test
	public void readsWindowsSingletonNullCounters() throws IOException
	{
		Path temporaryDirectory = Files.createTempDirectory("quiet-build-cpu-single-");
		try (DefaultToolingScope scope = new DefaultToolingScope())
		{
			Map<Long, Duration> actual = QuietBuildCpu.treeCpu(scope,
				new StringReader("{\"ProcessId\":8,\"ParentProcessId\":0,\"KernelModeTime\":null," +
					"\"UserModeTime\":10000001}"), 8, temporaryDirectory);
			requireThat(actual, "cpuByProcess").isEqualTo(Map.of(8L, Duration.ofSeconds(1, 100)), "expectedSingletonCpu");
		}
		finally
		{
			Files.delete(temporaryDirectory);
		}
	}

	/**
	 * Returns an empty immutable map when the successful Windows listing omits the requested root.
	 *
	 * @throws IOException if the temporary directory cannot be read or written
	 */
	@Test
	public void acceptsAnEmptyWindowsTree() throws IOException
	{
		Path temporaryDirectory = Files.createTempDirectory("quiet-build-cpu-empty-");
		try (DefaultToolingScope scope = new DefaultToolingScope())
		{
			Map<Long, Duration> actual = QuietBuildCpu.treeCpu(scope, new StringReader("[]"), 8, temporaryDirectory);
			requireThat(actual, "cpuByProcess").isEqualTo(Map.of(), "expectedEmptyTree");
			requireException(UnsupportedOperationException.class, () -> actual.put(8L, Duration.ZERO));
		}
		finally
		{
			Files.delete(temporaryDirectory);
		}
	}

	/**
	 * Rejects malformed JSON and missing or wrongly typed required Windows process fields.
	 *
	 * @throws IOException if the temporary directory cannot be read or written
	 */
	@Test
	public void rejectsMalformedWindowsObservations() throws IOException
	{
		Path temporaryDirectory = Files.createTempDirectory("quiet-build-cpu-malformed-");
		try (DefaultToolingScope scope = new DefaultToolingScope())
		{
			requireException(IOException.class, () -> QuietBuildCpu.treeCpu(scope,
				new StringReader("[{\"ProcessId\":8}"), 8, temporaryDirectory));
			requireException(IOException.class, () -> QuietBuildCpu.treeCpu(scope,
				new StringReader("[{\"ProcessId\":8,\"ParentProcessId\":0,\"KernelModeTime\":0}]"),
				8, temporaryDirectory));
			requireException(IOException.class, () -> QuietBuildCpu.treeCpu(scope,
				new StringReader("{\"ProcessId\":\"8\",\"ParentProcessId\":0,\"KernelModeTime\":0," +
					"\"UserModeTime\":0}"), 8, temporaryDirectory));
		}
		finally
		{
			Files.delete(temporaryDirectory);
		}
	}

	/**
	 * Reports a nonzero process observation as failure and preserves its fixed command and timeout boundary.
	 *
	 * @throws IOException if the process observation fails
	 */
	@Test
	public void reportsCommandFailure() throws IOException
	{
		try (DefaultToolingScope scope = new DefaultToolingScope())
		{
			AtomicReference<List<String>> command = new AtomicReference<>();
			AtomicReference<Optional<Duration>> timeout = new AtomicReference<>();
			AtomicBoolean closed = new AtomicBoolean();
			QuietBuildCapture.Access access = (arguments, context, limit) ->
			{
				command.set(List.copyOf(arguments));
				timeout.set(limit);
				return new QuietBuildCapture.Captured()
				{
					@Override
					public int status()
					{
						return 1;
					}

					@Override
					public java.io.Reader stdout()
					{
						return new StringReader("");
					}

					@Override
					public java.io.Reader stderr()
					{
						return new StringReader("observation failed");
					}

					@Override
					public void close()
					{
						closed.set(true);
					}
				};
			};
			QuietBuildCapture.Context context = new QuietBuildCapture.Context(Path.of("."), Path.of("."), Map.of());
			IOException failure = requireException(IOException.class,
				() -> QuietBuildCpu.factory(scope, access).create(51, context).read());
			requireThat(failure.getMessage().contains("observation failed"), "failureMessage").isTrue();
			requireThat(closed.get(), "captureClosed").isTrue();
			boolean windows = System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).startsWith("windows");
			int expectedSeconds = 60;
			String expectedExecutable = "ps";
			if (windows)
			{
				expectedSeconds = 120;
				expectedExecutable = "powershell";
			}
			requireThat(timeout.get(), "timeout").isEqualTo(Optional.of(Duration.ofSeconds(
				expectedSeconds)), "expectedPlatformTimeout");
			requireThat(command.get().getFirst(), "executable").isEqualTo(expectedExecutable, "expectedPlatformCommand");
		}
	}
}
