package com.holdmyspot.codexunleashed.tooling;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandleProxies;
import java.nio.file.Path;
import java.time.Instant;

/** Reads native Unix timestamps when JDK metadata conversion loses their nanosecond precision. */
public final class UnixFileMetadata
{
	/** Prevents construction. */
	private UnixFileMetadata()
	{
	}

	/** Represents the Linux statx signature with captured errno. */
	@FunctionalInterface
	public interface LinuxReader
	{
		/**
		 * Reads a native statx buffer.
		 *
		 * @param state captured errno storage
		 * @param directory ignored descriptor for the absolute path
		 * @param path the native filename
		 * @param flags native query flags
		 * @param mask requested attributes
		 * @param data native statx output
		 * @return native status
		 */
		int invoke(MemorySegment state, int directory, MemorySegment path, int flags, int mask, MemorySegment data);
	}

	/** Represents the Darwin 64-bit-inode stat signature with captured errno. */
	@FunctionalInterface
	public interface DarwinReader
	{
		/**
		 * Reads the Darwin 64-bit stat structure.
		 *
		 * @param state captured errno storage
		 * @param path the native filename
		 * @param data native stat output
		 * @return native status
		 */
		int invoke(MemorySegment state, MemorySegment path, MemorySegment data);
	}

	/** Owns shared layouts only when the native metadata path is used. */
	private static final class Binding
	{
		private static final Linker LINKER = Linker.nativeLinker();
		private static final MemoryLayout STATE = Linker.Option.captureStateLayout();
		private static final long ERRNO_OFFSET = STATE.byteOffset(MemoryLayout.PathElement.groupElement("errno"));

		/** Prevents construction. */
		private Binding()
		{
		}

		/**
		 * Binds a fixed signature selected by the platform-specific reader.
		 *
		 * @param name the native function
		 * @param descriptor its verified C signature
		 * @return a handle with captured errno
		 */
		// FFM downcalls intrinsically require native access. The named module receives explicit permission;
		// fixed platform signatures and bounded native buffers retain the restriction's safety obligations.
		@SuppressWarnings("restricted")
		private static MethodHandle bind(String name, FunctionDescriptor descriptor)
		{
			return LINKER.downcallHandle(LINKER.defaultLookup().find(name).
				orElseThrow(() -> new UnsupportedOperationException("The Unix runtime does not provide " + name)),
				descriptor, Linker.Option.captureCallState("errno"));
		}
	}

	/** Owns the architecture-independent Linux statx ABI from include/uapi/linux/stat.h. */
	private static final class Linux
	{
		private static final int MODIFIED = 0x40;
		private static final long BUFFER_SIZE = 0x100;
		private static final long SECONDS_OFFSET = 0x70;
		private static final long NANOS_OFFSET = SECONDS_OFFSET + Long.BYTES;
		private static final LinuxReader READER = MethodHandleProxies.asInterfaceInstance(LinuxReader.class,
			Binding.bind("statx", FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT,
				ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.ADDRESS)));

		/** Prevents construction. */
		private Linux()
		{
		}
	}

	/** Owns Darwin's __DARWIN_STRUCT_STAT64 ABI from bsd/sys/stat.h on supported 64-bit targets. */
	private static final class Darwin
	{
		private static final long BUFFER_SIZE = 144;
		private static final long SECONDS_OFFSET = 48;
		private static final long NANOS_OFFSET = SECONDS_OFFSET + Long.BYTES;
		private static final DarwinReader READER = MethodHandleProxies.asInterfaceInstance(DarwinReader.class,
			Binding.bind(symbol(), FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS)));

		/** Prevents construction. */
		private Darwin()
		{
		}

		/**
		 * Uses the explicit inode64 alias where Darwin exposes it, or the sole 64-bit stat ABI otherwise.
		 *
		 * @return Darwin's stat symbol
		 */
		private static String symbol()
		{
			if (Binding.LINKER.defaultLookup().find("stat$INODE64").isPresent())
				return "stat$INODE64";
			return "stat";
		}
	}

	/**
	 * Preserves native precision beyond signed-long epoch nanoseconds.
	 *
	 * @param path the source or directory
	 * @return its exact modification timestamp
	 * @throws IOException if native metadata is unavailable or cannot be read
	 */
	static Instant lastModified(Path path) throws IOException
	{
		// WORKAROUND: JDK UnixFileAttributes converts timestamps outside the jlong nanosecond range to microseconds.
		// Remove this native lookup when the unchanged far-future inventory test passes through Files metadata APIs.
		String operatingSystem = System.getProperty("os.name");
		try (Arena arena = Arena.ofConfined())
		{
			MemorySegment state = arena.allocate(Binding.STATE);
			MemorySegment name = arena.allocateFrom(path.toAbsolutePath().toString());
			if (operatingSystem.equals("Linux"))
			{
				MemorySegment data = arena.allocate(Linux.BUFFER_SIZE, Long.BYTES);
				check(Linux.READER.invoke(state, 0, name, 0, Linux.MODIFIED, data), state, path);
				if ((data.get(ValueLayout.JAVA_INT, 0) & Linux.MODIFIED) == 0)
					throw new IOException("Native metadata does not provide modification time for " + path);
				return Instant.ofEpochSecond(data.get(ValueLayout.JAVA_LONG, Linux.SECONDS_OFFSET),
					Integer.toUnsignedLong(data.get(ValueLayout.JAVA_INT, Linux.NANOS_OFFSET)));
			}
			if (operatingSystem.equals("Mac OS X"))
			{
				MemorySegment data = arena.allocate(Darwin.BUFFER_SIZE, Long.BYTES);
				check(Darwin.READER.invoke(state, name, data), state, path);
				return Instant.ofEpochSecond(data.get(ValueLayout.JAVA_LONG, Darwin.SECONDS_OFFSET),
					data.get(ValueLayout.JAVA_LONG, Darwin.NANOS_OFFSET));
			}
			throw new IOException("Exact native timestamp lookup is unsupported on " + operatingSystem);
		}
	}

	/**
	 * Reports native failures without replacing them with rounded metadata.
	 *
	 * @param status native return value
	 * @param state captured errno
	 * @param path the queried path
	 * @throws IOException if the native query fails
	 */
	private static void check(int status, MemorySegment state, Path path) throws IOException
	{
		if (status != 0)
			throw new IOException("Cannot read exact Unix timestamp for " + path + ": errno=" +
				state.get(ValueLayout.JAVA_INT, Binding.ERRNO_OFFSET));
	}
}
