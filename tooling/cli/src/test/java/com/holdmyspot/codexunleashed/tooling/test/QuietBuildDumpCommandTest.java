package com.holdmyspot.codexunleashed.tooling.test;

import com.holdmyspot.codexunleashed.tooling.QuietBuildDumpCommand;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.testng.annotations.Test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

/**
 * Checks direct quiet-build dump option handling and report output ownership.
 */
public final class QuietBuildDumpCommandTest
{
	/**
	 * Creates independent direct dump command checks.
	 */
	public QuietBuildDumpCommandTest()
	{
	}

	/**
	 * Writes UTF-8 report output, flushes it, and leaves caller output open for later writes.
	 *
	 * @throws IOException if report writing fails
	 */
	@Test
	public void writesAndFlushesWithoutClosingOutput() throws IOException
	{
		TrackingOutput outputBytes = new TrackingOutput();
		try (PrintStream output = new PrintStream(outputBytes, false, StandardCharsets.UTF_8))
		{
			int[] dumpCalls = {0};
			int status = QuietBuildDumpCommand.run(new String[]{"--dump"}, output, (jcmd, writer) ->
			{
				dumpCalls[0] += 1;
				requireThat(jcmd, "jcmd").isNull();
				writer.write("thread dump λ");
			});

			requireThat(status, "status").isEqualTo(0);
			requireThat(dumpCalls[0], "dumpCalls").isEqualTo(1);
			requireThat(outputBytes.toString(StandardCharsets.UTF_8), "report").isEqualTo("thread dump λ");
			requireThat(outputBytes.flushCalls, "flushCalls").isGreaterThan(0);
			requireThat(outputBytes.closed, "closed").isFalse();
			output.print("later");
			output.flush();
			requireThat(outputBytes.toString(StandardCharsets.UTF_8), "report").isEqualTo("thread dump λlater");
		}
	}

	/**
	 * Accepts either option order, duplicate dump flags, and the last JVM diagnostic path verbatim.
	 *
	 * @throws IOException if the temporary path fixture cannot be created or removed
	 */
	@Test
	public void decodesOptionOrdersAndKeepsLastPath() throws IOException
	{
		Path fixture = Files.createTempDirectory("quiet build dump command ");
		try
		{
			Path first = fixture.resolve("jcmd path with 'quotes'");
			Path last = fixture.resolve("second jcmd 'path'");
			List<Path> receivedPaths = new ArrayList<>();
			ByteArrayOutputStream firstBytes = new ByteArrayOutputStream();
			try (PrintStream output = new PrintStream(firstBytes, false, StandardCharsets.UTF_8))
			{
				int status = QuietBuildDumpCommand.run(new String[]{"--jcmd", first.toString(), "--dump"}, output,
					(jcmd, writer) -> receivedPaths.add(jcmd));
				requireThat(status, "status").isEqualTo(0);
			}
			ByteArrayOutputStream secondBytes = new ByteArrayOutputStream();
			try (PrintStream output = new PrintStream(secondBytes, false, StandardCharsets.UTF_8))
			{
				int status = QuietBuildDumpCommand.run(new String[]{"--dump", "--jcmd", first.toString(), "--dump", "--jcmd",
					last.toString()}, output, (jcmd, writer) -> receivedPaths.add(jcmd));
				requireThat(status, "status").isEqualTo(0);
			}

			requireThat(receivedPaths, "receivedPaths").isEqualTo(List.of(first, last));
			requireThat(firstBytes.size(), "firstOutputBytes").isEqualTo(0);
			requireThat(secondBytes.size(), "secondOutputBytes").isEqualTo(0);
		}
		finally
		{
			Files.delete(fixture);
		}
	}

	/**
	 * Rejects a request that omits the required dump flag before callback or output.
	 */
	@Test(expectedExceptions = IllegalArgumentException.class,
		expectedExceptionsMessageRegExp = ".*--dump.*")
	public void requiresDumpFlag()
	{
		rejectsBeforeOutput(new String[0]);
	}

	/**
	 * Rejects a JVM diagnostic option without its path before callback or output.
	 */
	@Test(expectedExceptions = IllegalArgumentException.class,
		expectedExceptionsMessageRegExp = ".*--jcmd.*PATH.*")
	public void requiresJcmdPath()
	{
		rejectsBeforeOutput(new String[]{"--dump", "--jcmd"});
	}

	/**
	 * Rejects unsupported options before callback or output.
	 */
	@Test(expectedExceptions = IllegalArgumentException.class,
		expectedExceptionsMessageRegExp = ".*--unsupported.*")
	public void rejectsUnknownOptions()
	{
		rejectsBeforeOutput(new String[]{"--dump", "--unsupported"});
	}

	/**
	 * Rejects positional arguments before callback or output.
	 */
	@Test(expectedExceptions = IllegalArgumentException.class,
		expectedExceptionsMessageRegExp = ".*unexpected.*")
	public void rejectsPositionalArguments()
	{
		rejectsBeforeOutput(new String[]{"--dump", "unexpected"});
	}

	/**
	 * Propagates report-writer failures from the selected dump callback.
	 *
	 * @throws IOException the scripted report failure
	 */
	@Test(expectedExceptions = IOException.class, expectedExceptionsMessageRegExp = "report failed")
	public void propagatesDumpFailure() throws IOException
	{
		try (PrintStream output = new PrintStream(new ByteArrayOutputStream(), false, StandardCharsets.UTF_8))
		{
			QuietBuildDumpCommand.run(new String[]{"--dump"}, output,
				(jcmd, writer) ->
				{
					throw new IOException("report failed");
				});
		}
	}

	/**
	 * Runs an invalid request with collaborators that fail if validation reaches either one.
	 *
	 * @param arguments invalid direct dump options
	 */
	private static void rejectsBeforeOutput(String[] arguments)
	{
		try (PrintStream output = new PrintStream(new RejectingOutput(), false, StandardCharsets.UTF_8))
		{
			try
			{
				QuietBuildDumpCommand.run(arguments, output, (jcmd, writer) ->
				{
					throw new AssertionError("Invalid arguments reached the dump callback");
				});
			}
			catch (IOException failure)
			{
				throw new AssertionError("Invalid arguments reached report output", failure);
			}
		}
	}

	/**
	 * Counts caller-output flushes and records whether a callee closed the supplied stream.
	 */
	private static final class TrackingOutput extends ByteArrayOutputStream
	{
		/** Number of flush requests received from the dump command. */
		private int flushCalls;
		/** Indicates whether the caller-owned stream was closed by the dump command. */
		private boolean closed;

		/**
		 * Creates empty tracked report output.
		 */
		private TrackingOutput()
		{
		}

		@Override
		public void flush() throws IOException
		{
			flushCalls += 1;
			super.flush();
		}

		@Override
		public void close() throws IOException
		{
			closed = true;
			super.close();
		}
	}

	/**
	 * Fails if argument validation attempts to emit any report bytes.
	 */
	private static final class RejectingOutput extends OutputStream
	{
		/**
		 * Creates an output stream that rejects report writes.
		 */
		private RejectingOutput()
		{
		}

		@Override
		public void write(int value)
		{
			throw new AssertionError("Invalid arguments wrote report output");
		}
	}
}
