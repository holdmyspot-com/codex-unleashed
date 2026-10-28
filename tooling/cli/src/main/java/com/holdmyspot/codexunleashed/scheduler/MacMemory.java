package com.holdmyspot.codexunleashed.scheduler;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.util.Objects;
import oshi.ffm.util.platform.mac.SysctlUtilFFM;

/**
 * Reads macOS host-memory counters while preserving unavailable measurements as failures.
 * Calls own independent native scopes and share no mutable sampling state.
 */
public final class MacMemory
{
	/**
	 * Prevents construction of this stateless reader.
	 */
	private MacMemory()
	{
	}

	/**
	 * Represents the native memory-pressure notification level.
	 */
	public enum Pressure
	{
		/** The native notification level is normal. */
		NORMAL,
		/** The native notification level is warning. */
		WARNING,
		/** The native notification level is critical. */
		CRITICAL
	}

	/**
	 * Holds sequential host observations; the fields do not represent an atomic kernel snapshot.
	 *
	 * @param totalBytes the positive physical-memory capacity in bytes
	 * @param availableBytes the nonnegative free-plus-inactive estimate in bytes, at most physical capacity
	 * @param swapTotalBytes the nonnegative configured swap capacity in bytes
	 * @param swapUsedBytes the nonnegative actual swap occupancy in bytes, at most swap capacity
	 * @param pressure the acquired native memory-pressure notification level
	 */
	public record Sample(long totalBytes, long availableBytes, long swapTotalBytes, long swapUsedBytes,
		Pressure pressure)
	{
		/**
		 * Creates a complete sample with valid physical-memory and swap byte quantities.
		 *
		 * @param totalBytes the positive physical-memory capacity in bytes
		 * @param availableBytes the physical availability in bytes, from zero through physical capacity
		 * @param swapTotalBytes the nonnegative swap capacity in bytes
		 * @param swapUsedBytes the swap occupancy in bytes, from zero through swap capacity
		 * @param pressure the nonnull acquired native pressure level
		 * @throws IllegalArgumentException if byte quantities violate their documented ranges
		 * @throws NullPointerException if {@code pressure} is null
		 */
		public Sample
		{
			if (totalBytes <= 0 || availableBytes < 0 || availableBytes > totalBytes)
				throw new IllegalArgumentException("Physical bytes require total > 0 and 0 <= available <= total: " +
					totalBytes + "/" + availableBytes);
			if (swapTotalBytes < 0 || swapUsedBytes < 0 || swapUsedBytes > swapTotalBytes)
				throw new IllegalArgumentException("Swap bytes require 0 <= used <= total: " +
					swapUsedBytes + "/" + swapTotalBytes);
			Objects.requireNonNull(pressure, "pressure may not be null");
		}
	}

	/**
	 * Reads physical memory, actual swap occupancy and native pressure on 64-bit macOS.
	 * A successful zero swap capacity is retained. Every acquired host-port send right is released before return,
	 * including when another query fails; cleanup failure prevents a successful return.
	 *
	 * @return a complete host-memory sample
	 * @throws UnsupportedOperationException if the platform or a required native symbol is unsupported
	 * @throws IOException if acquisition, native cleanup, counter validation or exact arithmetic fails
	 */
	public static Sample read() throws IOException
	{
		if (!System.getProperty("os.name", "").startsWith("Mac"))
			throw new UnsupportedOperationException("MacMemory requires macOS");

		long totalBytes = SysctlUtilFFM.sysctl("hw.memsize", -1L);
		int pageBytes = SysctlUtilFFM.sysctl("hw.pagesize", -1);
		if (totalBytes <= 0)
			throw new IOException("hw.memsize must return positive physical bytes: " + totalBytes);
		if (pageBytes <= 0)
			throw new IOException("hw.pagesize must return a positive page size: " + pageBytes);

		try (Arena arena = Arena.ofConfined())
		{
			var library = SymbolLookup.libraryLookup("/usr/lib/libSystem.B.dylib", arena);
			var hostSelf = bind(library, "mach_host_self", FunctionDescriptor.of(ValueLayout.JAVA_INT));
			var taskSelf = bind(library, "mach_task_self", FunctionDescriptor.of(ValueLayout.JAVA_INT));
			var release = bind(library, "mach_port_deallocate",
				FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT));
			var statisticsQuery = bind(library, "host_statistics", FunctionDescriptor.of(ValueLayout.JAVA_INT,
				ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS));
			int task = invoke("mach_task_self", taskSelf);
			int host = invoke("mach_host_self", hostSelf);
			if (host == 0)
				throw new IOException("mach_host_self returned a null host port");

			try (HostPort port = new HostPort(release, task, host))
			{
				var statistics = arena.allocate(60, 4);
				var count = arena.allocate(ValueLayout.JAVA_INT);
				count.set(ValueLayout.JAVA_INT, 0, 15);
				int status = invoke("host_statistics", statisticsQuery, host, 2, statistics, count);
				if (status != 0)
					throw new IOException("host_statistics failed with Mach status " + status);
				int returnedCount = count.get(ValueLayout.JAVA_INT, 0);
				if (returnedCount < 3)
					throw new IOException("host_statistics returned fewer than three required fields: " + returnedCount);
				long freePages = Integer.toUnsignedLong(statistics.get(ValueLayout.JAVA_INT, 0));
				long inactivePages = Integer.toUnsignedLong(statistics.get(ValueLayout.JAVA_INT, 8));
				long availableBytes = Math.multiplyExact(Math.addExact(freePages, inactivePages), pageBytes);
				if (availableBytes > totalBytes)
					throw new IOException("Available physical bytes exceed total bytes: " + availableBytes + ">" + totalBytes);

				var swap = arena.allocate(32, 8);
				if (!SysctlUtilFFM.sysctl("vm.swapusage", swap))
					throw new IOException("vm.swapusage acquisition failed");
				long swapTotalBytes = swap.get(ValueLayout.JAVA_LONG, 0);
				long swapUsedBytes = swap.get(ValueLayout.JAVA_LONG, 16);
				if (swapTotalBytes < 0 || swapUsedBytes < 0 || swapUsedBytes > swapTotalBytes)
					throw new IOException("Swap capacities require 0 <= used <= total bytes: " +
						swapUsedBytes + "/" + swapTotalBytes);

				int pressureFlag = SysctlUtilFFM.sysctl("kern.memorystatus_vm_pressure_level", -1);
				Pressure pressure = switch (pressureFlag)
				{
					case 1 -> Pressure.NORMAL;
					case 2 -> Pressure.WARNING;
					case 4 -> Pressure.CRITICAL;
					default -> throw new IOException("Unsupported native memory-pressure flag: " + pressureFlag);
				};
				return new Sample(totalBytes, availableBytes, swapTotalBytes, swapUsedBytes, pressure);
			}
		}
		catch (ArithmeticException e)
		{
			throw new IOException("Native memory counters exceed the signed 64-bit byte range", e);
		}
	}

	/**
	 * Binds a required macOS system-library function with its accepted native ABI.
	 *
	 * @param library the library lookup owned by the current call's native scope
	 * @param name the required native symbol
	 * @param descriptor the function's native return and argument layouts
	 * @return the bound native function
	 * @throws UnsupportedOperationException if the required symbol is absent
	 */
	private static MethodHandle bind(SymbolLookup library, String name, FunctionDescriptor descriptor)
	{
		var symbol = library.find(name).orElseThrow(() ->
			new UnsupportedOperationException("Required macOS native symbol is absent: " + name));
		return Linker.nativeLinker().downcallHandle(symbol, descriptor);
	}

	/**
	 * Invokes an integer-returning native function without discarding checked invocation failures.
	 *
	 * @param operation the native operation identified in a checked failure
	 * @param function the bound function
	 * @param arguments the function's native arguments
	 * @return the native integer result, including any operation-specific failure status
	 * @throws IOException if the native invocation raises a checked failure
	 */
	private static int invoke(String operation, MethodHandle function, Object... arguments) throws IOException
	{
		try
		{
			return (int) function.invokeWithArguments(arguments);
		}
		catch (RuntimeException | Error e)
		{
			throw e;
		}
		catch (Throwable e)
		{
			throw new IOException("Native invocation failed: " + operation, e);
		}
	}

	/**
	 * Owns one host-port send right and releases it before the enclosing native scope closes.
	 *
	 * @param release the native port-release function
	 * @param task the borrowed current-task port, which this resource never releases
	 * @param host the acquired host-port send right released by this resource
	 */
	private record HostPort(MethodHandle release, int task, int host) implements AutoCloseable
	{
		/**
		 * Releases the owned host-port send right.
		 *
		 * @throws IOException if native port release fails
		 */
		@Override
		public void close() throws IOException
		{
			int status = invoke("mach_port_deallocate", release, task, host);
			if (status != 0)
				throw new IOException("mach_port_deallocate failed with Mach status " + status);
		}
	}
}
