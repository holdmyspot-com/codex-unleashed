package com.holdmyspot.codexunleashed.tooling.test;

import com.holdmyspot.codexunleashed.tooling.DefaultToolingScope;
import com.holdmyspot.codexunleashed.tooling.DarwinQuietBuildResources;
import com.holdmyspot.codexunleashed.tooling.QuietBuildCapture;
import com.holdmyspot.codexunleashed.tooling.QuietBuildResources;
import com.holdmyspot.codexunleashed.tooling.ToolingScope;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.testng.annotations.Test;
import tools.jackson.databind.JsonNode;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

/** Tests Darwin resource conversion, capture requests and native-access ownership. */
public final class DarwinQuietBuildResourcesTest
{
	/** Creates Darwin resource reader tests. */
	public DarwinQuietBuildResourcesTest()
	{
	}

	/**
	 * Converts the contract's Darwin sysctl and vm_stat fixture into exact counters and requests.
	 *
	 * @throws IOException if the test context or fixture cannot be read
	 */
	@Test
	public void readsDarwinCounters() throws IOException
	{
		try (DefaultToolingScope scope = new DefaultToolingScope(); TestContext fixture = openContext(scope))
		{
			TrackingNativeAccess nativeAccess = new TrackingNativeAccess(ticks());
			ScriptedCapture capture = new ScriptedCapture(new CaptureResult(readFixture("sysctl.txt"), "", 0),
				new CaptureResult(readFixture("vm-stat.txt"), "", 0));
			try (DarwinQuietBuildResources reader = new DarwinQuietBuildResources(nativeAccess, capture, fixture.context()))
			{
				QuietBuildResources.DarwinCounters counters = reader.read();
				requireThat(counters, "counters").isEqualTo(new QuietBuildResources.DarwinCounters(ticks(),
					java.math.BigInteger.valueOf(4), java.math.BigInteger.valueOf(16_384),
					java.math.BigInteger.valueOf(98_304), java.math.BigInteger.valueOf(1_572_864),
					java.math.BigInteger.valueOf(7), java.math.BigInteger.valueOf(8),
					java.math.BigInteger.valueOf(9), java.math.BigInteger.valueOf(4),
					java.math.BigInteger.valueOf(2)));
			}
			requireThat(capture.commands(), "commands").isEqualTo(List.of(
				List.of("sysctl", "-n", "vm.swapusage", "kern.memorystatus_vm_pressure_level", "hw.memsize",
					"hw.logicalcpu"), List.of("vm_stat")));
			requireThat(capture.contexts(), "contexts").isEqualTo(List.of(fixture.context(), fixture.context()));
			requireThat(capture.timeouts(), "timeouts").isEqualTo(List.of(Optional.of(Duration.ofSeconds(30)),
				Optional.of(Duration.ofSeconds(30))));
			requireThat(capture.closedCount(), "closedCaptures").isEqualTo(2);
			requireThat(nativeAccess.closeCount(), "nativeAccessClosed").isEqualTo(1);
		}
	}

	/**
	 * Uses 4096-byte pages when vm_stat omits its page-size header.
	 *
	 * @throws IOException if the test context or fixture cannot be read
	 */
	@Test
	public void usesDefaultPageSize() throws IOException
	{
		try (DefaultToolingScope scope = new DefaultToolingScope(); TestContext fixture = openContext(scope))
		{
			ScriptedCapture capture = new ScriptedCapture(new CaptureResult(readFixture("sysctl.txt"), "", 0),
				new CaptureResult("Pages free: 2.\nPages inactive: 3.\nPages speculative: 1.\n", "", 0));
			try (DarwinQuietBuildResources reader = new DarwinQuietBuildResources(new TrackingNativeAccess(ticks()),
				capture, fixture.context()))
			{
				QuietBuildResources.DarwinCounters counters = reader.read();
				requireThat(counters.memoryAvailableBytes(), "memoryAvailableBytes").
					isEqualTo(java.math.BigInteger.valueOf(24_576));
			}
		}
	}

	/**
	 * Treats missing page counters as zero while retaining the reported page size.
	 *
	 * @throws IOException if the test context or fixture cannot be read
	 */
	@Test
	public void omitsAbsentPageCounters() throws IOException
	{
		try (DefaultToolingScope scope = new DefaultToolingScope(); TestContext fixture = openContext(scope))
		{
			ScriptedCapture capture = new ScriptedCapture(new CaptureResult(readFixture("sysctl.txt"), "", 0),
				new CaptureResult("Mach Virtual Memory Statistics: (page size of 16384 bytes)\n", "", 0));
			try (DarwinQuietBuildResources reader = new DarwinQuietBuildResources(new TrackingNativeAccess(ticks()),
				capture, fixture.context()))
			{
				QuietBuildResources.DarwinCounters counters = reader.read();
				requireThat(counters.memoryAvailableBytes(), "memoryAvailableBytes").isEqualTo(java.math.BigInteger.ZERO);
				requireThat(counters.swapInPages(), "swapInPages").isEqualTo(java.math.BigInteger.ZERO);
				requireThat(counters.swapOutPages(), "swapOutPages").isEqualTo(java.math.BigInteger.ZERO);
				requireThat(counters.pageIns(), "pageIns").isEqualTo(java.math.BigInteger.ZERO);
				requireThat(counters.compressedPages(), "compressedPages").isEqualTo(java.math.BigInteger.ZERO);
			}
		}
	}

	/**
	 * Reports a nonzero command status with bounded stderr text and closes that capture.
	 *
	 * @throws IOException if the test context or fixture cannot be read
	 */
	@Test
	public void rejectsFailedCapture() throws IOException
	{
		try (DefaultToolingScope scope = new DefaultToolingScope(); TestContext fixture = openContext(scope))
		{
			String diagnostic = "x".repeat(5000);
			ScriptedCapture capture = new ScriptedCapture(new CaptureResult("", diagnostic, 17));
			try (DarwinQuietBuildResources reader = new DarwinQuietBuildResources(new TrackingNativeAccess(ticks()),
				capture, fixture.context()))
			{
				IOException failure = null;
				try
				{
					reader.read();
				}
				catch (IOException expected)
				{
					failure = expected;
				}
				requireThat(failure, "failure").isNotNull();
				requireThat(failure.getMessage(), "failureMessage").contains("sysctl");
				requireThat(failure.getMessage(), "failureMessage").contains("status 17");
				requireThat(failure.getMessage().length(), "failureMessageLength").isLessThanOrEqualTo(4200);
			}
			requireThat(capture.closedCount(), "closedCaptures").isEqualTo(1);
		}
	}

	/**
	 * Closes native access once and rejects reads after the reader closes.
	 *
	 * @throws IOException if closing native access fails
	 */
	@Test
	public void closesDarwinNativeAccess() throws IOException
	{
		try (DefaultToolingScope scope = new DefaultToolingScope(); TestContext fixture = openContext(scope))
		{
			try (TrackingNativeAccess nativeAccess = new TrackingNativeAccess(ticks());
				DarwinQuietBuildResources reader = new DarwinQuietBuildResources(nativeAccess,
					new ScriptedCapture(), fixture.context()))
			{
				reader.close();
				reader.close();
				requireThat(nativeAccess.closeCount(), "nativeAccessClosed").isEqualTo(1);
				IOException readFailure = null;
				try
				{
					reader.read();
				}
				catch (IllegalStateException expected)
				{
					readFailure = new IOException(expected.getMessage(), expected);
				}
				requireThat(readFailure, "readFailureAfterClose").isNotNull();
			}
		}
	}

	/** Loads the process-boundary context and gives each test an isolated temporary child. */
	public static final class TestContext implements AutoCloseable
	{
		/** Invocation context passed to the reader. */
		private final QuietBuildCapture.Context context;
		/** Temporary directory owned by this fixture. */
		private final Path temporaryDirectory;

		/**
		 * Creates a test context with its owned temporary directory.
		 *
		 * @param context invocation context
		 * @param temporaryDirectory fixture-owned temporary directory
		 */
		private TestContext(QuietBuildCapture.Context context, Path temporaryDirectory)
		{
			this.context = context;
			this.temporaryDirectory = temporaryDirectory;
		}

		/**
		 * Returns the invocation context used by the reader.
		 *
		 * @return test invocation context
		 */
		public QuietBuildCapture.Context context()
		{
			return context;
		}

		/** Removes this test's isolated temporary directory. */
		@Override
		public void close() throws IOException
		{
			Files.delete(temporaryDirectory);
		}
	}

	/**
	 * Loads the controlled process context and adds an owned temporary directory.
	 *
	 * @param scope mapper provider for the controlled test context
	 * @return test context with an owned temporary directory
	 * @throws IOException if the controlled context is missing or malformed
	 */
	public static TestContext openContext(ToolingScope scope) throws IOException
	{
		String contextPath = System.getProperty("quiet.build.test.context");
		if (contextPath == null)
			throw new IOException("Missing quiet.build.test.context system property");
		JsonNode document = scope.getJsonMapper().readTree(Files.readString(Path.of(contextPath)));
		if (!document.isObject() || document.size() != 4 || !document.has("format_version") ||
			!document.has("working_directory") || !document.has("temporary_directory") || !document.has("environment"))
		{
			throw new IOException("Test context must contain exactly the four version 1 fields");
		}
		if (document.get("format_version").intValue() != 1 || !document.get("working_directory").isTextual() ||
			!document.get("temporary_directory").isTextual() || !document.get("environment").isObject())
		{
			throw new IOException("Test context fields have invalid types or version");
		}
		Map<String, String> environment = new HashMap<>();
		for (Map.Entry<String, JsonNode> entry : document.get("environment").properties())
		{
			if (!entry.getValue().isTextual())
				throw new IOException("Test context environment values must be strings");
			environment.put(entry.getKey(), entry.getValue().asString());
		}
		Path baseTemporaryDirectory = Path.of(document.get("temporary_directory").asString());
		Path temporaryDirectory = Files.createTempDirectory(baseTemporaryDirectory, "darwin-reader-");
		environment.put("TMPDIR", temporaryDirectory.toString());
		environment.put("TMP", temporaryDirectory.toString());
		environment.put("TEMP", temporaryDirectory.toString());
		return new TestContext(new QuietBuildCapture.Context(Path.of(document.get("working_directory").asString()),
			temporaryDirectory, environment), temporaryDirectory);
	}

	/**
	 * Returns the fixture's representative Darwin CPU ticks.
	 *
	 * @return representative Darwin CPU ticks
	 */
	private static QuietBuildResources.DarwinCpuTicks ticks()
	{
		return new QuietBuildResources.DarwinCpuTicks(java.math.BigInteger.TEN, java.math.BigInteger.valueOf(20),
			java.math.BigInteger.valueOf(300), java.math.BigInteger.ONE);
	}

	/**
	 * Reads one bundled bounded text fixture.
	 *
	 * @param name fixture filename
	 * @return fixture text
	 * @throws IOException if the fixture is missing or unreadable
	 */
	private static String readFixture(String name) throws IOException
	{
		String path = "/com/holdmyspot/codexunleashed/tooling/test/darwin-resources/" + name;
		try (InputStream input = DarwinQuietBuildResourcesTest.class.getResourceAsStream(path))
		{
			if (input == null)
				throw new IOException("Missing Darwin fixture " + name);
			return new String(input.readAllBytes(), StandardCharsets.UTF_8);
		}
	}

	/**
	 * One fake process result.
	 *
	 * @param stdout fake standard output
	 * @param stderr fake standard error
	 * @param status fake process status
	 */
	private record CaptureResult(String stdout, String stderr, int status)
	{
	}

	/** Test-owned capture fake retaining requests for exact contract assertions. */
	private static final class ScriptedCapture implements QuietBuildCapture.Access
	{
		/** Results returned to successive capture requests. */
		private final List<CaptureResult> results;
		/** Commands received by the fake. */
		private final List<List<String>> commands = new ArrayList<>();
		/** Contexts received by the fake. */
		private final List<QuietBuildCapture.Context> contexts = new ArrayList<>();
		/** Timeouts received by the fake. */
		private final List<Optional<Duration>> timeouts = new ArrayList<>();
		/** Number of captures closed by the reader. */
		private int closedCount;

		/**
		 * Creates the fake with ordered process results.
		 *
		 * @param results results returned to subsequent capture requests
		 */
		private ScriptedCapture(CaptureResult... results)
		{
			this.results = List.of(results);
		}

		@Override
		public QuietBuildCapture.Captured capture(List<String> command, QuietBuildCapture.Context context,
			Optional<Duration> timeout) throws IOException
		{
			commands.add(List.copyOf(command));
			contexts.add(context);
			timeouts.add(timeout);
			if (commands.size() > results.size())
				throw new IOException("Unexpected additional capture request");
			CaptureResult result = results.get(commands.size() - 1);
			return new QuietBuildCapture.Captured()
			{
				@Override
				public int status()
				{
					return result.status();
				}

				@Override
				public java.io.Reader stdout()
			{
				return new StringReader(result.stdout());
				}

				@Override
				public java.io.Reader stderr()
				{
				return new StringReader(result.stderr());
				}

				@Override
				public void close()
				{
					closedCount += 1;
				}
			};
		}

		/**
		 * Returns an immutable copy of received commands.
		 *
		 * @return immutable copy of received commands
		 */
		private List<List<String>> commands()
		{
			return List.copyOf(commands);
		}

		/**
		 * Returns an immutable copy of received contexts.
		 *
		 * @return immutable copy of received contexts
		 */
		private List<QuietBuildCapture.Context> contexts()
		{
			return List.copyOf(contexts);
		}

		/**
		 * Returns an immutable copy of received timeouts.
		 *
		 * @return immutable copy of received timeouts
		 */
		private List<Optional<Duration>> timeouts()
		{
			return List.copyOf(timeouts);
		}

		/**
		 * Returns the number of captures closed by the reader.
		 *
		 * @return number of captures closed by the reader
		 */
		private int closedCount()
		{
			return closedCount;
		}
	}

	/** Test-owned native access fake returning known ticks and tracking release. */
	private static final class TrackingNativeAccess implements DarwinQuietBuildResources.NativeAccess
	{
		/** CPU ticks returned by the fake. */
		private final QuietBuildResources.DarwinCpuTicks ticks;
		/** Number of effective closes. */
		private int closeCount;
		/** Whether this fake has already closed. */
		private boolean closed;

		/**
		 * Creates native access returning fixed ticks.
		 *
		 * @param ticks known tick values
		 */
		private TrackingNativeAccess(QuietBuildResources.DarwinCpuTicks ticks)
		{
			this.ticks = ticks;
		}

		@Override
		public QuietBuildResources.DarwinCpuTicks readCpuTicks()
		{
			return ticks;
		}

		@Override
		public void close()
		{
			if (!closed)
			{
				closed = true;
				closeCount += 1;
			}
		}

		/**
		 * Returns the number of effective closes.
		 *
		 * @return number of effective closes
		 */
		private int closeCount()
		{
			return closeCount;
		}
	}
}
