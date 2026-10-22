package com.holdmyspot.codexunleashed.tooling;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicLong;
import org.testng.annotations.Test;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertTrue;
import static org.testng.Assert.expectThrows;

/**
 * Verifies physical RAM budgets independently of the host running the tests.
 */
public final class PhysicalCompilerMemoryTest
{
	/** Creates the physical memory tests. */
	public PhysicalCompilerMemoryTest()
	{
	}

	/**
	 * Reads physical memory from the actual JDK management provider on the current platform.
	 *
	 * @throws IOException if physical memory is unavailable
	 */
	@Test
	public void readsHostPhysicalMemory() throws IOException
	{
		PhysicalCompilerMemory memory = PhysicalCompilerMemory.system();
		assertTrue(memory.available() >= 0);
		assertTrue(memory.reserve() >= 1L << 30);
	}

	/**
	 * Reads current free RAM on every observation, including a fully exhausted host.
	 *
	 * @throws IOException if a valid fixture reading is rejected
	 */
	@Test
	public void readsCurrentPhysicalMemory() throws IOException
	{
		AtomicLong free = new AtomicLong(12_345);
		PhysicalCompilerMemory memory = new PhysicalCompilerMemory(() -> 16L << 30, free::get);
		assertEquals(memory.available(), 12_345L);
		free.set(0);
		assertEquals(memory.available(), 0L);
	}

	/**
	 * Keeps at least one GiB free and otherwise reserves ten percent of physical RAM.
	 *
	 * @throws IOException if a valid fixture reading is rejected
	 */
	@Test
	public void reservesPhysicalMemory() throws IOException
	{
		assertEquals(new PhysicalCompilerMemory(() -> 4L << 30, () -> 0).reserve(), 1L << 30);
		assertEquals(new PhysicalCompilerMemory(() -> 32L << 30, () -> 0).reserve(),
			(32L << 30) / 100 * 10);
	}

	/** Rejects unavailable readings instead of admitting compilers using an invalid budget. */
	@Test
	public void rejectsUnavailableReadings()
	{
		assertEquals(expectThrows(IOException.class,
			() -> new PhysicalCompilerMemory(() -> 1, () -> -1).available()).getMessage(),
			"Free physical memory is unavailable: -1");
		assertEquals(expectThrows(IOException.class,
			() -> new PhysicalCompilerMemory(() -> 0, () -> 0).reserve()).getMessage(),
			"Total physical memory is unavailable: 0");
	}
}
