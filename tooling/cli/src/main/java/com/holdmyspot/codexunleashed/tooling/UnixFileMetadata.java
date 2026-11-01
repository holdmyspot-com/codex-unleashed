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

/**
 * Reads native Unix timestamps when JDK metadata conversion loses their nanosecond precision.
 */
public final class UnixFileMetadata
{
	/** Platform whose immutable ABI was selected when this invocation acquired the service. */
	private final String operatingSystem = System.getProperty("os.name");
	/** Linux invoker, or null when this service belongs to another platform. */
	private final LinuxReader linuxReader;
	/** Darwin invoker, or null when this service belongs to another platform. */
	private final DarwinReader darwinReader;
	/** Darwin no-follow invoker, or null on other platforms. */
	private final DarwinReader darwinNoFollowReader;

	/**
	 * Borrows the platform linker and resolves only the current platform's metadata signature.
	 * @param scope invocation-owned shared services, kept open until all operations finish
	 */
	UnixFileMetadata(ToolingScope scope)
	{
		Linker linker = scope.getNativeLinker();
		linuxReader = operatingSystem.equals("Linux") ? MethodHandleProxies.asInterfaceInstance(LinuxReader.class,
			Binding.bind(linker, "statx", FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT,
				ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.ADDRESS))) : null;
		darwinReader = operatingSystem.equals("Mac OS X") ? MethodHandleProxies.asInterfaceInstance(DarwinReader.class,
			Binding.bind(linker, Darwin.symbol(linker, true), FunctionDescriptor.of(ValueLayout.JAVA_INT,
				ValueLayout.ADDRESS, ValueLayout.ADDRESS))) : null;
		darwinNoFollowReader = operatingSystem.equals("Mac OS X") ? MethodHandleProxies.asInterfaceInstance(DarwinReader.class,
			Binding.bind(linker, Darwin.symbol(linker, false), FunctionDescriptor.of(ValueLayout.JAVA_INT,
				ValueLayout.ADDRESS, ValueLayout.ADDRESS))) : null;
	}

	/**
	 * Represents the Linux statx signature with captured errno.
	 */
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

	/**
	 * Represents the Darwin 64-bit-inode stat signature with captured errno.
	 */
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

	/**
	 * Owns shared layouts only when the native metadata path is used.
	 */
	private static final class Binding
	{
		/** Layout of the errno state captured by the supported native linker. */
		private static final MemoryLayout STATE = Linker.Option.captureStateLayout();
		/** Byte offset of the captured errno value. */
		private static final long ERRNO_OFFSET = STATE.byteOffset(MemoryLayout.PathElement.groupElement("errno"));

		/**
		 * Prevents construction.
		 */
		private Binding()
		{
		}

		/**
		 * Binds a fixed signature selected by the platform-specific reader.
		 *
		 * @param name the native function
		 * @param linker platform linker borrowed from the invocation scope
		 * @param descriptor its verified C signature
		 * @return a handle with captured errno
		 */
		// FFM downcalls intrinsically require native access. The named module receives explicit permission;
		// fixed platform signatures and bounded native buffers retain the restriction's safety obligations.
		@SuppressWarnings("restricted")
		private static MethodHandle bind(Linker linker, String name, FunctionDescriptor descriptor)
		{
			return linker.downcallHandle(linker.defaultLookup().find(name).
				orElseThrow(() -> new UnsupportedOperationException("The Unix runtime does not provide " + name)),
				descriptor, Linker.Option.captureCallState("errno"));
		}
	}

	/**
	 * Owns the architecture-independent Linux statx ABI from include/uapi/linux/stat.h.
	 */
	private static final class Linux
	{
		/** Linux STATX_MTIME attribute request and availability bit. */
		private static final int MODIFIED = 0x40;
		/** Size in bytes of the selected platform metadata ABI. */
		private static final long BUFFER_SIZE = 0x100;
		/** Byte offset of modification-time epoch seconds in this ABI. */
		private static final long SECONDS_OFFSET = 0x70;
		/** Byte offset of the modification-time fractional nanoseconds. */
		private static final long NANOS_OFFSET = SECONDS_OFFSET + Long.BYTES;

		/**
		 * Prevents construction.
		 */
		private Linux()
		{
		}
	}

	/**
	 * Owns Darwin's __DARWIN_STRUCT_STAT64 ABI from bsd/sys/stat.h on supported 64-bit targets.
	 */
	private static final class Darwin
	{
		/** Size in bytes of the selected platform metadata ABI. */
		private static final long BUFFER_SIZE = 144;
		/** Byte offset of modification-time epoch seconds in this ABI. */
		private static final long SECONDS_OFFSET = 48;
		/** Byte offset of the modification-time fractional nanoseconds. */
		private static final long NANOS_OFFSET = SECONDS_OFFSET + Long.BYTES;

		/**
		 * Prevents construction.
		 */
		private Darwin()
		{
		}

		/**
		 * Uses the explicit inode64 alias where Darwin exposes it, or the sole 64-bit stat ABI otherwise.
		 *
		 * @param linker platform linker borrowed from the invocation scope
		 * @param followLinks whether to query the referenced file rather than the link itself
		 * @return Darwin's stat or lstat symbol
		 */
		private static String symbol(Linker linker, boolean followLinks)
		{
			String base = followLinks ? "stat" : "lstat";
			if (linker.defaultLookup().find(base + "$INODE64").isPresent())
				return base + "$INODE64";
			return base;
		}
	}

	/**
	 * Preserves native precision beyond signed-long epoch nanoseconds.
	 *
	 * @param path the source or directory
	 * @return its exact modification timestamp
	 * @throws IOException if native metadata is unavailable or cannot be read
	 */
	Instant lastModified(Path path) throws IOException
	{
		return lastModified(path, true);
	}

	/**
	 * Preserves native timestamp precision while selecting stat or lstat semantics explicitly.
	 *
	 * @param path the source, directory or symbolic link
	 * @param followLinks true to query the referenced file, false to query the link itself
	 * @return its exact modification timestamp
	 * @throws IOException if native metadata is unavailable or cannot be read
	 */
	Instant lastModified(Path path, boolean followLinks) throws IOException
	{
		// WORKAROUND: JDK UnixFileAttributes converts timestamps outside the jlong nanosecond range to microseconds.
		// Remove this native lookup when the unchanged far-future inventory test passes through Files metadata APIs.
		try (Arena arena = Arena.ofConfined())
		{
			MemorySegment state = arena.allocate(Binding.STATE);
			MemorySegment name = arena.allocateFrom(path.toAbsolutePath().toString());
			if (operatingSystem.equals("Linux"))
			{
				MemorySegment data = arena.allocate(Linux.BUFFER_SIZE, Long.BYTES);
				check(linuxReader.invoke(state, 0, name, followLinks ? 0 : 0x100, Linux.MODIFIED, data), state, path);
				if ((data.get(ValueLayout.JAVA_INT, 0) & Linux.MODIFIED) == 0)
					throw new IOException("Native metadata does not provide modification time for " + path);
				return Instant.ofEpochSecond(data.get(ValueLayout.JAVA_LONG, Linux.SECONDS_OFFSET),
					Integer.toUnsignedLong(data.get(ValueLayout.JAVA_INT, Linux.NANOS_OFFSET)));
			}
			if (operatingSystem.equals("Mac OS X"))
			{
				MemorySegment data = arena.allocate(Darwin.BUFFER_SIZE, Long.BYTES);
				check((followLinks ? darwinReader : darwinNoFollowReader).invoke(state, name, data), state, path);
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
