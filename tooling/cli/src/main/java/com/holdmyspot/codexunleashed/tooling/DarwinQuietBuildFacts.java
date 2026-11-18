package com.holdmyspot.codexunleashed.tooling;

import java.io.IOException;
import java.io.Writer;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

/**
 * Formats Darwin process stop and signal facts for the host diagnostic report.
 */
public final class DarwinQuietBuildFacts
{
	/**
	 * BSD process-state names from Darwin's process information.
	 */
	private static final Map<Integer, String> BSD_STATUSES = Map.of(1, "SIDL", 2, "SRUN", 3, "SSLEEP", 4, "SSTOP",
		5, "SZOMB");
	/**
	 * Mach thread run-state names reported by Darwin's thread information.
	 */
	private static final Map<Integer, String> MACH_RUN_STATES = Map.of(1, "running", 2, "stopped", 3, "waiting",
		4, "uninterruptible", 5, "halted");
	/**
	 * Darwin signal names for signal numbers one through thirty-one.
	 */
	private static final Map<Integer, String> SIGNALS = Map.ofEntries(Map.entry(1, "SIGHUP"), Map.entry(2, "SIGINT"),
		Map.entry(3, "SIGQUIT"), Map.entry(4, "SIGILL"), Map.entry(5, "SIGTRAP"), Map.entry(6, "SIGABRT"),
		Map.entry(7, "SIGEMT"), Map.entry(8, "SIGFPE"), Map.entry(9, "SIGKILL"), Map.entry(10, "SIGBUS"),
		Map.entry(11, "SIGSEGV"), Map.entry(12, "SIGSYS"), Map.entry(13, "SIGPIPE"), Map.entry(14, "SIGALRM"),
		Map.entry(15, "SIGTERM"), Map.entry(16, "SIGURG"), Map.entry(17, "SIGSTOP"), Map.entry(18, "SIGTSTP"),
		Map.entry(19, "SIGCONT"), Map.entry(20, "SIGCHLD"), Map.entry(21, "SIGTTIN"), Map.entry(22, "SIGTTOU"),
		Map.entry(23, "SIGIO"), Map.entry(24, "SIGXCPU"), Map.entry(25, "SIGXFSZ"), Map.entry(26, "SIGVTALRM"),
		Map.entry(27, "SIGPROF"), Map.entry(28, "SIGWINCH"), Map.entry(29, "SIGINFO"), Map.entry(30, "SIGUSR1"),
		Map.entry(31, "SIGUSR2"));

	/**
	 * Prevents construction of this formatting utility.
	 */
	private DarwinQuietBuildFacts()
	{
	}

	/**
	 * Writes the BSD state, task suspension count and each supplied native thread state.
	 *
	 * @param output report writer
	 * @param status BSD process status, or null when unavailable
	 * @param taskSuspendCount task suspension count, or null when unavailable
	 * @param threads native thread states, null when unavailable and empty when no threads were returned
	 * @throws NullPointerException if {@code output} is null
	 * @throws IOException if the report cannot be written
	 */
	public static void writeStop(Writer output, Integer status, Integer taskSuspendCount,
		Iterable<ThreadState> threads) throws IOException
	{
		requireThat(output, "output").isNotNull();
		output.write("stop: bsd_status=");
		String statusName = "None";
		if (status != null)
			statusName = BSD_STATUSES.getOrDefault(status, status.toString());
		output.write(statusName);
		output.write(" task_suspend_count=");
		String suspendCount = "?";
		if (taskSuspendCount != null)
			suspendCount = taskSuspendCount.toString();
		output.write(suspendCount);
		output.write(" threads=");
		if (threads == null)
			output.write("?");
		else
		{
			boolean firstThread = true;
			for (ThreadState thread : threads)
			{
				if (!firstThread)
					output.write(",");
				output.write(MACH_RUN_STATES.getOrDefault(thread.runState(), Integer.toString(thread.runState())));
				output.write("/");
				output.write(Integer.toString(thread.suspendCount()));
				firstThread = false;
			}
		}
		output.write("\n");
	}

	/**
	 * Writes Darwin tracer, stop signal and pending signal names in lexical order.
	 *
	 * @param output report writer
	 * @param traced whether Darwin marks the process as traced, or null when unavailable
	 * @param stopSignal stop signal number, or null when unavailable
	 * @param pending pending signal bit mask for signal numbers one through thirty-two, or null when unavailable
	 * @throws NullPointerException if {@code output} is null
	 * @throws IOException if the report cannot be written
	 */
	public static void writeSignals(Writer output, Boolean traced, Integer stopSignal, Long pending) throws IOException
	{
		requireThat(output, "output").isNotNull();
		output.write("signals: traced=");
		String traceStatus = "?";
		if (traced != null)
		{
			if (traced)
				traceStatus = "yes";
			else
				traceStatus = "no";
		}
		output.write(traceStatus);
		output.write(" stop_signal=");
		String signalName = "?";
		if (stopSignal != null)
			signalName = SIGNALS.getOrDefault(stopSignal, stopSignal.toString());
		output.write(signalName);
		output.write(" pending=");
		if (pending == null)
			output.write("?");
		else
		{
			Set<String> names = new TreeSet<>();
			for (int signal = 1; signal <= 32; signal = signal + 1)
			{
				if ((pending & (1L << (signal - 1))) != 0)
					names.add(SIGNALS.getOrDefault(signal, Integer.toString(signal)));
			}
			if (names.isEmpty())
				output.write("none");
			else
				output.write(String.join(",", names));
		}
		output.write("\n");
	}

	/**
	 * One Darwin Mach thread's run state and suspension count.
	 *
	 * @param runState thread run-state number
	 * @param suspendCount thread suspension count
	 */
	public record ThreadState(int runState, int suspendCount)
	{
	}
}
