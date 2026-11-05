package com.holdmyspot.codexunleashed.scheduler.test;

import com.holdmyspot.codexunleashed.scheduler.NativeStrings;
import org.testng.annotations.Test;

import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

/**
 * Verifies that copying a native string reads no memory past its terminator.
 */
public final class NativeStringsTest
{
	/**
	 * Bytes in each of the two mapped regions: a multiple of every supported page size.
	 */
	private static final long REGION_BYTES = 64 * 1024;

	/**
	 * Creates the tests.
	 */
	public NativeStringsTest()
	{
	}

	/**
	 * A UTF-8 string whose terminator is the last readable byte before an inaccessible page copies without a fault.
	 */
	@Test
	public void utf8EndingAtInaccessiblePageCopies()
	{
		TestRequirements.requireLinux();
		try (GuardedRegion region = new GuardedRegion())
		{
			MemorySegment text = region.atEnd(new byte[]{'a', 'b', 0});
			requireThat(NativeStrings.utf8(text, Long.MAX_VALUE), "text").isEqualTo("ab");
		}
	}

	/**
	 * A UTF-16LE string whose terminator is the last readable code unit before an inaccessible page copies without a
	 * fault.
	 */
	@Test
	public void utf16EndingAtInaccessiblePageCopies()
	{
		TestRequirements.requireLinux();
		try (GuardedRegion region = new GuardedRegion())
		{
			MemorySegment text = region.atEnd(new byte[]{'a', 0, 'b', 0, 0, 0});
			requireThat(NativeStrings.utf16(text, 1024 * 1024), "text").isEqualTo("ab");
		}
	}

	/**
	 * A readable region followed directly by an inaccessible one, unmapped on close.
	 */
	private static final class GuardedRegion implements AutoCloseable
	{
		/** The native linker. */
		private final Linker linker = Linker.nativeLinker();
		/** {@code munmap(void*, size_t)}. */
		private final MethodHandle unmap = function("munmap", FunctionDescriptor.of(ValueLayout.JAVA_INT,
			ValueLayout.ADDRESS, ValueLayout.JAVA_LONG));
		/** Start of the readable region. */
		private final MemorySegment start;

		/**
		 * Maps both regions and makes the second inaccessible.
		 */
		private GuardedRegion()
		{
			MethodHandle map = function("mmap", FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS,
				ValueLayout.JAVA_LONG, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT,
				ValueLayout.JAVA_LONG));
			MethodHandle protect = function("mprotect", FunctionDescriptor.of(ValueLayout.JAVA_INT,
				ValueLayout.ADDRESS, ValueLayout.JAVA_LONG, ValueLayout.JAVA_INT));
			// PROT_READ | PROT_WRITE; MAP_PRIVATE | MAP_ANONYMOUS on Linux
			MemorySegment mapped = (MemorySegment) call(map, MemorySegment.NULL, 2 * REGION_BYTES, 3, 0x22, -1, 0L);
			requireThat(mapped.address(), "mapped").isNotEqualTo(-1L);
			start = mapped.reinterpret(2 * REGION_BYTES);
			// PROT_NONE
			int status = (int) call(protect, start.asSlice(REGION_BYTES), REGION_BYTES, 0);
			requireThat(status, "mprotect").isEqualTo(0);
		}

		/**
		 * Binds a C library function.
		 * @param name the function's name
		 * @param descriptor its signature
		 * @return the downcall handle
		 */
		private MethodHandle function(String name, FunctionDescriptor descriptor)
		{
			return linker.downcallHandle(linker.defaultLookup().find(name).orElseThrow(), descriptor);
		}

		/**
		 * Places bytes so that their last byte is the readable region's last byte.
		 * @param data the bytes
		 * @return the address of the first byte
		 */
		private MemorySegment atEnd(byte[] data)
		{
			MemorySegment target = start.asSlice(REGION_BYTES - data.length, data.length);
			target.copyFrom(MemorySegment.ofArray(data));
			return MemorySegment.ofAddress(target.address());
		}

		/**
		 * Invokes a downcall.
		 * @param function the downcall handle
		 * @param arguments its arguments
		 * @return its result
		 * @throws AssertionError if the downcall throws a checked exception
		 */
		private static Object call(MethodHandle function, Object... arguments)
		{
			try
			{
				return function.invokeWithArguments(arguments);
			}
			catch (RuntimeException | Error failure)
			{
				throw failure;
			}
			catch (Throwable failure)
			{
				throw new AssertionError("Downcall raised a checked exception", failure);
			}
		}

		@Override
		public void close()
		{
			int status = (int) call(unmap, start, 2 * REGION_BYTES);
			requireThat(status, "munmap").isEqualTo(0);
		}
	}
}
