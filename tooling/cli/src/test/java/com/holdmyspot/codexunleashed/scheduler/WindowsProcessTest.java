package com.holdmyspot.codexunleashed.scheduler;

import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.expectThrows;

/**
 * Verifies native exit-status and abortion outcomes without launching Windows work.
 */
public final class WindowsProcessTest
{
	/**
	 * Creates process-result contract tests.
	 */
	public WindowsProcessTest()
	{
	}

	/**
	 * Supplies successful, aborted and unsigned native exit statuses.
	 *
	 * @return root status and independent abortion state
	 */
	@DataProvider
	public Object[][] nativeOutcomes()
	{
		return new Object[][] {
			{0L, false},
			{0L, true},
			{125L, true},
			{0x8000_0000L, false},
			{0xffff_ffffL, false}
		};
	}

	/**
	 * Retains the full unsigned status and distinguishes abortion after root success.
	 *
	 * @param rootExit the original native root exit status
	 * @param aborted indicates whether the owner requested abortion
	 */
	@Test(dataProvider = "nativeOutcomes")
	public void preservesNativeOutcomes(long rootExit, boolean aborted)
	{
		var result = new WindowsProcess.Result(rootExit, aborted);

		assertEquals(result.rootExit(), rootExit);
		assertEquals(result.aborted(), aborted);
	}

	/**
	 * Supplies values outside the native unsigned DWORD range.
	 *
	 * @return rejected root statuses
	 */
	@DataProvider
	public Object[][] invalidStatuses()
	{
		return new Object[][] {
			{-1L},
			{0x1_0000_0000L}
		};
	}

	/**
	 * Rejects statuses that could otherwise be truncated into a successful native value.
	 *
	 * @param rootExit the out-of-range root status
	 */
	@Test(dataProvider = "invalidStatuses")
	public void rejectsOutOfRangeStatus(long rootExit)
	{
		expectThrows(IllegalArgumentException.class, () -> new WindowsProcess.Result(rootExit, false));
	}
}
