package com.holdmyspot.codexunleashed.scheduler;

import com.holdmyspot.codexunleashed.secrets.MacMemorySecrets;
import com.holdmyspot.codexunleashed.tooling.ToolingScope;
import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
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
	static
	{
		MacMemorySecrets.setMacMemoryAccess(MacMemory::read);
	}

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
	 * @param scope invocation-owned shared values, retained open for this read
	 * @return a complete host-memory sample
	 * @throws UnsupportedOperationException if the platform or a required native symbol is unsupported
	 * @throws IOException if acquisition, native cleanup, counter validation or exact arithmetic fails
	 */
	public static Sample read(ToolingScope scope) throws IOException
	{
		Objects.requireNonNull(scope, "scope may not be null");
		if (!System.getProperty("os.name", "").startsWith("Mac"))
			throw new UnsupportedOperationException("MacMemory requires macOS");

		return read(new SystemCalls(scope));
	}

	/**
	 * Acquires and validates one sample using call-local native operations.
	 *
	 * @param calls the native operations used only by this acquisition
	 * @return the complete sample after releasing its host port
	 * @throws IOException if acquisition, validation, arithmetic or release fails
	 */
	private static Sample read(NativeCalls calls) throws IOException
	{
		long totalBytes = calls.sysctl("hw.memsize", -1L);
		int pageBytes = calls.sysctl("hw.pagesize", -1);
		if (totalBytes <= 0)
			throw new IOException("hw.memsize must return positive physical bytes: " + totalBytes);
		if (pageBytes <= 0)
			throw new IOException("hw.pagesize must return a positive page size: " + pageBytes);

		try (Arena arena = Arena.ofConfined())
		{
			MachCalls mach = calls.mach(arena);
			int task = mach.taskSelf();
			int host = mach.hostSelf();
			if (host == 0)
				throw new IOException("mach_host_self returned a null host port");

			try (HostPort port = new HostPort(mach, task, host))
			{
				var statistics = arena.allocate(60, 4);
				var count = arena.allocate(ValueLayout.JAVA_INT);
				count.set(ValueLayout.JAVA_INT, 0, 15);
				int status = mach.hostStatistics(host, 2, statistics, count);
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
				if (!calls.sysctl("vm.swapusage", swap))
					throw new IOException("vm.swapusage acquisition failed");
				long swapTotalBytes = swap.get(ValueLayout.JAVA_LONG, 0);
				long swapUsedBytes = swap.get(ValueLayout.JAVA_LONG, 16);
				if (swapTotalBytes < 0 || swapUsedBytes < 0 || swapUsedBytes > swapTotalBytes)
					throw new IOException("Swap capacities require 0 <= used <= total bytes: " +
						swapUsedBytes + "/" + swapTotalBytes);

				int pressureFlag = calls.sysctl("kern.memorystatus_vm_pressure_level", -1);
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
	 * Supplies system queries and ABI-compatible functions for one acquisition.
	 */
	public interface NativeCalls
	{
		/**
		 * Reads a signed 64-bit sysctl value.
		 * @param name the native query name
		 * @param fallback the sentinel returned when acquisition fails
		 * @return the native value or the failure sentinel
		 */
		long sysctl(String name, long fallback);

		/**
		 * Reads a signed 32-bit sysctl value.
		 * @param name the native query name
		 * @param fallback the sentinel returned when acquisition fails
		 * @return the native value or the failure sentinel
		 */
		int sysctl(String name, int fallback);

		/**
		 * Acquires a native sysctl structure into the caller's buffer.
		 * @param name the native query name
		 * @param result the writable native result buffer
		 * @return true on successful acquisition
		 */
		boolean sysctl(String name, MemorySegment result);

		/**
		 * Supplies the Mach functions within the acquisition scope.
		 * @param arena the scope that owns the loaded library
		 * @return the typed Mach functions
		 * @throws UnsupportedOperationException if a required native symbol is absent
		 */
		MachCalls mach(Arena arena);
	}

	/**
	 * Calls the Mach functions that one acquisition requires with their accepted native ABI.
	 */
	public interface MachCalls
	{
		/**
		 * Borrows the current-task port.
		 * @return the current-task port
		 * @throws IOException if the native invocation raises a checked failure
		 */
		int taskSelf() throws IOException;

		/**
		 * Acquires the host-port send right.
		 * @return the host port, or MACH_PORT_NULL
		 * @throws IOException if the native invocation raises a checked failure
		 */
		int hostSelf() throws IOException;

		/**
		 * Releases a port right held by a task.
		 * @param task the task that holds the right
		 * @param port the port right to release
		 * @return the Mach release status
		 * @throws IOException if the native invocation raises a checked failure
		 */
		int portDeallocate(int task, int port) throws IOException;

		/**
		 * Reads VM statistics for a host.
		 * @param host the acquired host port
		 * @param flavor the requested Mach statistics flavor
		 * @param result the writable VM_STATISTICS buffer
		 * @param count the input capacity and output field count
		 * @return the Mach acquisition status
		 * @throws IOException if the native invocation raises a checked failure
		 */
		int hostStatistics(int host, int flavor, MemorySegment result, MemorySegment count) throws IOException;
	}

	/**
	 * Acquires system values and lazily opens the library in the current read's arena.
	 */
	private static final class SystemCalls implements NativeCalls
	{
		/** Native linker borrowed from the invocation scope. */
		private final Linker linker;
		/** System-library lookup initialized by the first binding and confined to one read. */
		private SymbolLookup library;

		/**
		 * Creates the native operations for one read.
		 * @param scope invocation-owned shared values
		 */
		private SystemCalls(ToolingScope scope)
		{
			linker = scope.getNativeLinker();
		}

		@Override
		public long sysctl(String name, long fallback)
		{
			return SysctlUtilFFM.sysctl(name, fallback);
		}

		@Override
		public int sysctl(String name, int fallback)
		{
			return SysctlUtilFFM.sysctl(name, fallback);
		}

		@Override
		public boolean sysctl(String name, MemorySegment result)
		{
			return SysctlUtilFFM.sysctl(name, result);
		}

		@Override
		public MachCalls mach(Arena arena)
		{
			if (library == null)
				library = SymbolLookup.libraryLookup("/usr/lib/libSystem.B.dylib", arena);
			return new BoundMachCalls(
				MacMemory.bind(linker, library, "mach_task_self", FunctionDescriptor.of(ValueLayout.JAVA_INT)),
				MacMemory.bind(linker, library, "mach_host_self", FunctionDescriptor.of(ValueLayout.JAVA_INT)),
				MacMemory.bind(linker, library, "mach_port_deallocate",
					FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT)),
				MacMemory.bind(linker, library, "host_statistics", FunctionDescriptor.of(ValueLayout.JAVA_INT,
					ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS)));
		}
	}

	/**
	 * Calls the bound system-library functions without discarding checked invocation failures.
	 */
	private static final class BoundMachCalls implements MachCalls
	{
		/** The bound {@code mach_task_self}. */
		private final MethodHandle taskSelf;
		/** The bound {@code mach_host_self}. */
		private final MethodHandle hostSelf;
		/** The bound {@code mach_port_deallocate}. */
		private final MethodHandle portDeallocate;
		/** The bound {@code host_statistics}. */
		private final MethodHandle hostStatistics;

		/**
		 * Retains the bound functions.
		 *
		 * @param taskSelf the bound {@code mach_task_self}
		 * @param hostSelf the bound {@code mach_host_self}
		 * @param portDeallocate the bound {@code mach_port_deallocate}
		 * @param hostStatistics the bound {@code host_statistics}
		 */
		private BoundMachCalls(MethodHandle taskSelf, MethodHandle hostSelf, MethodHandle portDeallocate,
			MethodHandle hostStatistics)
		{
			this.taskSelf = taskSelf;
			this.hostSelf = hostSelf;
			this.portDeallocate = portDeallocate;
			this.hostStatistics = hostStatistics;
		}

		@Override
		public int taskSelf() throws IOException
		{
			return invoke("mach_task_self", taskSelf);
		}

		@Override
		public int hostSelf() throws IOException
		{
			return invoke("mach_host_self", hostSelf);
		}

		@Override
		public int portDeallocate(int task, int port) throws IOException
		{
			return invoke("mach_port_deallocate", portDeallocate, task, port);
		}

		@Override
		public int hostStatistics(int host, int flavor, MemorySegment result, MemorySegment count) throws IOException
		{
			return invoke("host_statistics", hostStatistics, host, flavor, result, count);
		}
	}

	/**
	 * Binds a required macOS system-library function with its accepted native ABI.
	 *
	 * @param linker the native linker borrowed from the invocation scope
	 * @param library the library lookup owned by the current call's native scope
	 * @param name the required native symbol
	 * @param descriptor the function's native return and argument layouts
	 * @return the bound native function
	 * @throws UnsupportedOperationException if the required symbol is absent
	 */
	private static MethodHandle bind(Linker linker, SymbolLookup library, String name, FunctionDescriptor descriptor)
	{
		var symbol = library.find(name).orElseThrow(() ->
			new UnsupportedOperationException("Required macOS native symbol is absent: " + name));
		return linker.downcallHandle(symbol, descriptor);
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
	 * @param mach the Mach functions that release the right
	 * @param task the borrowed current-task port, which this resource never releases
	 * @param host the acquired host-port send right released by this resource
	 */
	private record HostPort(MachCalls mach, int task, int host) implements AutoCloseable
	{
		/**
		 * Releases the owned host-port send right.
		 *
		 * @throws IOException if native port release fails
		 */
		@Override
		public void close() throws IOException
		{
			int status = mach.portDeallocate(task, host);
			if (status != 0)
				throw new IOException("mach_port_deallocate failed with Mach status " + status);
		}
	}
}
