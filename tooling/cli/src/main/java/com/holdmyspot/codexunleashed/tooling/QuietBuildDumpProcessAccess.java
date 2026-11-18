package com.holdmyspot.codexunleashed.tooling;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static com.holdmyspot.codexunleashed.tooling.QuietBuildReport.DumpTimeoutException;

/**
 * Starts diagnostic children through a replaceable process-start boundary.
 */
public final class QuietBuildDumpProcessAccess implements QuietBuildDumpProcess
{
	/**
	 * Character count read and written for each output copy operation, as required by the dump streaming contract.
	 */
	private static final int OUTPUT_CHUNK_SIZE = 8192;

	/**
	 * Maximum time allowed to stop and reap a direct child after a timeout or another copy failure.
	 */
	private static final Duration CHILD_CLEANUP_TIMEOUT = Duration.ofSeconds(10);

	/**
	 * Starts a child using the supplied literal command and invocation context.
	 */
	@FunctionalInterface
	public interface Starter
	{
		/**
		 * Starts the requested child.
		 *
		 * @param command executable and literal arguments
		 * @param context child invocation paths and explicitly supplied variables
		 * @return started child owned by the process access
		 * @throws IOException if the child cannot be started
		 */
		Process start(List<String> command, QuietBuildCapture.Context context) throws IOException;
	}

	/**
	 * Process-start operation used for each run.
	 */
	private final Starter starter;

	/**
	 * Creates access that starts real system processes.
	 */
	public QuietBuildDumpProcessAccess()
	{
		this(QuietBuildDumpProcessAccess::startSystemProcess);
	}

	/**
	 * Creates access that uses the supplied process-start operation.
	 *
	 * @param starter operation that starts and transfers ownership of the child
	 * @throws NullPointerException if {@code starter} is null
	 */
	public QuietBuildDumpProcessAccess(Starter starter)
	{
		this.starter = Objects.requireNonNull(starter, "starter");
	}

	/**
	 * Runs the command, streams its output, and returns its ordinary exit status.
	 *
	 * @param command executable and literal arguments
	 * @param context child invocation paths and explicitly supplied variables
	 * @param output  borrowed report writer that remains open after this call
	 * @param timeout maximum time allowed for the child
	 * @return the child's ordinary exit status, including nonzero values
	 * @throws NullPointerException                  if an argument or command element is null
	 * @throws IllegalArgumentException              if {@code command} is empty or {@code timeout} is negative
	 * @throws QuietBuildReport.DumpTimeoutException if the child exceeds {@code timeout}
	 * @throws IOException                           if starting the child, streaming its output, waiting, or cleanup
	 *                                               fails
	 */
	@Override
	public int run(List<String> command, QuietBuildCapture.Context context, Writer output, Duration timeout)
		throws IOException
	{
		List<String> arguments = List.copyOf(command);
		Objects.requireNonNull(context, "context");
		Objects.requireNonNull(output, "output");
		Objects.requireNonNull(timeout, "timeout");
		if (arguments.isEmpty())
			throw new IllegalArgumentException("A diagnostic process requires an executable");
		if (timeout.isNegative())
			throw new IllegalArgumentException("A diagnostic process timeout must not be negative");

		Process child = Objects.requireNonNull(starter.start(arguments, context), "starter returned no process");
		try (child; Reader reader = new InputStreamReader(child.getInputStream(), StandardCharsets.UTF_8.newDecoder().
			onMalformedInput(CodingErrorAction.REPLACE).
			onUnmappableCharacter(CodingErrorAction.REPLACE)))
		{
			return copyUntilFinished(child, reader, output, timeout);
		}
	}

	/**
	 * Coordinates the direct child and its output copier through completion, timeout or failure.
	 *
	 * @param child direct child owned by this operation
	 * @param reader decoded child output owned by this operation
	 * @param output borrowed report writer
	 * @param timeout maximum time allowed for the child
	 * @return the child's ordinary exit status
	 * @throws IOException if waiting, copying, or cleanup fails
	 */
	private static int copyUntilFinished(Process child, Reader reader, Writer output, Duration timeout)
		throws IOException
	{
		AtomicReference<Exception> copyFailure = new AtomicReference<>();
		Thread copier = new Thread(() -> copyOutput(reader, output, child, copyFailure),
			"quiet-build-dump-output");
		try
		{
			copier.start();
		}
		catch (RuntimeException startFailure)
		{
			IOException cleanupFailure = stopAndReap(child);
			if (cleanupFailure != null)
				startFailure.addSuppressed(cleanupFailure);
			if (Thread.interrupted())
				Thread.currentThread().interrupt();
			throw startFailure;
		}

		Throwable primaryFailure = null;
		boolean interrupted = false;
		int status = -1;
		try
		{
			boolean childFinished = child.waitFor(timeoutNanos(timeout), TimeUnit.NANOSECONDS);
			if (childFinished)
				status = child.exitValue();
			else
				primaryFailure = new DumpTimeoutException("Diagnostic child exceeded timeout " + timeout);
		}
		catch (InterruptedException waitFailure)
		{
			interrupted = true;
			primaryFailure = new IOException("Interrupted while waiting for diagnostic child", waitFailure);
		}
		catch (RuntimeException waitFailure)
		{
			primaryFailure = waitFailure;
		}

		Exception outputFailure = copyFailure.get();
		if (primaryFailure == null && outputFailure != null)
			primaryFailure = outputFailure;
		if (primaryFailure != null)
		{
			IOException cleanupFailure = stopAndReap(child);
			if (cleanupFailure != null)
				primaryFailure.addSuppressed(cleanupFailure);
			if (Thread.interrupted())
				interrupted = true;
			try
			{
				reader.close();
			}
			catch (IOException closeFailure)
			{
				primaryFailure.addSuppressed(closeFailure);
			}
			interrupted |= joinCopier(copier);
			outputFailure = copyFailure.get();
			if (outputFailure != null && outputFailure != primaryFailure)
				primaryFailure.addSuppressed(outputFailure);
		}
		else
		{
			interrupted = joinCopier(copier);
			if (interrupted)
				primaryFailure = new IOException("Interrupted while waiting for diagnostic output");
			outputFailure = copyFailure.get();
			if (primaryFailure == null && outputFailure != null)
			{
				primaryFailure = outputFailure;
				IOException cleanupFailure = stopAndReap(child);
				if (cleanupFailure != null)
					primaryFailure.addSuppressed(cleanupFailure);
				if (Thread.interrupted())
					interrupted = true;
			}
			if (primaryFailure != null)
			{
				try
				{
					reader.close();
				}
				catch (IOException closeFailure)
				{
					primaryFailure.addSuppressed(closeFailure);
				}
			}
			else
			{
				try
				{
					reader.close();
				}
				catch (IOException closeFailure)
				{
					primaryFailure = closeFailure;
				}
			}
		}

		if (interrupted)
			Thread.currentThread().interrupt();
		if (primaryFailure instanceof IOException ioFailure)
			throw ioFailure;
		if (primaryFailure instanceof RuntimeException runtimeFailure)
			throw runtimeFailure;
		if (primaryFailure != null)
			throw new IOException("Diagnostic child output failed", primaryFailure);
		return status;
	}

	/**
	 * Copies decoded child output in bounded chunks and flushes each chunk to the report writer.
	 *
	 * @param input child stdout merged with stderr
	 * @param output borrowed report writer
	 * @param child direct child whose output is being consumed
	 * @param failure destination for a read or write failure
	 */
	private static void copyOutput(Reader input, Writer output, Process child, AtomicReference<Exception> failure)
	{
		try
		{
			char[] chunk = new char[OUTPUT_CHUNK_SIZE];
			int count = input.read(chunk);
			while (count != -1)
			{
				output.write(chunk, 0, count);
				output.flush();
				count = input.read(chunk);
			}
		}
		catch (IOException | RuntimeException copyFailure)
		{
			failure.compareAndSet(null, copyFailure);
			try
			{
				child.destroyForcibly();
			}
			catch (RuntimeException destroyFailure)
			{
				copyFailure.addSuppressed(destroyFailure);
			}
		}
	}

	/**
	 * Stops and reaps the direct child within the configured cleanup bound.
	 *
	 * @param child direct child owned by this operation
	 * @return cleanup failure, or null when the child was reaped
	 */
	private static IOException stopAndReap(Process child)
	{
		try
		{
			child.destroyForcibly();
			if (!child.waitFor(CHILD_CLEANUP_TIMEOUT.toNanos(), TimeUnit.NANOSECONDS))
				return new IOException("Diagnostic child did not exit within " + CHILD_CLEANUP_TIMEOUT);
			return null;
		}
		catch (InterruptedException cleanupFailure)
		{
			Thread.currentThread().interrupt();
			return new IOException("Interrupted while stopping diagnostic child", cleanupFailure);
		}
		catch (RuntimeException cleanupFailure)
		{
			return new IOException("Could not stop diagnostic child", cleanupFailure);
		}
	}

	/**
	 * Waits for the output copier to finish, recording interruption for restoration after cleanup.
	 *
	 * @param copier thread consuming the child output
	 * @return whether waiting was interrupted
	 */
	private static boolean joinCopier(Thread copier)
	{
		boolean interrupted = false;
		while (copier.isAlive())
		{
			try
			{
				copier.join();
			}
			catch (InterruptedException _)
			{
				interrupted = true;
			}
		}
		return interrupted;
	}

	/**
	 * Converts the nonnegative process timeout to the largest nanosecond wait supported by Process.
	 *
	 * @param timeout nonnegative maximum child duration
	 * @return timeout in nanoseconds, saturated at {@link Long#MAX_VALUE}
	 */
	private static long timeoutNanos(Duration timeout)
	{
		try
		{
			return timeout.toNanos();
		}
		catch (ArithmeticException _)
		{
			return Long.MAX_VALUE;
		}
	}

	/**
	 * Starts a child with the supplied context and merged output.
	 *
	 * @param command executable and literal arguments
	 * @param context child invocation paths and explicitly supplied variables
	 * @return started child process
	 * @throws IOException if process startup fails
	 */
	private static Process startSystemProcess(List<String> command, QuietBuildCapture.Context context)
		throws IOException
	{
		ProcessBuilder builder = SystemCommands.createBuilder(command).directory(context.workingDirectory().toFile()).
			redirectInput(ProcessBuilder.Redirect.INHERIT).redirectErrorStream(true);
		builder.environment().clear();
		builder.environment().putAll(context.environment());
		return builder.start();
	}
}
