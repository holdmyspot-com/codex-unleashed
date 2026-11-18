package com.holdmyspot.codexunleashed.tooling;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.NoSuchElementException;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

/**
 * Reads Unix effective identity and Darwin process stop facts for one host dump invocation.
 */
public final class QuietBuildNativeFacts
{
	/**
	 * Operations needed by process diagnostics.
	 */
	public interface Access
	{
		/**
		 * Returns the effective Unix user ID, or zero for Windows.
		 *
		 * @return effective user ID
		 * @throws IOException if native identity lookup fails
		 */
		long effectiveUid() throws IOException;

		/**
		 * Writes Darwin stop and signal facts for one process.
		 *
		 * @param pid process ID to inspect
		 * @param output report destination
		 * @throws IOException if native facts cannot be read or written
		 */
		void writeDarwinStop(long pid, Writer output) throws IOException;
	}

	/**
	 * Native operations selected for this invocation's platform.
	 */
	private final Access access;

	/**
	 * Selects native operations for the actual supported host and borrows its tooling scope.
	 *
	 * @param scope invocation scope providing the platform linker
	 * @param platform requested process report platform
	 * @throws NullPointerException if either {@code scope} or {@code platform} are null
	 * @throws UnsupportedOperationException if the actual host, architecture or native operation is unsupported
	 */
	public QuietBuildNativeFacts(ToolingScope scope, QuietBuildDump.Platform platform)
	{
		requireThat(scope, "scope").isNotNull();
		requireThat(platform, "platform").isNotNull();
		requireSupportedHost(platform);
		Linker linker = null;
		if (platform != QuietBuildDump.Platform.WINDOWS)
			linker = scope.getNativeLinker();
		access = switch (platform)
		{
			case WINDOWS -> new WindowsAccess();
			case LINUX -> new UnixAccess(bindGetEuid(linker));
			case DARWIN -> new DarwinAccess(linker);
		};
	}

	/**
	 * Returns the native operations selected by this instance.
	 *
	 * @return native operations for this report invocation
	 */
	public Access access()
	{
		return access;
	}

	/**
	 * Validates data for requireSupportedHost.
	 *
	 * @param platform supplied platform
	 */
	private static void requireSupportedHost(QuietBuildDump.Platform platform)
	{
		String osName = System.getProperty("os.name");
		String architecture = System.getProperty("os.arch");
		String normalizedOs = osName.toLowerCase(Locale.ROOT);
		String normalizedArchitecture = architecture.toLowerCase(Locale.ROOT);
		boolean supportedArchitecture = normalizedArchitecture.equals("amd64") ||
			normalizedArchitecture.equals("x86_64") || normalizedArchitecture.equals("aarch64") ||
			normalizedArchitecture.equals("arm64");
		boolean supportedOperatingSystem = switch (platform)
		{
			case LINUX -> normalizedOs.equals("linux");
			case DARWIN -> normalizedOs.equals("darwin") || normalizedOs.startsWith("mac os");
			case WINDOWS -> normalizedOs.startsWith("windows");
		};
		if (!supportedArchitecture || !supportedOperatingSystem)
			throw new UnsupportedOperationException("Native process facts are unsupported for OS " + osName +
				" and architecture " + architecture + " while selecting " + platform);
	}

	/**
	 * Binds data for bindGetEuid.
	 *
	 * @param linker supplied linker
	  *
	  * @return the requested result
	 */
	private static GetEuid bindGetEuid(Linker linker)
	{
		MethodHandle handle = bind(linker, linker.defaultLookup(), "geteuid",
			FunctionDescriptor.of(ValueLayout.JAVA_INT));
		return nativeProxy(GetEuid.class, handle);
	}

	/**
	 * Adapts a native function interface to a method handle.
	 *
	 * @param <T> native function interface type
	 * @param functionType native function interface class
	 * @param handle native function handle
	 * @return proxy implementing the requested native function interface
	 */
	private static <T> T nativeProxy(Class<T> functionType, MethodHandle handle)
	{
		Object proxy = Proxy.newProxyInstance(functionType.getClassLoader(), new Class<?>[]{functionType},
			(instance, method, arguments) ->
			{
				if (method.getDeclaringClass() == Object.class)
					return switch (method.getName())
					{
						case "toString" -> functionType.getSimpleName();
						case "hashCode" -> System.identityHashCode(instance);
						case "equals" -> instance == arguments[0];
						default -> throw new IllegalStateException("Unexpected native proxy method: " + method);
					};
				List<Object> invocationArguments = new ArrayList<>();
				if (arguments != null)
					invocationArguments.addAll(java.util.Arrays.asList(arguments));
				return handle.invokeWithArguments(invocationArguments);
			});
		return functionType.cast(proxy);
	}

	/**
	 * Binds data for bind.
	 *
	 * @param linker supplied linker
	 * @param symbols supplied symbols
	 * @param name supplied name
	 * @param descriptor supplied descriptor
	  *
	  * @return the requested result
	 */
	private static MethodHandle bind(Linker linker, SymbolLookup symbols, String name,
		FunctionDescriptor descriptor)
	{
		return linker.downcallHandle(symbols.find(name).orElseThrow(() ->
			new UnsupportedOperationException("Native process operation is unavailable: " + name)), descriptor);
	}

	/**
	 * Retains data for retain.
	 *
	 * @param failure supplied failure
	 * @param next supplied next
	  *
	  * @return the requested result
	 */
	private static IOException retain(IOException failure, IOException next)
	{
		if (failure == null)
			return next;
		failure.addSuppressed(next);
		return failure;
	}

	/**
	 * Releases data for releasePort.
	 *
	 * @param bindings supplied bindings
	 * @param ownTask supplied ownTask
	 * @param port supplied port
	 * @param failure supplied failure
	  *
	  * @return the requested result
	 */
	private static IOException releasePort(DarwinBindings bindings, int ownTask, int port, IOException failure)
	{
		try
		{
			int result = bindings.machPortDeallocate.invoke(ownTask, port);
			if (result != 0)
				failure = retain(failure, new IOException("mach_port_deallocate failed with status " + result));
		}
		catch (RuntimeException nativeFailure)
		{
			failure = retain(failure, new IOException("mach_port_deallocate failed", nativeFailure));
		}
		return failure;
	}

	/**
	 * Releases data for releaseThreadPort.
	 *
	 * @param bindings supplied bindings
	 * @param ownTask supplied ownTask
	 * @param port supplied port
	 * @throws IOException when the operation fails
	 */
	private static void releaseThreadPort(DarwinBindings bindings, int ownTask, int port) throws IOException
	{
		int result = bindings.machPortDeallocate.invoke(ownTask, port);
		if (result != 0)
			throw new IOException("mach_port_deallocate failed with status " + result);
	}

	/**
	 * Converts data for unsigned.
	 *
	 * @param value supplied value
	  *
	  * @return the requested result
	 */
	private static long unsigned(int value)
	{
		return Integer.toUnsignedLong(value);
	}

	/**
	 * Native function that returns the effective user identifier.
	 */
	@FunctionalInterface
	private interface GetEuid
	{
	/**
	 * Performs data for invoke.
	 *
	 * @return the value produced by this operation
	 */
		int invoke();
	}

	/**
	 * Windows process-fact access with no Unix identity.
	 */
	private static final class WindowsAccess implements Access
	{
		/**
		 * Creates Windows access without Unix process facts.
		 */
		private WindowsAccess()
		{
		}

		@Override
		public long effectiveUid()
		{
			return 0;
		}

		@Override
		public void writeDarwinStop(long pid, Writer output)
		{
			throw new UnsupportedOperationException("Darwin process facts are unavailable on Windows");
		}
	}

	/**
	 * Unix process-fact access backed by the effective-user function.
	 */
	private static final class UnixAccess implements Access
	{
		/**
		 * Bound effective-user lookup function.
		 */
		private final GetEuid getEuid;

	/**
	 * Performs data for UnixAccess.
	 *
	 * @param getEuid supplied getEuid
	 */
		private UnixAccess(GetEuid getEuid)
		{
			this.getEuid = getEuid;
		}

		@Override
		public long effectiveUid()
		{
			return unsigned(getEuid.invoke());
		}

		@Override
		public void writeDarwinStop(long pid, Writer output)
		{
			throw new UnsupportedOperationException("Darwin process facts are unavailable on Linux");
		}
	}

	/**
	 * Darwin process-fact access backed by native bindings.
	 */
	private static final class DarwinAccess implements Access
	{
		/**
		 * Native functions owned by this invocation.
		 */
		private final DarwinBindings bindings;

	/**
	 * Performs data for DarwinAccess.
	 *
	 * @param linker supplied linker
	 */
		private DarwinAccess(Linker linker)
		{
			bindings = new DarwinBindings(linker);
		}

		@Override
		public long effectiveUid()
		{
			return unsigned(bindings.getEuid.invoke());
		}

		@Override
		public void writeDarwinStop(long pid, Writer output) throws IOException
		{
			requireThat(output, "output").isNotNull();
			Integer bsdStatus = readBsdStatus(pid);
			try
			{
				writeTaskStop(pid, bsdStatus, output);
				writeSignalFacts(pid, output);
			}
			catch (UncheckedIOException failure)
			{
				throw failure.getCause();
			}
			catch (RuntimeException failure)
			{
				throw new NativeFactsFailure("Darwin native stop facts could not be read", failure);
			}
		}

	/**
	 * Reads data for readBsdStatus.
	 *
	 * @param pid supplied pid
	  *
	  * @return the requested result
	 */
		private Integer readBsdStatus(long pid)
		{
			try (Arena arena = Arena.ofConfined())
			{
				MemorySegment record = arena.allocate(136, 8);
				int bytes = bindings.procPidInfo.invoke((int) pid, 3, 0, record, 136);
				if (bytes < 8)
					return null;
				return record.get(ValueLayout.JAVA_INT, 4);
			}
			catch (RuntimeException _)
			{
				return null;
			}
		}

	/**
	 * Writes data for writeTaskStop.
	 *
	 * @param pid supplied pid
	 * @param bsdStatus supplied bsdStatus
	 * @param output supplied output
	 * @throws IOException when the operation fails
	 */
		private void writeTaskStop(long pid, Integer bsdStatus, Writer output) throws IOException
		{
			try (Arena arena = Arena.ofConfined())
			{
				int ownTask = bindings.taskSelf.get(ValueLayout.JAVA_INT, 0);
				MemorySegment taskOutput = arena.allocate(ValueLayout.JAVA_INT);
				int taskResult = bindings.taskForPid.invoke(ownTask, (int) pid, taskOutput);
				if (taskResult != 0)
				{
					DarwinQuietBuildFacts.writeStop(output, bsdStatus, null, null);
					return;
				}
				int task = taskOutput.get(ValueLayout.JAVA_INT, 0);
				writeOwnedTaskStop(arena, ownTask, task, bsdStatus, output);
			}
		}

	/**
	 * Writes data for writeOwnedTaskStop.
	 *
	 * @param arena supplied arena
	 * @param ownTask supplied ownTask
	 * @param task supplied task
	 * @param bsdStatus supplied bsdStatus
	 * @param output supplied output
	 * @throws IOException when the operation fails
	 */
		private void writeOwnedTaskStop(Arena arena, int ownTask, int task, Integer bsdStatus,
			Writer output) throws IOException
		{
			IOException primaryFailure = null;
			MemorySegment threadArray = MemorySegment.NULL;
			long threadCount = 0;
			boolean threadArrayOwned = false;
			boolean writerFailure = false;
			ThreadStateIterator threadIterator = null;
			try
			{
				Integer taskSuspendCount = readTaskSuspendCount(arena, task);
				MemorySegment arrayOutput = arena.allocate(ValueLayout.ADDRESS);
				MemorySegment countOutput = arena.allocate(ValueLayout.JAVA_INT);
				int threadsResult = bindings.taskThreads.invoke(task, arrayOutput, countOutput);
				MemorySegment returnedThreadArray = arrayOutput.get(ValueLayout.ADDRESS, 0);
				threadCount = unsigned(countOutput.get(ValueLayout.JAVA_INT, 0));
				threadArrayOwned = !returnedThreadArray.equals(MemorySegment.NULL);
				if (threadArrayOwned)
					threadArray = returnedThreadArray.reinterpret(threadCount * Integer.BYTES);
				if (threadsResult == 0)
				{
					threadIterator = new ThreadStateIterator(threadArray, threadCount, ownTask, bindings);
					ThreadStateIterator ownedThreadIterator = threadIterator;
					Iterable<DarwinQuietBuildFacts.ThreadState> threads = () -> ownedThreadIterator;
					DarwinQuietBuildFacts.writeStop(output, bsdStatus, taskSuspendCount, threads);
				}
				else
					DarwinQuietBuildFacts.writeStop(output, bsdStatus, taskSuspendCount, null);
			}
			catch (IOException failure)
			{
				primaryFailure = failure;
				writerFailure = true;
			}
			catch (RuntimeException failure)
			{
				primaryFailure = new NativeFactsFailure("Darwin task stop facts could not be read", failure);
			}
			finally
			{
				if (threadArrayOwned)
				{
					long releasedThreadCount = 0;
					if (threadIterator != null)
						releasedThreadCount = threadIterator.nextIndex();
					primaryFailure = releaseThreadArray(ownTask, threadArray, threadCount, releasedThreadCount,
						primaryFailure);
				}
				primaryFailure = releasePort(ownTask, task, primaryFailure);
			}
			if (primaryFailure != null)
			{
				if (primaryFailure instanceof NativeFactsFailure nativeFailure)
					throw nativeFailure;
				if (!writerFailure)
					throw new NativeFactsFailure("Darwin native resources could not be released", primaryFailure);
				throw primaryFailure;
			}
		}

	/**
	 * Reads data for readTaskSuspendCount.
	 *
	 * @param arena supplied arena
	 * @param task supplied task
	  *
	  * @return the requested result
	 */
		private Integer readTaskSuspendCount(Arena arena, int task)
		{
			MemorySegment information = arena.allocateFrom(ValueLayout.JAVA_INT, new int[12]);
			MemorySegment count = arena.allocate(ValueLayout.JAVA_INT);
			count.set(ValueLayout.JAVA_INT, 0, 12);
			if (bindings.taskInfo.invoke(task, 20, information, count) != 0)
				return null;
			return information.get(ValueLayout.JAVA_INT, 11L * Integer.BYTES);
		}

	/**
	 * Releases data for releaseThreadArray.
	 *
	 * @param ownTask supplied ownTask
	 * @param threadArray supplied threadArray
	 * @param threadCount supplied threadCount
	 * @param releasedThreadCount supplied releasedThreadCount
	 * @param failure supplied failure
	  *
	  * @return the requested result
	 */
		private IOException releaseThreadArray(int ownTask, MemorySegment threadArray, long threadCount,
			long releasedThreadCount, IOException failure)
		{
		for (long index = releasedThreadCount; index < threadCount; index = index + 1)
			{
				int thread = threadArray.get(ValueLayout.JAVA_INT, index * Integer.BYTES);
				failure = releasePort(ownTask, thread, failure);
			}
			try
			{
				int result = bindings.vmDeallocate.invoke(ownTask, threadArray.address(),
					threadCount * Integer.BYTES);
				if (result != 0)
					failure = retain(failure, new IOException("vm_deallocate failed with status " + result));
			}
			catch (RuntimeException nativeFailure)
			{
				failure = retain(failure, new IOException("vm_deallocate failed", nativeFailure));
			}
			return failure;
		}

	/**
	 * Releases data for releasePort.
	 *
	 * @param ownTask supplied ownTask
	 * @param port supplied port
	 * @param failure supplied failure
	  *
	  * @return the requested result
	 */
		private IOException releasePort(int ownTask, int port, IOException failure)
		{
			return QuietBuildNativeFacts.releasePort(bindings, ownTask, port, failure);
		}

	/**
	 * Writes data for writeSignalFacts.
	 *
	 * @param pid supplied pid
	 * @param output supplied output
	 * @throws IOException when the operation fails
	 */
		private void writeSignalFacts(long pid, Writer output) throws IOException
		{
			SignalFacts facts = readSignalFacts(pid);
			DarwinQuietBuildFacts.writeSignals(output, facts.traced(), facts.stopSignal(), facts.pending());
		}

	/**
	 * Reads data for readSignalFacts.
	 *
	 * @param pid supplied pid
	  *
	  * @return the requested result
	 */
		private SignalFacts readSignalFacts(long pid)
		{
			try (Arena arena = Arena.ofConfined())
			{
				MemorySegment mib = arena.allocateFrom(ValueLayout.JAVA_INT, 1, 14, 1, (int) pid);
				MemorySegment record = arena.allocate(648, 8);
				MemorySegment returnedSize = arena.allocate(ValueLayout.JAVA_LONG);
				returnedSize.set(ValueLayout.JAVA_LONG, 0, record.byteSize());
				int result = bindings.sysctl.invoke(mib, 4, record, returnedSize, MemorySegment.NULL, 0);
				if (result != 0 || returnedSize.get(ValueLayout.JAVA_LONG, 0) < 282 ||
					record.get(ValueLayout.JAVA_INT, 40) != (int) pid)
					return new SignalFacts(null, null, null);
				boolean traced = (record.get(ValueLayout.JAVA_INT, 32) & 0x800) != 0;
				int pending = record.get(ValueLayout.JAVA_INT, 208);
				int stopSignal = Short.toUnsignedInt(record.get(ValueLayout.JAVA_SHORT, 280));
				return new SignalFacts(traced, stopSignal, Integer.toUnsignedLong(pending));
			}
			catch (RuntimeException _)
			{
				return new SignalFacts(null, null, null);
			}
		}
	}

	/**
	 * Iterates thread states from one owned native thread array.
	 */
	private static final class ThreadStateIterator implements Iterator<DarwinQuietBuildFacts.ThreadState>
	{
		/**
		 * Thread array owned by this iterator.
		 */
		private final MemorySegment threadArray;
		/**
		 * Number of threads in the native array.
		 */
		private final long threadCount;
		/**
		 * Task port used to read the thread array.
		 */
		private final int ownTask;
		/**
		 * Native functions owned by this invocation.
		 */
		private final DarwinBindings bindings;
		/**
		 * Index of the next native thread identifier to read.
		 */
		private long nextIndex;
		/**
		 * Next decoded native thread state, when available.
		 */
		private DarwinQuietBuildFacts.ThreadState next;

	/**
	 * Performs data for ThreadStateIterator.
	 *
	 * @param threadArray supplied threadArray
	 * @param threadCount supplied threadCount
	 * @param ownTask supplied ownTask
	 * @param bindings supplied bindings
	 */
		private ThreadStateIterator(MemorySegment threadArray, long threadCount, int ownTask,
			DarwinBindings bindings)
		{
			this.threadArray = threadArray;
			this.threadCount = threadCount;
			this.ownTask = ownTask;
			this.bindings = bindings;
		}

		@Override
		public boolean hasNext()
		{
			while (next == null && nextIndex < threadCount)
			{
				int thread = threadArray.get(ValueLayout.JAVA_INT, nextIndex * Integer.BYTES);
				nextIndex = nextIndex + 1;
				boolean releaseAttempted = false;
				try (Arena arena = Arena.ofConfined())
				{
					MemorySegment information = arena.allocateFrom(ValueLayout.JAVA_INT, new int[10]);
					MemorySegment count = arena.allocate(ValueLayout.JAVA_INT);
					count.set(ValueLayout.JAVA_INT, 0, 10);
					int status = bindings.threadInfo.invoke(thread, 3, information, count);
					DarwinQuietBuildFacts.ThreadState current = null;
					if (status == 0)
						current = new DarwinQuietBuildFacts.ThreadState(information.get(ValueLayout.JAVA_INT,
							6L * Integer.BYTES), information.get(ValueLayout.JAVA_INT, 8L * Integer.BYTES));
					releaseAttempted = true;
					releaseThreadPort(bindings, ownTask, thread);
					next = current;
				}
				catch (IOException failure)
				{
					throw new UncheckedIOException(failure);
				}
				catch (RuntimeException failure)
				{
					IOException threadFailure = new IOException("thread_info failed", failure);
					if (!releaseAttempted)
					{
						IOException releaseFailure = releasePort(bindings, ownTask, thread, null);
						if (releaseFailure != null)
							threadFailure.addSuppressed(releaseFailure);
					}
					throw new UncheckedIOException(threadFailure);
				}
			}
			return next != null;
		}

		@Override
		public DarwinQuietBuildFacts.ThreadState next()
		{
			if (!hasNext())
				throw new NoSuchElementException();
			DarwinQuietBuildFacts.ThreadState result = next;
			next = null;
			return result;
		}

	/**
	 * Returns data for nextIndex.
	  *
	  * @return the requested result
	 */
		private long nextIndex()
		{
			return nextIndex;
		}
	}

	/**
	 * Optional traced, stopped, and pending-signal facts for one process.
	 *
	 * @param traced whether the process is traced, when known
	 * @param stopSignal stop signal number, when known
	 * @param pending pending signal bits, when known
	 */
	private record SignalFacts(Boolean traced, Integer stopSignal, Long pending)
	{
	}

	/**
	 * Bound Darwin native functions borrowed for this invocation.
	 */
	private static final class DarwinBindings
	{
		/**
		 * Lookup for process-owned Darwin system symbols.
		 */
		private final SymbolLookup symbols;
		/**
		 * Borrowed task-self Mach port value.
		 */
		private final MemorySegment taskSelf;
		/**
		 * Bound effective-user lookup function.
		 */
		private final GetEuid getEuid;
		/**
		 * Bound system-control lookup function.
		 */
		private final Sysctl sysctl;
		/**
		 * Bound BSD process information function.
		 */
		private final ProcPidInfo procPidInfo;
		/**
		 * Bound process task-port acquisition function.
		 */
		private final TaskForPid taskForPid;
		/**
		 * Bound task information function.
		 */
		private final TaskInfo taskInfo;
		/**
		 * Bound task thread-array function.
		 */
		private final TaskThreads taskThreads;
		/**
		 * Bound thread information function.
		 */
		private final ThreadInfo threadInfo;
		/**
		 * Bound Mach virtual-memory release function.
		 */
		private final VmDeallocate vmDeallocate;
		/**
		 * Bound Mach port release function.
		 */
		private final MachPortDeallocate machPortDeallocate;

	/**
	 * Performs data for DarwinBindings.
	 *
	 * @param linker supplied linker
	 */
		private DarwinBindings(Linker linker)
		{
			symbols = SymbolLookup.libraryLookup("/usr/lib/libSystem.B.dylib", Arena.global());
			getEuid = bindFunction(linker, "geteuid", FunctionDescriptor.of(ValueLayout.JAVA_INT), GetEuid.class);
			sysctl = bindFunction(linker, "sysctl", FunctionDescriptor.of(ValueLayout.JAVA_INT,
				ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS,
				ValueLayout.ADDRESS, ValueLayout.JAVA_LONG), Sysctl.class);
			procPidInfo = bindFunction(linker, "proc_pidinfo", FunctionDescriptor.of(ValueLayout.JAVA_INT,
				ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_LONG, ValueLayout.ADDRESS,
				ValueLayout.JAVA_INT), ProcPidInfo.class);
			taskForPid = bindFunction(linker, "task_for_pid", FunctionDescriptor.of(ValueLayout.JAVA_INT,
				ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.ADDRESS), TaskForPid.class);
			taskInfo = bindFunction(linker, "task_info", FunctionDescriptor.of(ValueLayout.JAVA_INT,
				ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS), TaskInfo.class);
			taskThreads = bindFunction(linker, "task_threads", FunctionDescriptor.of(ValueLayout.JAVA_INT,
				ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS), TaskThreads.class);
			threadInfo = bindFunction(linker, "thread_info", FunctionDescriptor.of(ValueLayout.JAVA_INT,
				ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS), ThreadInfo.class);
			vmDeallocate = bindFunction(linker, "vm_deallocate", FunctionDescriptor.of(ValueLayout.JAVA_INT,
				ValueLayout.JAVA_INT, ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG), VmDeallocate.class);
			machPortDeallocate = bindFunction(linker, "mach_port_deallocate", FunctionDescriptor.of(ValueLayout.JAVA_INT,
				ValueLayout.JAVA_INT, ValueLayout.JAVA_INT), MachPortDeallocate.class);
			taskSelf = symbols.find("mach_task_self_").orElseThrow(() ->
				new UnsupportedOperationException("Native process operation is unavailable: mach_task_self_"));
		}

	/**
	 * Binds a named native function to its interface proxy.
	 *
	 * @param <T> native function interface type
	 * @param linker borrowed native linker
	 * @param name native symbol name
	 * @param descriptor native function descriptor
	 * @param functionType native function interface class
	 * @return bound native function interface
	 */
		private <T> T bindFunction(Linker linker, String name, FunctionDescriptor descriptor, Class<T> functionType)
		{
			return nativeProxy(functionType, bind(linker, symbols, name, descriptor));
		}
	}

	/**
	 * Native function that reads a Darwin system control value.
	 */
	@FunctionalInterface
	private interface Sysctl
	{
	/**
	 * Performs data for invoke.
	 *
	 * @param mib supplied mib
	 * @param count supplied count
	 * @param old supplied old
	 * @param oldSize supplied oldSize
	 * @param replacement supplied replacement
	 * @param replacementSize supplied replacementSize
	 * @return the value produced by this operation
	 */
		int invoke(MemorySegment mib, int count, MemorySegment old, MemorySegment oldSize,
			MemorySegment replacement, long replacementSize);
	}

	/**
	 * Native function that reads BSD process information.
	 */
	@FunctionalInterface
	private interface ProcPidInfo
	{
	/**
	 * Performs data for invoke.
	 *
	 * @param pid supplied pid
	 * @param flavor supplied flavor
	 * @param argument supplied argument
	 * @param output supplied output
	 * @param outputSize supplied outputSize
	 * @return the value produced by this operation
	 */
		int invoke(int pid, int flavor, long argument, MemorySegment output, int outputSize);
	}

	/**
	 * Native function that acquires a task port for a process.
	 */
	@FunctionalInterface
	private interface TaskForPid
	{
	/**
	 * Performs data for invoke.
	 *
	 * @param ownTask supplied ownTask
	 * @param pid supplied pid
	 * @param taskOutput supplied taskOutput
	 * @return the value produced by this operation
	 */
		int invoke(int ownTask, int pid, MemorySegment taskOutput);
	}

	/**
	 * Native function that reads task information.
	 */
	@FunctionalInterface
	private interface TaskInfo
	{
	/**
	 * Performs data for invoke.
	 *
	 * @param task supplied task
	 * @param flavor supplied flavor
	 * @param information supplied information
	 * @param count supplied count
	 * @return the value produced by this operation
	 */
		int invoke(int task, int flavor, MemorySegment information, MemorySegment count);
	}

	/**
	 * Native function that returns a task thread array.
	 */
	@FunctionalInterface
	private interface TaskThreads
	{
	/**
	 * Performs data for invoke.
	 *
	 * @param task supplied task
	 * @param threadArrayOutput supplied threadArrayOutput
	 * @param countOutput supplied countOutput
	 * @return the value produced by this operation
	 */
		int invoke(int task, MemorySegment threadArrayOutput, MemorySegment countOutput);
	}

	/**
	 * Native function that reads one thread state.
	 */
	@FunctionalInterface
	private interface ThreadInfo
	{
	/**
	 * Performs data for invoke.
	 *
	 * @param thread supplied thread
	 * @param flavor supplied flavor
	 * @param information supplied information
	 * @param count supplied count
	 * @return the value produced by this operation
	 */
		int invoke(int thread, int flavor, MemorySegment information, MemorySegment count);
	}

	/**
	 * Native function that releases a Mach virtual-memory allocation.
	 */
	@FunctionalInterface
	private interface VmDeallocate
	{
	/**
	 * Performs data for invoke.
	 *
	 * @param task supplied task
	 * @param address supplied address
	 * @param size supplied size
	 * @return the value produced by this operation
	 */
		int invoke(int task, long address, long size);
	}

	/**
	 * Native function that releases a Mach port right.
	 */
	@FunctionalInterface
	private interface MachPortDeallocate
	{
	/**
	 * Performs data for invoke.
	 *
	 * @param task supplied task
	 * @param port supplied port
	 * @return the value produced by this operation
	 */
		int invoke(int task, int port);
	}

	/**
	 * Failure carrying the operation context for native process facts.
	 */
	static final class NativeFactsFailure extends IOException
	{
		/**
		 * Stable serialization version for native-facts failures.
		 */
		private static final long serialVersionUID = 1L;

	/**
	 * Performs data for NativeFactsFailure.
	 *
	 * @param message supplied message
	 * @param cause supplied cause
	 */
		private NativeFactsFailure(String message, Throwable cause)
		{
			super(message, cause);
		}
	}
}
