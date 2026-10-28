package com.holdmyspot.codexunleashed.scheduler;

import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.expectThrows;

/**
 * Verifies the memory-sample value contract without acquiring native observations.
 */
public final class MacMemoryTest
{
	/**
	 * Creates memory-sample contract tests.
	 */
	public MacMemoryTest()
	{
	}

	/**
	 * Supplies byte quantities that violate physical-memory or swap ranges.
	 *
	 * @return physical capacity, physical availability, swap capacity and swap occupancy
	 */
	@DataProvider
	public Object[][] invalidSamples()
	{
		return new Object[][] {
			{0L, 0L, 0L, 0L},
			{-1L, 0L, 0L, 0L},
			{1L, -1L, 0L, 0L},
			{1L, 2L, 0L, 0L},
			{1L, 0L, -1L, 0L},
			{1L, 0L, 1L, -1L},
			{1L, 0L, 1L, 2L}
		};
	}

	/**
	 * Rejects invalid byte quantities before a sample can reach admission policy.
	 *
	 * @param totalBytes the invalid sample's physical capacity
	 * @param availableBytes the invalid sample's physical availability
	 * @param swapTotalBytes the invalid sample's swap capacity
	 * @param swapUsedBytes the invalid sample's swap occupancy
	 */
	@Test(dataProvider = "invalidSamples")
	public void rejectsInvalidSamples(long totalBytes, long availableBytes, long swapTotalBytes, long swapUsedBytes)
	{
		expectThrows(IllegalArgumentException.class, () ->
			new MacMemory.Sample(totalBytes, availableBytes, swapTotalBytes, swapUsedBytes, MacMemory.Pressure.NORMAL));
	}

	/**
	 * Rejects an absent pressure observation rather than storing an incomplete sample.
	 */
	@Test
	public void rejectsMissingPressure()
	{
		expectThrows(NullPointerException.class, () -> new MacMemory.Sample(1, 0, 0, 0, null));
	}

	/**
	 * Preserves valid zero counters and exact capacities through the signed-long limit.
	 */
	@Test
	public void preservesValidSamples()
	{
		var zero = new MacMemory.Sample(1, 0, 0, 0, MacMemory.Pressure.NORMAL);
		var largest = new MacMemory.Sample(Long.MAX_VALUE, Long.MAX_VALUE, Long.MAX_VALUE,
			Long.MAX_VALUE, MacMemory.Pressure.CRITICAL);

		assertEquals(zero.availableBytes(), 0L);
		assertEquals(zero.swapUsedBytes(), 0L);
		assertEquals(largest.availableBytes(), Long.MAX_VALUE);
		assertEquals(largest.swapUsedBytes(), Long.MAX_VALUE);
	}
}
