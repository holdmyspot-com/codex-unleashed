package com.holdmyspot.codexunleashed.tooling.test;

import com.holdmyspot.codexunleashed.tooling.QuietBuildCapture;
import com.holdmyspot.codexunleashed.tooling.QuietBuildResources;
import com.holdmyspot.codexunleashed.tooling.QuietBuildResources.DarwinCounters;
import com.holdmyspot.codexunleashed.tooling.QuietBuildResources.DarwinCpuTicks;
import com.holdmyspot.codexunleashed.tooling.QuietBuildResources.LinuxCounters;
import com.holdmyspot.codexunleashed.tooling.QuietBuildResources.LinuxCpuState;
import com.holdmyspot.codexunleashed.tooling.QuietBuildResources.PagingCounter;
import com.holdmyspot.codexunleashed.tooling.QuietBuildResources.Sample;
import com.holdmyspot.codexunleashed.tooling.QuietBuildResources.WindowsCounters;
import com.holdmyspot.codexunleashed.tooling.QuietBuildResources.WindowsCpuTicks;
import java.math.BigInteger;
import java.nio.file.Path;
import java.time.Instant;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;
import java.util.OptionalInt;
import org.testng.annotations.Test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

/**
 * Verifies immutable snapshots and unrestricted values in quiet-build resource records.
 */
public final class QuietBuildResourcesTest
{
	/**
	 * Creates resource-domain tests.
	 */
	public QuietBuildResourcesTest()
	{
	}

	/**
	 * Record snapshots retain their original map values and reject mutation through accessors.
	 */
	@Test
	public void snapshotsRemainIndependentAndImmutable()
	{
		Map<LinuxCpuState, BigInteger> ticks = new EnumMap<>(LinuxCpuState.class);
		ticks.put(LinuxCpuState.USER, BigInteger.ONE);
		Map<String, BigInteger> memoryStalls = new HashMap<>();
		memoryStalls.put("some arbitrary row", BigInteger.TEN);
		Map<String, BigInteger> cpuStalls = new HashMap<>();
		cpuStalls.put("other row", BigInteger.valueOf(-3));
		LinuxCounters counters = new LinuxCounters(ticks, BigInteger.ONE, BigInteger.TEN, BigInteger.ZERO,
			BigInteger.valueOf(-1), BigInteger.ZERO, BigInteger.ZERO, BigInteger.ZERO, memoryStalls, cpuStalls);
		Map<String, String> environment = new HashMap<>(Map.of("CHILD_ONLY", "present"));
		QuietBuildCapture.Context context = new QuietBuildCapture.Context(Path.of("relative"), Path.of("tmp"),
			environment);

		ticks.put(LinuxCpuState.IDLE, BigInteger.TWO);
		memoryStalls.clear();
		cpuStalls.put("later", BigInteger.ONE);
		environment.put("LATER", "changed");

		requireThat(counters.cpuTicks(), "cpuTicks").isEqualTo(Map.of(LinuxCpuState.USER, BigInteger.ONE));
		requireThat(counters.memoryStallMicroseconds(), "memoryStalls").isEqualTo(Map.of("some arbitrary row",
			BigInteger.TEN));
		requireThat(counters.cpuStallMicroseconds(), "cpuStalls").isEqualTo(Map.of("other row", BigInteger.valueOf(-3)));
		requireThat(context.environment(), "environment").isEqualTo(Map.of("CHILD_ONLY", "present"));
		assertUnsupportedOperation(() -> counters.cpuTicks().put(LinuxCpuState.NICE, BigInteger.ONE));
		assertUnsupportedOperation(() -> counters.memoryStallMicroseconds().clear());
		assertUnsupportedOperation(() -> counters.cpuStallMicroseconds().clear());
		assertUnsupportedOperation(() -> context.environment().put("X", "Y"));
	}

	/**
	 * Typed resource samples preserve large and signed counters, absent processor counts and non-finite paging values.
	 */
	@Test
	public void recordsPreserveExactSuppliedValues()
	{
		BigInteger large = BigInteger.ONE.shiftLeft(130);
		DarwinCounters darwin = new DarwinCounters(new DarwinCpuTicks(large.negate(), BigInteger.valueOf(-2),
			BigInteger.ZERO, large), BigInteger.valueOf(-1), large, BigInteger.ZERO, BigInteger.valueOf(-3),
			BigInteger.valueOf(4), BigInteger.valueOf(5), BigInteger.valueOf(6), BigInteger.valueOf(7),
			BigInteger.valueOf(8));
		WindowsCounters windows = new WindowsCounters(new WindowsCpuTicks(BigInteger.ZERO, BigInteger.ONE,
			BigInteger.TWO), OptionalInt.empty(), large.negate(), BigInteger.ZERO, BigInteger.valueOf(-5),
			BigInteger.valueOf(6), Map.of(PagingCounter.PAGES_INPUT_PER_SECOND, Double.NaN));
		LinuxCounters empty = new LinuxCounters(Map.of(), BigInteger.ZERO, BigInteger.ZERO, BigInteger.ZERO,
			BigInteger.ZERO, BigInteger.ZERO, BigInteger.ZERO, BigInteger.ZERO, Map.of(), Map.of());
		Instant time = Instant.parse("2026-11-13T12:34:56Z");
		Sample sample = new Sample(time, darwin);

		requireThat(darwin.cpuTicks().user(), "userTicks").isEqualTo(large.negate());
		requireThat(darwin.processors(), "processors").isEqualTo(BigInteger.valueOf(-1));
		requireThat(darwin.memoryTotalBytes(), "memoryTotal").isEqualTo(large);
		requireThat(windows.processors(), "windowsProcessors").isEqualTo(OptionalInt.empty());
		requireThat(windows.memoryTotalBytes(), "windowsMemory").isEqualTo(large.negate());
		requireThat(Double.isNaN(windows.paging().get(PagingCounter.PAGES_INPUT_PER_SECOND)), "pagingNaN").isTrue();
		requireThat(empty.cpuTicks(), "emptyCpuTicks").isEmpty();
		requireThat(empty.memoryStallMicroseconds(), "emptyMemoryStalls").isEmpty();
		requireThat(sample.time(), "sampleTime").isEqualTo(time);
		requireThat(sample.outcome(), "sampleOutcome").isEqualTo(darwin);
	}

	/**
	 * Required record references reject null values and null map keys or values.
	 */
	@Test
	@SuppressWarnings("PMD.UseEnumCollections")
	public void rejectsNullRequiredValuesAndMapEntries()
	{
		Map<LinuxCpuState, BigInteger> nullKey = new HashMap<>();
		nullKey.put(null, BigInteger.ONE);
		Map<LinuxCpuState, BigInteger> nullValue = new EnumMap<>(LinuxCpuState.class);
		nullValue.put(LinuxCpuState.USER, null);
		Map<String, BigInteger> nullLabel = new HashMap<>();
		nullLabel.put(null, BigInteger.ONE);
		Map<PagingCounter, Double> nullPagingValue = new EnumMap<>(PagingCounter.class);
		nullPagingValue.put(PagingCounter.PAGES_INPUT_PER_SECOND, null);
		Map<String, String> nullEnvironmentValue = new HashMap<>();
		nullEnvironmentValue.put("CHILD", null);
		Map<PagingCounter, Double> paging = new EnumMap<>(PagingCounter.class);
		paging.put(PagingCounter.PAGES_INPUT_PER_SECOND, 1.0);
		WindowsCounters windowsSnapshot = new WindowsCounters(new WindowsCpuTicks(BigInteger.ZERO, BigInteger.ZERO,
			BigInteger.ZERO), OptionalInt.empty(), BigInteger.ZERO, BigInteger.ZERO, BigInteger.ZERO,
			BigInteger.ZERO, paging);
		paging.clear();
		requireThat(windowsSnapshot.paging(), "pagingSnapshot").isEqualTo(
			Map.of(PagingCounter.PAGES_INPUT_PER_SECOND, 1.0));
		assertUnsupportedOperation(() -> windowsSnapshot.paging().clear());
		assertNullPointer(() -> new LinuxCounters(nullKey, BigInteger.ONE, BigInteger.ONE, BigInteger.ONE,
			BigInteger.ONE, BigInteger.ONE, BigInteger.ONE, BigInteger.ONE, Map.of(), Map.of()));
		assertNullPointer(() -> new LinuxCounters(nullValue, BigInteger.ONE, BigInteger.ONE, BigInteger.ONE,
			BigInteger.ONE, BigInteger.ONE, BigInteger.ONE, BigInteger.ONE, Map.of(), Map.of()));
		assertNullPointer(() -> new LinuxCounters(Map.of(), BigInteger.ONE, BigInteger.ONE, BigInteger.ONE,
			BigInteger.ONE, BigInteger.ONE, BigInteger.ONE, BigInteger.ONE, nullLabel, Map.of()));
		assertNullPointer(() -> new LinuxCounters(null, BigInteger.ONE, BigInteger.ONE, BigInteger.ONE,
			BigInteger.ONE, BigInteger.ONE, BigInteger.ONE, BigInteger.ONE, Map.of(), Map.of()));
		assertNullPointer(() -> new LinuxCounters(Map.of(), null, BigInteger.ONE, BigInteger.ONE, BigInteger.ONE,
			BigInteger.ONE, BigInteger.ONE, BigInteger.ONE, Map.of(), Map.of()));
		assertNullPointer(() -> new LinuxCounters(Map.of(), BigInteger.ONE, null, BigInteger.ONE, BigInteger.ONE,
			BigInteger.ONE, BigInteger.ONE, BigInteger.ONE, Map.of(), Map.of()));
		assertNullPointer(() -> new LinuxCounters(Map.of(), BigInteger.ONE, BigInteger.ONE, null, BigInteger.ONE,
			BigInteger.ONE, BigInteger.ONE, BigInteger.ONE, Map.of(), Map.of()));
		assertNullPointer(() -> new LinuxCounters(Map.of(), BigInteger.ONE, BigInteger.ONE, BigInteger.ONE, null,
			BigInteger.ONE, BigInteger.ONE, BigInteger.ONE, Map.of(), Map.of()));
		assertNullPointer(() -> new LinuxCounters(Map.of(), BigInteger.ONE, BigInteger.ONE, BigInteger.ONE,
			BigInteger.ONE, null, BigInteger.ONE, BigInteger.ONE, Map.of(), Map.of()));
		assertNullPointer(() -> new LinuxCounters(Map.of(), BigInteger.ONE, BigInteger.ONE, BigInteger.ONE,
			BigInteger.ONE, BigInteger.ONE, null, BigInteger.ONE, Map.of(), Map.of()));
		assertNullPointer(() -> new LinuxCounters(Map.of(), BigInteger.ONE, BigInteger.ONE, BigInteger.ONE,
			BigInteger.ONE, BigInteger.ONE, BigInteger.ONE, null, Map.of(), Map.of()));
		assertNullPointer(() -> new LinuxCounters(Map.of(), BigInteger.ONE, BigInteger.ONE, BigInteger.ONE,
			BigInteger.ONE, BigInteger.ONE, BigInteger.ONE, BigInteger.ONE, null, Map.of()));
		assertNullPointer(() -> new LinuxCounters(Map.of(), BigInteger.ONE, BigInteger.ONE, BigInteger.ONE,
			BigInteger.ONE, BigInteger.ONE, BigInteger.ONE, BigInteger.ONE, Map.of(), null));
		assertNullPointer(() -> new DarwinCpuTicks(null, BigInteger.ZERO, BigInteger.ZERO, BigInteger.ZERO));
		assertNullPointer(() -> new DarwinCpuTicks(BigInteger.ZERO, null, BigInteger.ZERO, BigInteger.ZERO));
		assertNullPointer(() -> new DarwinCpuTicks(BigInteger.ZERO, BigInteger.ZERO, null, BigInteger.ZERO));
		assertNullPointer(() -> new DarwinCpuTicks(BigInteger.ZERO, BigInteger.ZERO, BigInteger.ZERO, null));
		assertNullPointer(() -> new DarwinCounters(null, BigInteger.ONE, BigInteger.ONE, BigInteger.ONE,
			BigInteger.ONE, BigInteger.ONE, BigInteger.ONE, BigInteger.ONE, BigInteger.ONE, BigInteger.ONE));
		assertNullPointer(() -> new DarwinCounters(new DarwinCpuTicks(BigInteger.ZERO, BigInteger.ZERO,
			BigInteger.ZERO, BigInteger.ZERO), null, BigInteger.ONE, BigInteger.ONE, BigInteger.ONE, BigInteger.ONE,
			BigInteger.ONE, BigInteger.ONE, BigInteger.ONE, BigInteger.ONE));
		assertNullPointer(() -> new DarwinCounters(new DarwinCpuTicks(BigInteger.ZERO, BigInteger.ZERO,
			BigInteger.ZERO, BigInteger.ZERO), BigInteger.ONE, null, BigInteger.ONE, BigInteger.ONE, BigInteger.ONE,
			BigInteger.ONE, BigInteger.ONE, BigInteger.ONE, BigInteger.ONE));
		assertNullPointer(() -> new DarwinCounters(new DarwinCpuTicks(BigInteger.ZERO, BigInteger.ZERO,
			BigInteger.ZERO, BigInteger.ZERO), BigInteger.ONE, BigInteger.ONE, null, BigInteger.ONE, BigInteger.ONE,
			BigInteger.ONE, BigInteger.ONE, BigInteger.ONE, BigInteger.ONE));
		assertNullPointer(() -> new DarwinCounters(new DarwinCpuTicks(BigInteger.ZERO, BigInteger.ZERO,
			BigInteger.ZERO, BigInteger.ZERO), BigInteger.ONE, BigInteger.ONE, BigInteger.ONE, null, BigInteger.ONE,
			BigInteger.ONE, BigInteger.ONE, BigInteger.ONE, BigInteger.ONE));
		assertNullPointer(() -> new DarwinCounters(new DarwinCpuTicks(BigInteger.ZERO, BigInteger.ZERO,
			BigInteger.ZERO, BigInteger.ZERO), BigInteger.ONE, BigInteger.ONE, BigInteger.ONE, BigInteger.ONE, null,
			BigInteger.ONE, BigInteger.ONE, BigInteger.ONE, BigInteger.ONE));
		assertNullPointer(() -> new DarwinCounters(new DarwinCpuTicks(BigInteger.ZERO, BigInteger.ZERO,
			BigInteger.ZERO, BigInteger.ZERO), BigInteger.ONE, BigInteger.ONE, BigInteger.ONE, BigInteger.ONE,
			BigInteger.ONE, null, BigInteger.ONE, BigInteger.ONE, BigInteger.ONE));
		assertNullPointer(() -> new DarwinCounters(new DarwinCpuTicks(BigInteger.ZERO, BigInteger.ZERO,
			BigInteger.ZERO, BigInteger.ZERO), BigInteger.ONE, BigInteger.ONE, BigInteger.ONE, BigInteger.ONE,
			BigInteger.ONE, BigInteger.ONE, null, BigInteger.ONE, BigInteger.ONE));
		assertNullPointer(() -> new DarwinCounters(new DarwinCpuTicks(BigInteger.ZERO, BigInteger.ZERO,
			BigInteger.ZERO, BigInteger.ZERO), BigInteger.ONE, BigInteger.ONE, BigInteger.ONE, BigInteger.ONE,
			BigInteger.ONE, BigInteger.ONE, BigInteger.ONE, null, BigInteger.ONE));
		assertNullPointer(() -> new DarwinCounters(new DarwinCpuTicks(BigInteger.ZERO, BigInteger.ZERO,
			BigInteger.ZERO, BigInteger.ZERO), BigInteger.ONE, BigInteger.ONE, BigInteger.ONE, BigInteger.ONE,
			BigInteger.ONE, BigInteger.ONE, BigInteger.ONE, BigInteger.ONE, null));
		assertNullPointer(() -> new WindowsCounters(null, OptionalInt.empty(), BigInteger.ONE, BigInteger.ONE,
			BigInteger.ONE, BigInteger.ONE, Map.of()));
		assertNullPointer(() -> new WindowsCpuTicks(null, BigInteger.ZERO, BigInteger.ZERO));
		assertNullPointer(() -> new WindowsCpuTicks(BigInteger.ZERO, null, BigInteger.ZERO));
		assertNullPointer(() -> new WindowsCpuTicks(BigInteger.ZERO, BigInteger.ZERO, null));
		assertNullPointer(() -> new WindowsCounters(new WindowsCpuTicks(BigInteger.ZERO, BigInteger.ZERO,
			BigInteger.ZERO), null, BigInteger.ONE, BigInteger.ONE, BigInteger.ONE, BigInteger.ONE, Map.of()));
		assertNullPointer(() -> new WindowsCounters(new WindowsCpuTicks(BigInteger.ZERO, BigInteger.ZERO,
			BigInteger.ZERO), OptionalInt.empty(), null, BigInteger.ONE, BigInteger.ONE, BigInteger.ONE, Map.of()));
		assertNullPointer(() -> new WindowsCounters(new WindowsCpuTicks(BigInteger.ZERO, BigInteger.ZERO,
			BigInteger.ZERO), OptionalInt.empty(), BigInteger.ONE, null, BigInteger.ONE, BigInteger.ONE, Map.of()));
		assertNullPointer(() -> new WindowsCounters(new WindowsCpuTicks(BigInteger.ZERO, BigInteger.ZERO,
			BigInteger.ZERO), OptionalInt.empty(), BigInteger.ONE, BigInteger.ONE, null, BigInteger.ONE, Map.of()));
		assertNullPointer(() -> new WindowsCounters(new WindowsCpuTicks(BigInteger.ZERO, BigInteger.ZERO,
			BigInteger.ZERO), OptionalInt.empty(), BigInteger.ONE, BigInteger.ONE, BigInteger.ONE, null, Map.of()));
		assertNullPointer(() -> new WindowsCounters(new WindowsCpuTicks(BigInteger.ZERO, BigInteger.ZERO,
			BigInteger.ZERO), OptionalInt.empty(), BigInteger.ONE, BigInteger.ONE, BigInteger.ONE, BigInteger.ONE, null));
		assertNullPointer(() -> new WindowsCounters(new WindowsCpuTicks(BigInteger.ZERO, BigInteger.ZERO,
			BigInteger.ZERO), OptionalInt.empty(), BigInteger.ONE, BigInteger.ONE, BigInteger.ONE,
			BigInteger.ONE, nullPagingValue));
		assertNullPointer(() -> new Sample(null, new QuietBuildResources.Failure("failure")));
		assertNullPointer(() -> new Sample(Instant.EPOCH, null));
		assertNullPointer(() -> new QuietBuildResources.Failure(null));
		assertNullPointer(() -> new QuietBuildCapture.Context(null, Path.of("tmp"), Map.of()));
		assertNullPointer(() -> new QuietBuildCapture.Context(Path.of("work"), null, Map.of()));
		assertNullPointer(() -> new QuietBuildCapture.Context(Path.of("work"), Path.of("tmp"), null));
		assertNullPointer(() -> new QuietBuildCapture.Context(Path.of("work"), Path.of("tmp"), nullEnvironmentValue));
	}

	/**
	 * Requires the supplied construction to reject a null reference.
	 *
	 * @param construction record or context construction
	 */
	private static void assertNullPointer(Runnable construction)
	{
		boolean rejected = false;
		try
		{
			construction.run();
		}
		catch (NullPointerException exception)
		{
			rejected = exception != null;
		}
		requireThat(rejected, "nullRejected").isTrue();
	}

	/**
	 * Requires a snapshot accessor to reject mutation.
	 *
	 * @param mutation attempted map mutation
	 */
	private static void assertUnsupportedOperation(Runnable mutation)
	{
		boolean rejected = false;
		try
		{
			mutation.run();
		}
		catch (UnsupportedOperationException exception)
		{
			rejected = exception != null;
		}
		requireThat(rejected, "mutationRejected").isTrue();
	}
}
