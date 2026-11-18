package com.holdmyspot.codexunleashed.tooling;

import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

/**
 * Decides when build inactivity requires CPU observation, a report, or termination.
 * <p>
 * Callers supply wall-clock observations and execute the returned actions. A failed CPU observation has a
 * separate result and never establishes inactivity.
 * <p>
 * <b>Thread Safety:</b> This class is thread-safe.
 */
public final class QuietBuildPolicy
{
	/**
	 * Caller-selected inactivity thresholds used for every observation in a monitored build.
	 */
	private final Limits limits;

	/**
	 * Creates an inactivity policy.
	 *
	 * @param limits report, CPU-observation and termination thresholds
	 * @throws NullPointerException if {@code limits} is null
	 */
	public QuietBuildPolicy(Limits limits)
	{
		requireThat(limits, "limits").isNotNull();
		this.limits = limits;
	}

	/**
	 * Starts monitoring from the later of invocation time and the newest output timestamp.
	 *
	 * @param now          invocation time
	 * @param latestOutput newest output timestamp, or {@link Instant#EPOCH} when no output exists
	 * @return initial state with an immediately eligible CPU observation
	 * @throws NullPointerException if an argument is null
	 * @throws DateTimeException   if the first report deadline exceeds the supported instant range
	 * @throws ArithmeticException if deadline arithmetic overflows
	 */
	public State start(Instant now, Instant latestOutput)
	{
		Instant lastActivity = now;
		if (latestOutput.isAfter(now))
			lastActivity = latestOutput;
		return new State(lastActivity, lastActivity.plus(limits.firstReport()), Instant.EPOCH, Map.of());
	}

	/**
	 * Gives new output priority over CPU observation, then evaluates inactivity when CPU observation is not due.
	 *
	 * @param state        previously installed state
	 * @param now          current wall-clock observation
	 * @param latestOutput newest output timestamp, or {@link Instant#EPOCH} when no output exists
	 * @return continued monitoring, a CPU-read request, a report, or an inactivity stop
	 * @throws NullPointerException if an argument is null
	 * @throws DateTimeException   if a computed deadline exceeds the supported instant range
	 * @throws ArithmeticException if duration or deadline arithmetic overflows
	 */
	public Observation observe(State state, Instant now, Instant latestOutput)
	{
		requireThat(now, "now").isNotNull();
		if (latestOutput.isAfter(state.lastActivity()))
		{
			return new Continue(new State(latestOutput, latestOutput.plus(limits.firstReport()),
				state.nextCpuCheck(), state.previousCpu()));
		}
		boolean cpuCheckDue = now.compareTo(state.nextCpuCheck()) >= 0;
		if (cpuCheckDue)
		{
			return new ReadCpu(new State(state.lastActivity(), state.nextReport(), now.plus(limits.cpuInterval()),
				state.previousCpu()), now);
		}
		return decideQuietness(state, now);
	}

	/**
	 * Applies a complete successful CPU snapshot and resets inactivity when its accumulated CPU increase meets the limit.
	 *
	 * @param request    pending CPU observation and its observation time
	 * @param currentCpu cumulative CPU duration for each currently observed positive process ID
	 * @return continued monitoring, a report, or an inactivity stop
	 * @throws NullPointerException     if an argument, map key, or map value is null
	 * @throws IllegalArgumentException if a process ID is not positive or a cumulative CPU duration is negative
	 * @throws DateTimeException       if a computed deadline exceeds the supported instant range
	 * @throws ArithmeticException     if duration or deadline arithmetic overflows
	 */
	public Decision cpuRead(ReadCpu request, Map<Long, Duration> currentCpu)
	{
		State pending = request.state();
		currentCpu = copyCpu(currentCpu);
		Duration usedCpu = Duration.ZERO;
		for (Map.Entry<Long, Duration> entry : currentCpu.entrySet())
		{
			Duration previous = pending.previousCpu().getOrDefault(entry.getKey(), Duration.ZERO);
			Duration increase = entry.getValue().minus(previous);
			if (increase.isPositive())
				usedCpu = usedCpu.plus(increase);
		}
		boolean cpuActive = usedCpu.compareTo(limits.minimumCpu()) >= 0;
		if (cpuActive)
		{
			return new Continue(new State(request.now(), request.now().plus(limits.firstReport()),
				pending.nextCpuCheck(), currentCpu));
		}
		State updated = new State(pending.lastActivity(), pending.nextReport(), pending.nextCpuCheck(), currentCpu);
		return decideQuietness(updated, request.now());
	}

	/**
	 * Records that CPU activity is unknown without treating the failed observation as inactivity.
	 *
	 * @param request pending CPU observation
	 * @return monitoring failure with unchanged pending state and its quiet duration
	 * @throws NullPointerException if {@code request} is null
	 * @throws ArithmeticException if the quiet duration overflows
	 */
	public MonitoringFailure cpuFailed(ReadCpu request)
	{
		return new MonitoringFailure(request.state(), Duration.between(request.state().lastActivity(), request.now()));
	}

	/**
	 * Copies a CPU snapshot after validating its process identifiers and cumulative durations.
	 *
	 * @param cpu cumulative CPU duration by positive process ID
	 * @return immutable snapshot
	 * @throws NullPointerException     if the map, a key, or a value is null
	 * @throws IllegalArgumentException if a process ID is not positive or a duration is negative
	 */
	private static Map<Long, Duration> copyCpu(Map<Long, Duration> cpu)
	{
		Map<Long, Duration> snapshot = Map.copyOf(cpu);
		for (Map.Entry<Long, Duration> entry : snapshot.entrySet())
		{
			requireThat(entry.getKey(), "processId").isGreaterThan(0L);
			requireThat(entry.getValue(), "cumulativeCpu").isGreaterThanOrEqualTo(Duration.ZERO);
		}
		return snapshot;
	}

	/**
	 * Gives termination priority over periodic reporting at the same observation time.
	 *
	 * @param state current monitoring state
	 * @param now   wall-clock observation
	 * @return stop, report, or continued monitoring
	 * @throws DateTimeException   if a report deadline exceeds the supported instant range
	 * @throws ArithmeticException if duration or deadline arithmetic overflows
	 */
	private Decision decideQuietness(State state, Instant now)
	{
		Duration quiet = Duration.between(state.lastActivity(), now);
		boolean stopDue = quiet.compareTo(limits.stopAfter()) >= 0;
		if (stopDue)
			return new StopQuietBuild(state, quiet);
		boolean reportDue = now.compareTo(state.nextReport()) >= 0;
		if (reportDue)
		{
			return new WriteReport(new State(state.lastActivity(), now.plus(limits.reportInterval()),
				state.nextCpuCheck(), state.previousCpu()), quiet);
		}
		return new Continue(state);
	}

	/**
	 * Thresholds for inactivity decisions. Signed durations preserve caller-selected wall-clock policy.
	 * <p>
	 * <b>Thread Safety:</b> This class is thread-safe.
	 *
	 * @param firstReport    quiet duration before the first report after activity
	 * @param reportInterval duration between subsequent reports
	 * @param stopAfter      quiet duration at which to stop the build
	 * @param cpuInterval    duration between CPU observations
	 * @param minimumCpu     minimum accumulated CPU increase that counts as activity
	 */
	public record Limits(Duration firstReport, Duration reportInterval, Duration stopAfter, Duration cpuInterval,
		Duration minimumCpu)
	{
		/**
		 * Creates the policy thresholds without restricting their sign.
		 *
		 * @param firstReport    quiet duration before the first report
		 * @param reportInterval duration between subsequent reports
		 * @param stopAfter      quiet duration at which to stop
		 * @param cpuInterval    duration between CPU observations
		 * @param minimumCpu     accumulated CPU increase that counts as activity
		 * @throws NullPointerException if an argument is null
		 */
		public Limits
		{
			requireThat(firstReport, "firstReport").isNotNull();
			requireThat(reportInterval, "reportInterval").isNotNull();
			requireThat(stopAfter, "stopAfter").isNotNull();
			requireThat(cpuInterval, "cpuInterval").isNotNull();
			requireThat(minimumCpu, "minimumCpu").isNotNull();
		}
	}

	/**
	 * Installed activity, report and CPU-observation state for one build.
	 * <p>
	 * <b>Thread Safety:</b> This class is thread-safe.
	 *
	 * @param lastActivity last accepted output or CPU activity time
	 * @param nextReport   next report deadline
	 * @param nextCpuCheck next CPU-observation deadline
	 * @param previousCpu  complete last successful cumulative CPU snapshot by positive process ID
	 */
	public record State(Instant lastActivity, Instant nextReport, Instant nextCpuCheck, Map<Long, Duration> previousCpu)
	{
		/**
		 * Creates monitoring state with an immutable CPU snapshot.
		 *
		 * @param lastActivity last accepted activity time
		 * @param nextReport   next report deadline
		 * @param nextCpuCheck next CPU-observation deadline
		 * @param previousCpu  cumulative CPU snapshot by positive process ID
		 * @throws NullPointerException     if an argument, map key, or map value is null
		 * @throws IllegalArgumentException if a process ID is not positive or a duration is negative
		 */
		public State
		{
			requireThat(lastActivity, "lastActivity").isNotNull();
			requireThat(nextReport, "nextReport").isNotNull();
			requireThat(nextCpuCheck, "nextCpuCheck").isNotNull();
			previousCpu = copyCpu(previousCpu);
		}
	}

	/**
	 * Next monitoring action or a request for the CPU information needed to decide it.
	 * <p>
	 * <b>Thread Safety:</b> Implementations are thread-safe.
	 */
	public sealed interface Observation permits Decision, ReadCpu
	{
		/**
		 * Returns the state to install when performing this action or observation.
		 *
		 * @return resulting monitoring state
		 */
		State state();
	}

	/**
	 * Action decided from complete observations.
	 * <p>
	 * <b>Thread Safety:</b> Implementations are thread-safe.
	 */
	public sealed interface Decision extends Observation permits Continue, WriteReport, StopQuietBuild
	{
	}

	/**
	 * Continues monitoring with the supplied state.
	 * <p>
	 * <b>Thread Safety:</b> This class is thread-safe.
	 *
	 * @param state state to install
	 */
	public record Continue(State state) implements Decision
	{
		/**
		 * Creates a continuation.
		 *
		 * @param state state to install
		 * @throws NullPointerException if {@code state} is null
		 */
		public Continue
		{
			requireThat(state, "state").isNotNull();
		}
	}

	/**
	 * Requests a diagnostic report without treating that report as activity.
	 * <p>
	 * <b>Thread Safety:</b> This class is thread-safe.
	 *
	 * @param state resulting state with the advanced report deadline
	 * @param quiet elapsed wall-clock duration since activity
	 */
	public record WriteReport(State state, Duration quiet) implements Decision
	{
		/**
		 * Creates a report action.
		 *
		 * @param state resulting monitoring state
		 * @param quiet elapsed duration since activity
		 * @throws NullPointerException if an argument is null
		 */
		public WriteReport
		{
			requireThat(state, "state").isNotNull();
			requireThat(quiet, "quiet").isNotNull();
		}
	}

	/**
	 * Requests termination after the quiet duration reaches the stop threshold.
	 * <p>
	 * <b>Thread Safety:</b> This class is thread-safe.
	 *
	 * @param state resulting monitoring state
	 * @param quiet elapsed wall-clock duration since activity
	 */
	public record StopQuietBuild(State state, Duration quiet) implements Decision
	{
		/**
		 * Creates an inactivity stop action.
		 *
		 * @param state resulting monitoring state
		 * @param quiet elapsed duration since activity
		 * @throws NullPointerException if an argument is null
		 */
		public StopQuietBuild
		{
			requireThat(state, "state").isNotNull();
			requireThat(quiet, "quiet").isNotNull();
		}
	}

	/**
	 * Requests a CPU snapshot before inactivity can be decided.
	 * <p>
	 * <b>Thread Safety:</b> This class is thread-safe.
	 *
	 * @param state pending state with its advanced CPU deadline
	 * @param now   wall-clock time associated with this observation
	 */
	public record ReadCpu(State state, Instant now) implements Observation
	{
		/**
		 * Creates a pending CPU observation.
		 *
		 * @param state pending monitoring state
		 * @param now   time associated with this observation
		 * @throws NullPointerException if an argument is null
		 */
		public ReadCpu
		{
			requireThat(state, "state").isNotNull();
			requireThat(now, "now").isNotNull();
		}
	}

	/**
	 * Reports unknown CPU activity for the coordinator to diagnose and clean up.
	 * <p>
	 * <b>Thread Safety:</b> This class is thread-safe.
	 *
	 * @param state unchanged pending monitoring state
	 * @param quiet elapsed wall-clock duration since activity
	 */
	public record MonitoringFailure(State state, Duration quiet)
	{
		/**
		 * Creates a monitoring failure without declaring the build idle.
		 *
		 * @param state unchanged pending state
		 * @param quiet elapsed duration since activity
		 * @throws NullPointerException if an argument is null
		 */
		public MonitoringFailure
		{
			requireThat(state, "state").isNotNull();
			requireThat(quiet, "quiet").isNotNull();
		}
	}
}
