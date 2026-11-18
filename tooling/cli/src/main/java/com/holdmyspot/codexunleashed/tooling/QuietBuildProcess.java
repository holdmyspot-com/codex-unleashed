package com.holdmyspot.codexunleashed.tooling;

import java.io.IOException;
import java.util.List;
import java.util.Optional;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

/**
 * Owns one build root and its process-group or process-tree cleanup on a single invocation thread.
 */
public sealed interface QuietBuildProcess extends AutoCloseable permits UnixQuietBuildProcess, WindowsQuietBuildProcess
{
	/**
	 * Terminal child outcome, preserving the platform's ordinary-exit and signal distinctions.
	 */
	sealed interface Completion permits UnixExit, UnixSignal, WindowsExit
	{
	}
	/**
	 * Ordinary Unix exit.
	 *
	 * @param code exit code in 0..255
	 */
	record UnixExit(int code) implements Completion
	{
		/**
		 * Constructs a Unix exit.
		 *
		 * @param code exit code in 0..255
		 */
		public UnixExit
		{
			requireThat(code, "code").isGreaterThanOrEqualTo(0).isLessThanOrEqualTo(255);
		}
	}
	/**
	 * Unix termination by signal.
	 *
	 * @param number     positive signal number
	 * @param coreDumped whether a core was indicated
	 */
	record UnixSignal(int number, boolean coreDumped) implements Completion
	{
		/**
		 * Constructs a signal result.
		 *
		 * @param number     positive signal
		 * @param coreDumped core indicator
		 */
		public UnixSignal
		{
			requireThat(number, "number").isPositive();
		}
	}
	/**
	 * Windows exit DWORD.
	 *
	 * @param unsignedCode exit bits interpreted in 0..4294967295
	 */
	record WindowsExit(long unsignedCode) implements Completion
	{
		/**
		 * Constructs a Windows exit.
		 *
		 * @param unsignedCode unsigned DWORD
		 */
		public WindowsExit
		{
			requireThat(unsignedCode, "unsignedCode").isGreaterThanOrEqualTo(0).isLessThanOrEqualTo(0xffff_ffffL);
		}
	}
	/**
	 * Observed cleanup outcome, without inferring descendant reaping.
	 */
	enum GroupState
	{
		/**
		 * The owned group or successfully stopped Windows tree is gone.
		 */
		GONE,
		/**
		 * The owned group or tree could not be stopped with the available permissions.
		 */
		ACCESS_DENIED,
		/**
		 * Termination could not be established within the bounded cleanup attempt.
		 */
		GRACE_EXPIRED
	}
	/**
	 * Cleanup observation.
	 *
	 * @param state       observed group/tree state
	 * @param diagnostics retained cleanup diagnostics
	 */
	record StopOutcome(GroupState state, List<String> diagnostics)
	{
		/**
		 * Constructs an immutable outcome.
		 *
		 * @param state       group state
		 * @param diagnostics cleanup diagnostics
		 */
		public StopOutcome
		{
			requireThat(state, "state").isNotNull();
			diagnostics = List.copyOf(diagnostics);
		}
	}
	/**
	 * Returns the owned positive root PID.
	 *
	 * @return owned PID
	 */
	long pid();
	/**
	 * Reaps and caches a completed root, or observes it running.
	 *
	 * @return completion or empty
	 * @throws IOException on wait failure
	 */
	Optional<Completion> poll() throws IOException;
	/**
	 * Waits for and caches the owned root completion.
	 *
	 * @return completion
	 * @throws IOException          on wait failure
	 * @throws InterruptedException on interruption
	 */
	Completion await() throws IOException, InterruptedException;
	/**
	 * Stops the owned group/tree.
	 *
	 * @return observed cleanup outcome
	 * @throws IOException          on native failure
	 * @throws InterruptedException on interruption
	 */
	StopOutcome stop() throws IOException, InterruptedException;
	/**
	 * Stops outstanding owned work and reaps the root.
	 *
	 * @throws IOException if bounded cleanup fails
	 */
	@Override
	void close() throws IOException;
}
