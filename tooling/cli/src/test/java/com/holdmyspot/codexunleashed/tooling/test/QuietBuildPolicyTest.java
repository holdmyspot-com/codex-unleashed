package com.holdmyspot.codexunleashed.tooling.test;

import com.holdmyspot.codexunleashed.tooling.QuietBuildPolicy;
import com.holdmyspot.codexunleashed.tooling.QuietBuildPolicy.Continue;
import com.holdmyspot.codexunleashed.tooling.QuietBuildPolicy.Limits;
import com.holdmyspot.codexunleashed.tooling.QuietBuildPolicy.MonitoringFailure;
import com.holdmyspot.codexunleashed.tooling.QuietBuildPolicy.ReadCpu;
import com.holdmyspot.codexunleashed.tooling.QuietBuildPolicy.State;
import com.holdmyspot.codexunleashed.tooling.QuietBuildPolicy.StopQuietBuild;
import com.holdmyspot.codexunleashed.tooling.QuietBuildPolicy.WriteReport;
import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

/**
 * Verifies {@link QuietBuildPolicy} decisions using supplied times and CPU snapshots.
 */
public final class QuietBuildPolicyTest
{
	/**
	 * Creates policy tests.
	 */
	public QuietBuildPolicyTest()
	{
	}

	/**
	 * Starts activity at invocation time unless output has a future timestamp.
	 */
	@Test
	public void startsFromLaterActivity()
	{
		QuietBuildPolicy policy = policy();
		requireThat(policy.start(time(10), time(0)), "invocationState").
			isEqualTo(new State(time(10), time(20), Instant.EPOCH, Map.of()));
		requireThat(policy.start(time(10), time(15)), "futureOutputState").
			isEqualTo(new State(time(15), time(25), Instant.EPOCH, Map.of()));
	}

	/**
	 * New output resets report timing without changing a due CPU observation or its previous snapshot.
	 */
	@Test
	public void outputPrecedesCpuAndTimeout()
	{
		Map<Long, Duration> cpu = Map.of(1L, Duration.ofSeconds(3));
		State state = new State(time(0), time(10), time(5), cpu);
		Continue expected = new Continue(new State(time(60), time(70), time(5), cpu));
		requireThat(policy().observe(state, time(60), time(60)), "decision").isEqualTo(expected);
	}

	/**
	 * A due CPU read advances only its own deadline before activity can be classified.
	 */
	@Test
	public void cpuRequestAdvancesOnlyItsDeadline()
	{
		Map<Long, Duration> cpu = Map.of(1L, Duration.ofSeconds(3));
		State state = new State(time(0), time(10), time(5), cpu);
		ReadCpu expected = new ReadCpu(new State(time(0), time(10), time(120), cpu), time(60));
		requireThat(policy().observe(state, time(60), time(0)), "decision").isEqualTo(expected);
	}

	/**
	 * A CPU read that is not due neither clears nor reinterprets the previous successful snapshot.
	 */
	@Test
	public void notDueCpuPreservesSnapshot()
	{
		State state = new State(time(10), time(20), time(100), Map.of(1L, Duration.ofSeconds(3)));
		requireThat(policy().observe(state, time(11), time(10)), "decision").isEqualTo(new Continue(state));
	}

	/**
	 * CPU activity at the exact fractional threshold rescues a build whose quiet timeout would otherwise expire.
	 */
	@Test
	public void exactCpuThresholdRescuesTimeout()
	{
		QuietBuildPolicy policy = policy();
		State state = new State(time(0), time(10), time(0), Map.of());
		ReadCpu request = (ReadCpu) policy.observe(state, time(60), time(0));
		Map<Long, Duration> cpu = Map.of(1L, Duration.ofMillis(100), 2L, Duration.ofMillis(200));
		Continue expected = new Continue(new State(time(60), time(70), time(120), cpu));
		requireThat(policy.cpuRead(request, cpu), "decision").isEqualTo(expected);
	}

	/**
	 * Existing cumulative CPU is subtracted before comparing the activity threshold.
	 */
	@Test
	public void countsCpuSincePreviousSnapshot()
	{
		Map<Long, Duration> before = Map.of(1L, Duration.ofSeconds(5));
		Map<Long, Duration> after = Map.of(1L, Duration.ofMillis(5_300));
		ReadCpu request = new ReadCpu(new State(time(0), time(10), time(120), before), time(60));
		Continue expected = new Continue(new State(time(60), time(70), time(120), after));
		requireThat(policy().cpuRead(request, after), "decision").isEqualTo(expected);
	}

	/**
	 * New processes contribute their full CPU while disappeared processes and regressed counters add no negative delta.
	 */
	@Test
	public void handlesChangedProcessPopulation()
	{
		Map<Long, Duration> before = Map.of(1L, Duration.ofSeconds(1), 2L, Duration.ofSeconds(100));
		Map<Long, Duration> after = Map.of(1L, Duration.ofMillis(900), 3L, Duration.ofMillis(300));
		ReadCpu request = new ReadCpu(new State(time(0), time(10), time(120), before), time(60));
		Continue expected = new Continue(new State(time(60), time(70), time(120), after));
		requireThat(policy().cpuRead(request, after), "decision").isEqualTo(expected);
	}

	/**
	 * A successful empty snapshot clears old process counters and can establish an inactivity timeout.
	 */
	@Test
	public void emptyCpuSnapshotAllowsTimeout()
	{
		ReadCpu request = new ReadCpu(new State(time(0), time(10), time(120),
			Map.of(1L, Duration.ofSeconds(100))), time(60));
		StopQuietBuild expected = new StopQuietBuild(new State(time(0), time(10), time(120), Map.of()),
			Duration.ofSeconds(60));
		requireThat(policy().cpuRead(request, Map.of()), "decision").isEqualTo(expected);
	}

	/**
	 * CPU below the threshold permits the due report and retains the complete new snapshot.
	 */
	@Test
	public void lowCpuAllowsDueReport()
	{
		Map<Long, Duration> cpu = Map.of(1L, Duration.ofMillis(299));
		ReadCpu request = new ReadCpu(new State(time(0), time(10), time(80), Map.of()), time(20));
		WriteReport expected = new WriteReport(new State(time(0), time(35), time(80), cpu), Duration.ofSeconds(20));
		requireThat(policy().cpuRead(request, cpu), "decision").isEqualTo(expected);
	}

	/**
	 * Reporting advances its deadline from the observation time without resetting activity.
	 */
	@Test
	public void reportPreservesActivity()
	{
		State state = new State(time(0), time(10), time(100), Map.of(1L, Duration.ofSeconds(3)));
		WriteReport expected = new WriteReport(new State(time(0), time(35), time(100), state.previousCpu()),
			Duration.ofSeconds(20));
		requireThat(policy().observe(state, time(20), time(0)), "decision").isEqualTo(expected);
	}

	/**
	 * An exact stop deadline takes precedence over a simultaneously due report.
	 */
	@Test
	public void timeoutPrecedesReport()
	{
		State state = new State(time(0), time(10), time(100), Map.of());
		requireThat(policy().observe(state, time(50), time(0)), "decision").
			isEqualTo(new StopQuietBuild(state, Duration.ofSeconds(50)));
	}

	/**
	 * A failed CPU read returns a distinct monitoring failure with its pending state intact.
	 */
	@Test
	public void failedCpuRemainsUnknown()
	{
		State state = new State(time(0), time(10), time(120), Map.of(1L, Duration.ofSeconds(3)));
		ReadCpu request = new ReadCpu(state, time(60));
		requireThat(policy().cpuFailed(request), "failure").
			isEqualTo(new MonitoringFailure(state, Duration.ofSeconds(60)));
	}

	/**
	 * A backward clock observation preserves state until an actual deadline is reached.
	 */
	@Test
	public void rollbackPreservesState()
	{
		State state = new State(time(10), time(20), time(100), Map.of());
		requireThat(policy().observe(state, time(5), time(10)), "decision").isEqualTo(new Continue(state));
		requireThat(policy().cpuFailed(new ReadCpu(state, time(5))), "failure").
			isEqualTo(new MonitoringFailure(state, Duration.ofSeconds(-5)));
	}

	/**
	 * Signed policy durations remain accepted and affect deadlines without clamping.
	 */
	@Test
	public void preservesSignedLimits()
	{
		Duration negative = Duration.ofSeconds(-1);
		QuietBuildPolicy policy = new QuietBuildPolicy(new Limits(negative, negative, negative, negative, negative));
		State state = policy.start(time(10), time(0));
		requireThat(state, "initialState").isEqualTo(new State(time(10), time(9), Instant.EPOCH, Map.of()));
		ReadCpu request = (ReadCpu) policy.observe(state, time(10), time(0));
		requireThat(policy.cpuRead(request, Map.of()), "decision").
			isEqualTo(new Continue(new State(time(10), time(9), time(9), Map.of())));
	}

	/**
	 * Monitoring state and successful CPU results retain snapshots rather than mutable caller maps.
	 */
	@Test
	public void copiesCpuInputs()
	{
		Map<Long, Duration> cpu = new HashMap<>();
		cpu.put(1L, Duration.ofMillis(300));
		State state = new State(time(0), time(10), time(120), cpu);
		Continue expected = new Continue(new State(time(60), time(70), time(120), cpu));
		ReadCpu request = new ReadCpu(new State(time(0), time(10), time(120), Map.of()), time(60));
		QuietBuildPolicy.Decision decision = policy().cpuRead(request, cpu);
		cpu.clear();

		requireThat(state.previousCpu(), "initialSnapshot").isEqualTo(Map.of(1L, Duration.ofMillis(300)));
		requireThat(decision, "decision").isEqualTo(expected);
	}

	/**
	 * A retained snapshot cannot be modified through its accessor.
	 */
	@Test(expectedExceptions = UnsupportedOperationException.class)
	public void snapshotAccessorIsImmutable()
	{
		new State(time(0), time(10), time(120), Map.of()).previousCpu().put(1L, Duration.ZERO);
	}

	/**
	 * Supplies invalid process identifiers and cumulative CPU counters.
	 *
	 * @return invalid CPU snapshots
	 */
	@DataProvider
	public Object[][] invalidCpu()
	{
		return new Object[][]
		{
			{Map.of(0L, Duration.ZERO)},
			{Map.of(-1L, Duration.ZERO)},
			{Map.of(1L, Duration.ofNanos(-1))}
		};
	}

	/**
	 * State construction rejects invalid CPU snapshots before retaining them.
	 *
	 * @param cpu invalid CPU snapshot
	 */
	@Test(dataProvider = "invalidCpu", expectedExceptions = IllegalArgumentException.class)
	public void rejectsInvalidStateCpu(Map<Long, Duration> cpu)
	{
		new State(time(0), time(10), time(120), cpu);
	}

	/**
	 * Successful observation input rejects invalid CPU counters instead of treating them as inactivity.
	 *
	 * @param cpu invalid CPU snapshot
	 */
	@Test(dataProvider = "invalidCpu", expectedExceptions = IllegalArgumentException.class)
	public void rejectsInvalidObservedCpu(Map<Long, Duration> cpu)
	{
		ReadCpu request = new ReadCpu(new State(time(0), time(10), time(120), Map.of()), time(60));
		policy().cpuRead(request, cpu);
	}

	/**
	 * Null observation time is rejected even when new output otherwise determines the action.
	 */
	@Test(expectedExceptions = NullPointerException.class)
	public void rejectsNullObservationTime()
	{
		policy().observe(new State(time(0), time(10), time(120), Map.of()), null, time(60));
	}

	/**
	 * Null stored limits cannot create an unusable policy.
	 */
	@Test(expectedExceptions = NullPointerException.class)
	public void rejectsNullLimit()
	{
		new Limits(null, Duration.ZERO, Duration.ZERO, Duration.ZERO, Duration.ZERO);
	}

	/**
	 * Deadline overflow remains an explicit time failure rather than a false inactivity decision.
	 */
	@Test(expectedExceptions = DateTimeException.class)
	public void deadlineOverflowIsExplicit()
	{
		policy().start(Instant.MAX, Instant.EPOCH);
	}

	/**
	 * Creates a policy with exact fractional CPU thresholds.
	 *
	 * @return policy with ten-second first reports, fifteen-second intervals and a fifty-second stop threshold
	 */
	private static QuietBuildPolicy policy()
	{
		return new QuietBuildPolicy(new Limits(Duration.ofSeconds(10), Duration.ofSeconds(15), Duration.ofSeconds(50),
			Duration.ofSeconds(60), Duration.ofMillis(300)));
	}

	/**
	 * Creates a supplied wall-clock observation relative to the epoch.
	 *
	 * @param seconds epoch-relative observation in seconds
	 * @return exact observation instant
	 */
	private static Instant time(long seconds)
	{
		return Instant.ofEpochSecond(seconds);
	}
}
