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

/** Sets exact Unix timestamps when the JDK's signed-long nanosecond conversion cannot preserve them. */
public final class UnixFileTimes
{
	/** Prevents construction. */
	private UnixFileTimes()
	{
	}

	/** Provides the fixed native signature without translating unrelated unchecked failures. */
	@FunctionalInterface
	public interface Setter
	{
		/**
		 * Invokes utimensat with captured errno and an absolute path.
		 *
		 * @param state native call state
		 * @param directory ignored directory descriptor for the absolute path
		 * @param path the null-terminated native filename
		 * @param times access and modification timespecs
		 * @param flags native operation flags
		 * @return the native status
		 */
		int invoke(MemorySegment state, int directory, MemorySegment path, MemorySegment times, int flags);
	}

	/** Owns the immutable native signature and layouts, initialized only for affected timestamp writes. */
	private static final class Binding
	{
		private static final Linker LINKER = Linker.nativeLinker();
		private static final MemoryLayout TIMESPEC = timespec();
		private static final MemoryLayout STATE = Linker.Option.captureStateLayout();
		private static final long ERRNO_OFFSET = STATE.byteOffset(MemoryLayout.PathElement.groupElement("errno"));
		private static final Setter SETTER = bind();

		/** Prevents construction. */
		private Binding()
		{
		}

		/**
		 * Requires the 64-bit POSIX ABI used by supported Unix release targets.
		 *
		 * @return a pair of native C longs
		 * @throws UnsupportedOperationException if the platform has a different C long size
		 */
		private static MemoryLayout timespec()
		{
			MemoryLayout cLong = LINKER.canonicalLayouts().get("long");
			if (cLong.byteSize() != Long.BYTES)
				throw new UnsupportedOperationException("Exact Unix timestamps require a 64-bit C long");
			return MemoryLayout.structLayout(cLong.withName("seconds"), cLong.withName("nanos"));
		}

		/**
		 * Binds the fixed POSIX function with captured errno.
		 *
		 * @return its typed invoker
		 */
		// The public FFM API intrinsically marks downcalls restricted. The launcher grants this module native access;
		// the fixed signature, 64-bit layouts and confined allocations are checked and exercised by timestamp tests.
		@SuppressWarnings("restricted")
		private static Setter bind()
		{
			MethodHandle handle = LINKER.downcallHandle(LINKER.defaultLookup().find("utimensat").
				orElseThrow(() -> new UnsupportedOperationException("The Unix runtime does not provide utimensat")),
				FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.ADDRESS,
					ValueLayout.ADDRESS, ValueLayout.JAVA_INT), Linker.Option.captureCallState("errno"));
			return MethodHandleProxies.asInterfaceInstance(Setter.class, handle);
		}
	}

	/**
	 * Writes access and modification time without changing creation time or following a relative directory descriptor.
	 *
	 * @param path the file or directory
	 * @param instant the required timestamp
	 * @throws IOException if the native filesystem operation fails
	 */
	static void set(Path path, Instant instant) throws IOException
	{
		// WORKAROUND: JDK 27's UnixNativeDispatcher divides signed epoch nanoseconds using C truncation,
		// producing an invalid negative tv_nsec, and UnixFileAttributeViews retries with the time clamped to zero.
		// Source: OpenJDK jdk-27+35, UnixNativeDispatcher.c futimens/utimensat and UnixFileAttributeViews.Basic.setTimes.
		// Remove this adapter when unchanged negative-fraction and out-of-long-range tests pass through Files APIs.
		try (Arena arena = Arena.ofConfined())
		{
			MemorySegment times = arena.allocate(MemoryLayout.sequenceLayout(2, Binding.TIMESPEC));
			for (int index = 0; index < 2; index += 1)
			{
				long offset = index * Binding.TIMESPEC.byteSize();
				times.set(ValueLayout.JAVA_LONG, offset, instant.getEpochSecond());
				times.set(ValueLayout.JAVA_LONG, offset + Long.BYTES, instant.getNano());
			}
			MemorySegment state = arena.allocate(Binding.STATE);
			MemorySegment name = arena.allocateFrom(path.toAbsolutePath().toString());
			int status = Binding.SETTER.invoke(state, 0, name, times, 0);
			if (status != 0)
				throw new IOException("Cannot set exact Unix timestamp for " + path + ": errno=" +
					state.get(ValueLayout.JAVA_INT, Binding.ERRNO_OFFSET));
		}
	}
}
