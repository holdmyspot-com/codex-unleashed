package com.holdmyspot.codexunleashed.scheduler;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.util.ArrayList;
import java.util.List;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;
import static com.holdmyspot.codexunleashed.scheduler.TestRequirements.requireException;
import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

/**
 * Verifies sample validation and acquisition decisions using controlled native outcomes.
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
		requireException(IllegalArgumentException.class, () ->
			new MacMemory.Sample(totalBytes, availableBytes, swapTotalBytes, swapUsedBytes, MacMemory.Pressure.NORMAL));
	}

	/**
	 * Rejects an absent pressure observation rather than storing an incomplete sample.
	 */
	@Test
	public void rejectsMissingPressure()
	{
		requireException(NullPointerException.class, () -> new MacMemory.Sample(1, 0, 0, 0, null));
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

		requireThat(zero.availableBytes(), "zeroAvailable").isEqualTo(0L, "expected");
		requireThat(zero.swapUsedBytes(), "zeroSwap").isEqualTo(0L, "expected");
		requireThat(largest.availableBytes(), "largestAvailable").isEqualTo(Long.MAX_VALUE, "expected");
		requireThat(largest.swapUsedBytes(), "largestSwap").isEqualTo(Long.MAX_VALUE, "expected");
	}

	/**
	 * Rejects a null host port without attempting to release a right that was never acquired.
	 */
	@Test
	public void rejectsNullHostPort()
	{
		var calls = new NativeOutcomes();
		calls.host = 0;

		var failure = requireException(IOException.class, () -> MacMemory.SharedSecrets.read(calls));
		requireThat(failure.getMessage(), "nullPortFailure").isEqualTo(
			"mach_host_self returned a null host port", "expected");
		requireThat(calls.released, "unacquiredRights").isEmpty();
	}

	/**
	 * Supplies acquired-port failures and their expected production diagnostics.
	 * @return native statistics status, returned field count, free pages and expected failure
	 */
	@DataProvider
	public Object[][] acquisitionFailures()
	{
		return new Object[][] {
			{5, 15, 1, "host_statistics failed with Mach status 5"},
			{0, 2, 1, "host_statistics returned fewer than three required fields: 2"},
			{0, 15, 5, "Available physical bytes exceed total bytes: 24576>16384"}
		};
	}

	/**
	 * Rejects incomplete or inconsistent statistics and releases the acquired host right once.
	 * @param status the host_statistics return status
	 * @param count the returned integer-field count
	 * @param freePages the returned free-page counter
	 * @param message the expected acquisition diagnostic
	 */
	@Test(dataProvider = "acquisitionFailures")
	public void rejectsAcquisitionFailure(int status, int count, int freePages, String message)
	{
		var calls = new NativeOutcomes();
		calls.status = status;
		calls.count = count;
		calls.freePages = freePages;

		var failure = requireException(IOException.class, () -> MacMemory.SharedSecrets.read(calls));
		requireThat(failure.getMessage(), "acquisitionFailure").isEqualTo(message, "expected");
		requireThat(calls.released, "releasedRights").isEqualTo(List.of("11:22"), "expected");
	}

	/**
	 * Prevents an otherwise valid sample from escaping when its host right cannot be released.
	 */
	@Test
	public void rejectsCleanupFailure()
	{
		var calls = new NativeOutcomes();
		calls.releaseStatus = 17;

		var failure = requireException(IOException.class, () -> MacMemory.SharedSecrets.read(calls));
		requireThat(failure.getMessage(), "releaseFailure").isEqualTo(
			"mach_port_deallocate failed with Mach status 17", "expected");
		requireThat(calls.released, "singleReleaseAttempt").isEqualTo(List.of("11:22"), "expected");
	}

	/**
	 * Preserves acquisition as the primary failure and failed release as its suppressed exception.
	 */
	@Test
	public void preservesAcquisitionAndCleanupFailures()
	{
		var calls = new NativeOutcomes();
		calls.status = 5;
		calls.releaseStatus = 17;

		var failure = requireException(IOException.class, () -> MacMemory.SharedSecrets.read(calls));
		requireThat(failure.getMessage(), "primaryFailure").isEqualTo(
			"host_statistics failed with Mach status 5", "expected");
		requireThat(failure.getSuppressed().length, "suppressedCount").isEqualTo(1, "expected");
		requireThat(failure.getSuppressed()[0].getMessage(), "suppressedReleaseFailure").isEqualTo(
			"mach_port_deallocate failed with Mach status 17", "expected");
		requireThat(calls.released, "singleReleaseAttempt").isEqualTo(List.of("11:22"), "expected");
	}

	/**
	 * Supplies the supported native pressure flags and their interpreted levels.
	 * @return native notification flag and expected pressure
	 */
	@DataProvider
	public Object[][] pressureLevels()
	{
		return new Object[][] {
			{1, MacMemory.Pressure.NORMAL},
			{2, MacMemory.Pressure.WARNING},
			{4, MacMemory.Pressure.CRITICAL}
		};
	}

	/**
	 * Reads free plus inactive pages, preserves valid zero swap and maps native pressure after successful release.
	 * @param flag the acquired native notification flag
	 * @param pressure the expected interpreted pressure
	 * @throws IOException if valid observations are rejected
	 */
	@Test(dataProvider = "pressureLevels")
	public void acceptsZeroSwapAndPressure(int flag, MacMemory.Pressure pressure) throws IOException
	{
		var calls = new NativeOutcomes();
		calls.pressure = flag;

		var sample = MacMemory.SharedSecrets.read(calls);
		requireThat(sample, "acquiredSample").isEqualTo(
			new MacMemory.Sample(16384, 8192, 0, 0, pressure), "expected");
		requireThat(calls.released, "releasedBeforeReturn").isEqualTo(List.of("11:22"), "expected");
	}

	/**
	 * Models Mach integer statuses and sysctl structures at the same ABI boundary as the accepted native reader.
	 * These controlled outcomes establish Java interpretation and cleanup, not kernel acquisition.
	 */
	private static final class NativeOutcomes implements MacMemory.NativeCalls
	{
		/** Acquired Mach host send right; zero models MACH_PORT_NULL. */
		private int host = 22;
		/** Mach host_statistics status; zero means success. */
		private int status;
		/** Returned host_statistics count in 32-bit integer fields. */
		private int count = 15;
		/** Returned free physical pages, combined with one inactive page. */
		private int freePages = 1;
		/** Mach release status; zero means success. */
		private int releaseStatus;
		/** Acquired notification flag, using the native normal level by default. */
		private int pressure = 1;
		/** Task and host rights passed to native release, in invocation order. */
		private final List<String> released = new ArrayList<>();

		/**
		 * Creates independent native outcomes for one acquisition.
		 */
		private NativeOutcomes()
		{
		}

		@Override
		public long sysctl(String name, long fallback)
		{
			requireThat(name, "longQuery").isEqualTo("hw.memsize", "expected");
			return 16384;
		}

		@Override
		public int sysctl(String name, int fallback)
		{
			return switch (name)
			{
				case "hw.pagesize" -> 4096;
				case "kern.memorystatus_vm_pressure_level" -> pressure;
				default -> throw new AssertionError("Unexpected integer sysctl: " + name);
			};
		}

		@Override
		public boolean sysctl(String name, MemorySegment result)
		{
			requireThat(name, "structureQuery").isEqualTo("vm.swapusage", "expected");
			requireThat(result.byteSize(), "swapStructureBytes").isEqualTo(32L, "expected");
			result.set(ValueLayout.JAVA_LONG, 0, 0);
			result.set(ValueLayout.JAVA_LONG, 16, 0);
			return true;
		}

		@Override
		public MethodHandle bind(Arena arena, String name, FunctionDescriptor descriptor)
		{
			String method = switch (name)
			{
				case "mach_host_self" -> "hostSelf";
				case "mach_task_self" -> "taskSelf";
				case "mach_port_deallocate" -> "release";
				case "host_statistics" -> "statistics";
				default -> throw new AssertionError("Unexpected Mach function: " + name);
			};
			try
			{
				return MethodHandles.lookup().findVirtual(NativeOutcomes.class, method, descriptor.toMethodType()).bindTo(this);
			}
			catch (ReflectiveOperationException e)
			{
				throw new AssertionError("Native fixture does not match the production ABI", e);
			}
		}

		/**
		 * Acquires the scripted host send right.
		 * @return the host port or MACH_PORT_NULL
		 */
		private int hostSelf()
		{
			return host;
		}

		/**
		 * Borrows the scripted current-task port.
		 * @return the current-task port
		 */
		private int taskSelf()
		{
			return 11;
		}

		/**
		 * Records release of the acquired host right by the current task.
		 * @param task the borrowing task
		 * @param host the host send right
		 * @return the scripted Mach release status
		 */
		private int release(int task, int host)
		{
			released.add(task + ":" + host);
			return releaseStatus;
		}

		/**
		 * Supplies the VM_STATISTICS free and inactive fields and returned count.
		 * @param host the acquired host right
		 * @param flavor the requested Mach statistics flavor
		 * @param result the writable VM_STATISTICS buffer
		 * @param count the input capacity and output field count
		 * @return the scripted Mach acquisition status
		 */
		private int statistics(int host, int flavor, MemorySegment result, MemorySegment count)
		{
			requireThat(host, "queriedHost").isEqualTo(22, "expected");
			requireThat(flavor, "statisticsFlavor").isEqualTo(2, "expected");
			requireThat(result.byteSize(), "statisticsStructureBytes").isEqualTo(60L, "expected");
			requireThat(count.get(ValueLayout.JAVA_INT, 0), "inputFieldCapacity").isEqualTo(15, "expected");
			result.set(ValueLayout.JAVA_INT, 0, freePages);
			result.set(ValueLayout.JAVA_INT, 8, 1);
			count.set(ValueLayout.JAVA_INT, 0, this.count);
			return status;
		}
	}
}
