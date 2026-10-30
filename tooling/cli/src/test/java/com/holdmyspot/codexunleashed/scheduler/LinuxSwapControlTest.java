package com.holdmyspot.codexunleashed.scheduler;

import com.holdmyspot.codexunleashed.tooling.SystemCommands;
import java.io.IOException;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;
import java.util.ArrayList;
import java.util.List;
import org.testng.annotations.Test;
import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

/**
 * Tests Java decisions from supplied command outcomes, based on the retained prerequisite operation.
 * Fixtures do not execute swap controls and cannot prove native swap effects or command availability.
 */
public final class LinuxSwapControlTest
{
	/** Creates the focused test fixture without native effects. */
	public LinuxSwapControlTest()
	{
	}

	/**
	 * A successful disabling command is not proof that even unused active swap disappeared.
	 *
	 * @throws IOException if the preparation unexpectedly lacks a complete result
	 */
	@Test
	public void successfulCommandWithActiveSwapRequiresMonitoring() throws IOException
	{
		List<List<String>> calls = new ArrayList<>();
		var control = new LinuxSwapControl(command ->
		{
			calls.add(command);
			return new SystemCommands.Result(0, command.getFirst().equals("swapon") ? "/swapfile\n" : "", "");
		});

		LinuxSwapControl.Result result = control.prepare();

		requireThat(result.mode(), "mode").isEqualTo(LinuxSwapControl.Mode.MONITORING_REQUIRED, "expected");
		requireThat(calls, "commands").isEqualTo(List.of(List.of("sudo", "-n", "swapoff", "--all"),
			List.of("swapon", "--show=NAME", "--noheadings", "--raw")), "expected");
	}

	/**
	 * A fresh empty configuration verifies disabled state; another call must not repeat either command.
	 *
	 * @throws IOException if the preparation unexpectedly lacks a complete result
	 */
	@Test
	public void emptyConfigurationIsVerifiedAndPreparationDoesNotRepeat() throws IOException
	{
		List<List<String>> calls = new ArrayList<>();
		var control = new LinuxSwapControl(command ->
		{
			calls.add(command);
			return new SystemCommands.Result(0, "", "");
		});
		LinuxSwapControl.Result result = control.prepare();
		requireThat(result.mode(), "mode").isEqualTo(LinuxSwapControl.Mode.VERIFIED_DISABLED, "expected");
		requireThat(control.prepare(), "retainedResult").isReferenceEqualTo(result, "originalResult");
		requireThat(calls.size(), "commandCount").isEqualTo(2, "expected");
	}

	/**
	 * A failed disable request does not override independent evidence that no active swap remains.
	 *
	 * @throws IOException if the preparation unexpectedly lacks a complete result
	 */
	@Test
	public void failedCommandAndEmptyConfigurationRemainSeparate() throws IOException
	{
		var control = new LinuxSwapControl(command -> new SystemCommands.Result(
			command.getFirst().equals("sudo") ? 1 : 0, "", "retained diagnostic"));
		LinuxSwapControl.Result result = control.prepare();
		requireThat(result.mode(), "mode").isEqualTo(LinuxSwapControl.Mode.VERIFIED_DISABLED, "expected");
		requireThat(result.command().status(), "disableStatus").isEqualTo(1, "expected");
		requireThat(result.command().stderr(), "disableDiagnostic").isEqualTo("retained diagnostic", "expected");
	}

	/**
	 * A rejected disable command with still-active swap requires monitoring and preserves its exit evidence.
	 *
	 * @throws IOException if the preparation unexpectedly lacks a complete result
	 */
	@Test
	public void rejectedCommandWithActiveSwapRequiresMonitoring() throws IOException
	{
		var control = new LinuxSwapControl(command -> command.getFirst().equals("sudo") ?
			new SystemCommands.Result(1, "", "permission denied") : new SystemCommands.Result(0, "/swapfile\n", ""));
		LinuxSwapControl.Result result = control.prepare();
		requireThat(result.mode(), "mode").isEqualTo(LinuxSwapControl.Mode.MONITORING_REQUIRED, "expected");
		requireThat(result.command().status(), "disableStatus").isEqualTo(1, "expected");
	}

	/**
	 * Startup, waiting or capture cleanup failure still permits a separate effective-state inspection.
	 *
	 * @throws IOException if the preparation unexpectedly lacks a complete result
	 */
	@Test
	public void invocationFailureRetainsCauseAndStillInspects() throws IOException
	{
		IOException failure = new IOException("capture cleanup failed");
		List<List<String>> calls = new ArrayList<>();
		var control = new LinuxSwapControl(command ->
		{
			calls.add(command);
			if (command.getFirst().equals("sudo"))
				throw failure;
			return new SystemCommands.Result(0, "/swapfile\n", "");
		});
		LinuxSwapControl.Result result = control.prepare();
		requireThat(result.mode(), "mode").isEqualTo(LinuxSwapControl.Mode.MONITORING_REQUIRED, "expected");
		requireThat(result.commandFailure(), "failure").isReferenceEqualTo(failure, "original");
		requireThat(result.command(), "completedCommand").isNull();
		requireThat(control.prepare(), "repeat").isReferenceEqualTo(result, "original");
		requireThat(calls.size(), "commandCount").isEqualTo(2, "expected");
	}

	/**
	 * Unavailable inspection is uncertainty and keeps its first cause without retrying the machine change.
	 *
	 * @throws IOException if the preparation unexpectedly lacks a complete result
	 */
	@Test
	public void inspectionFailureIsUnknownAndRetained() throws IOException
	{
		IOException failure = new IOException("active configuration unavailable");
		List<List<String>> calls = new ArrayList<>();
		var control = new LinuxSwapControl(command ->
		{
			calls.add(command);
			if (command.getFirst().equals("swapon"))
				throw failure;
			return new SystemCommands.Result(0, "", "");
		});
		LinuxSwapControl.Result result = control.prepare();
		requireThat(result.mode(), "mode").isEqualTo(LinuxSwapControl.Mode.UNKNOWN, "expected");
		requireThat(result.verificationFailure(), "failure").isReferenceEqualTo(failure, "original");
		requireThat(control.prepare(), "repeat").isReferenceEqualTo(result, "original");
		requireThat(calls.size(), "commandCount").isEqualTo(2, "expected");
	}

	/**
	 * Empty output from an unsuccessful inspection cannot verify disabled swap.
	 *
	 * @throws IOException if the preparation unexpectedly lacks a complete result
	 */
	@Test
	public void unsuccessfulEmptyInspectionRemainsUnknown() throws IOException
	{
		var control = new LinuxSwapControl(command -> new SystemCommands.Result(
			command.getFirst().equals("swapon") ? 7 : 0, "", "inspection denied"));
		LinuxSwapControl.Result result = control.prepare();
		requireThat(result.mode(), "mode").isEqualTo(LinuxSwapControl.Mode.UNKNOWN, "expected");
		requireThat(result.verificationFailure().getMessage(), "diagnostic").isEqualTo(
			"Active swap inspection failed with status 7: inspection denied", "expected");
	}

	/**
	 * Operational executor exceptions retain their causes separately for disable and inspection.
	 *
	 * @throws IOException if the preparation unexpectedly lacks a complete result
	 */
	@Test
	public void operationalFailuresAreContextualAndSeparate() throws IOException
	{
		RuntimeException failure = new IllegalStateException("executor unavailable");
		var control = new LinuxSwapControl(command -> { throw failure; });
		LinuxSwapControl.Result result = control.prepare();
		requireThat(result.mode(), "mode").isEqualTo(LinuxSwapControl.Mode.UNKNOWN, "expected");
		requireThat(result.commandFailure().getCause(), "disableCause").isReferenceEqualTo(failure, "original");
		requireThat(result.verificationFailure().getCause(), "inspectionCause").isReferenceEqualTo(failure, "original");
	}

	/**
	 * Missing command results never turn into empty successful listings.
	 *
	 * @throws IOException if the preparation unexpectedly lacks a complete result
	 */
	@Test
	public void missingResultsRemainUnknown() throws IOException
	{
		var control = new LinuxSwapControl(command -> null);
		LinuxSwapControl.Result result = control.prepare();
		requireThat(result.mode(), "mode").isEqualTo(LinuxSwapControl.Mode.UNKNOWN, "expected");
		requireThat(result.commandFailure().getMessage(), "disableFailure").isEqualTo(
			"Swap command returned no result: sudo", "expected");
		requireThat(result.verificationFailure().getMessage(), "inspectionFailure").isEqualTo(
			"Swap command returned no result: swapon", "expected");
	}

	/**
	 * A fatal command outcome propagates unchanged and prevents every subsequent automatic command.
	 *
	 * @throws IOException if the preparation unexpectedly lacks a complete result
	 */
	@Test
	public void fatalFailurePreventsResendAtEitherBoundary() throws IOException
	{
		for (String operation : List.of("sudo", "swapon"))
		{
			LinkageError failure = new LinkageError("fatal " + operation);
			List<List<String>> calls = new ArrayList<>();
			var control = new LinuxSwapControl(command ->
			{
				calls.add(command);
				if (command.getFirst().equals(operation))
					throw failure;
				return new SystemCommands.Result(0, "", "");
			});
			requireThat(thrown(control::prepare), "fatal").isReferenceEqualTo(failure, "original");
			int completedCalls = calls.size();
			Throwable repeated = thrown(control::prepare);
			requireThat(repeated instanceof IOException, "repeatRejected").isEqualTo(true, "expected");
			requireThat(repeated.getCause(), "retainedFatal").isReferenceEqualTo(failure, "original");
			requireThat(calls.size(), "commandCount").isEqualTo(completedCalls, "beforeRepeat");
		}
	}

	/**
	 * A second thread cannot issue commands or consume the constructing thread's attempt.
	 *
	 * @throws IOException if the preparation unexpectedly lacks a complete result
	 * @throws InterruptedException if the test thread is interrupted while waiting for the foreign caller
	 */
	@Test
	public void otherThreadCannotPrepare() throws IOException, InterruptedException
	{
		List<List<String>> calls = new ArrayList<>();
		var control = new LinuxSwapControl(command ->
		{
			calls.add(command);
			return new SystemCommands.Result(0, "", "");
		});
		var failure = new AtomicReference<Throwable>();
		Thread thread = new Thread(() -> failure.set(thrown(control::prepare)));
		thread.start();
		thread.join();
		requireThat(failure.get() instanceof IllegalStateException, "wrongThreadRejected").isEqualTo(true, "expected");
		requireThat(calls.size(), "commandsBeforeOwner").isEqualTo(0, "expected");
		requireThat(control.prepare().mode(), "ownerMode").isEqualTo(LinuxSwapControl.Mode.VERIFIED_DISABLED, "expected");
	}

	/** Invalid production paths are rejected without executing machine commands. */
	@Test
	public void relativePathsAreRejectedBeforeCommands()
	{
		Throwable failure = thrown(() -> new LinuxSwapControl(Path.of("relative"), Path.of("also-relative")));
		requireThat(failure instanceof IllegalArgumentException, "relativePathsRejected").isEqualTo(true, "expected");
	}

	/**
	 * Interruption reported by disable prevents a second process and retains uncertain state.
	 *
	 * @throws IOException if preparation unexpectedly throws instead of retaining its operational result
	 */
	@Test
	public void interruptedDisableDoesNotInspect() throws IOException
	{
		IOException failure = new IOException("interrupted while waiting for disable");
		List<List<String>> calls = new ArrayList<>();
		var control = new LinuxSwapControl(command ->
		{
			calls.add(command);
			if (command.getFirst().equals("sudo"))
			{
				Thread.currentThread().interrupt();
				throw failure;
			}
			return new SystemCommands.Result(0, "", "");
		});
		try
		{
			LinuxSwapControl.Result result = control.prepare();
			requireThat(calls.size(), "commandCount").isEqualTo(1, "disableOnly");
			requireThat(result.mode(), "mode").isEqualTo(LinuxSwapControl.Mode.UNKNOWN, "expected");
			requireThat(result.commandFailure(), "disableFailure").isReferenceEqualTo(failure, "original");
			requireThat(result.verificationFailure(), "inspectionNotPerformed").isNotNull();
			requireThat(Thread.currentThread().isInterrupted(), "interruptPreserved").isEqualTo(true, "expected");
			requireThat(control.prepare(), "retainedResult").isReferenceEqualTo(result, "original");
		}
		finally
		{
			Thread.interrupted();
		}
		requireThat(control.prepare().mode(), "afterInterruptCleared").isEqualTo(LinuxSwapControl.Mode.UNKNOWN, "expected");
		requireThat(calls.size(), "noResend").isEqualTo(1, "disableOnly");
	}

	/**
	 * An interrupted caller cannot start disabling or inspection, including after the flag is cleared.
	 *
	 * @throws IOException if preparation unexpectedly throws instead of retaining its operational result
	 */
	@Test
	public void preinterruptedCallerDoesNotIssueCommands() throws IOException
	{
		List<List<String>> calls = new ArrayList<>();
		var control = new LinuxSwapControl(command ->
		{
			calls.add(command);
			return new SystemCommands.Result(0, "", "");
		});
		Thread.currentThread().interrupt();
		try
		{
			LinuxSwapControl.Result result = control.prepare();
			requireThat(calls.size(), "commandCount").isEqualTo(0, "expected");
			requireThat(result.mode(), "mode").isEqualTo(LinuxSwapControl.Mode.UNKNOWN, "expected");
			requireThat(result.command(), "completedDisable").isNull();
			requireThat(result.commandFailure(), "disableNotPerformed").isNotNull();
			requireThat(result.verificationFailure(), "inspectionNotPerformed").isNotNull();
			requireThat(Thread.currentThread().isInterrupted(), "interruptPreserved").isEqualTo(true, "expected");
			requireThat(control.prepare(), "retainedResult").isReferenceEqualTo(result, "original");
		}
		finally
		{
			Thread.interrupted();
		}
		requireThat(control.prepare().mode(), "afterInterruptCleared").isEqualTo(LinuxSwapControl.Mode.UNKNOWN, "expected");
		requireThat(calls.size(), "noResend").isEqualTo(0, "expected");
	}

	/**
	 * Interruption after a command's successful return cannot authorize another command or verify effective state.
	 *
	 * @throws IOException if preparation unexpectedly throws instead of retaining its operational result
	 */
	@Test
	public void interruptedSuccessfulCommandLeavesStateUnknown() throws IOException
	{
		for (String operation : List.of("sudo", "swapon"))
		{
			List<List<String>> calls = new ArrayList<>();
			var control = new LinuxSwapControl(command ->
			{
				calls.add(command);
				if (command.getFirst().equals(operation))
					Thread.currentThread().interrupt();
				return new SystemCommands.Result(0, "", "");
			});
			int expectedCalls = operation.equals("sudo") ? 1 : 2;
			try
			{
				LinuxSwapControl.Result result = control.prepare();
				requireThat(calls.size(), "commandCount").isEqualTo(expectedCalls, "throughInterruptedCommand");
				requireThat(result.mode(), "mode").isEqualTo(LinuxSwapControl.Mode.UNKNOWN, "expected");
				requireThat(result.command().status(), "disableStatus").isEqualTo(0, "observed");
				requireThat(result.commandFailure(), "disableInvocationFailure").isNull();
				requireThat(result.verificationFailure() instanceof java.io.InterruptedIOException,
					"interruptionRecorded").isEqualTo(true, "expected");
				requireThat(Thread.currentThread().isInterrupted(), "interruptPreserved").isEqualTo(true, "expected");
			}
			finally
			{
				Thread.interrupted();
			}
			requireThat(control.prepare().mode(), "afterInterruptCleared").isEqualTo(LinuxSwapControl.Mode.UNKNOWN, "expected");
			requireThat(calls.size(), "noResend").isEqualTo(expectedCalls, "throughInterruptedCommand");
		}
	}

	/**
	 * An inspection interrupted while waiting preserves the original failure, flag and once-only command count.
	 *
	 * @throws IOException if preparation unexpectedly throws instead of retaining its operational result
	 */
	@Test
	public void interruptedInspectionRetainsCauseAndDoesNotRepeat() throws IOException
	{
		IOException failure = new IOException("interrupted while waiting for inspection");
		List<List<String>> calls = new ArrayList<>();
		var control = new LinuxSwapControl(command ->
		{
			calls.add(command);
			if (command.getFirst().equals("swapon"))
			{
				Thread.currentThread().interrupt();
				throw failure;
			}
			return new SystemCommands.Result(0, "", "");
		});
		try
		{
			LinuxSwapControl.Result result = control.prepare();
			requireThat(result.mode(), "mode").isEqualTo(LinuxSwapControl.Mode.UNKNOWN, "expected");
			requireThat(result.verificationFailure(), "inspectionFailure").isReferenceEqualTo(failure, "original");
			requireThat(Thread.currentThread().isInterrupted(), "interruptPreserved").isEqualTo(true, "expected");
			requireThat(control.prepare(), "retainedResult").isReferenceEqualTo(result, "original");
		}
		finally
		{
			Thread.interrupted();
		}
		requireThat(control.prepare().mode(), "afterInterruptCleared").isEqualTo(LinuxSwapControl.Mode.UNKNOWN, "expected");
		requireThat(calls.size(), "noResend").isEqualTo(2, "disableAndInspection");
	}

	/**
	 * Captures the operation's actual failure; null records an unexpectedly successful operation.
	 *
	 * @param operation the operation expected to fail
	 * @return its actual thrown failure, or null
	 */
	private static Throwable thrown(Operation operation)
	{
		try
		{
			operation.run();
			return null;
		}
		catch (Throwable failure)
		{
			return failure;
		}
	}

	/** Supplies one potentially failing operation without adding its own outcome. */
	@FunctionalInterface
	private interface Operation
	{
		/**
		 * Executes the supplied production call.
		 *
		 * @throws IOException if the production operation fails
		 */
		void run() throws IOException;
	}
}
