package com.holdmyspot.codexunleashed.scheduler;

import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import java.util.Scanner;

/**
 * Reads Linux host memory counters without controlling processes or machine configuration.
 * <p>
 * This sampler requires MemAvailable and memory PSI support. Measurements are host-wide, acquired sequentially and
 * not an atomic snapshot. The controller must separately account for cgroup limits and owned process-tree residency.
 * Cumulative activity counters require comparison between samples; their absolute values do not describe current
 * pressure. Independent calls own their readers and share no mutable state.
 */
public final class LinuxMemory
{
	/**
	 * Prevents construction of this stateless sampler.
	 */
	private LinuxMemory()
	{
	}

	/**
	 * Represents nonnegative host memory and cumulative activity measurements.
	 *
	 * @param totalBytes positive usable physical bytes
	 * @param availableBytes estimated physical bytes available for new work without swapping, at most totalBytes
	 * @param swapTotalBytes configured swap capacity in bytes
	 * @param swapUsedBytes occupied swap bytes, at most swapTotalBytes
	 * @param swapOutPages cumulative pages written to swap since boot
	 * @param stallMicros cumulative microseconds during which at least some tasks stalled on memory
	 */
	public record Sample(long totalBytes, long availableBytes, long swapTotalBytes, long swapUsedBytes,
		long swapOutPages, long stallMicros)
	{
		/**
		 * Creates a validated set of host counters.
		 *
		 * @param totalBytes positive usable physical bytes
		 * @param availableBytes nonnegative available physical bytes, at most totalBytes
		 * @param swapTotalBytes nonnegative swap capacity in bytes
		 * @param swapUsedBytes nonnegative occupied swap bytes, at most swapTotalBytes
		 * @param swapOutPages nonnegative cumulative pages written to swap
		 * @param stallMicros nonnegative cumulative memory stall microseconds
		 * @throws IllegalArgumentException if a count is negative, {@code totalBytes} is zero,
		 * {@code availableBytes} exceeds {@code totalBytes}, or {@code swapUsedBytes} exceeds {@code swapTotalBytes}
		 */
		public Sample
		{
			if (totalBytes <= 0)
				throw new IllegalArgumentException("totalBytes must be positive; received " + totalBytes);
			if (availableBytes < 0 || swapTotalBytes < 0 || swapUsedBytes < 0 || swapOutPages < 0 || stallMicros < 0)
				throw new IllegalArgumentException("Memory and activity counts must be nonnegative");
			if (availableBytes > totalBytes)
				throw new IllegalArgumentException("availableBytes exceeds totalBytes: " + availableBytes + " > " + totalBytes);
			if (swapUsedBytes > swapTotalBytes)
				throw new IllegalArgumentException("swapUsedBytes exceeds swapTotalBytes: " + swapUsedBytes + " > " + swapTotalBytes);
		}
	}

	/**
	 * Reads meminfo, vmstat and pressure/memory below a supplied Linux proc filesystem root.
	 * <p>
	 * Kernel kB values are converted to bytes using 1,024 bytes per unit. Missing or failed observations are reported
	 * as failures rather than zero activity. PSI's some-total microseconds and vmstat's pswpout pages remain cumulative.
	 *
	 * @param procRoot the nonnull proc filesystem root, normally /proc, supplied by the runtime boundary
	 * @return the host memory counters; zero swap occupancy is valid when the required observations report it
	 * @throws NullPointerException if {@code procRoot} is null
	 * @throws IOException if a required file cannot be read, a required counter is absent, its count or unit is
	 * invalid, byte conversion overflows, or the resulting measurements are inconsistent
	 */
	public static Sample read(Path procRoot) throws IOException
	{
		Path meminfo = procRoot.resolve("meminfo");
		Properties memory = readCounters(meminfo);
		long total = readBytes(memory, meminfo, "MemTotal");
		long available = readBytes(memory, meminfo, "MemAvailable");
		long swapTotal = readBytes(memory, meminfo, "SwapTotal");
		long swapFree = readBytes(memory, meminfo, "SwapFree");
		if (swapFree > swapTotal)
			throw new IOException("SwapFree exceeds SwapTotal in " + meminfo);

		Path vmstat = procRoot.resolve("vmstat");
		Properties activity = readCounters(vmstat);
		long swapOut = readCount(requireCounter(activity, vmstat, "pswpout"), vmstat, "pswpout");
		Path pressure = procRoot.resolve("pressure/memory");
		long stalls = readStallMicros(readCounters(pressure), pressure);
		try
		{
			return new Sample(total, available, swapTotal, swapTotal - swapFree, swapOut, stalls);
		}
		catch (IllegalArgumentException failure)
		{
			throw new IOException("Inconsistent memory measurements below " + procRoot + ": " + failure.getMessage(), failure);
		}
	}

	/**
	 * Reads colon or whitespace-separated kernel counter records with the Java properties reader.
	 *
	 * @param source the kernel counter file
	 * @return the file's key/value records
	 * @throws IOException if {@code source} cannot be read or contains invalid property escapes
	 */
	private static Properties readCounters(Path source) throws IOException
	{
		Properties counters = new Properties();
		try (Reader input = Files.newBufferedReader(source))
		{
			counters.load(input);
		}
		catch (IllegalArgumentException failure)
		{
			throw new IOException("Invalid counter records in " + source, failure);
		}
		return counters;
	}

	/**
	 * Requires a named kernel counter before interpreting its value.
	 *
	 * @param counters the file's key/value records
	 * @param source the kernel counter file for diagnostics
	 * @param name the required counter name
	 * @return the trimmed counter value
	 * @throws IOException if {@code name} is absent
	 */
	private static String requireCounter(Properties counters, Path source, String name) throws IOException
	{
		String value = counters.getProperty(name);
		if (value == null)
			throw new IOException("Missing " + name + " counter in " + source);
		return value.strip();
	}

	/**
	 * Converts a named kB memory counter to an exact byte count.
	 *
	 * @param counters the meminfo records
	 * @param source the meminfo file for diagnostics
	 * @param name the required memory counter
	 * @return its nonnegative byte count
	 * @throws IOException if {@code name} is absent, lacks its kB unit, has an invalid count, or overflows bytes
	 */
	private static long readBytes(Properties counters, Path source, String name) throws IOException
	{
		String value = requireCounter(counters, source, name);
		String[] parts = value.split("\\s+");
		if (parts.length != 2 || parts[1].equals("kB") == false)
			throw new IOException("Expected a count followed by kB for " + name + " in " + source + "; received " + value);
		long kibibytes = readCount(parts[0], source, name);
		try
		{
			return Math.multiplyExact(kibibytes, 1024);
		}
		catch (ArithmeticException failure)
		{
			throw new IOException("Byte conversion overflows for " + name + " in " + source + "; received " + value, failure);
		}
	}

	/**
	 * Reads the cumulative some-total memory stall time without interpreting it as current pressure.
	 *
	 * @param counters the PSI records
	 * @param source the PSI file for diagnostics
	 * @return the nonnegative cumulative stall microseconds
	 * @throws IOException if the some record or its total is absent, or the total count is invalid
	 */
	private static long readStallMicros(Properties counters, Path source) throws IOException
	{
		try (Scanner fields = new Scanner(requireCounter(counters, source, "some")))
		{
			while (fields.hasNext())
			{
				String field = fields.next();
				if (field.startsWith("total="))
					return readCount(field.substring("total=".length()), source, "some.total");
			}
		}
		throw new IOException("Missing some.total counter in " + source);
	}

	/**
	 * Parses a nonnegative integer kernel counter without substituting a fallback value on failure.
	 *
	 * @param value the integer text
	 * @param source the kernel counter file for diagnostics
	 * @param name the counter name
	 * @return the nonnegative count
	 * @throws IOException if {@code value} is negative or cannot be represented by a long
	 */
	private static long readCount(String value, Path source, String name) throws IOException
	{
		try
		{
			long count = Long.parseLong(value);
			if (count < 0)
				throw new IOException("Negative " + name + " counter in " + source + "; received " + value);
			return count;
		}
		catch (NumberFormatException failure)
		{
			throw new IOException("Invalid integer for " + name + " in " + source + "; received " + value, failure);
		}
	}
}
