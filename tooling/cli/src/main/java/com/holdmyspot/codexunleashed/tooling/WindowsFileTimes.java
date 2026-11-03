package com.holdmyspot.codexunleashed.tooling;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandleProxies;
import java.lang.invoke.MethodHandles;
import java.math.BigInteger;
import java.nio.file.Path;
import java.time.Instant;

/**
 * Preserves native Windows timestamp range and 100-nanosecond precision through JDK conversion defects.
 */
public final class WindowsFileTimes
{
	/** Immutable signatures borrowing the system library's existing process lifetime. */
	private final Binding binding;
	/** Seconds between the Windows FILETIME epoch and the Unix epoch. */
	private static final long EPOCH_OFFSET_SECONDS = 11_644_473_600L;
	/** Number of native 100-nanosecond FILETIME ticks per second. */
	private static final BigInteger TICKS_PER_SECOND = BigInteger.valueOf(10_000_000L);
	/** Unsigned all-one-bits timestamp reserved by SetFileTime for preserving a value. */
	private static final BigInteger PRESERVE_TIME = BigInteger.ONE.shiftLeft(Long.SIZE).subtract(BigInteger.ONE);
	/** FILE_READ_ATTRIBUTES access right required for native timestamp reads. */
	private static final int READ_ATTRIBUTES = 0x80;
	/** FILE_WRITE_ATTRIBUTES access right required for native timestamp writes. */
	private static final int WRITE_ATTRIBUTES = 0x100;

	/**
	 * Resolves shared native signatures while preserving the system library's existing process lifetime.
	 * @param scope invocation-owned platform services, kept open until all operations finish
	 * @throws RuntimeException if library loading or signature binding fails
	 */
	WindowsFileTimes(ToolingScope scope)
	{
		binding = new Binding(scope.getNativeLinker());
	}

	/**
	 * Provides the fixed CreateFileW signature after binding its sharing and opening options.
	 */
	@FunctionalInterface
	public interface Opener
	{
		/**
		 * Opens an existing file or directory for attribute access.
		 *
		 * @param state the captured native error state
		 * @param name the terminated UTF-16 filename
		 * @param access the requested attribute access
		 * @return the native handle or INVALID_HANDLE_VALUE
		 */
		MemorySegment invoke(MemorySegment state, MemorySegment name, int access);
	}

	/**
	 * Provides the shared GetFileTime and SetFileTime signatures.
	 */
	@FunctionalInterface
	public interface TimesCall
	{
		/**
		 * Reads or writes the selected native timestamps.
		 *
		 * @param state the captured native error state
		 * @param handle the open file handle
		 * @param creation the creation-time address or NULL
		 * @param access the access-time address or NULL
		 * @param modified the modification-time address
		 * @return the native boolean success value
		 */
		int invoke(MemorySegment state, MemorySegment handle, MemorySegment creation, MemorySegment access,
			MemorySegment modified);
	}

	/**
	 * Provides the fixed CloseHandle signature.
	 */
	@FunctionalInterface
	public interface Closer
	{
		/**
		 * Closes the owned file handle.
		 *
		 * @param state the captured native error state
		 * @param handle the owned handle
		 * @return the native boolean success value
		 */
		int invoke(MemorySegment state, MemorySegment handle);
	}

	/**
	 * Owns immutable invocation-scoped native bindings, initialized only for affected Windows timestamps.
	 */
	private static final class Binding
	{
		/** Platform linker borrowed from the owning Pouch scope. */
		private final Linker linker;
		/** Process-lifetime lookup for the platform system library. */
		private final SymbolLookup symbols;
		/** Layout of the captured Windows error state. */
		private static final MemoryLayout STATE = Linker.Option.captureStateLayout();
		/** Byte offset of captured GetLastError before cleanup can replace it. */
		private static final long ERROR_OFFSET = STATE.byteOffset(MemoryLayout.PathElement.groupElement("GetLastError"));
		/** Native unsigned FILETIME represented as low and high DWORD values. */
		private static final MemoryLayout FILETIME = MemoryLayout.structLayout(
			ValueLayout.JAVA_INT.withName("low"), ValueLayout.JAVA_INT.withName("high"));
		/** Opens the referenced file with the original following semantics. */
		private final Opener open;
		/** Attribute opener retaining the symbolic link itself. */
		private final Opener openNoFollow;
		/** Bound native GetFileTime operation. */
		private final TimesCall get;
		/** Bound native SetFileTime operation. */
		private final TimesCall set;
		/** Bound native CloseHandle operation. */
		private final Closer close;

		/**
		 * Resolves signatures without changing the system library's process lifetime.
		 * @param linker platform service borrowed from the invocation scope
		 */
		private Binding(Linker linker)
		{
			this.linker = linker;
			symbols = symbols();
			open = opener(true);
			openNoFollow = opener(false);
			get = times("GetFileTime");
			set = times("SetFileTime");
			close = MethodHandleProxies.asInterfaceInstance(Closer.class,
				function("CloseHandle", FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS)));
		}

		/**
		 * Loads the Windows system library for the supported 64-bit release targets.
		 *
		 * @return the process-lifetime symbol lookup
		 * @throws UnsupportedOperationException if the native pointer size is unsupported
		 */
		// Restricted FFM access is granted to this module by the launcher; native timestamp tests exercise the bindings.
		@SuppressWarnings("restricted")
		private static SymbolLookup symbols()
		{
			if (ValueLayout.ADDRESS.byteSize() != Long.BYTES)
				throw new UnsupportedOperationException("Exact Windows timestamps require a 64-bit Windows runtime");
			return SymbolLookup.libraryLookup("Kernel32", Arena.global());
		}

		/**
		 * Binds a fixed Windows function and captures its error before the Java runtime can overwrite it.
		 *
		 * @param name the native function name
		 * @param descriptor the fixed native signature
		 * @return the captured-state downcall
		 */
		// Restricted downcalls use fixed documented signatures and confined argument allocations.
		@SuppressWarnings("restricted")
		private MethodHandle function(String name, FunctionDescriptor descriptor)
		{
			return linker.downcallHandle(symbols.find(name).
				orElseThrow(() -> new UnsupportedOperationException("The Windows runtime does not provide " + name)),
				descriptor, Linker.Option.captureCallState("GetLastError"));
		}

		/**
		 * Shares existing files for read, write and delete and also opens directories.
		 *
		 * @param followLinks whether to omit FILE_FLAG_OPEN_REPARSE_POINT and follow symbolic links
		 * @return the opener with OPEN_EXISTING and the selected documented attribute flags
		 */
		private Opener opener(boolean followLinks)
		{
			MethodHandle handle = function("CreateFileW", FunctionDescriptor.of(ValueLayout.ADDRESS,
				ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.ADDRESS,
				ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.ADDRESS));
			handle = MethodHandles.insertArguments(handle, 3, 7, MemorySegment.NULL, 3, 0x02000000 | (followLinks ? 0 : 0x00200000), MemorySegment.NULL);
			return MethodHandleProxies.asInterfaceInstance(Opener.class, handle);
		}

		/**
		 * Binds a native time operation without translating unrelated unchecked failures.
		 *
		 * @param name GetFileTime or SetFileTime
		 * @return the typed native operation
		 */
		private TimesCall times(String name)
		{
			return MethodHandleProxies.asInterfaceInstance(TimesCall.class, function(name,
				FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS,
					ValueLayout.ADDRESS, ValueLayout.ADDRESS)));
		}
	}

	/**
	 * Writes access and modification time at native precision, retaining creation time.
	 *
	 * @param path the native file or directory
	 * @param instant the requested timestamp
	 * @throws IOException if the timestamp is unrepresentable or native file access fails
	 */
	void set(Path path, Instant instant) throws IOException
	{
		// WORKAROUND: OpenJDK jdk-27+35 WindowsFileAttributes.toWindowsTime saturates FileTime.to(NANOSECONDS)
		// and truncates negative fractional timestamps toward zero. Remove when native boundary tests pass via Files.
		long ticks = encode(instant);
		try (Arena arena = Arena.ofConfined(); Handle handle = new Handle(path, WRITE_ATTRIBUTES, arena))
		{
			MemorySegment time = arena.allocate(Binding.FILETIME);
			time.set(ValueLayout.JAVA_INT, 0, (int) ticks);
			time.set(ValueLayout.JAVA_INT, Integer.BYTES, (int) (ticks >>> Integer.SIZE));
			if (binding.set.invoke(handle.state, handle.value, MemorySegment.NULL, time, time) == 0)
				throw handle.failure("SetFileTime");
		}
	}

	/**
	 * Reads modification time without the JDK's out-of-long-nanosecond microsecond rounding.
	 *
	 * @param path the native file or directory
	 * @return the precise native timestamp
	 * @throws IOException if native file access fails
	 */
	public Instant lastModified(Path path) throws IOException
	{
		return lastModified(path, true);
	}

	/**
	 * Reads exact native metadata for either the referenced file or the reparse point itself.
	 *
	 * @param path the native file, directory or symbolic link
	 * @param followLinks true for the referenced file, false for FILE_FLAG_OPEN_REPARSE_POINT
	 * @return the precise native timestamp
	 * @throws IOException if native opening, reading or owned handle closing fails
	 */
	public Instant lastModified(Path path, boolean followLinks) throws IOException
	{
		try (Arena arena = Arena.ofConfined(); Handle handle = new Handle(path, READ_ATTRIBUTES, arena, followLinks))
		{
			MemorySegment time = arena.allocate(Binding.FILETIME);
			if (binding.get.invoke(handle.state, handle.value, MemorySegment.NULL, MemorySegment.NULL, time) == 0)
				throw handle.failure("GetFileTime");
			long ticks = Integer.toUnsignedLong(time.get(ValueLayout.JAVA_INT, 0)) |
				(Integer.toUnsignedLong(time.get(ValueLayout.JAVA_INT, Integer.BYTES)) << Integer.SIZE);
			return decode(ticks);
		}
	}

	/**
	 * Converts an instant to unsigned FILETIME ticks, rounding down only to the native 100-nanosecond resolution.
	 *
	 * @param instant the requested timestamp
	 * @return the unsigned ticks in a long bit pattern
	 * @throws IOException if the rounded tick count is not between one and {@code 2^64 - 2}, inclusive;
	 *     zero and the all-one-bits preserve-time sentinel are rejected
	 */
	static long encode(Instant instant) throws IOException
	{
		BigInteger ticks = BigInteger.valueOf(instant.getEpochSecond()).add(BigInteger.valueOf(EPOCH_OFFSET_SECONDS)).
			multiply(TICKS_PER_SECOND).add(BigInteger.valueOf(instant.getNano() / 100));
		if (ticks.signum() <= 0 || ticks.compareTo(PRESERVE_TIME) >= 0)
			throw new IOException("Timestamp cannot be set as a Windows FILETIME: " + instant);
		return ticks.longValue();
	}

	/**
	 * Decodes the complete unsigned FILETIME range without signed epoch-nanosecond overflow.
	 *
	 * @param ticks the unsigned FILETIME bit pattern
	 * @return the native timestamp
	 */
	static Instant decode(long ticks)
	{
		BigInteger[] parts = new BigInteger(Long.toUnsignedString(ticks)).divideAndRemainder(TICKS_PER_SECOND);
		return Instant.ofEpochSecond(parts[0].longValueExact() - EPOCH_OFFSET_SECONDS,
			parts[1].longValueExact() * 100);
	}

	/**
	 * Owns one native file handle and its captured error state within the caller's arena.
	 */
	private final class Handle implements AutoCloseable
	{
		/** Exact native path retained for failure diagnostics. */
		private final Path path;
		/** Captured error-state storage borrowed from the operation arena. */
		private final MemorySegment state;
		/** Owned native file handle, closed before its operation arena. */
		private final MemorySegment value;

		/**
		 * Opens an existing native path and preserves UTF-16 code units without charset replacement.
		 *
		 * @param path the native path
		 * @param access the required attribute access
		 * @param arena the caller-owned allocation lifetime
		 * @throws IOException if native file opening fails
		 */
		private Handle(Path path, int access, Arena arena) throws IOException
		{
			this(path, access, arena, true);
		}

		/**
		 * Opens an existing native path with explicit following or reparse-point semantics.
		 *
		 * @param path the native path
		 * @param access the required attribute access
		 * @param arena the caller-owned allocation lifetime
		 * @param followLinks true for the referenced file, false for the link itself
		 * @throws IOException if native file opening fails
		 */
		private Handle(Path path, int access, Arena arena, boolean followLinks) throws IOException
		{
			this.path = path;
			state = arena.allocate(Binding.STATE);
			String name = path.toAbsolutePath().toString();
			if (name.length() > 247 && !name.startsWith("\\\\?\\"))
			{
				name = path.toAbsolutePath().normalize().toString();
				if (name.startsWith("\\\\"))
					name = "\\\\?\\UNC\\" + name.substring(2);
				else
					name = "\\\\?\\" + name;
			}
			MemorySegment filename = arena.allocateFrom(ValueLayout.JAVA_CHAR, (name + '\0').toCharArray());
			value = (followLinks ? binding.open : binding.openNoFollow).invoke(state, filename, access);
			if (value.address() == -1L)
				throw failure("CreateFileW");
		}

		/**
		 * Captures the failed operation's error before any cleanup call changes the state.
		 *
		 * @param operation the native operation
		 * @return the precise filesystem failure
		 */
		private IOException failure(String operation)
		{
			return new IOException(operation + " failed for " + path + ": GetLastError=" +
				Integer.toUnsignedString(state.get(ValueLayout.JAVA_INT, Binding.ERROR_OFFSET)));
		}

		/**
		 * Closes the handle before its allocation arena and retains cleanup failures through try-with-resources.
		 *
		 * @throws IOException if native handle closing fails
		 */
		@Override
		public void close() throws IOException
		{
			if (binding.close.invoke(state, value) == 0)
				throw failure("CloseHandle");
		}
	}
}
