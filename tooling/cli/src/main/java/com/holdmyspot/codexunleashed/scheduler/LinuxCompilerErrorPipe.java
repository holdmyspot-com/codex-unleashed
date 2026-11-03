package com.holdmyspot.codexunleashed.scheduler;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.time.Duration;
import java.util.Objects;

/**
 * Owns the standard-error pipe of one Linux compiler attempt and relays its output to Cargo.
 * <p>
 * The wrapper lends the write end to the attempt as its standard error and then closes its own copy, so the pipe
 * reaches end of file once the attempt's last process has exited. A relay thread reads the pipe until then and closes
 * the read end itself, so abandoning an owner releases the pipe when the attempt's processes exit. Instances are
 * confined to the wrapper's protocol thread, apart from that relay thread.
 */
public final class LinuxCompilerErrorPipe
{
	/** Size in bytes of each read from the pipe. */
	private static final int READ_SIZE = 65_536;

	/** Native functions borrowed from the invocation scope. */
	private final LinuxBorrowedStreams.NativeBindings bindings;
	/** Relay that receives this attempt's output, shared with the compiler's other attempts. */
	private final CompilerErrorRelay relay;
	/** Read end, owned and closed by the relay thread. */
	private final int readEnd;
	/** Write end lent to the attempt; -1 once this owner has closed its copy. */
	private int writeEnd;
	/** Thread that relays the pipe's output until end of file. */
	private final Thread reader;
	/** First failure of the relay thread, or null if it read to end of file and closed the read end. */
	private volatile Throwable readFailure;
	/** Indicates whether {@link #end} already waited for the relay thread. */
	private boolean ended;

	/**
	 * Creates the pipe and starts relaying its output.
	 * @param bindings native functions borrowed from the invocation scope
	 * @param relay relay that receives the attempt's output
	 * @throws NullPointerException if any argument is null
	 * @throws IOException if the pipe cannot be created
	 */
	public LinuxCompilerErrorPipe(LinuxBorrowedStreams.NativeBindings bindings, CompilerErrorRelay relay) throws IOException
	{
		this.bindings = Objects.requireNonNull(bindings, "bindings");
		this.relay = Objects.requireNonNull(relay, "relay");
		int[] ends = bindings.pipe();
		readEnd = ends[0];
		writeEnd = ends[1];
		reader = Thread.ofPlatform().daemon().name("compiler-error-relay").unstarted(this::relayOutput);
		reader.start();
	}

	/**
	 * Returns the write end to lend to the attempt as its standard error.
	 * @return the open write descriptor
	 * @throws IllegalStateException if the write end was already closed
	 */
	public int writeEnd()
	{
		if (writeEnd < 0)
			throw new IllegalStateException("Compiler standard-error write end is closed");
		return writeEnd;
	}

	/**
	 * Closes this owner's copy of the write end, so that the pipe ends when the attempt's processes exit.
	 * Has no effect if the copy is already closed.
	 * @throws IOException if the descriptor cannot be closed
	 */
	public void closeWriteEnd() throws IOException
	{
		if (writeEnd < 0)
			return;
		int descriptor = writeEnd;
		writeEnd = -1;
		bindings.require("close", descriptor);
	}

	/**
	 * Waits for the attempt's output to end, then ends the attempt in the relay.
	 * @param superseded {@code true} if the scheduler will retry the compiler in another attempt
	 * @param timeout how long to wait for the attempt's processes to close the pipe
	 * @throws IllegalStateException if the attempt already ended
	 * @throws IOException if the pipe stays open for {@code timeout}, reading or closing it fails, or writing to Cargo
	 *     fails
	 */
	public void end(boolean superseded, Duration timeout) throws IOException
	{
		closeWriteEnd();
		if (ended)
			throw new IllegalStateException("Compiler standard error already ended");
		try
		{
			if (!reader.join(timeout))
				throw new IOException("Compiler standard error stayed open for " + timeout + " after the attempt ended");
		}
		catch (InterruptedException cause)
		{
			Thread.currentThread().interrupt();
			throw new InterruptedIOException("Interrupted while awaiting compiler standard error");
		}
		ended = true;
		Throwable failure = readFailure;
		if (failure != null)
			throw new IOException("Compiler standard error could not be relayed", failure);
		relay.endAttempt(superseded);
	}

	/**
	 * Reads the pipe until end of file, forwarding each read to the relay, then closes the read end. A failure ends the
	 * reading and is retained.
	 */
	private void relayOutput()
	{
		byte[] bytes = new byte[READ_SIZE];
		try (var arena = Arena.ofConfined())
		{
			var buffer = arena.allocate(READ_SIZE);
			while (true)
			{
				var result = bindings.call("read", readEnd, buffer, (long) READ_SIZE);
				if (result.value() == 0)
					return;
				if (result.value() < 0)
				{
					// EINTR
					if (result.errno() == 4)
						continue;
					throw new IOException("Compiler standard error read failed with errno=" + result.errno());
				}
				int length = Math.toIntExact(result.value());
				MemorySegment.copy(buffer, ValueLayout.JAVA_BYTE, 0, bytes, 0, length);
				relay.accept(bytes, 0, length);
			}
		}
		catch (IOException | RuntimeException cause)
		{
			readFailure = cause;
		}
		finally
		{
			try { bindings.require("close", readEnd); }
			catch (IOException | RuntimeException cause)
			{
				if (readFailure == null) readFailure = cause;
			}
		}
	}
}
