package com.holdmyspot.codexunleashed.tooling;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Objects;
import java.util.function.LongSupplier;

/**
 * Supplies physical RAM admission budgets for one scheduler host.
 *
 * @param reserve the host RAM reserve in bytes
 * @param available current usable physical memory, excluding swap capacity
 */
record CompilerMemory(long reserve, LongSupplier available)
{
	/**
	 * Creates memory readings.
	 *
	 * @param reserve the host RAM reserve in bytes
	 * @param available current usable physical memory
	 * @throws NullPointerException if the memory source is null
	 * @throws IllegalArgumentException if the reserve is not positive or exceeds half of {@link Long#MAX_VALUE}
	 */
	CompilerMemory
	{
		Objects.requireNonNull(available, "available");
		if (reserve <= 0 || reserve > Long.MAX_VALUE / 2)
			throw new IllegalArgumentException("Invalid compiler memory reserve: " + reserve);
	}

	/**
	 * Selects Linux control-group-aware readings or the JDK's Windows and macOS physical-memory provider.
	 *
	 * @return the host's memory budget and current memory reader
	 * @throws IOException if the platform or physical-memory provider is unavailable
	 */
	static CompilerMemory system() throws IOException
	{
		String operatingSystem = System.getProperty("os.name");
		if (operatingSystem.equals("Linux"))
			return new CompilerMemory(LinuxCompilerMemory.reserve(), CompilerMemory::linuxAvailable);
		if (operatingSystem.equals("Mac OS X") || operatingSystem.startsWith("Windows"))
		{
			PhysicalCompilerMemory memory = PhysicalCompilerMemory.system();
			return new CompilerMemory(memory.reserve(), () -> physicalAvailable(memory));
		}
		throw new IOException("Compiler scheduling does not support " + operatingSystem);
	}

	/**
	 * Preserves Linux read failures across the scheduler's nonthrowing observation boundary.
	 *
	 * @return current usable physical memory in bytes
	 * @throws UncheckedIOException if memory cannot be read
	 */
	private static long linuxAvailable()
	{
		try
		{
			return LinuxCompilerMemory.available();
		}
		catch (IOException failure)
		{
			throw new UncheckedIOException(failure);
		}
	}

	/**
	 * Preserves physical-memory read failures across the scheduler's nonthrowing observation boundary.
	 *
	 * @param memory the JDK physical-memory reader
	 * @return current free physical memory in bytes
	 * @throws UncheckedIOException if memory cannot be read
	 */
	private static long physicalAvailable(PhysicalCompilerMemory memory)
	{
		try
		{
			return memory.available();
		}
		catch (IOException failure)
		{
			throw new UncheckedIOException(failure);
		}
	}
}
