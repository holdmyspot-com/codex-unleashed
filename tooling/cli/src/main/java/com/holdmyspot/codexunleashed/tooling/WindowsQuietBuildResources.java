package com.holdmyspot.codexunleashed.tooling;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.lang.reflect.UndeclaredThrowableException;
import java.math.BigInteger;
import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;
import java.util.OptionalInt;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

/** Reads Windows resource counters for one quiet-build invocation. */
public final class WindowsQuietBuildResources implements QuietBuildResources.Reader
{
	/** Required size of MEMORYSTATUSEX in bytes. */
	private static final long MEMORY_STATUS_SIZE = 64;
	/** Byte offset of total physical memory in MEMORYSTATUSEX. */
	private static final long MEMORY_TOTAL_PHYSICAL_OFFSET = 8;
	/** Byte offset of available physical memory in MEMORYSTATUSEX. */
	private static final long MEMORY_AVAILABLE_PHYSICAL_OFFSET = 16;
	/** Byte offset of the system commit limit in MEMORYSTATUSEX. */
	private static final long MEMORY_COMMIT_LIMIT_OFFSET = 24;
	/** Byte offset of available commit in MEMORYSTATUSEX. */
	private static final long MEMORY_COMMIT_AVAILABLE_OFFSET = 32;
	/** Requested PDH double counter format. */
	private static final int PDH_FORMAT_DOUBLE = 0x200;
	/** Byte size of PDH_FMT_COUNTERVALUE on the supported Windows ABIs. */
	private static final long PDH_COUNTER_VALUE_SIZE = 16;
	/** Byte offset of the double value in PDH_FMT_COUNTERVALUE. */
	private static final long PDH_COUNTER_VALUE_OFFSET = 8;
	/** Processor group value that asks Windows for all active groups. */
	private static final short ALL_PROCESSOR_GROUPS = (short) 0xffff;
	/** Native operations owned by this reader. */
	private final NativeAccess nativeAccess;
	/** Indicates whether this reader has released its native access. */
	private boolean closed;

	/**
	 * Creates a reader that owns its native counter access.
	 *
	 * @param nativeAccess the owned Windows counter access
	 */
	public WindowsQuietBuildResources(NativeAccess nativeAccess)
	{
		requireThat(nativeAccess, "nativeAccess").isNotNull();
		this.nativeAccess = nativeAccess;
	}

	/**
	 * Creates invocation-owned native counter readers using the supplied tooling scope.
	 *
	 * @param scope the open invocation scope that supplies the native linker
	 * @return a reader factory for Windows resource counters
	 */
	public static QuietBuildResources.ReaderFactory factory(ToolingScope scope)
	{
		requireThat(scope, "scope").isNotNull();
		return context -> new WindowsQuietBuildResources(new FfmNativeAccess(scope.getNativeLinker()));
	}

	/**
	 * Reads one Windows counter snapshot.
	 *
	 * @return the Windows counters
	 * @throws IOException if the counters cannot be read
	 * @throws IllegalStateException if this reader is closed
	 */
	@Override
	public QuietBuildResources.WindowsCounters read() throws IOException
	{
		if (closed)
			throw new IllegalStateException("Windows resource reader is closed");

		NativeSnapshot snapshot = nativeAccess.read();
		QuietBuildResources.WindowsCpuTicks cpuTicks = new QuietBuildResources.WindowsCpuTicks(snapshot.idle(),
			snapshot.kernel().subtract(snapshot.idle()), snapshot.user());
		BigInteger commitUsedBytes = snapshot.commitLimitBytes().subtract(snapshot.commitAvailableBytes());
		return new QuietBuildResources.WindowsCounters(cpuTicks, snapshot.processors(), snapshot.memoryTotalBytes(),
			snapshot.memoryAvailableBytes(), commitUsedBytes, snapshot.commitLimitBytes(), snapshot.paging());
	}

	/** Releases this reader's native access exactly once. */
	@Override
	public void close() throws IOException
	{
		if (closed)
			return;
		closed = true;
		nativeAccess.close();
	}

	/** Owns one Windows native query and the counters added to it. */
	public interface NativeAccess extends AutoCloseable
	{
		/**
		 * Reads the Windows CPU, memory and currently available paging values.
		 *
		 * @return the native counter snapshot
		 * @throws IOException if a required Windows counter cannot be read
		 */
		NativeSnapshot read() throws IOException;

		/**
		 * Closes the owned native query.
		 *
		 * @throws IOException if closing the query fails
		 */
		@Override
		void close() throws IOException;
	}

	/**
	 * The immutable values captured from Windows native counters.
	 *
	 * @param idle cumulative idle processor ticks
	 * @param kernel cumulative kernel processor ticks, including idle ticks
	 * @param user cumulative user processor ticks
	 * @param processors processor count when available
	 * @param memoryTotalBytes total physical memory in bytes
	 * @param memoryAvailableBytes available physical memory in bytes
	 * @param commitLimitBytes system commit limit in bytes
	 * @param commitAvailableBytes available commit in bytes
	 * @param paging successfully formatted paging counters
	 */
	public record NativeSnapshot(BigInteger idle, BigInteger kernel, BigInteger user, OptionalInt processors,
		BigInteger memoryTotalBytes, BigInteger memoryAvailableBytes, BigInteger commitLimitBytes,
		BigInteger commitAvailableBytes, Map<QuietBuildResources.PagingCounter, Double> paging)
	{
		/**
		 * Creates one immutable snapshot of native Windows counters.
		 *
		 * @param idle cumulative idle processor ticks
		 * @param kernel cumulative kernel processor ticks, including idle ticks
		 * @param user cumulative user processor ticks
		 * @param processors processor count when available
		 * @param memoryTotalBytes total physical memory in bytes
		 * @param memoryAvailableBytes available physical memory in bytes
		 * @param commitLimitBytes system commit limit in bytes
		 * @param commitAvailableBytes available commit in bytes
		 * @param paging successfully formatted paging counters
		 */
		public NativeSnapshot
		{
			requireThat(idle, "idle").isNotNull();
			requireThat(kernel, "kernel").isNotNull();
			requireThat(user, "user").isNotNull();
			requireThat(processors, "processors").isNotNull();
			requireThat(memoryTotalBytes, "memoryTotalBytes").isNotNull();
			requireThat(memoryAvailableBytes, "memoryAvailableBytes").isNotNull();
			requireThat(commitLimitBytes, "commitLimitBytes").isNotNull();
			requireThat(commitAvailableBytes, "commitAvailableBytes").isNotNull();
			requireThat(paging, "paging").isNotNull();
			paging = Map.copyOf(paging);
		}
	}

	/** Owns the FFM bindings and the invocation's single PDH query. */
	private static final class FfmNativeAccess implements NativeAccess
	{
		/** Native call bindings borrowed for this reader's lifetime. */
		private final Binding binding;
		/** The single query handle whose closure releases all acquired paging counters. */
		private final MemorySegment query;
		/** Counter handles successfully added to the query. */
		private final Map<QuietBuildResources.PagingCounter, MemorySegment> pagingCounters;
		/** Indicates whether the PDH query has been closed. */
		private boolean closed;

		/**
		 * Binds the supported Windows APIs and opens one PDH query.
		 *
		 * @param linker the native linker borrowed from the invocation scope
		 * @throws IOException if PDH cannot open its query
		 * @throws UnsupportedOperationException if the host ABI or a required API is unavailable
		 */
		private FfmNativeAccess(Linker linker) throws IOException
		{
			requireWindowsAbi();
			binding = new Binding(linker);
			MemorySegment acquiredQuery = MemorySegment.NULL;
			Map<QuietBuildResources.PagingCounter, MemorySegment> acquiredCounters =
				new EnumMap<>(QuietBuildResources.PagingCounter.class);

			try (Arena arena = Arena.ofConfined())
			{
				MemorySegment queryOut = arena.allocate(ValueLayout.ADDRESS);
				int openStatus = binding.openQuery(MemorySegment.NULL, 0, queryOut);
				acquiredQuery = queryOut.get(ValueLayout.ADDRESS, 0);
				if (openStatus != 0)
					throw new IOException("PdhOpenQueryW failed: " + Integer.toUnsignedString(openStatus));
				if (acquiredQuery.address() == 0)
					throw new IOException("PdhOpenQueryW returned a null query");

				addCounter(acquiredQuery, arena, acquiredCounters,
					QuietBuildResources.PagingCounter.PAGES_INPUT_PER_SECOND,
					"\\Memory\\Pages Input/sec");
				addCounter(acquiredQuery, arena, acquiredCounters,
					QuietBuildResources.PagingCounter.PAGES_OUTPUT_PER_SECOND,
					"\\Memory\\Pages Output/sec");
				addCounter(acquiredQuery, arena, acquiredCounters,
					QuietBuildResources.PagingCounter.PAGING_FILE_USAGE_PERCENT,
					"\\Paging File(_Total)\\% Usage");
			}
			catch (IOException | RuntimeException | Error failure)
			{
				closeAfterConstructionFailure(acquiredQuery, failure);
				throw failure;
			}

			query = acquiredQuery;
			pagingCounters = Map.copyOf(acquiredCounters);
		}

		/**
		 * Reads CPU and memory values and collects the current PDH paging values once.
		 *
		 * @return one native snapshot
		 * @throws IOException if a required Windows API fails
		 */
		@Override
		public NativeSnapshot read() throws IOException
		{
			if (closed)
				throw new IllegalStateException("Windows native access is closed");

			try (Arena arena = Arena.ofConfined())
			{
				MemorySegment idle = arena.allocate(Long.BYTES, Long.BYTES);
				MemorySegment kernel = arena.allocate(Long.BYTES, Long.BYTES);
				MemorySegment user = arena.allocate(Long.BYTES, Long.BYTES);
				if (binding.getSystemTimes(idle, kernel, user) == 0)
					throw new IOException("GetSystemTimes failed");

				MemorySegment memory = arena.allocate(MEMORY_STATUS_SIZE, Long.BYTES);
				memory.set(ValueLayout.JAVA_INT, 0, (int) MEMORY_STATUS_SIZE);
				if (binding.globalMemoryStatus(memory) == 0)
					throw new IOException("GlobalMemoryStatusEx failed");

				OptionalInt processors = readProcessors();
				Map<QuietBuildResources.PagingCounter, Double> paging = readPaging(arena);
				return new NativeSnapshot(unsignedLong(idle, 0), unsignedLong(kernel, 0), unsignedLong(user, 0),
					processors, unsignedLong(memory, MEMORY_TOTAL_PHYSICAL_OFFSET),
					unsignedLong(memory, MEMORY_AVAILABLE_PHYSICAL_OFFSET),
					unsignedLong(memory, MEMORY_COMMIT_LIMIT_OFFSET),
					unsignedLong(memory, MEMORY_COMMIT_AVAILABLE_OFFSET), paging);
			}
		}

		/**
		 * Closes the PDH query and thereby releases every counter added to it.
		 *
		 * @throws IOException if PDH cannot close the query
		 */
		@Override
		public void close() throws IOException
		{
			if (closed)
				return;
			closed = true;
			int status = binding.closeQuery(query);
			if (status != 0)
				throw new IOException("PdhCloseQuery failed: " + Integer.toUnsignedString(status));
		}

		/**
		 * Adds one optional English PDH counter to the owned query.
		 *
		 * @param acquiredQuery query that receives the counter
		 * @param arena temporary storage for the counter name and output pointer
		 * @param counters acquired counter handles by resource meaning
		 * @param counter resource counter represented by the English name
		 * @param name English PDH counter name
		 */
		private void addCounter(MemorySegment acquiredQuery, Arena arena,
			Map<QuietBuildResources.PagingCounter, MemorySegment> counters,
			QuietBuildResources.PagingCounter counter, String name)
		{
			MemorySegment counterOut = arena.allocate(ValueLayout.ADDRESS);
			MemorySegment wideName = arena.allocateFrom(ValueLayout.JAVA_CHAR, (name + '\0').toCharArray());
			int status = binding.addEnglishCounter(acquiredQuery, wideName, 0, counterOut);
			if (status == 0)
				counters.put(counter, counterOut.get(ValueLayout.ADDRESS, 0));
		}

		/**
		 * Reads the total processor count for all active groups.
		 *
		 * @return the count, or empty when Windows reports zero
		 * @throws IOException if a nonzero count cannot fit OptionalInt
		 */
		private OptionalInt readProcessors() throws IOException
		{
		long count = Integer.toUnsignedLong(binding.activeProcessorCount(ALL_PROCESSOR_GROUPS));
			if (count == 0)
				return OptionalInt.empty();
			if (count > Integer.MAX_VALUE)
				throw new IOException("GetActiveProcessorCount returned a value above OptionalInt's range: " + count);
			return OptionalInt.of((int) count);
		}

		/**
		 * Collects PDH once and retains each successful double-format result.
		 *
		 * @param arena operation-owned storage for formatted values
		 * @return available paging measurements, empty when collection fails
		 */
		private Map<QuietBuildResources.PagingCounter, Double> readPaging(Arena arena)
		{
			Map<QuietBuildResources.PagingCounter, Double> values =
				new EnumMap<>(QuietBuildResources.PagingCounter.class);
			if (binding.collectQuery(query) != 0)
				return values;

			for (Map.Entry<QuietBuildResources.PagingCounter, MemorySegment> counter : pagingCounters.entrySet())
			{
				MemorySegment formatted = arena.allocate(PDH_COUNTER_VALUE_SIZE, Long.BYTES);
				int status = binding.getFormattedCounter(counter.getValue(), PDH_FORMAT_DOUBLE, MemorySegment.NULL,
					formatted);
				if (status == 0)
					values.put(counter.getKey(), formatted.get(ValueLayout.JAVA_DOUBLE, PDH_COUNTER_VALUE_OFFSET));
			}
			return values;
		}

		/**
		 * Closes an acquired query when construction fails and retains cleanup errors as suppressed failures.
		 *
		 * @param acquiredQuery query handle acquired before construction failed
		 * @param failure primary construction failure
		 */
		private void closeAfterConstructionFailure(MemorySegment acquiredQuery, Throwable failure)
		{
			if (acquiredQuery.address() == 0)
				return;
			try
			{
				int status = binding.closeQuery(acquiredQuery);
				if (status != 0)
					failure.addSuppressed(new IOException("PdhCloseQuery failed during construction: " +
						Integer.toUnsignedString(status)));
			}
			catch (RuntimeException | Error cleanupFailure)
			{
				failure.addSuppressed(cleanupFailure);
			}
		}
	}

	/** Holds the native signatures used by this invocation's Windows counter reader. */
	private static final class Binding
	{
		/** GetSystemTimes binding. */
		private final MethodHandle getSystemTimesHandle;
		/** GlobalMemoryStatusEx binding. */
		private final MethodHandle globalMemoryStatusHandle;
		/** GetActiveProcessorCount binding. */
		private final MethodHandle activeProcessorCountHandle;
		/** PdhOpenQueryW binding. */
		private final MethodHandle openQueryHandle;
		/** PdhAddEnglishCounterW binding. */
		private final MethodHandle addEnglishCounterHandle;
		/** PdhCollectQueryData binding. */
		private final MethodHandle collectQueryHandle;
		/** PdhGetFormattedCounterValue binding. */
		private final MethodHandle getFormattedCounterHandle;
		/** PdhCloseQuery binding. */
		private final MethodHandle closeQueryHandle;

		/**
		 * Binds the required Kernel32 and PDH functions without owning their system libraries.
		 *
		 * @param linker native linker borrowed from the invocation scope
		 */
		private Binding(Linker linker)
		{
			SymbolLookup kernel32 = library("Kernel32");
			SymbolLookup pdh = library("Pdh");
			getSystemTimesHandle = function(linker, kernel32, "GetSystemTimes",
				FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS,
					ValueLayout.ADDRESS));
			globalMemoryStatusHandle = function(linker, kernel32, "GlobalMemoryStatusEx",
				FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS));
			activeProcessorCountHandle = function(linker, kernel32, "GetActiveProcessorCount",
				FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_SHORT));
			openQueryHandle = function(linker, pdh, "PdhOpenQueryW",
				FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_LONG,
					ValueLayout.ADDRESS));
			addEnglishCounterHandle = function(linker, pdh, "PdhAddEnglishCounterW",
				FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS,
					ValueLayout.JAVA_LONG, ValueLayout.ADDRESS));
			collectQueryHandle = function(linker, pdh, "PdhCollectQueryData",
				FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS));
			getFormattedCounterHandle = function(linker, pdh, "PdhGetFormattedCounterValue",
				FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT,
					ValueLayout.ADDRESS, ValueLayout.ADDRESS));
			closeQueryHandle = function(linker, pdh, "PdhCloseQuery",
				FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS));
		}

		/**
		 * Loads a process-owned Windows system library.
		 *
		 * @param name system library name
		 * @return process-lifetime symbol lookup
		 */
		@SuppressWarnings("restricted")
		private static SymbolLookup library(String name)
		{
			return SymbolLookup.libraryLookup(name, Arena.global());
		}

		/**
		 * Resolves and binds one documented native signature.
		 *
		 * @param linker native linker borrowed from the invocation scope
		 * @param symbols process-owned system library symbols
		 * @param name native function name
		 * @param descriptor exact native call signature
		 * @return bound native call
		 */
		@SuppressWarnings("restricted")
		private static MethodHandle function(Linker linker, SymbolLookup symbols, String name,
			FunctionDescriptor descriptor)
		{
			return linker.downcallHandle(symbols.find(name).
				orElseThrow(() -> new UnsupportedOperationException("Windows " + hostDescription() +
					" does not provide " + name)), descriptor);
		}

		/**
		 * Calls GetSystemTimes with the exact native signature.
		 *
		 * @param idle FILETIME output for idle ticks
		 * @param kernel FILETIME output for kernel ticks
		 * @param user FILETIME output for user ticks
		 * @return native BOOL result
		 */
		private int getSystemTimes(MemorySegment idle, MemorySegment kernel, MemorySegment user)
		{
			try
			{
				return (int) getSystemTimesHandle.invokeExact(idle, kernel, user);
			}
			catch (Throwable failure)
			{
				throw translateInvocationFailure(failure);
			}
		}

		/**
		 * Calls GlobalMemoryStatusEx with the exact native signature.
		 *
		 * @param memoryStatus MEMORYSTATUSEX output structure
		 * @return native BOOL result
		 */
		private int globalMemoryStatus(MemorySegment memoryStatus)
		{
			try
			{
				return (int) globalMemoryStatusHandle.invokeExact(memoryStatus);
			}
			catch (Throwable failure)
			{
				throw translateInvocationFailure(failure);
			}
		}

		/**
		 * Calls GetActiveProcessorCount with the exact native signature.
		 *
		 * @param group processor group identifier
		 * @return unsigned processor count as a 32-bit bit pattern
		 */
		private int activeProcessorCount(short group)
		{
			try
			{
				return (int) activeProcessorCountHandle.invokeExact(group);
			}
			catch (Throwable failure)
			{
				throw translateInvocationFailure(failure);
			}
		}

		/**
		 * Calls PdhOpenQueryW with the exact native signature.
		 *
		 * @param source optional data source pointer
		 * @param userData caller data associated with the query
		 * @param queryOut address that receives the owned query handle
		 * @return PDH status code
		 */
		private int openQuery(MemorySegment source, long userData, MemorySegment queryOut)
		{
			try
			{
				return (int) openQueryHandle.invokeExact(source, userData, queryOut);
			}
			catch (Throwable failure)
			{
				throw translateInvocationFailure(failure);
			}
		}

		/**
		 * Calls PdhAddEnglishCounterW with the exact native signature.
		 *
		 * @param query owned query handle
		 * @param name null-terminated UTF-16 counter name
		 * @param userData caller data associated with the counter
		 * @param counterOut address that receives the counter handle
		 * @return PDH status code
		 */
		private int addEnglishCounter(MemorySegment query, MemorySegment name, long userData,
			MemorySegment counterOut)
		{
			try
			{
				return (int) addEnglishCounterHandle.invokeExact(query, name, userData, counterOut);
			}
			catch (Throwable failure)
			{
				throw translateInvocationFailure(failure);
			}
		}

		/**
		 * Calls PdhCollectQueryData with the exact native signature.
		 *
		 * @param query owned query handle
		 * @return PDH status code
		 */
		private int collectQuery(MemorySegment query)
		{
			try
			{
				return (int) collectQueryHandle.invokeExact(query);
			}
			catch (Throwable failure)
			{
				throw translateInvocationFailure(failure);
			}
		}

		/**
		 * Calls PdhGetFormattedCounterValue with the exact native signature.
		 *
		 * @param counter owned counter handle
		 * @param format PDH value format
		 * @param typeOut optional counter type output address
		 * @param valueOut PDH_FMT_COUNTERVALUE output structure
		 * @return PDH status code
		 */
		private int getFormattedCounter(MemorySegment counter, int format, MemorySegment typeOut,
			MemorySegment valueOut)
		{
			try
			{
				return (int) getFormattedCounterHandle.invokeExact(counter, format, typeOut, valueOut);
			}
			catch (Throwable failure)
			{
				throw translateInvocationFailure(failure);
			}
		}

		/**
		 * Calls PdhCloseQuery with the exact native signature.
		 *
		 * @param query owned query handle
		 * @return PDH status code
		 */
		private int closeQuery(MemorySegment query)
		{
			try
			{
				return (int) closeQueryHandle.invokeExact(query);
			}
			catch (Throwable failure)
			{
				throw translateInvocationFailure(failure);
			}
		}

		/**
		 * Preserves proxy invocation behavior for native-handle failures.
		 *
		 * @param failure failure thrown by an exact method-handle invocation
		 * @return unchecked wrapper for a checked throwable
		 */
		private static RuntimeException translateInvocationFailure(Throwable failure)
		{
			if (failure instanceof RuntimeException runtimeFailure)
				return runtimeFailure;
			if (failure instanceof Error error)
				throw error;
			return new UndeclaredThrowableException(failure);
		}
	}

	/**
	 * Converts a native unsigned 64-bit value to a nonnegative integer without signed-long truncation.
	 *
	 * @param segment native memory containing the value
	 * @param offset byte offset of the value
	 * @return the exact nonnegative value
	 */
	private static BigInteger unsignedLong(MemorySegment segment, long offset)
	{
		return new BigInteger(Long.toUnsignedString(segment.get(ValueLayout.JAVA_LONG, offset)));
	}

	/**
	 * Requires one of the supported 64-bit Windows ABIs before resolving native functions.
	 *
	 * @throws UnsupportedOperationException if the current operating system or architecture is unsupported
	 */
	private static void requireWindowsAbi()
	{
		String os = System.getProperty("os.name", "");
		String architecture = System.getProperty("os.arch", "");
		String normalizedOs = os.toLowerCase(Locale.ROOT);
		String normalizedArchitecture = architecture.toLowerCase(Locale.ROOT);
		boolean supportedArchitecture = normalizedArchitecture.equals("amd64") ||
			normalizedArchitecture.equals("x86_64") || normalizedArchitecture.equals("aarch64") ||
			normalizedArchitecture.equals("arm64");
		if (!normalizedOs.startsWith("windows") || !supportedArchitecture ||
			ValueLayout.ADDRESS.byteSize() != Long.BYTES)
			throw new UnsupportedOperationException("Windows counters require a supported 64-bit Windows ABI; host is " +
				hostDescription());
	}

	/**
	 * Describes the current operating system and architecture for native binding failures.
	 *
	 * @return operating system name and architecture
	 */
	private static String hostDescription()
	{
		return System.getProperty("os.name", "unknown OS") + "/" + System.getProperty("os.arch", "unknown architecture");
	}
}
