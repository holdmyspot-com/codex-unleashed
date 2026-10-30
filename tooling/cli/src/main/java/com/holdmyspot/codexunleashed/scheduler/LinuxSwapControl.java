package com.holdmyspot.codexunleashed.scheduler;

import com.holdmyspot.codexunleashed.tooling.SystemCommands;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/**
 * Attempts Linux machine-wide swap disabling once before workload admission, then verifies active configuration.
 * <p>
 * The caller selects a disposable Linux host and owns its machine settings. This operation does not restore swap,
 * launch work or prove that monitoring works. A monitoring result requires an independent complete controller reading.
 * Calls belong to the constructing thread. Native VM acceptance and scheduler composition remain unaccepted.
 */
public final class LinuxSwapControl
{
	/** Noninteractive control selected and verified by the retained Linux prerequisite diagnostic. */
	private static final List<String> DISABLE = List.of("sudo", "-n", "swapoff", "--all");

	/** Active swap names, without occupancy counters or a header that could resemble an entry. */
	private static final List<String> INSPECT = List.of("swapon", "--show=NAME", "--noheadings", "--raw");

	/** Command boundary borrowed for this one preparation. */
	private final Commands commands;

	/** Thread authorized to observe or change preparation state. */
	private final Thread owner = Thread.currentThread();

	/** Indicates that preparation began, including an unknown outcome after an Error. */
	private boolean attempted = false;

	/** Complete retained result, or null before a result has been established. */
	private Result result = null;

	/** First fatal failure that prevented a complete result, or null when none was observed. */
	private Error failure = null;

	/** Effective swap state after preparation; usage alone never selects a mode. */
	public enum Mode
	{
		/** A fresh successful active-configuration listing contains no entries. */
		VERIFIED_DISABLED,
		/** Active swap remains configured; independent working monitoring is required for admission. */
		MONITORING_REQUIRED,
		/** Active configuration could not be verified; this is not evidence that swap is disabled. */
		UNKNOWN
	}

	/**
	 * Separates the disabling command's observed outcome from effective-state verification.
	 *
	 * @param mode the fresh effective-state classification
	 * @param command the completed disable result, including a nonzero status, or null when invocation failed
	 * @param commandFailure the disable invocation failure or interruption before invocation, otherwise null
	 * @param verificationFailure the inspection failure for UNKNOWN, otherwise null
	 */
	public record Result(Mode mode, SystemCommands.Result command, IOException commandFailure,
		IOException verificationFailure)
	{
		/**
		 * Creates a consistent preparation result without inferring effective state from command status.
		 *
		 * @param mode the effective-state classification
		 * @param command the completed disabling command or null on invocation failure
		 * @param commandFailure the invocation failure or null when a command result exists
		 * @param verificationFailure the inspection failure exactly when mode is UNKNOWN
		 * @throws NullPointerException if mode is null
		 * @throws IllegalArgumentException if command evidence is absent or duplicated, or uncertainty lacks its cause
		 */
		public Result
		{
			Objects.requireNonNull(mode, "mode may not be null");
			boolean ambiguousCommand = (command == null) == (commandFailure == null);
			boolean inconsistentVerification = (mode == Mode.UNKNOWN) != (verificationFailure != null);
			if (ambiguousCommand || inconsistentVerification)
				throw new IllegalArgumentException("Preparation requires separate command and verification evidence");
		}
	}

	/** Executes one literal command and returns only after capture and process cleanup complete. */
	@FunctionalInterface
	interface Commands
	{
		/**
		 * Executes one command without interpreting a nonzero exit as successful state verification.
		 *
		 * @param command the executable and literal arguments
		 * @return the completed result
		 * @throws IOException if invocation or owned capture cleanup fails
		 */
		SystemCommands.Result run(List<String> command) throws IOException;
	}

	/**
	 * Creates a preparation using the existing owned command-capture operation; performs no command yet.
	 *
	 * @param workingDirectory the absolute command working directory
	 * @param temporaryDirectory the absolute existing parent of owned command captures
	 * @throws NullPointerException if either path is null
	 * @throws IllegalArgumentException if either path is relative
	 */
	public LinuxSwapControl(Path workingDirectory, Path temporaryDirectory)
	{
		Objects.requireNonNull(workingDirectory, "workingDirectory may not be null");
		Objects.requireNonNull(temporaryDirectory, "temporaryDirectory may not be null");
		if (workingDirectory.isAbsolute() == false || temporaryDirectory.isAbsolute() == false)
			throw new IllegalArgumentException("Swap command working and temporary directories must be absolute");
		commands = command -> SystemCommands.capture(command, workingDirectory, temporaryDirectory);
	}

	/**
	 * Creates a preparation at the command boundary, without performing an operation.
	 *
	 * @param commands the borrowed command executor
	 * @throws NullPointerException if commands is null
	 */
	LinuxSwapControl(Commands commands)
	{
		this.commands = Objects.requireNonNull(commands, "commands may not be null");
	}

	/**
	 * Attempts disabling once, then inspects active swap configuration independently of the command outcome.
	 * Repeated calls return the same result without further commands. A fatal Error prohibits automatic retry.
	 * An interrupted caller issues no command. Interruption prevents subsequent commands, preserves the flag and
	 * retains UNKNOWN without automatic retry, even after the flag is cleared.
	 * UNKNOWN and MONITORING_REQUIRED grant no workload-admission authority; the controller must obtain its own
	 * complete monitoring reading. Command startup, interruption and cleanup failures retain their cause.
	 *
	 * @return the retained command evidence and effective-state result
	 * @throws IllegalStateException if called outside the constructing thread
	 * @throws IOException if an earlier fatal Error left preparation without a result
	 * @throws Error if a command boundary throws an Error; the owner latches it before propagation
	 */
	public Result prepare() throws IOException
	{
		if (Thread.currentThread() != owner)
			throw new IllegalStateException("Swap preparation belongs to its constructing thread");
		if (attempted)
		{
			if (result != null)
				return result;
			throw new IOException("Swap preparation has an unknown fatal outcome; automatic retry is prohibited", failure);
		}
		attempted = true;
		try
		{
			SystemCommands.Result command = null;
			IOException commandFailure = null;
			try
			{
				command = invoke(DISABLE);
			}
			catch (IOException failed)
			{
				commandFailure = failed;
			}

			Mode mode = Mode.UNKNOWN;
			IOException verificationFailure = null;
			try
			{
				SystemCommands.Result inspection = invoke(INSPECT);
				if (Thread.currentThread().isInterrupted())
					throw new InterruptedIOException("Swap inspection was interrupted; effective state remains unknown");
				if (inspection.status() != 0)
					throw new IOException("Active swap inspection failed with status " + inspection.status() +
						": " + inspection.stderr());
				mode = inspection.stdout().isBlank() ? Mode.VERIFIED_DISABLED : Mode.MONITORING_REQUIRED;
			}
			catch (IOException failed)
			{
				verificationFailure = failed;
			}
			result = new Result(mode, command, commandFailure, verificationFailure);
			return result;
		}
		catch (Error fatal)
		{
			failure = fatal;
			throw fatal;
		}
	}

	/**
	 * Obtains one complete command result and translates operational executor failures without hiding their cause.
	 *
	 * @param command the executable and literal arguments
	 * @return the nonnull complete result
	 * @throws IOException if interrupted before invocation, execution fails or returns no result
	 */
	private SystemCommands.Result invoke(List<String> command) throws IOException
	{
		if (Thread.currentThread().isInterrupted())
			throw new InterruptedIOException("Swap command was not started because its caller is interrupted: " +
				command.getFirst());
		try
		{
			SystemCommands.Result observed = commands.run(command);
			if (observed == null)
				throw new IOException("Swap command returned no result: " + command.getFirst());
			return observed;
		}
		catch (RuntimeException failed)
		{
			throw new IOException("Swap command failed: " + command.getFirst(), failed);
		}
	}
}
