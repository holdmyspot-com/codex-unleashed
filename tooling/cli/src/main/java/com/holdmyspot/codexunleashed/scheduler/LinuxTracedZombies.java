package com.holdmyspot.codexunleashed.scheduler;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/**
 * Releases processes that exited while a thread of this JVM traced them.
 * <p>
 * A traced process that exits stays a zombie that its parent cannot reap until its tracer collects its exit. Frida's
 * child gating traces each held child from a thread of this JVM and never collects one that is killed while held:
 * its resume then fails, because a zombie cannot be detached. Any thread of the tracer's process may collect the exit,
 * which hands the zombie back to its parent.
 */
public final class LinuxTracedZombies
{
	/**
	 * Linux {@code WNOHANG}: return at once if no child has changed state.
	 */
	private static final int WNOHANG = 1;
	/**
	 * Linux {@code __WALL}: wait for every kind of child, including traced processes.
	 */
	private static final int WALL = 0x40000000;
	/**
	 * Linux {@code ECHILD}: the process is not a child or tracee of the calling process.
	 */
	private static final int ECHILD = 10;
	/**
	 * Byte offset of errno in the captured call state.
	 */
	private static final long ERRNO_OFFSET = Linker.Option.captureStateLayout().
		byteOffset(MemoryLayout.PathElement.groupElement("errno"));
	/**
	 * The libc {@code waitpid} binding, capturing errno.
	 */
	private static final MethodHandle WAITPID = bindWaitpid();

	private LinuxTracedZombies()
	{
	}

	/**
	 * Collects the traced exit of a process, so that its parent can reap it.
	 * @param pid the process ID
	 * @return true if the process was a zombie traced by a thread of this JVM and its exit was collected; false if it is
	 *   running, absent, untraced, or traced by another process
	 * @throws IOException if its status cannot be read or the collection fails
	 */
	public static boolean release(long pid) throws IOException
	{
		Map<String, String> status;
		try
		{
			status = status(pid);
		}
		catch (NoSuchFileException _)
		{
			return false;
		}
		if (!status.getOrDefault("State", "").startsWith("Z"))
			return false;
		String tracer = status.getOrDefault("TracerPid", "0");
		if (tracer.equals("0") || !Files.isDirectory(Path.of("/proc/self/task", tracer)))
			return false;

		try (Arena arena = Arena.ofConfined())
		{
			MemorySegment state = arena.allocate(Linker.Option.captureStateLayout());
			int result = (int) WAITPID.invokeExact(state, (int) pid, MemorySegment.NULL, WNOHANG | WALL);
			if (result == pid)
				return true;
			if (result == 0)
				return false;
			int errno = state.get(ValueLayout.JAVA_INT, ERRNO_OFFSET);
			// Another thread of this JVM, such as the tracer itself, collected the exit first.
			if (errno == ECHILD)
				return false;
			throw new IOException("waitpid(" + pid + ") failed with errno " + errno);
		}
		catch (IOException | RuntimeException | Error failure)
		{
			throw failure;
		}
		catch (Throwable failure)
		{
			throw new IOException("waitpid(" + pid + ") invocation failed", failure);
		}
	}

	/**
	 * Reports whether a process still runs.
	 * @param pid the process ID
	 * @return false if the process is absent or a zombie
	 * @throws IOException if its status exists but cannot be read
	 */
	public static boolean running(long pid) throws IOException
	{
		try
		{
			String state = status(pid).getOrDefault("State", "");
			return !state.startsWith("Z") && !state.startsWith("X");
		}
		catch (NoSuchFileException _)
		{
			return false;
		}
	}

	/**
	 * Reads the fields of a process's status file.
	 * @param pid the process ID
	 * @return each field's value, without surrounding whitespace
	 * @throws NoSuchFileException if the process is absent
	 * @throws IOException         if the status cannot be read
	 */
	private static Map<String, String> status(long pid) throws IOException
	{
		Map<String, String> fields = new HashMap<>();
		for (String line : Files.readAllLines(Path.of("/proc", Long.toString(pid), "status")))
		{
			int colon = line.indexOf(':');
			if (colon > 0)
				fields.put(line.substring(0, colon), line.substring(colon + 1).strip());
		}
		return fields;
	}

	/**
	 * Binds libc {@code waitpid} with errno capture.
	 * @return the downcall handle
	 * @throws IllegalStateException if libc has no {@code waitpid}
	 */
	private static MethodHandle bindWaitpid()
	{
		Linker linker = Linker.nativeLinker();
		MemorySegment symbol = linker.defaultLookup().find("waitpid").
			orElseThrow(() -> new IllegalStateException("libc has no waitpid"));
		return linker.downcallHandle(symbol, FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT,
			ValueLayout.ADDRESS, ValueLayout.JAVA_INT), Linker.Option.captureCallState("errno"));
	}
}
