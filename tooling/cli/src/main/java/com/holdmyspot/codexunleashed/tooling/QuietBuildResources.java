package com.holdmyspot.codexunleashed.tooling;

import java.io.IOException;
import java.math.BigInteger;
import java.time.Instant;
import java.util.Map;
import java.util.OptionalInt;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

/**
 * Typed snapshots and ownership contracts for operating-system resource observations.
 */
@SuppressWarnings("PMD.MissingStaticMethodInNonInstantiatableClass")
public final class QuietBuildResources
{
	/**
	 * Creates the declaration container.
	 */
	private QuietBuildResources()
	{
	}

	/** Linux CPU time fields in the order reported by procfs. */
	public enum LinuxCpuState
	{
		/** User mode ticks. */
		USER,
		/** Niced user mode ticks. */
		NICE,
		/** Kernel mode ticks. */
		SYSTEM,
		/** Idle ticks. */
		IDLE,
		/** I/O wait ticks. */
		IOWAIT,
		/** Hardware interrupt ticks. */
		IRQ,
		/** Software interrupt ticks. */
		SOFTIRQ,
		/** Virtualization steal ticks. */
		STEAL
	}

	/** Paging measurements available from a platform reader. */
	public enum PagingCounter
	{
		/** Pages read from paging storage per second. */
		PAGES_INPUT_PER_SECOND,
		/** Pages written to paging storage per second. */
		PAGES_OUTPUT_PER_SECOND,
		/** Percentage of paging storage in use. */
		PAGING_FILE_USAGE_PERCENT
	}

	/** One successful counter set or a textual failure. */
	public sealed interface Outcome permits Counters, Failure
	{
	}

	/** A platform-specific successful set of resource counters. */
	public sealed interface Counters extends Outcome permits LinuxCounters, DarwinCounters, WindowsCounters
	{
	}

	/**
	 * Cumulative Darwin CPU ticks in user, system, idle and nice states.
	 *
	 * @param user user ticks
	 * @param system system ticks
	 * @param idle idle ticks
	 * @param nice nice ticks
	 */
	public record DarwinCpuTicks(BigInteger user, BigInteger system, BigInteger idle, BigInteger nice)
	{
		/**
		 * Creates the CPU tick values without restricting their magnitude or sign.
		 *
		 * @param user user ticks
		 * @param system system ticks
		 * @param idle idle ticks
		 * @param nice nice ticks
		 */
		public DarwinCpuTicks
		{
			requireThat(user, "user").isNotNull();
			requireThat(system, "system").isNotNull();
			requireThat(idle, "idle").isNotNull();
			requireThat(nice, "nice").isNotNull();
		}
	}

	/**
	 * Cumulative Windows CPU ticks.
	 *
	 * @param idle idle ticks
	 * @param system system ticks
	 * @param user user ticks
	 */
	public record WindowsCpuTicks(BigInteger idle, BigInteger system, BigInteger user)
	{
		/**
		 * Creates the CPU tick values without restricting their magnitude or sign.
		 *
		 * @param idle idle ticks
		 * @param system system ticks
		 * @param user user ticks
		 */
		public WindowsCpuTicks
		{
			requireThat(idle, "idle").isNotNull();
			requireThat(system, "system").isNotNull();
			requireThat(user, "user").isNotNull();
		}
	}

	/**
	 * Linux resource counters and arbitrary PSI row labels.
	 *
	 * @param cpuTicks CPU ticks by state
	 * @param processors processor count
	 * @param memoryTotalBytes total memory
	 * @param memoryAvailableBytes available memory
	 * @param swapUsedBytes used swap
	 * @param swapInPages cumulative pages read from swap
	 * @param swapOutPages cumulative pages written to swap
	 * @param majorFaults cumulative major faults
	 * @param memoryStallMicroseconds memory PSI totals by row label
	 * @param cpuStallMicroseconds CPU PSI totals by row label
	 */
	public record LinuxCounters(Map<LinuxCpuState, BigInteger> cpuTicks, BigInteger processors,
		BigInteger memoryTotalBytes, BigInteger memoryAvailableBytes, BigInteger swapUsedBytes,
		BigInteger swapInPages, BigInteger swapOutPages, BigInteger majorFaults,
		Map<String, BigInteger> memoryStallMicroseconds, Map<String, BigInteger> cpuStallMicroseconds)
		implements Counters
	{
		/**
		 * Creates a Linux snapshot with independent immutable map copies.
		 *
		 * @param cpuTicks CPU ticks by state
		 * @param processors processor count
		 * @param memoryTotalBytes total memory
		 * @param memoryAvailableBytes available memory
		 * @param swapUsedBytes used swap
		 * @param swapInPages cumulative pages read from swap
		 * @param swapOutPages cumulative pages written to swap
		 * @param majorFaults cumulative major faults
		 * @param memoryStallMicroseconds memory PSI totals by row label
		 * @param cpuStallMicroseconds CPU PSI totals by row label
		 */
		public LinuxCounters
		{
			cpuTicks = Map.copyOf(cpuTicks);
			requireThat(processors, "processors").isNotNull();
			requireThat(memoryTotalBytes, "memoryTotalBytes").isNotNull();
			requireThat(memoryAvailableBytes, "memoryAvailableBytes").isNotNull();
			requireThat(swapUsedBytes, "swapUsedBytes").isNotNull();
			requireThat(swapInPages, "swapInPages").isNotNull();
			requireThat(swapOutPages, "swapOutPages").isNotNull();
			requireThat(majorFaults, "majorFaults").isNotNull();
			memoryStallMicroseconds = Map.copyOf(memoryStallMicroseconds);
			cpuStallMicroseconds = Map.copyOf(cpuStallMicroseconds);
		}
	}

	/**
	 * Darwin resource counters.
	 *
	 * @param cpuTicks CPU ticks
	 * @param processors processor count
	 * @param memoryTotalBytes total memory
	 * @param memoryAvailableBytes available memory
	 * @param swapUsedBytes used swap
	 * @param swapInPages pages read from swap per second
	 * @param swapOutPages pages written to swap per second
	 * @param pageIns cumulative page-ins
	 * @param compressedPages compressed memory pages
	 * @param memoryPressureLevel memory pressure level
	 */
	public record DarwinCounters(DarwinCpuTicks cpuTicks, BigInteger processors, BigInteger memoryTotalBytes,
		BigInteger memoryAvailableBytes, BigInteger swapUsedBytes, BigInteger swapInPages, BigInteger swapOutPages,
		BigInteger pageIns, BigInteger compressedPages, BigInteger memoryPressureLevel) implements Counters
	{
		/**
		 * Creates a Darwin snapshot without restricting counter values.
		 *
		 * @param cpuTicks CPU ticks
		 * @param processors processor count
		 * @param memoryTotalBytes total memory
		 * @param memoryAvailableBytes available memory
		 * @param swapUsedBytes used swap
		 * @param swapInPages pages read from swap per second
		 * @param swapOutPages pages written to swap per second
		 * @param pageIns cumulative page-ins
		 * @param compressedPages compressed memory pages
		 * @param memoryPressureLevel memory pressure level
		 */
		public DarwinCounters
		{
			requireThat(cpuTicks, "cpuTicks").isNotNull();
			requireThat(processors, "processors").isNotNull();
			requireThat(memoryTotalBytes, "memoryTotalBytes").isNotNull();
			requireThat(memoryAvailableBytes, "memoryAvailableBytes").isNotNull();
			requireThat(swapUsedBytes, "swapUsedBytes").isNotNull();
			requireThat(swapInPages, "swapInPages").isNotNull();
			requireThat(swapOutPages, "swapOutPages").isNotNull();
			requireThat(pageIns, "pageIns").isNotNull();
			requireThat(compressedPages, "compressedPages").isNotNull();
			requireThat(memoryPressureLevel, "memoryPressureLevel").isNotNull();
		}
	}

	/**
	 * Windows resource counters.
	 *
	 * @param cpuTicks CPU ticks
	 * @param processors processor count when available
	 * @param memoryTotalBytes total memory
	 * @param memoryAvailableBytes available memory
	 * @param commitUsedBytes committed memory in use
	 * @param commitLimitBytes commit limit
	 * @param paging paging measurements by counter
	 */
	public record WindowsCounters(WindowsCpuTicks cpuTicks, OptionalInt processors, BigInteger memoryTotalBytes,
		BigInteger memoryAvailableBytes, BigInteger commitUsedBytes, BigInteger commitLimitBytes,
		Map<PagingCounter, Double> paging) implements Counters
	{
		/**
		 * Creates a Windows snapshot, retaining absent processor count and unrestricted paging values.
		 *
		 * @param cpuTicks CPU ticks
		 * @param processors processor count when available
		 * @param memoryTotalBytes total memory
		 * @param memoryAvailableBytes available memory
		 * @param commitUsedBytes committed memory in use
		 * @param commitLimitBytes commit limit
		 * @param paging paging measurements by counter
		 */
		public WindowsCounters
		{
			requireThat(cpuTicks, "cpuTicks").isNotNull();
			requireThat(processors, "processors").isNotNull();
			requireThat(memoryTotalBytes, "memoryTotalBytes").isNotNull();
			requireThat(memoryAvailableBytes, "memoryAvailableBytes").isNotNull();
			requireThat(commitUsedBytes, "commitUsedBytes").isNotNull();
			requireThat(commitLimitBytes, "commitLimitBytes").isNotNull();
			paging = Map.copyOf(paging);
		}
	}

	/**
	 * A counter-read failure with its supplied description.
	 *
	 * @param description failure description
	 */
	public record Failure(String description) implements Outcome
	{
		/**
		 * Creates a failure value.
		 *
		 * @param description failure description
		 */
		public Failure
		{
			requireThat(description, "description").isNotNull();
		}
	}

	/**
	 * A counter result and its observation time.
	 *
	 * @param time observation time
	 * @param outcome counter result
	 */
	public record Sample(Instant time, Outcome outcome)
	{
		/**
		 * Creates a time-stamped counter result.
		 *
		 * @param time observation time
		 * @param outcome counter result
		 */
		public Sample
		{
			requireThat(time, "time").isNotNull();
			requireThat(outcome, "outcome").isNotNull();
		}
	}

	/** A single invocation-owned resource reader. */
	public interface Reader extends AutoCloseable
	{
		/**
		 * Reads one platform counter snapshot.
		 *
		 * @return one platform counter snapshot
		 * @throws IOException if the counters cannot be read
		 */
		Counters read() throws IOException;

		/** Releases resources owned by this invocation's reader. */
		@Override
		void close() throws IOException;
	}

	/** Opens a reader for one invocation and its complete child context. */
	@FunctionalInterface
	public interface ReaderFactory
	{
		/**
		 * Opens an invocation-owned reader.
		 *
		 * @param context complete child execution context
		 * @return reader owned by the invocation
		 * @throws IOException if acquisition fails
		 */
		Reader open(QuietBuildCapture.Context context) throws IOException;
	}
}
