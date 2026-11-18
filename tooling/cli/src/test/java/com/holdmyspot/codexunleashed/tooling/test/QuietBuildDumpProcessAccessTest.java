package com.holdmyspot.codexunleashed.tooling.test;

import com.holdmyspot.codexunleashed.tooling.DefaultToolingScope;
import com.holdmyspot.codexunleashed.tooling.QuietBuildCapture;
import com.holdmyspot.codexunleashed.tooling.QuietBuildDumpProcess;
import com.holdmyspot.codexunleashed.tooling.QuietBuildDumpProcessAccess;
import com.holdmyspot.codexunleashed.tooling.QuietBuildReport;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.StringWriter;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.testng.annotations.Test;
import tools.jackson.databind.JsonNode;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

/**
 * Verifies report streaming through the diagnostic process-start boundary.
 */
public final class QuietBuildDumpProcessAccessTest
{
	/**
	 * Creates process-access tests; each test owns its temporary context directory.
	 */
	public QuietBuildDumpProcessAccessTest()
	{
	}

	/**
	 * The process access writes merged child output to the report before returning.
	 *
	 * @throws IOException if test context setup or cleanup fails
	 */
	@Test
	public void streamsMergedReport() throws IOException
	{
		TestContext fixture = testContext();
		try
		{
			String expected = "stdout\n" + "x".repeat(16_384) + "stderr\n";
			try (ControlledProcess child = new ControlledProcess(expected, 0);
				TrackingWriter output = new TrackingWriter())
			{
				QuietBuildDumpProcess access = new QuietBuildDumpProcessAccess((command, context) -> child);
				int status = access.run(List.of("diagnostic-tool"), fixture.context(), output, Duration.ofSeconds(2));

				requireThat(status, "status").isEqualTo(0);
				requireThat(output.toString(), "mergedReport").isEqualTo(expected);
				requireThat(output.flushCount(), "outputFlushCount").isGreaterThan(1);
			}
		}
		finally
		{
			deleteTree(fixture.temporaryDirectory());
		}
	}

	/**
	 * A timed-out child leaves its already streamed report prefix and is stopped and reaped.
	 *
	 * @throws IOException if test context setup or cleanup fails
	 */
	@Test
	public void keepsPartialTextOnTimeout() throws IOException
	{
		TestContext fixture = testContext();
		CountDownLatch partialOutputFlushed = new CountDownLatch(1);
		try
		{
			try (ControlledProcess child = new ControlledProcess("partial diagnostic\n", 0, true,
				partialOutputFlushed);
				TrackingWriter output = new TrackingWriter(partialOutputFlushed))
			{
				QuietBuildDumpProcessAccess access = new QuietBuildDumpProcessAccess((command, context) -> child);
				QuietBuildReport.DumpTimeoutException failure = null;
				try
				{
					access.run(List.of("diagnostic-tool"), fixture.context(), output, Duration.ZERO);
				}
				catch (QuietBuildReport.DumpTimeoutException timeout)
				{
					failure = timeout;
				}

				requireThat(failure, "timeout").isNotNull();
				requireThat(output.toString(), "partialReport").isEqualTo("partial diagnostic\n");
				requireThat(child.isAlive(), "childAlive").isFalse();
				requireThat(child.inputClosed(), "childOutputClosed").isTrue();
			}
		}
		finally
		{
			deleteTree(fixture.temporaryDirectory());
		}
	}

	/**
	 * A completed child returns its ordinary nonzero exit status.
	 *
	 * @throws IOException if test context setup or cleanup fails
	 */
	@Test
	public void preservesChildExitStatus() throws IOException
	{
		TestContext fixture = testContext();
		try
		{
			try (ControlledProcess child = new ControlledProcess("diagnostic\n", 23))
			{
				int status = new QuietBuildDumpProcessAccess((command, context) -> child).run(List.of("tool"),
					fixture.context(), new StringWriter(), Duration.ofSeconds(2));
				requireThat(status, "status").isEqualTo(23);
			}
		}
		finally
		{
			deleteTree(fixture.temporaryDirectory());
		}
	}

	/**
	 * Finishing the process run leaves the caller's report writer open.
	 *
	 * @throws IOException if test context setup or cleanup fails
	 */
	@Test
	public void keepsBorrowedWriterOpen() throws IOException
	{
		TestContext fixture = testContext();
		try
		{
			try (ControlledProcess child = new ControlledProcess("diagnostic\n", 0);
				TrackingWriter output = new TrackingWriter())
			{
				new QuietBuildDumpProcessAccess((command, context) -> child).run(List.of("tool"), fixture.context(),
					output, Duration.ofSeconds(2));
				requireThat(output.closed(), "writerClosed").isFalse();
			}
		}
		finally
		{
			deleteTree(fixture.temporaryDirectory());
		}
	}

	/**
	 * A report-writer failure stops and reaps the direct child and closes its owned output stream.
	 *
	 * @throws IOException if test context setup or cleanup fails
	 */
	@Test
	public void cleansUpAfterWriteFailure() throws IOException
	{
		TestContext fixture = testContext();
		try
		{
			try (ControlledProcess child = new ControlledProcess("diagnostic\n", 0);
				Writer output = new Writer()
			{
				@Override
				public void write(char[] buffer, int offset, int length) throws IOException
				{
					throw new IOException("report writer failed");
				}

				@Override
				public void flush()
				{
				}

				@Override
				public void close()
				{
				}
			})
			{
				IOException failure = null;
				try
				{
					new QuietBuildDumpProcessAccess((command, context) -> child).run(List.of("tool"), fixture.context(),
						output, Duration.ofSeconds(2));
				}
				catch (IOException writeFailure)
				{
					failure = writeFailure;
				}

				requireThat(failure, "writeFailure").isNotNull();
				requireThat(failure.getMessage(), "writeFailureMessage").isEqualTo("report writer failed");
				requireThat(child.isAlive(), "childAlive").isFalse();
				requireThat(child.inputClosed(), "childOutputClosed").isTrue();
			}
		}
		finally
		{
			deleteTree(fixture.temporaryDirectory());
		}
	}

	/**
	 * The starter receives the literal command and complete caller-supplied child context.
	 *
	 * @throws IOException if test context setup or cleanup fails
	 */
	@Test
	public void passesCompleteChildContext() throws IOException
	{
		TestContext fixture = testContext();
		try
		{
			List<String> command = List.of("tool with spaces", "$literal", "quote\"value");
			try (ControlledProcess child = new ControlledProcess("", 0))
			{
				QuietBuildDumpProcessAccess access = new QuietBuildDumpProcessAccess((startedCommand, startedContext) ->
				{
					requireThat(startedCommand, "startedCommand").isEqualTo(command);
					requireThat(startedContext, "startedContext").isEqualTo(fixture.context());
					return child;
				});
				access.run(command, fixture.context(), new StringWriter(), Duration.ofSeconds(2));
				requireThat(child.isAlive(), "childAlive").isFalse();
			}
		}
		finally
		{
			deleteTree(fixture.temporaryDirectory());
		}
	}

	/**
	 * The real process starter delivers both output streams and the child's ordinary exit status.
	 *
	 * @throws IOException if the child cannot start, its report cannot be read, or test cleanup fails
	 */
	@Test
	public void runsRealReportChild() throws IOException
	{
		TestContext fixture = testContext();
		try
		{
			String executableName = "java";
			if (System.getProperty("os.name").startsWith("Windows"))
				executableName = "java.exe";
			Path java = Path.of(System.getProperty("java.home"), "bin", executableName);
			Path contextFile = Path.of(System.getProperty("quiet.build.test.context"));
			Path classes = contextFile.getParent().resolve("build/test-classes");
			List<String> command = List.of(java.toString(), "--class-path", classes.toString(),
				QuietBuildDumpChild.class.getName(), "7");
			StringWriter output = new StringWriter();
			int status = new QuietBuildDumpProcessAccess().run(command, fixture.context(), output, Duration.ofSeconds(30));
			requireThat(status, "status").isEqualTo(7);
			requireThat(output.toString(), "childOutput").isEqualTo("stdout" + System.lineSeparator() +
				"stderr" + System.lineSeparator());
		}
		finally
		{
			deleteTree(fixture.temporaryDirectory());
		}
	}

	/**
	 * Reads the explicit build context and creates this test's private temporary child and environment.
	 *
	 * @return owned test context and temporary directory
	 * @throws IOException if the build context cannot be read or the temporary child cannot be created
	 */
	private static TestContext testContext() throws IOException
	{
		Path contextFile = Path.of(System.getProperty("quiet.build.test.context"));
		try (DefaultToolingScope scope = new DefaultToolingScope();
			BufferedReader reader = Files.newBufferedReader(contextFile))
		{
			JsonNode contextNode = scope.getJsonMapper().readTree(reader);
			Path workingDirectory = Path.of(contextNode.get("working_directory").textValue());
			Path temporaryRoot = Path.of(contextNode.get("temporary_directory").textValue());
			Path temporaryDirectory = Files.createTempDirectory(temporaryRoot, "dump-stream-test-");
			Map<String, String> environment = new HashMap<>();
			for (Map.Entry<String, JsonNode> entry : contextNode.get("environment").properties())
				environment.put(entry.getKey(), entry.getValue().textValue());
			environment.put("TMPDIR", temporaryDirectory.toString());
			environment.put("TMP", temporaryDirectory.toString());
			environment.put("TEMP", temporaryDirectory.toString());
			return new TestContext(temporaryDirectory,
				new QuietBuildCapture.Context(workingDirectory, temporaryDirectory, environment));
		}
	}

	/**
	 * Removes only a temporary directory created by this test.
	 *
	 * @param root test-owned directory
	 * @throws IOException if test files cannot be deleted
	 */
	private static void deleteTree(Path root) throws IOException
	{
		try (Stream<Path> paths = Files.walk(root))
		{
			for (Path path : paths.sorted(Comparator.reverseOrder()).toList())
				Files.delete(path);
		}
	}

	/**
	 * Carries the caller context and temporary directory owned by one test.
	 *
	 * @param temporaryDirectory test-owned temporary directory
	 * @param context child process context with that directory's environment
	 */
	private record TestContext(Path temporaryDirectory, QuietBuildCapture.Context context)
	{
	}

	/**
	 * Controls process output, status, timeout and cleanup for one test without starting an external child.
	 */
	private static final class ControlledProcess extends Process
	{
		/** Child stdout and stderr represented as one merged stream. */
		private final TrackingInputStream input;
		/** Result returned by the completed child. */
		private final int status;
		/** Whether the test makes the child exceed its timeout. */
		private final boolean timesOut;
		/** Signal that output is visible before the simulated timeout is reported. */
		private final CountDownLatch outputFlushed;
		/** Whether the represented child is still running. */
		private boolean alive = true;
		/** Writable stream supplied to the child. */
		private final ByteArrayOutputStream childInput = new ByteArrayOutputStream();

		/**
		 * Creates a completed controlled child.
		 *
		 * @param mergedOutput child stdout and stderr as one stream
		 * @param status child exit status
		 */
		private ControlledProcess(String mergedOutput, int status)
		{
			this(mergedOutput, status, false, null);
		}

		/**
		 * Creates a controlled child with an optional timeout result.
		 *
		 * @param mergedOutput child stdout and stderr as one stream
		 * @param status child exit status
		 * @param timesOut whether timed waiting reports a timeout
		 */
		private ControlledProcess(String mergedOutput, int status, boolean timesOut)
		{
			this(mergedOutput, status, timesOut, null);
		}

		/**
		 * Creates a controlled child with an optional timeout result and output-flush prerequisite.
		 *
		 * @param mergedOutput child stdout and stderr as one stream
		 * @param status child exit status
		 * @param timesOut whether timed waiting reports a timeout
		 * @param outputFlushed signal that must be observed before reporting the simulated timeout
		 */
		private ControlledProcess(String mergedOutput, int status, boolean timesOut, CountDownLatch outputFlushed)
		{
			this.input = new TrackingInputStream(mergedOutput);
			this.status = status;
			this.timesOut = timesOut;
			this.outputFlushed = outputFlushed;
		}

		@Override
		public OutputStream getOutputStream()
		{
			return childInput;
		}

		@Override
		public InputStream getInputStream()
		{
			return input;
		}

		@Override
		public InputStream getErrorStream()
		{
			return InputStream.nullInputStream();
		}

		@Override
		public int waitFor()
		{
			alive = false;
			return status;
		}

		@Override
		public boolean waitFor(long timeout, TimeUnit unit) throws InterruptedException
		{
			if (alive && timesOut)
			{
				if (outputFlushed == null)
					throw new IllegalStateException("Timed-out controlled child requires an output-flush signal");
				if (!outputFlushed.await(5, TimeUnit.SECONDS))
					throw new IllegalStateException("Timed out waiting for controlled output to flush before child timeout");
				return false;
			}
			alive = false;
			return true;
		}

		@Override
		public int exitValue()
		{
			if (alive)
				throw new IllegalThreadStateException("Child is still running");
			return status;
		}

		@Override
		public void destroy()
		{
			alive = false;
		}

		@Override
		public Process destroyForcibly()
		{
			alive = false;
			return this;
		}

		@Override
		public boolean isAlive()
		{
			return alive;
		}

		/**
		 * Indicates whether the process access closed the merged child output stream.
		 *
		 * @return {@code true} if the stream was closed
		 */
		private boolean inputClosed()
		{
			return input.closed();
		}
	}

	/**
	 * Tracks closure of the controlled child's merged stdout and stderr stream.
	 */
	private static final class TrackingInputStream extends ByteArrayInputStream
	{
		/** Indicates whether process cleanup closed this stream. */
		private boolean closed;

		/**
		 * Creates child output bytes from its UTF-8 text.
		 *
		 * @param output child output
		 */
		private TrackingInputStream(String output)
		{
			super(output.getBytes(java.nio.charset.StandardCharsets.UTF_8));
		}

		@Override
		public void close() throws IOException
		{
			closed = true;
			super.close();
		}

		/**
		 * Indicates whether the stream is closed.
		 *
		 * @return {@code true} if closed
		 */
		private boolean closed()
		{
			return closed;
		}
	}

	/**
	 * Tracks whether report output was closed by the process access.
	 */
	private static final class TrackingWriter extends StringWriter
	{
		/** Optional signal for a test that requires flushed output before simulating timeout. */
		private final CountDownLatch outputFlushed;
		/** Indicates whether the process access closed this borrowed writer. */
		private boolean closed;
		/** Number of times the process access flushed this borrowed writer. */
		private int flushCount;

		/**
		 * Creates a report writer that records flush and close calls.
		 */
		private TrackingWriter()
		{
			this(null);
		}

		/**
		 * Creates a report writer that records flush and close calls.
		 *
		 * @param outputFlushed optional signal to release after output reaches this writer
		 */
		private TrackingWriter(CountDownLatch outputFlushed)
		{
			this.outputFlushed = outputFlushed;
		}

		@Override
		public void flush()
		{
			flushCount += 1;
			if (outputFlushed != null)
				outputFlushed.countDown();
		}

		@Override
		public void close()
		{
			closed = true;
		}

		/**
		 * Indicates whether this report writer was closed.
		 *
		 * @return {@code true} if the process access closed it
		 */
		private boolean closed()
		{
			return closed;
		}

		/**
		 * Returns the number of output chunks flushed by the process access.
		 *
		 * @return flush count
		 */
		private int flushCount()
		{
			return flushCount;
		}
	}
}
