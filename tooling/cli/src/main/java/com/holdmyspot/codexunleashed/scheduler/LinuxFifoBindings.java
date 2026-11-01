package com.holdmyspot.codexunleashed.scheduler;

import com.holdmyspot.codexunleashed.tooling.ToolingScope;
import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.OptionalInt;

/**
 * Immutable Linux LP64 FIFO functions shared by borrowers of one tooling scope.
 * These bindings own no descriptor or filesystem entry. Each caller retains its endpoint and keeps the scope open
 * until every operation finishes. Every operation owns its native argument and captured-error storage.
 */
public final class LinuxFifoBindings implements LinuxCargoJobserver.Calls
{
	/** Linux open flags from asm-generic/fcntl.h; clients independently reopen their own blocking endpoints. */
	private static final int OWNER_FLAGS = (02 | 04000) | 02000000;
	/** Linux interrupted-system-call errno from asm-generic/errno-base.h. */
	private static final int INTERRUPTED = 4;
	/** Linux EAGAIN from asm-generic/errno-base.h; a nonblocking FIFO has no available byte. */
	private static final int WOULD_BLOCK = 11;
	/** Native ABI linker shared by these resource-free bindings. */
	private final Linker linker;
	/** Captured errno member supplied by the JDK linker rather than global errno lookup. */
	private final long errnoOffset = Linker.Option.captureStateLayout()
		.byteOffset(MemoryLayout.PathElement.groupElement("errno"));
	/** Linux int mkfifo(const char *, mode_t), with uint32 mode_t. */
	private final MethodHandle mkfifo;
	/** Linux int open(const char *, int, ...), supplying ignored promoted mode 0. */
	private final MethodHandle open;
	/** Linux ssize_t read(int, void *, size_t), requesting exactly one byte per operation. */
	private final MethodHandle read;
	/** Linux ssize_t write(int, const void *, size_t), with LP64 word-sized count and result. */
	private final MethodHandle write;
	/** Linux statx(int, const char *, int, unsigned int, struct statx *) with same-call errno. */
	private final MethodHandle statx;
	/** Linux int close(int); Linux consumes the descriptor even when interrupted. */
	private final MethodHandle close;

	/**
	 * Resolves fixed native bindings without acquiring a descriptor or filesystem entry.
	 * @param scope borrowed shared native linker, retained open through this adapter's operations
	 * @throws NullPointerException if {@code scope} is null
	 * @throws IllegalStateException if the scope is closed
	 * @throws UnsupportedOperationException if Linux LP64 or a required libc symbol is unavailable
	 */
	public LinuxFifoBindings(ToolingScope scope)
	{
		Objects.requireNonNull(scope, "scope");
		if (!System.getProperty("os.name").equals("Linux") || ValueLayout.ADDRESS.byteSize() != Long.BYTES)
			throw new UnsupportedOperationException("FIFO bindings require Linux LP64");
		this.linker = scope.getNativeLinker();
		this.mkfifo = bind("mkfifo", FunctionDescriptor.of(ValueLayout.JAVA_INT,
			ValueLayout.ADDRESS, ValueLayout.JAVA_INT), false);
		this.open = bind("open", FunctionDescriptor.of(ValueLayout.JAVA_INT,
			ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT), true);
		this.read = bind("read", FunctionDescriptor.of(ValueLayout.JAVA_LONG,
			ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_LONG), false);
		this.write = bind("write", FunctionDescriptor.of(ValueLayout.JAVA_LONG,
			ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_LONG), false);
		this.statx = bind("statx", FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT,
			ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.ADDRESS), false);
		this.close = bind("close", FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT), false);
	}

	/**
	 * Resolves one Linux symbol with same-call errno capture.
	 * @param name required libc symbol
	 * @param signature verified LP64 signature
	 * @param variadic indicates whether the third argument is a promoted open vararg
	 * @return native handle with captured call-state argument
	 */
	private MethodHandle bind(String name, FunctionDescriptor signature, boolean variadic)
	{
		MemorySegment symbol = linker.defaultLookup().find(name)
			.orElseThrow(() -> new UnsupportedOperationException("Missing Linux libc symbol: " + name));
		if (variadic)
			return linker.downcallHandle(symbol, signature, Linker.Option.captureCallState("errno"),
				Linker.Option.firstVariadicArg(2));
		return linker.downcallHandle(symbol, signature, Linker.Option.captureCallState("errno"));
	}

	/**
	 * Calls a bound operation, preserving its native result and same-invocation errno on failure.
	 * @param operation bound native operation
	 * @param arguments operation arguments after captured state
	 * @return result and failure errno, with zero errno on success
	 * @throws IOException if invocation fails before a native result can be obtained
	 */
	private NativeResult call(MethodHandle operation, Object... arguments) throws IOException
	{
		try (Arena arena = Arena.ofConfined())
		{
			MemorySegment state = arena.allocate(Linker.Option.captureStateLayout());
			List<Object> values = new ArrayList<>();
			values.add(state);
			values.addAll(List.of(arguments));
			long result = ((Number) operation.invokeWithArguments(values)).longValue();
			int error = result == -1 ? state.get(ValueLayout.JAVA_INT, errnoOffset) : 0;
			return new NativeResult(result, error);
		}
		catch (RuntimeException | Error failure) { throw failure; }
		catch (Throwable failure) { throw new IOException("Linux FIFO native invocation failed", failure); }
	}

	@Override
	public void createFifo(Path path, int mode) throws IOException
	{
		try (Arena arena = Arena.ofConfined())
		{
			requireSuccess("mkfifo " + path, call(mkfifo, arena.allocateFrom(path.toString()), mode));
		}
	}

	@Override
	public int open(Path path) throws IOException
	{
		try (Arena arena = Arena.ofConfined())
		{
			NativeResult result = call(open, arena.allocateFrom(path.toString()), OWNER_FLAGS, 0);
			requireSuccess("open " + path, result);
			return Math.toIntExact(result.value());
		}
	}

	@Override
	public OptionalInt tryRead(int descriptor) throws IOException
	{
		try (Arena arena = Arena.ofConfined())
		{
			MemorySegment buffer = arena.allocate(ValueLayout.JAVA_BYTE);
			while (true)
			{
				NativeResult result = call(read, descriptor, buffer, 1L);
				if (result.value() == 1)
					return OptionalInt.of(Byte.toUnsignedInt(buffer.get(ValueLayout.JAVA_BYTE, 0)));
				boolean interrupted = result.value() == -1 && result.error() == INTERRUPTED;
				if (interrupted)
					continue;
				boolean empty = result.value() == -1 && result.error() == WOULD_BLOCK;
				if (empty)
					return OptionalInt.empty();
				throw new IOException("read FIFO token returned " + result.value() + " with errno " + result.error());
			}
		}
	}

	@Override
	public int write(int descriptor, byte[] tokens, int count) throws IOException
	{
		try (Arena arena = Arena.ofConfined())
		{
			MemorySegment buffer = arena.allocateFrom(ValueLayout.JAVA_BYTE, tokens);
			while (true)
			{
				NativeResult result = call(write, descriptor, buffer, (long) count);
				if (result.value() == -1 && result.error() == INTERRUPTED)
					continue;
				requireSuccess("write FIFO tokens", result);
				return Math.toIntExact(result.value());
			}
		}
	}

	/**
	 * Observes one retained FIFO descriptor without consulting its pathname.
	 * @param descriptor retained nonnegative native endpoint
	 * @return exact kernel device components and unsigned inode bits
	 * @throws IllegalArgumentException if {@code descriptor} is negative
	 * @throws IOException if native metadata fails, is incomplete or does not describe a FIFO
	 */
	@Override
	public Identity identity(int descriptor) throws IOException
	{
		if (descriptor < 0)
			throw new IllegalArgumentException("FIFO descriptor must be nonnegative");
		try (Arena arena = Arena.ofConfined())
		{
			// Linux UAPI struct statx is 256 bytes; its fixed-width fields use the same layout on Linux LP64 targets.
			MemorySegment data = arena.allocate(0x100, Long.BYTES);
			NativeResult result = call(statx, descriptor, arena.allocateFrom(""), 0x1000, 0x101, data);
			requireSuccess("statx FIFO descriptor " + descriptor, result);
			int mask = data.get(ValueLayout.JAVA_INT, 0);
			if ((mask & 0x101) != 0x101)
				throw new IOException("FIFO descriptor metadata lacks type or inode: " + Integer.toUnsignedString(mask));
			int mode = Short.toUnsignedInt(data.get(ValueLayout.JAVA_SHORT, 0x1c));
			if ((mode & 0xf000) != 0x1000)
				throw new IOException("Descriptor does not identify a FIFO: " + descriptor);
			return new Identity(Integer.toUnsignedLong(data.get(ValueLayout.JAVA_INT, 0x88)),
				Integer.toUnsignedLong(data.get(ValueLayout.JAVA_INT, 0x8c)), data.get(ValueLayout.JAVA_LONG, 0x20));
		}
	}

	@Override
	public void close(int descriptor) throws IOException
	{
		requireSuccess("close FIFO descriptor " + descriptor, call(close, descriptor));
	}

	/**
	 * Converts a failed libc return into an operation-specific diagnostic.
	 * @param operation failed or successful native operation
	 * @param result same-invocation status and errno
	 * @throws IOException if libc returned -1
	 */
	private void requireSuccess(String operation, NativeResult result) throws IOException
	{
		if (result.value() == -1)
			throw new IOException(operation + " failed with errno " + result.error());
	}

	/**
	 * Preserves the native return and its errno only when the return indicated failure.
	 * @param value native return value
	 * @param error captured errno for -1, or zero on success
	 */
	private record NativeResult(long value, int error) { }

	/**
	 * Carries independently observed native descriptor fields without parsing external text.
	 * @param major unsigned-32-bit device major
	 * @param minor unsigned-32-bit device minor
	 * @param inode unsigned-64-bit inode represented by its exact long bits
	 */
	public record Identity(long major, long minor, long inode)
	{
		/**
		 * Validates the native field magnitudes without narrowing their representations.
		 * @param major unsigned device major from 0 through 4294967295
		 * @param minor unsigned device minor from 0 through 4294967295
		 * @param inode unsigned inode bits, including values above signed-long range
		 * @throws IllegalArgumentException if a device component is outside its unsigned-32-bit range
		 */
		public Identity
		{
			if (major < 0 || major > 0xffff_ffffL || minor < 0 || minor > 0xffff_ffffL)
				throw new IllegalArgumentException("Cargo FIFO device components must fit unsigned 32 bits");
		}
	}

}
