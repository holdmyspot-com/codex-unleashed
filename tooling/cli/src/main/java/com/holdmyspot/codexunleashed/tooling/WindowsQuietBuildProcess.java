package com.holdmyspot.codexunleashed.tooling;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * Owns a Windows build root and the literal taskkill tree operation used by {@link QuietBuildLauncher}.
 * A completed root alone does not establish whether its descendants still exist.
 */
public final class WindowsQuietBuildProcess implements QuietBuildProcess
{
	/**
	 * Maximum taskkill execution time.
	 */
	private static final Duration TASKKILL_TIMEOUT = Duration.ofSeconds(120);
	/**
	 * Maximum root or forced helper termination wait.
	 */
	private static final Duration REAP_TIMEOUT = Duration.ofSeconds(10);
	/**
	 * Preserves the full DWORD returned by the JDK process boundary.
	 *
	 * @param code signed Java representation of the DWORD
	 * @return completion retaining all exit bits
	 */
	public static WindowsExit decodeExit(int code)
	{
		return new WindowsExit(Integer.toUnsignedLong(code));
	}

	/**
	 * Retains taskkill's failed status and both diagnostic streams without claiming tree disappearance.
	 *
	 * @param result completed taskkill capture
	 * @return failure observation, or empty if root reaping may proceed
	 */
	public static Optional<StopOutcome> classifyTaskkill(SystemCommands.Result result)
	{
		if (result.status() == 0)
			return Optional.empty();
		return Optional.of(new StopOutcome(GroupState.ACCESS_DENIED,
			List.of("taskkill returned " + result.status(), result.stdout(), result.stderr())));
	}

	/**
	 * Creates the literal command that forcibly terminates the owned root and its descendants.
	 *
	 * @param pid owned root process identifier
	 * @return taskkill executable and arguments selecting forced tree termination for that root
	 */
	public static List<String> createTaskkillCommand(long pid)
	{
		return List.of("taskkill", "/T", "/F", "/PID", Long.toString(pid));
	}

	/**
	 * Owned direct child.
	 */
	private final Process process;
	/**
	 * Caller-supplied environment variables used for root startup and tree cleanup.
	 */
	private final Map<String, String> environment;
	/**
	 * Child working directory used by cleanup commands.
	 */
	private final Path workingDirectory;
	/**
	 * Stable root completion; null until observed exited.
	 */
	private Completion completion;

	/**
	 * Starts the child with literal Windows arguments, inherited streams and the environment policy of
	 * {@link QuietBuildLauncher}.
	 *
	 * @param command          validated executable and literal arguments
	 * @param workingDirectory child cwd
	 * @param environment      explicit child variables and their values
	 * @throws IOException if startup fails
	 */
	public WindowsQuietBuildProcess(List<String> command, Path workingDirectory, Map<String, String> environment)
		throws IOException
	{
		this.workingDirectory = workingDirectory;
		this.environment = Map.copyOf(environment);
		ProcessBuilder builder = builder(command).inheritIO();
		process = builder.start();
	}

	@Override
	public long pid()
	{
		return process.pid();
	}

	@Override
	public Optional<Completion> poll()
	{
		if (completion == null && !process.isAlive())
			completion = decodeExit(process.exitValue());
		return Optional.ofNullable(completion);
	}

	@Override
	public Completion await() throws InterruptedException
	{
		process.waitFor();
		return poll().orElseThrow();
	}

	@Override
	public StopOutcome stop() throws IOException, InterruptedException
	{
		return stop(null);
	}

	@Override
	public void close() throws IOException
	{
		if (poll().isPresent())
		{
			process.close();
			return;
		}
		try (Cleanup cleanup = new Cleanup())
		{
			StopOutcome outcome = stop(cleanup);
			if (poll().isEmpty() || outcome.state() != GroupState.GONE)
				throw new IOException("Windows build cleanup incomplete: " + outcome);
			process.close();
		}
		catch (InterruptedException failure)
		{
			Thread.currentThread().interrupt();
			throw new IOException("Windows build cleanup unexpectedly interrupted: " + pid(), failure);
		}
	}

	/**
	 * Captures taskkill diagnostics while retaining separate bounded helper and root lifetimes.
	 *
	 * @param cleanup noninterruptible cleanup scope, or null for interruptible waits
	 * @return observed tree cleanup status
	 * @throws IOException          if taskkill startup, capture, or cleanup fails
	 * @throws InterruptedException if an interruptible wait is interrupted
	 */
	private StopOutcome stop(Cleanup cleanup) throws IOException, InterruptedException
	{
		if (poll().isPresent())
		{
			return new StopOutcome(GroupState.GRACE_EXPIRED,
				List.of("Windows root already exited; taskkill cannot establish descendant termination"));
		}
		try (CaptureFiles files = new CaptureFiles())
		{
			ProcessBuilder builder = builder(createTaskkillCommand(pid())).
				redirectInput(ProcessBuilder.Redirect.from(Path.of("NUL").toFile())).
				redirectOutput(files.stdout.toFile()).redirectError(files.stderr.toFile());
			try (Helper helper = new Helper(builder.start()))
			{
				if (!waitFor(helper.process, TASKKILL_TIMEOUT, cleanup))
				{
					return new StopOutcome(GroupState.GRACE_EXPIRED,
						List.of("taskkill exceeded its 120-second deadline for owned PID " + pid()));
				}
				SystemCommands.Result result = new SystemCommands.Result(helper.process.exitValue(),
					Files.readString(files.stdout), Files.readString(files.stderr));
				Optional<StopOutcome> failure = classifyTaskkill(result);
				if (failure.isPresent())
					return failure.orElseThrow();
			}
		}
		if (!waitFor(process, REAP_TIMEOUT, cleanup))
			return new StopOutcome(GroupState.GRACE_EXPIRED, List.of("Windows root remained alive after taskkill"));
		poll();
		return new StopOutcome(GroupState.GONE, List.of());
	}

	/**
	 * Uses the established literal Windows command encoding and the environment policy of {@link QuietBuildLauncher}.
	 *
	 * @param command executable and literal arguments
	 * @return configured child builder
	 * @throws IOException if Windows argument encoding fails
	 */
	private ProcessBuilder builder(List<String> command) throws IOException
	{
		ProcessBuilder builder = SystemCommands.createBuilder(command).directory(workingDirectory.toFile());
		builder.environment().clear();
		builder.environment().putAll(environment);
		return builder;
	}

	/**
	 * Preserves a monotonic deadline when close encounters interruption.
	 *
	 * @param child   owned root or helper
	 * @param timeout maximum elapsed wait
	 * @param cleanup noninterruptible cleanup scope, or null for interruptible waits
	 * @return whether the owned child exited
	 * @throws InterruptedException if an interruptible wait is interrupted
	 */
	private static boolean waitFor(Process child, Duration timeout, Cleanup cleanup) throws InterruptedException
	{
		long started = System.nanoTime();
		while (System.nanoTime() - started < timeout.toNanos())
		{
			try
			{
				long remaining = timeout.toNanos() - (System.nanoTime() - started);
				if (remaining <= 0)
					return !child.isAlive();
				return child.waitFor(remaining, TimeUnit.NANOSECONDS);
			}
			catch (InterruptedException failure)
			{
				if (cleanup == null)
					throw failure;
				cleanup.interrupted = true;
			}
		}
		return !child.isAlive();
	}

	/**
	 * Records interruption while bounded cleanup continues, restoring it at the operation boundary.
	 */
	private static final class Cleanup implements AutoCloseable
	{
		/**
		 * Interrupt to restore after cleanup and reaping finish.
		 */
		private boolean interrupted = Thread.interrupted();

		/**
		 * Begins noninterruptible resource cleanup.
		 */
		private Cleanup()
		{
		}

		@Override
		public void close()
		{
			if (interrupted)
				Thread.currentThread().interrupt();
		}
	}

	/**
	 * Terminates and reaps an owned cleanup helper before allowing JDK resource closure.
	 */
	private static final class Helper implements AutoCloseable
	{
		/**
		 * Sole owned helper process.
		 */
		private final Process process;

		/**
		 * Accepts helper ownership immediately after successful startup.
		 *
		 * @param process owned cleanup helper
		 */
		private Helper(Process process)
		{
			this.process = process;
		}

		@Override
		public void close() throws IOException
		{
			try (Cleanup cleanup = new Cleanup())
			{
				// JDK Process.close can otherwise wait beyond the helper deadline for a resistant child.
				if (process.isAlive())
				{
					process.destroyForcibly();
					if (!waitFor(process, REAP_TIMEOUT, cleanup))
						throw new IOException("Owned cleanup helper did not exit after forced termination: " + process.pid());
				}
				process.close();
			}
			catch (InterruptedException failure)
			{
				Thread.currentThread().interrupt();
				throw new IOException("Owned helper cleanup unexpectedly interrupted: " + process.pid(), failure);
			}
		}
	}

	/**
	 * Owns capture files until taskkill has been reaped; cleanup failures remain visible.
	 */
	private static final class CaptureFiles implements AutoCloseable
	{
		/**
		 * Owned capture directory in the JVM's configured temporary directory.
		 */
		private final Path directory = Files.createTempDirectory("quiet-build-taskkill-");
		/**
		 * Captured taskkill stdout.
		 */
		private final Path stdout = directory.resolve("stdout");
		/**
		 * Captured taskkill stderr.
		 */
		private final Path stderr = directory.resolve("stderr");

		/**
		 * Allocates isolated capture storage.
		 *
		 * @throws IOException if the directory cannot be created
		 */
		private CaptureFiles() throws IOException
		{
		}

		@Override
		public void close() throws IOException
		{
			IOException failure = null;
			for (Path path : List.of(stdout, stderr, directory))
			{
				try
				{
					Files.deleteIfExists(path);
				}
				catch (IOException cleanupFailure)
				{
					if (failure == null)
						failure = cleanupFailure;
					else
						failure.addSuppressed(cleanupFailure);
				}
			}
			if (failure != null)
				throw failure;
		}
	}
}
