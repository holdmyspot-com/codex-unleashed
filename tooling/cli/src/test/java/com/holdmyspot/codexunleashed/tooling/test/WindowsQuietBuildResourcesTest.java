package com.holdmyspot.codexunleashed.tooling.test;

import com.holdmyspot.codexunleashed.tooling.QuietBuildResources.PagingCounter;
import com.holdmyspot.codexunleashed.tooling.QuietBuildResources.WindowsCounters;
import com.holdmyspot.codexunleashed.tooling.QuietBuildResources.WindowsCpuTicks;
import com.holdmyspot.codexunleashed.tooling.WindowsQuietBuildResources;
import com.holdmyspot.codexunleashed.tooling.WindowsQuietBuildResources.NativeAccess;
import com.holdmyspot.codexunleashed.tooling.WindowsQuietBuildResources.NativeSnapshot;
import java.io.IOException;
import java.math.BigInteger;
import java.util.EnumMap;
import java.util.Map;
import java.util.OptionalInt;
import org.testng.annotations.Test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

/** Verifies Windows counter interpretation and reader ownership using controlled native snapshots. */
public final class WindowsQuietBuildResourcesTest
{
	/** Creates independent Windows resource reader tests. */
	public WindowsQuietBuildResourcesTest()
	{
	}

	/**
	 * Subtracting idle ticks and available commit produces system ticks and used commit.
	 *
	 * @throws IOException if the controlled counter read fails
	 */
	@Test
	public void subtractsIdleAndAvailableCommit() throws IOException
	{
		NativeSnapshot snapshot = snapshot(BigInteger.valueOf(9), BigInteger.valueOf(40), BigInteger.valueOf(11),
			OptionalInt.of(8), BigInteger.valueOf(1024), BigInteger.valueOf(512), BigInteger.valueOf(2048),
			BigInteger.ZERO, Map.of());

		try (WindowsQuietBuildResources reader = new WindowsQuietBuildResources(new FakeNativeAccess(snapshot)))
		{
			WindowsCounters counters = reader.read();
			requireThat(counters.cpuTicks(), "cpuTicks").isEqualTo(new WindowsCpuTicks(BigInteger.valueOf(9),
				BigInteger.valueOf(31), BigInteger.valueOf(11)));
			requireThat(counters.commitUsedBytes(), "commitUsedBytes").isEqualTo(BigInteger.valueOf(2048));
		}
	}

	/**
	 * Missing processor-group counts remain absent in the Windows counter result.
	 *
	 * @throws IOException if the controlled counter read fails
	 */
	@Test
	public void retainsUnavailableProcessorCount() throws IOException
	{
		NativeSnapshot snapshot = snapshot(BigInteger.ONE, BigInteger.TWO, BigInteger.TEN, OptionalInt.empty(),
			BigInteger.TEN, BigInteger.ONE, BigInteger.valueOf(20), BigInteger.valueOf(5), Map.of());

		try (WindowsQuietBuildResources reader = new WindowsQuietBuildResources(new FakeNativeAccess(snapshot)))
		{
			requireThat(reader.read().processors(), "processors").isEqualTo(OptionalInt.empty());
		}
	}

	/**
	 * Only successfully formatted paging counters appear in the Windows result.
	 *
	 * @throws IOException if the controlled counter read fails
	 */
	@Test
	public void retainsPagingOmissions() throws IOException
	{
		Map<PagingCounter, Double> paging = new EnumMap<>(PagingCounter.class);
		paging.put(PagingCounter.PAGING_FILE_USAGE_PERCENT, 12.5);
		NativeSnapshot snapshot = snapshot(BigInteger.ONE, BigInteger.TWO, BigInteger.TEN, OptionalInt.of(4),
			BigInteger.TEN, BigInteger.ONE, BigInteger.valueOf(20), BigInteger.valueOf(5), paging);
		paging.clear();

		try (WindowsQuietBuildResources reader = new WindowsQuietBuildResources(new FakeNativeAccess(snapshot)))
		{
			requireThat(reader.read().paging(), "paging").isEqualTo(
				Map.of(PagingCounter.PAGING_FILE_USAGE_PERCENT, 12.5));
		}
	}

	/**
	 * Unsigned native values larger than a signed long remain exact through derived counters.
	 *
	 * @throws IOException if the controlled counter read fails
	 */
	@Test
	public void keepsUnsignedNativeCounters() throws IOException
	{
		BigInteger idle = BigInteger.ONE.shiftLeft(70);
		BigInteger kernel = idle.add(BigInteger.valueOf(5));
		BigInteger memory = BigInteger.ONE.shiftLeft(130);
		NativeSnapshot snapshot = snapshot(idle, kernel, BigInteger.ONE.shiftLeft(100), OptionalInt.of(2), memory,
			memory.shiftRight(1), BigInteger.ONE.shiftLeft(101), BigInteger.ONE.shiftLeft(100), Map.of());

		try (WindowsQuietBuildResources reader = new WindowsQuietBuildResources(new FakeNativeAccess(snapshot)))
		{
			WindowsCounters counters = reader.read();
			requireThat(counters.cpuTicks().system(), "systemTicks").isEqualTo(BigInteger.valueOf(5));
			requireThat(counters.memoryTotalBytes(), "memoryTotalBytes").isEqualTo(memory);
			requireThat(counters.commitUsedBytes(), "commitUsedBytes").isEqualTo(BigInteger.ONE.shiftLeft(100));
		}
	}

	/**
	 * Closing a reader closes its native access exactly once, including repeated close calls.
	 *
	 * @throws IOException if closing the reader fails
	 */
	@Test
	public void closesWindowsNativeAccess() throws IOException
	{
		try (FakeNativeAccess access = new FakeNativeAccess(snapshot(BigInteger.ONE, BigInteger.TWO, BigInteger.TEN,
			OptionalInt.of(1), BigInteger.TEN, BigInteger.ONE, BigInteger.TEN, BigInteger.ONE, Map.of()));
			WindowsQuietBuildResources reader = new WindowsQuietBuildResources(access))
		{
			reader.close();
			reader.close();
			requireThat(access.closeCount, "closeCount").isEqualTo(1);
		}
	}

	/**
	 * Reading after close reports that the reader's lifecycle has ended.
	 *
	 * @throws IOException if closing the reader fails
	 */
	@Test(expectedExceptions = IllegalStateException.class,
		expectedExceptionsMessageRegExp = ".*reader is closed.*")
	public void rejectsReadAfterClose() throws IOException
	{
		FakeNativeAccess access = new FakeNativeAccess(snapshot(BigInteger.ONE, BigInteger.TWO, BigInteger.TEN,
			OptionalInt.of(1), BigInteger.TEN, BigInteger.ONE, BigInteger.TEN, BigInteger.ONE, Map.of()));
		try (access; WindowsQuietBuildResources reader = new WindowsQuietBuildResources(access))
		{
			reader.close();
			reader.read();
		}
	}

	/**
	 * Creates a complete native snapshot for one test case.
	 *
	 * @param idle cumulative idle processor ticks
	 * @param kernel cumulative kernel processor ticks including idle
	 * @param user cumulative user processor ticks
	 * @param processors processor count when available
	 * @param memoryTotalBytes total physical memory in bytes
	 * @param memoryAvailableBytes available physical memory in bytes
	 * @param commitLimitBytes system commit limit in bytes
	 * @param commitAvailableBytes available commit in bytes
	 * @param paging successfully formatted paging counters
	 * @return the immutable native counter snapshot
	 */
	private static NativeSnapshot snapshot(BigInteger idle, BigInteger kernel, BigInteger user, OptionalInt processors,
		BigInteger memoryTotalBytes, BigInteger memoryAvailableBytes, BigInteger commitLimitBytes,
		BigInteger commitAvailableBytes, Map<PagingCounter, Double> paging)
	{
		return new NativeSnapshot(idle, kernel, user, processors, memoryTotalBytes, memoryAvailableBytes,
			commitLimitBytes, commitAvailableBytes, paging);
	}

	/** Supplies one controlled native snapshot and records when production closes that access. */
	private static final class FakeNativeAccess implements NativeAccess
	{
		/** Native values returned to the production reader. */
		private final NativeSnapshot snapshot;
		/** Number of times production or test cleanup has closed the fake. */
		private int closeCount;

		/**
		 * Creates a fake for one native snapshot.
		 *
		 * @param snapshot the values supplied to the production reader
		 */
		private FakeNativeAccess(NativeSnapshot snapshot)
		{
			this.snapshot = snapshot;
		}

		/** Supplies the configured values to the production reader. */
		@Override
		public NativeSnapshot read()
		{
			return snapshot;
		}

		/** Records production release of this fake native access. */
		@Override
		public void close()
		{
			closeCount += 1;
		}
	}
}
