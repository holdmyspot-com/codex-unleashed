package com.holdmyspot.codexunleashed.tooling;

import io.github.cowwoc.pouch10.core.WrappedCheckedException;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.Reader;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

/** Reads Darwin resource counters through the accepted process-capture and Mach CPU interfaces. */
public final class DarwinQuietBuildResources implements QuietBuildResources.Reader
{
	/** Captures one complete sysctl snapshot. */
	private static final List<String> SYSCTL = List.of("sysctl", "-n", "vm.swapusage",
		"kern.memorystatus_vm_pressure_level", "hw.memsize", "hw.logicalcpu");
	/** Maximum stderr characters retained for a failed command. */
	private static final int MAX_ERROR_CHARACTERS = 4096;
	/** Default Darwin virtual memory page size. */
	private static final BigInteger DEFAULT_PAGE_SIZE = BigInteger.valueOf(4096);
	/** Number of bytes in a binary megabyte. */
	private static final double BYTES_PER_MEGABYTE = 1024.0 * 1024.0;
	/** Darwin CPU load flavor. */
	private static final int HOST_CPU_LOAD_INFO = 3;
	/** Number of words in host_cpu_load_info_data_t. */
	private static final int CPU_TICK_COUNT = 4;
	/** Native access owned by this reader. */
	private final NativeAccess nativeAccess;
	/** Borrowed process capture implementation. */
	private final QuietBuildCapture.Access capture;
	/** Borrowed complete child process context. */
	private final QuietBuildCapture.Context context;
	/** Whether this reader has released its native access. */
	private boolean closed;

	/** Native CPU access owned by a Darwin reader. */
	public interface NativeAccess extends AutoCloseable
	{
		/**
		 * Reads cumulative CPU ticks.
		 *
		 * @return cumulative user, system, idle and nice ticks
		 * @throws IOException if native CPU counters cannot be read
		 */
		QuietBuildResources.DarwinCpuTicks readCpuTicks() throws IOException;

		/** Releases resources owned by the native access. */
		@Override
		void close() throws IOException;
	}

	/**
	 * Creates a reader that owns native access and borrows the process capture and context.
	 *
	 * @param nativeAccess native interface owned by this reader
	 * @param capture process capture borrowed by this reader
	 * @param context complete child process context borrowed by this reader
	 */
	public DarwinQuietBuildResources(NativeAccess nativeAccess, QuietBuildCapture.Access capture,
		QuietBuildCapture.Context context)
	{
		requireThat(nativeAccess, "nativeAccess").isNotNull();
		requireThat(capture, "capture").isNotNull();
		requireThat(context, "context").isNotNull();
		this.nativeAccess = nativeAccess;
		this.capture = capture;
		this.context = context;
	}

	/**
	 * Selects the native ABI and opens an invocation-owned Darwin reader.
	 *
	 * @param scope invocation scope whose linker is borrowed
	 * @param capture accepted child process capture access
	 * @return factory that opens one Darwin reader for each supplied context
	 */
	public static QuietBuildResources.ReaderFactory factory(ToolingScope scope, QuietBuildCapture.Access capture)
	{
		requireThat(scope, "scope").isNotNull();
		requireThat(capture, "capture").isNotNull();
		String os = System.getProperty("os.name", "unknown");
		String arch = System.getProperty("os.arch", "unknown");
		String normalizedOs = os.toLowerCase(Locale.ROOT);
		String normalizedArch = arch.toLowerCase(Locale.ROOT);
		if (!(normalizedOs.contains("mac") || normalizedOs.contains("darwin")) ||
			!(normalizedArch.equals("x86_64") || normalizedArch.equals("amd64") ||
				normalizedArch.equals("aarch64") || normalizedArch.equals("arm64")))
			throw unsupported(os, arch, "Mach host CPU statistics");
		if (ValueLayout.ADDRESS.byteSize() != Long.BYTES)
			throw unsupported(os, arch, "64-bit Mach host CPU statistics");
		return context ->
		{
			requireThat(context, "context").isNotNull();
			NativeAccess access = new MachNativeAccess(scope.getNativeLinker(), os, arch);
			try
			{
				return new DarwinQuietBuildResources(access, capture, context);
			}
			catch (RuntimeException | Error failure)
			{
				try
				{
					access.close();
				}
				catch (IOException closeFailure)
				{
					failure.addSuppressed(closeFailure);
				}
				throw failure;
			}
		};
	}

	/**
	 * Reads CPU and system counters using two separately-owned command captures.
	 *
	 * @return one Darwin resource snapshot
	 * @throws IOException if native counters or either command cannot be read
	 */
	@Override
	public QuietBuildResources.DarwinCounters read() throws IOException
	{
		if (closed)
			throw new IllegalStateException("Darwin resource reader is closed");
		QuietBuildResources.DarwinCpuTicks ticks = nativeAccess.readCpuTicks();
		SysctlValues sysctl = readSysctl();
		VmStatValues vmStat = readVmStat();
		BigInteger availablePages = vmStat.free().add(vmStat.inactive()).add(vmStat.speculative());
		return new QuietBuildResources.DarwinCounters(ticks, sysctl.processors(), sysctl.memoryBytes(),
			availablePages.multiply(vmStat.pageSize()), sysctl.swapBytes(), vmStat.swapIns(), vmStat.swapOuts(),
			vmStat.pageIns(), vmStat.compressedPages(), sysctl.pressure());
	}

	/** Closes owned native access once. */
	@Override
	public void close() throws IOException
	{
		if (!closed)
		{
			closed = true;
			nativeAccess.close();
		}
	}

	/**
	 * Parses the four sysctl values, closing the invocation capture before returning.
	 *
	 * @return parsed sysctl values
	 * @throws IOException if the command output is missing or malformed
	 */
	private SysctlValues readSysctl() throws IOException
	{
		try (QuietBuildCapture.Captured result = successfulCapture(SYSCTL))
		{
			try (BufferedReader reader = new BufferedReader(result.stdout()))
			{
				String swap = requiredLine(reader, "sysctl swap usage");
				BigInteger pressure = parseInteger(requiredLine(reader, "sysctl memory pressure"), "memory pressure");
				BigInteger memory = parseInteger(requiredLine(reader, "sysctl memory size"), "memory size");
				BigInteger processors = parseInteger(requiredLine(reader, "sysctl logical processors"),
					"logical processors");
				if (reader.readLine() != null)
					throw new IOException("sysctl returned more than four values");
				return new SysctlValues(parseSwap(swap), pressure, memory, processors);
			}
		}
	}

	/**
	 * Parses the bounded vm_stat diagnostic stream.
	 *
	 * @return parsed virtual memory statistics
	 * @throws IOException if the command output is malformed
	 */
	private VmStatValues readVmStat() throws IOException
	{
		try (QuietBuildCapture.Captured result = successfulCapture(List.of("vm_stat"));
			BufferedReader reader = new BufferedReader(result.stdout()))
		{
			BigInteger pageSize = DEFAULT_PAGE_SIZE;
			BigInteger free = BigInteger.ZERO;
			BigInteger inactive = BigInteger.ZERO;
			BigInteger speculative = BigInteger.ZERO;
			BigInteger swapIns = BigInteger.ZERO;
			BigInteger swapOuts = BigInteger.ZERO;
			BigInteger pageIns = BigInteger.ZERO;
			BigInteger compressed = BigInteger.ZERO;
			String line;
			while (true)
			{
				line = reader.readLine();
				if (line == null)
					break;
				int header = line.indexOf("page size of");
				if (header >= 0)
				{
					String after = line.substring(header + "page size of".length()).stripLeading();
					String token = after.split("\\s+", 2)[0];
					pageSize = parseInteger(token, "vm_stat page size");
					continue;
				}
				int colon = line.indexOf(':');
				if (colon < 0)
					continue;
				String name = line.substring(0, colon).strip();
				String raw = line.substring(colon + 1).strip().replaceAll("^[\\\"']+|[\\\"']+$", "");
				if (raw.endsWith("."))
					raw = raw.substring(0, raw.length() - 1);
			if (!raw.matches("[0-9]+"))
				continue;
			BigInteger value = new BigInteger(raw);
				switch (name)
				{
					case "Pages free" -> free = value;
					case "Pages inactive" -> inactive = value;
					case "Pages speculative" -> speculative = value;
					case "Swapins" -> swapIns = value;
					case "Swapouts" -> swapOuts = value;
					case "Pageins" -> pageIns = value;
					case "Pages occupied by compressor" -> compressed = value;
				default ->
				{
				}
				}
			}
			return new VmStatValues(pageSize, free, inactive, speculative, swapIns, swapOuts, pageIns, compressed);
		}
	}

	/**
	 * Obtains a successful capture and reports command failure with bounded stderr.
	 *
	 * @param command exact command and arguments to execute
	 * @return successful capture owned by the caller
	 * @throws IOException if capture fails or the command exits unsuccessfully
	 */
	private QuietBuildCapture.Captured successfulCapture(List<String> command) throws IOException
	{
		QuietBuildCapture.Captured result = capture.capture(command, context, Optional.of(Duration.ofSeconds(30)));
		if (result.status() == 0)
			return result;
		IOException failure;
		try
		{
			String stderr = readBounded(result.stderr(), MAX_ERROR_CHARACTERS);
			String diagnostic = command.get(0) + " exited with status " + result.status();
			if (!stderr.isEmpty())
				diagnostic += ": " + stderr;
			failure = new IOException(diagnostic);
		}
		catch (IOException readFailure)
		{
			failure = new IOException(command.get(0) + " exited with status " + result.status() +
				" (stderr could not be read)", readFailure);
		}
		try
		{
			result.close();
		}
		catch (IOException closeFailure)
		{
			failure.addSuppressed(closeFailure);
		}
		throw failure;
	}

	/**
	 * Consumes stderr completely while retaining only the allowed prefix.
	 *
	 * @param reader stderr stream to consume
	 * @param maximum maximum number of retained characters
	 * @return the retained prefix
	 * @throws IOException if stderr cannot be read
	 */
	private static String readBounded(Reader reader, int maximum) throws IOException
	{
		StringBuilder result = new StringBuilder(maximum);
		char[] buffer = new char[1024];
		int count;
		while (true)
		{
			count = reader.read(buffer);
			if (count == -1)
				break;
			int retained = Math.min(count, maximum - result.length());
			if (retained > 0)
				result.append(buffer, 0, retained);
		}
		return result.toString();
	}

	/**
	 * Reads a required line from a command result.
	 *
	 * @param reader command output reader
	 * @param field field name used in the missing-value diagnostic
	 * @return the stripped line
	 * @throws IOException if the line is absent
	 */
	private static String requiredLine(BufferedReader reader, String field) throws IOException
	{
		String line = reader.readLine();
		if (line == null)
			throw new IOException("Missing " + field + " output");
		return line.strip();
	}

	/**
	 * Parses an integer-valued sysctl field.
	 *
	 * @param value raw sysctl value
	 * @param field field name used in the diagnostic
	 * @return parsed integer value
	 * @throws IOException if the value is malformed
	 */
	private static BigInteger parseInteger(String value, String field) throws IOException
	{
		try
		{
			return new BigInteger(value.strip());
		}
		catch (NumberFormatException failure)
		{
			throw new IOException("Invalid " + field + " value: " + value, failure);
		}
	}

	/**
	 * Implements the original Python binary64 megabyte conversion.
	 *
	 * @param line swap-usage output line
	 * @return swap usage in bytes, truncated toward zero
	 * @throws IOException if the value is malformed or outside the finite binary64 range
	 */
	private static BigInteger parseSwap(String line) throws IOException
	{
		String[] tokens = line.split("\\s+");
		for (int index = 0; index < tokens.length; index += 1)
		{
			if (tokens[index].equals("used") && index + 2 < tokens.length)
			{
				String amount = tokens[index + 2];
				if (amount.endsWith("M"))
					amount = amount.substring(0, amount.length() - 1);
				try
				{
					double megabytes = Double.parseDouble(amount);
					double bytes = megabytes * BYTES_PER_MEGABYTE;
					if (!Double.isFinite(bytes))
						throw new NumberFormatException("non-finite byte count");
					return BigDecimal.valueOf(bytes).toBigInteger();
				}
				catch (NumberFormatException failure)
				{
					throw new IOException("Invalid sysctl swap usage: " + line, failure);
				}
			}
		}
		throw new IOException("Invalid sysctl swap usage: " + line);
	}

	/**
	 * Creates a host- and architecture-specific unsupported-operation diagnostic.
	 *
	 * @param os operating system name
	 * @param arch architecture name
	 * @param operation unsupported native operation
	 * @return diagnostic exception
	 */
	private static UnsupportedOperationException unsupported(String os, String arch, String operation)
	{
		return new UnsupportedOperationException("Unsupported " + operation + " on " + os + "/" + arch);
	}

	/**
	 * Parsed sysctl values needed for one Darwin snapshot.
	 *
	 * @param swapBytes used swap in bytes
	 * @param pressure memory pressure level
	 * @param memoryBytes installed memory in bytes
	 * @param processors logical processor count
	 */
	private record SysctlValues(BigInteger swapBytes, BigInteger pressure, BigInteger memoryBytes,
		BigInteger processors)
	{
	}

	/**
	 * Parsed page counters and page size from one vm_stat invocation.
	 *
	 * @param pageSize bytes per page
	 * @param free free page count
	 * @param inactive inactive page count
	 * @param speculative speculative page count
	 * @param swapIns cumulative swap-ins
	 * @param swapOuts cumulative swap-outs
	 * @param pageIns cumulative page-ins
	 * @param compressedPages pages occupied by compressor
	 */
	private record VmStatValues(BigInteger pageSize, BigInteger free, BigInteger inactive, BigInteger speculative,
		BigInteger swapIns, BigInteger swapOuts, BigInteger pageIns, BigInteger compressedPages)
	{
	}

	/** Invocation-owned binding to the three Mach CPU functions and task-self global. */
	private static final class MachNativeAccess implements NativeAccess
	{
		/** Borrowed invocation linker used for downcalls. */
		private final Linker linker;
		/** Original operating system label for binding diagnostics. */
		private final String os;
		/** Original architecture label for binding diagnostics. */
		private final String arch;
		/** Bound mach_host_self call. */
		private final MethodHandle hostSelfHandle;
		/** Bound host_statistics call. */
		private final MethodHandle hostStatisticsHandle;
		/** Bound mach_port_deallocate call. */
		private final MethodHandle deallocateHandle;
		/** Borrowed address of the task-self global. */
		private final MemorySegment taskSelf;
		/** Whether this native access has been closed. */
		private boolean closed;

		/**
		 * Binds the Darwin Mach interfaces while borrowing the linker.
		 *
		 * @param linker invocation linker
		 * @param os operating system name for diagnostics
		 * @param arch architecture name for diagnostics
		 */
		@SuppressWarnings("restricted")
		private MachNativeAccess(Linker linker, String os, String arch)
		{
			this.linker = linker;
			this.os = os;
			this.arch = arch;
			SymbolLookup symbols = SymbolLookup.libraryLookup("/usr/lib/libSystem.B.dylib", Arena.global());
			hostSelfHandle = bind(symbols, "mach_host_self", FunctionDescriptor.of(ValueLayout.JAVA_INT));
			hostStatisticsHandle = bind(symbols, "host_statistics", FunctionDescriptor.of(ValueLayout.JAVA_INT,
				ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS));
			deallocateHandle = bind(symbols, "mach_port_deallocate", FunctionDescriptor.of(ValueLayout.JAVA_INT,
				ValueLayout.JAVA_INT, ValueLayout.JAVA_INT));
			taskSelf = symbols.find("mach_task_self_").map(symbol ->
				symbol.reinterpret(ValueLayout.JAVA_INT.byteSize())).orElseThrow(() ->
					unsupported(os, arch, "mach_task_self_ symbol binding"));
		}

		/**
		 * Resolves and binds one named Mach function.
		 *
		 * @param symbols process-lifetime system library symbols
		 * @param name native function name
		 * @param descriptor fixed function signature
		 * @return callable native handle
		 */
		@SuppressWarnings("restricted")
		private MethodHandle bind(SymbolLookup symbols, String name, FunctionDescriptor descriptor)
		{
			return linker.downcallHandle(symbols.find(name).orElseThrow(() -> unsupported(os, arch, name)), descriptor);
		}

		@Override
		public QuietBuildResources.DarwinCpuTicks readCpuTicks() throws IOException
		{
			if (closed)
				throw new IllegalStateException("Darwin native access is closed");
			int host = machHostSelf();
			try (HostRight right = new HostRight(host))
			{
				int ownedHost = right.port();
				try (Arena arena = Arena.ofConfined())
				{
					MemorySegment information = arena.allocate(ValueLayout.JAVA_INT, CPU_TICK_COUNT);
					MemorySegment count = arena.allocate(ValueLayout.JAVA_INT);
					count.set(ValueLayout.JAVA_INT, 0, CPU_TICK_COUNT);
					int status = hostStatistics(ownedHost, HOST_CPU_LOAD_INFO, information, count);
					if (status != 0)
						throw new IOException("host_statistics failed");
					if (count.get(ValueLayout.JAVA_INT, 0) < CPU_TICK_COUNT)
						throw new IOException("host_statistics returned fewer than four CPU ticks");
					return new QuietBuildResources.DarwinCpuTicks(unsigned(information, 0), unsigned(information, 1),
						unsigned(information, 2), unsigned(information, 3));
				}
			}
		}

		/**
		 * Calls mach_host_self with its exact native signature.
		 *
		 * @return host send-right name
		 */
		private int machHostSelf()
		{
			try
			{
				return (int) hostSelfHandle.invokeExact();
			}
			catch (Throwable failure)
			{
				throw translateInvocationFailure(failure);
			}
		}

		/**
		 * Calls host_statistics with its exact native signature.
		 *
		 * @param host owned host send-right name
		 * @param flavor Mach statistics flavor
		 * @param information output buffer
		 * @param count in/out word count
		 * @return Mach return code
		 */
		private int hostStatistics(int host, int flavor, MemorySegment information, MemorySegment count)
		{
			try
			{
				return (int) hostStatisticsHandle.invokeExact(host, flavor, information, count);
			}
			catch (Throwable failure)
			{
				throw translateInvocationFailure(failure);
			}
		}

		/**
		 * Calls mach_port_deallocate with its exact native signature.
		 *
		 * @param task task port
		 * @param name port name to release
		 * @return Mach return code
		 */
		private int machPortDeallocate(int task, int name)
		{
			try
			{
				return (int) deallocateHandle.invokeExact(task, name);
			}
			catch (Throwable failure)
			{
				throw translateInvocationFailure(failure);
			}
		}

		/**
		 * Preserves unchecked failures and wraps checked method-handle failures according to project convention.
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
			return WrappedCheckedException.wrap(failure);
		}

		/**
		 * Converts one native 32-bit word to an unsigned integer.
		 *
		 * @param words native CPU tick words
		 * @param index position of the word to convert
		 * @return unsigned tick value
		 */
		private BigInteger unsigned(MemorySegment words, int index)
		{
			return BigInteger.valueOf(Integer.toUnsignedLong(words.getAtIndex(ValueLayout.JAVA_INT, index)));
		}

		@Override
		public void close()
		{
			closed = true;
		}

		/** Owns and releases a host send right acquired for one statistics call. */
		private final class HostRight implements AutoCloseable
		{
			/** Acquired host send-right name. */
			private final int port;
			/** Whether the send right has been released. */
			private boolean released;

			/**
			 * Stores the acquired host port for scoped release.
			 *
			 * @param port acquired host send-right name
			 */
			private HostRight(int port)
			{
				this.port = port;
			}

			/**
			 * Returns the send-right name while this resource is open.
			 *
			 * @return owned host send-right name
			 */
			private int port()
			{
				return port;
			}

			@Override
			public void close() throws IOException
			{
				if (!released)
				{
					released = true;
					int task = taskSelf.get(ValueLayout.JAVA_INT, 0);
					if (machPortDeallocate(task, port) != 0)
						throw new IOException("mach_port_deallocate failed");
				}
			}
		}
	}
}
