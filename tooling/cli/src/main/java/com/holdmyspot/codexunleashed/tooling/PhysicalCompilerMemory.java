package com.holdmyspot.codexunleashed.tooling;

import com.sun.management.OperatingSystemMXBean;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.util.Objects;
import java.util.function.LongSupplier;

/**
 * Reads physical memory for compiler scheduling on hosts without Linux control groups.
 */
final class PhysicalCompilerMemory
{
	/** The share of physical memory reserved for the host. */
	private static final int RESERVE_PERCENT = 10;
	/** The minimum host RAM reserve in bytes. */
	private static final long MINIMUM_RESERVE = 1L << 30;
	/** Supplies total physical memory in bytes. */
	private final LongSupplier total;
	/** Supplies currently free physical memory in bytes, excluding swap. */
	private final LongSupplier free;

	/**
	 * Creates physical memory readings.
	 *
	 * @param total the total physical memory source
	 * @param free the free physical memory source
	 * @throws NullPointerException if a source is null
	 */
	PhysicalCompilerMemory(LongSupplier total, LongSupplier free)
	{
		this.total = Objects.requireNonNull(total, "total");
		this.free = Objects.requireNonNull(free, "free");
	}

	/**
	 * Uses the JDK's physical memory readings without its separate swap readings.
	 *
	 * @return the host's physical memory reader
	 * @throws IOException if the runtime does not provide physical memory readings
	 */
	static PhysicalCompilerMemory system() throws IOException
	{
		OperatingSystemMXBean memory = ManagementFactory.getPlatformMXBean(OperatingSystemMXBean.class);
		if (memory == null)
			throw new IOException("The Java runtime does not provide physical memory readings");
		return new PhysicalCompilerMemory(memory::getTotalMemorySize, memory::getFreeMemorySize);
	}

	/**
	 * Returns currently available physical memory without counting swap capacity.
	 *
	 * @return free physical memory in bytes
	 * @throws IOException if the host cannot provide a usable reading
	 */
	long available() throws IOException
	{
		long reading = free.getAsLong();
		if (reading < 0)
			throw new IOException("Free physical memory is unavailable: " + reading);
		return reading;
	}

	/**
	 * Returns the physical RAM reserve for new compiler admission.
	 *
	 * @return the reserve in bytes
	 * @throws IOException if the host cannot provide total physical memory
	 */
	long reserve() throws IOException
	{
		return reserve(total.getAsLong());
	}

	/**
	 * Calculates the shared host RAM reserve from a physical memory reading.
	 *
	 * @param total the total physical memory in bytes
	 * @return the reserve in bytes
	 * @throws IOException if total memory is unavailable
	 */
	static long reserve(long total) throws IOException
	{
		if (total <= 0)
			throw new IOException("Total physical memory is unavailable: " + total);
		return Math.max(MINIMUM_RESERVE, total / 100 * RESERVE_PERCENT);
	}
}
