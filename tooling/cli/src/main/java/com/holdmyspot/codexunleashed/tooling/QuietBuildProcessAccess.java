package com.holdmyspot.codexunleashed.tooling;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * Starts diagnostic child processes and owns their captured output and cleanup.
 */
public final class QuietBuildProcessAccess implements QuietBuildCapture.Access
{
	/**
	 * Prefix identifying the temporary directory holding one child's captured output.
	 */
	private static final String CAPTURE_DIRECTORY_PREFIX = "quiet-build-capture-";
	/**
	 * File containing one child's captured standard output.
	 */
	private static final String STDOUT_FILE_NAME = "stdout";
	/**
	 * File containing one child's captured standard error.
	 */
	private static final String STDERR_FILE_NAME = "stderr";
	/**
	 * Maximum time to wait for the direct child after forceful termination.
	 */
	private static final Duration CHILD_CLEANUP_TIMEOUT = Duration.ofSeconds(10);
	/**
	 * Number of nanoseconds in one second, used to preserve a Duration's exact wait bound.
	 */
	private static final long NANOSECONDS_PER_SECOND = 1_000_000_000L;

	/**
	 * Creates process access for quiet-build diagnostics.
	 */
	public QuietBuildProcessAccess()
	{
	}

	/**
	 * Runs a child with literal arguments and the supplied working directory and environment, including the Windows
	 * additions described by {@link QuietBuildCapture.Context}. The child inherits standard input; stdout and stderr
	 * are spooled separately until the returned capture is closed. A present timeout bounds the child's run and raises
	 * {@link CaptureTimeoutException} after the direct child is stopped and reaped.
	 *
	 * @param command executable and literal child arguments
	 * @param context child working directory, temporary directory and explicitly supplied variables
	 * @param timeout optional bound for the child's run
	 * @return completed child status and readers owned by the returned capture
	 * @throws NullPointerException     if an argument is null
	 * @throws IllegalArgumentException if the command is empty
	 * @throws CaptureTimeoutException  if the child exceeds the supplied timeout
	 * @throws IOException              if the child cannot start, waiting is interrupted, or capture cleanup fails
	 */
	@Override
	public QuietBuildCapture.Captured capture(List<String> command, QuietBuildCapture.Context context,
		Optional<Duration> timeout) throws IOException
	{
		List<String> arguments = List.copyOf(command);
		Objects.requireNonNull(context, "context");
		Objects.requireNonNull(timeout, "timeout");
		if (arguments.isEmpty())
			throw new IllegalArgumentException("An external command requires an executable");

		ProcessBuilder builder = SystemCommands.createBuilder(arguments).
			directory(context.workingDirectory().toFile()).redirectInput(ProcessBuilder.Redirect.INHERIT);
		Map<String, String> childEnvironment = context.environment();
		builder.environment().clear();
		builder.environment().putAll(childEnvironment);

		try (SpoolFiles spools = SpoolFiles.create(context.temporaryDirectory()))
		{
			builder.redirectOutput(spools.stdout().toFile()).redirectError(spools.stderr().toFile());
			try (Process process = builder.start())
			{
				int status = await(process, arguments, timeout);
				return spools.transfer(status);
			}
		}
	}

	/**
	 * Waits for the child and handles timeout or interruption before output ownership can be returned.
	 *
	 * @param process direct child process
	 * @param command executable and literal arguments
	 * @param timeout optional child run bound
	 * @return ordinary child exit status, including nonzero outcomes
	 * @throws IOException if waiting or child cleanup fails
	 */
	private static int await(Process process, List<String> command, Optional<Duration> timeout) throws IOException
	{
		try
		{
			if (timeout.isEmpty())
				return process.waitFor();

			Duration bound = timeout.orElseThrow();
			if (process.waitFor(timeoutNanos(bound), TimeUnit.NANOSECONDS))
				return process.exitValue();

			CaptureTimeoutException timeoutFailure = new CaptureTimeoutException(command.getFirst(), bound);
			try
			{
				stopAndReap(process);
			}
			catch (IOException cleanupFailure)
			{
				IOException failure = new IOException("Timed out command could not be fully stopped and reaped",
					timeoutFailure);
				failure.addSuppressed(cleanupFailure);
				throw failure;
			}
			throw timeoutFailure;
		}
		catch (InterruptedException interruption)
		{
			IOException failure = new IOException("Interrupted while waiting for external command " + command.getFirst(),
				interruption);
			try
			{
				stopAndReap(process);
			}
			catch (IOException cleanupFailure)
			{
				failure.addSuppressed(cleanupFailure);
			}
			Thread.currentThread().interrupt();
			throw failure;
		}
	}

	/**
	 * Converts a nonnegative duration to nanoseconds without overflow.
	 *
	 * @param duration exact wait bound
	 * @return nanoseconds, saturated only when the duration exceeds the wait API's range
	 */
	private static long timeoutNanos(Duration duration)
	{
		if (duration.isNegative())
			return 0;

		long seconds = duration.getSeconds();
		int additionalNanoseconds = duration.getNano();
		long maximumSeconds = Long.MAX_VALUE / NANOSECONDS_PER_SECOND;
		long remainingNanoseconds = Long.MAX_VALUE % NANOSECONDS_PER_SECOND;
		if (seconds > maximumSeconds ||
			(seconds == maximumSeconds && additionalNanoseconds > remainingNanoseconds))
			return Long.MAX_VALUE;
		return seconds * NANOSECONDS_PER_SECOND + additionalNanoseconds;
	}

	/**
	 * Forces the direct child to stop and waits a bounded time for the operating system to reap it.
	 *
	 * @param process direct child to stop
	 * @throws IOException if termination does not complete within the cleanup bound
	 */
	private static void stopAndReap(Process process) throws IOException
	{
		process.destroyForcibly();
		try
		{
			if (!process.waitFor(CHILD_CLEANUP_TIMEOUT.toNanos(), TimeUnit.NANOSECONDS))
				throw new IOException("Direct child " + process.pid() + " was not reaped within " +
					CHILD_CLEANUP_TIMEOUT);
		}
		catch (InterruptedException interruption)
		{
			Thread.currentThread().interrupt();
			throw new IOException("Interrupted while reaping direct child " + process.pid(), interruption);
		}
	}

	/**
	 * Reports a failed cleanup without losing the first failure.
	 *
	 * @param first first cleanup failure, or null if none occurred
	 * @param next additional cleanup failure
	 * @return the first failure with {@code next} suppressed, or {@code next} when no earlier failure exists
	 */
	private static IOException retain(IOException first, IOException next)
	{
		if (first == null)
			return next;
		first.addSuppressed(next);
		return first;
	}

	/**
	 * Owns the output files until their readers and the completed result take ownership.
	 */
	private static final class SpoolFiles implements AutoCloseable
	{
		/** Temporary directory containing this invocation's two output files. */
		private final Path directory;
		/** File containing this child's standard output. */
		private final Path stdout;
		/** File containing this child's standard error. */
		private final Path stderr;
		/** Whether the completed capture now owns the files. */
		private boolean transferred;

		/**
		 * Assigns output paths beneath a newly created temporary directory.
		 *
		 * @param directory temporary directory for this invocation
		 */
		private SpoolFiles(Path directory)
		{
			this.directory = directory;
			stdout = directory.resolve(STDOUT_FILE_NAME);
			stderr = directory.resolve(STDERR_FILE_NAME);
		}

		/**
		 * Creates the invocation's output directory and empty stream files.
		 *
		 * @param parent existing temporary directory from the child context
		 * @return owned stream files
		 * @throws IOException if the directory or either file cannot be created
		 */
		private static SpoolFiles create(Path parent) throws IOException
		{
			Path directory = Files.createTempDirectory(parent, CAPTURE_DIRECTORY_PREFIX);
			SpoolFiles spools = new SpoolFiles(directory);
			try
			{
				Files.createFile(spools.stdout);
				Files.createFile(spools.stderr);
				return spools;
			}
			catch (IOException | RuntimeException failure)
			{
				try
				{
					spools.deleteFiles();
				}
				catch (IOException cleanupFailure)
				{
					failure.addSuppressed(cleanupFailure);
				}
				throw failure;
			}
		}

		/**
		 * Returns this child's stdout file.
		 *
		 * @return stdout spool path
		 */
		private Path stdout()
		{
			return stdout;
		}

		/**
		 * Returns this child's stderr file.
		 *
		 * @return stderr spool path
		 */
		private Path stderr()
		{
			return stderr;
		}

		/**
		 * Transfers completed stream readers and their files to a result.
		 *
		 * @param status completed child exit status
		 * @return result that owns both readers and spool files
		 * @throws IOException if either reader cannot be opened
		 */
		private QuietBuildCapture.Captured transfer(int status) throws IOException
		{
			CapturedResult result = new CapturedResult(status, this);
			transferred = true;
			return result;
		}

		/**
		 * Removes both output files and their directory, retaining every cleanup failure.
		 *
		 * @throws IOException if one or more resources cannot be removed
		 */
		@Override
		public void close() throws IOException
		{
			if (!transferred)
				deleteFiles();
		}

		/**
		 * Removes owned files and their directory.
		 *
		 * @throws IOException if one or more resources cannot be removed
		 */
		private void deleteFiles() throws IOException
		{
			IOException failure = null;
			for (Path path : new Path[]{stdout, stderr, directory})
			{
				try
				{
					Files.deleteIfExists(path);
				}
				catch (IOException cleanupFailure)
				{
					failure = retain(failure, cleanupFailure);
				}
			}
			if (failure != null)
				throw failure;
		}
	}

	/**
	 * Completed status, readers and spool files owned by one child invocation.
	 */
	private static final class CapturedResult implements QuietBuildCapture.Captured
	{
		/** Child exit status, including an unsuccessful outcome. */
		private final int status;
		/** Reader for this child's stdout. */
		private final BufferedReader stdout;
		/** Reader for this child's stderr. */
		private final BufferedReader stderr;
		/** Output files to remove after closing both readers. */
		private final SpoolFiles spools;

		/**
		 * Opens both completed stream files for the caller.
		 *
		 * @param status completed child exit status
		 * @param spools output files created for the invocation
		 * @throws IOException if either stream cannot be opened
		 */
		private CapturedResult(int status, SpoolFiles spools) throws IOException
		{
			this.status = status;
			this.spools = spools;
			stdout = Files.newBufferedReader(spools.stdout(), StandardCharsets.UTF_8);
			try
			{
				stderr = Files.newBufferedReader(spools.stderr(), StandardCharsets.UTF_8);
			}
			catch (IOException failure)
			{
				try
				{
					stdout.close();
				}
				catch (IOException cleanupFailure)
				{
					failure.addSuppressed(cleanupFailure);
				}
				throw failure;
			}
		}

		/**
		 * Returns the child exit status.
		 *
		 * @return process exit status, including nonzero outcomes
		 */
		@Override
		public int status()
		{
			return status;
		}

		/**
		 * Returns the completed child's stdout reader.
		 *
		 * @return stdout reader, owned by this result
		 */
		@Override
		public Reader stdout()
		{
			return stdout;
		}

		/**
		 * Returns the completed child's stderr reader.
		 *
		 * @return stderr reader, owned by this result
		 */
		@Override
		public Reader stderr()
		{
			return stderr;
		}

		/**
		 * Closes both readers and deletes their spool files and directory.
		 *
		 * @throws IOException if one or more readers or files cannot be closed or deleted
		 */
		@Override
		public void close() throws IOException
		{
			IOException failure = null;
			try
			{
				stdout.close();
			}
			catch (IOException cleanupFailure)
			{
				failure = retain(failure, cleanupFailure);
			}
			try
			{
				stderr.close();
			}
			catch (IOException cleanupFailure)
			{
				failure = retain(failure, cleanupFailure);
			}
			try
			{
				spools.deleteFiles();
			}
			catch (IOException cleanupFailure)
			{
				failure = retain(failure, cleanupFailure);
			}
			if (failure != null)
				throw failure;
		}
	}

	/**
	 * Identifies a child that remained active beyond its supplied run bound.
	 */
	public static final class CaptureTimeoutException extends IOException
	{
		/**
		 * Serialization identifier required by {@link IOException}.
		 */
		private static final long serialVersionUID = 1L;

		/**
		 * Describes the child and duration that exceeded the run bound.
		 *
		 * @param executable child executable name
		 * @param timeout run bound exceeded
		 */
		private CaptureTimeoutException(String executable, Duration timeout)
		{
			super("External command " + executable + " exceeded timeout " + timeout);
		}
	}
}
