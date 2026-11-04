package com.holdmyspot.codexunleashed.scheduler.test;

import com.holdmyspot.codexunleashed.scheduler.LinuxTracedZombies;
import org.testng.annotations.Test;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

/**
 * Verifies that a process killed while a thread of this JVM traced it is released to its parent.
 */
public final class LinuxTracedZombiesTest
{
	/**
	 * Linux {@code PTRACE_SEIZE}, which traces a process without stopping it.
	 */
	private static final int PTRACE_SEIZE = 0x4206;

	/**
	 * Creates the tests.
	 */
	public LinuxTracedZombiesTest()
	{
	}

	/**
	 * A grandchild traced by another thread of this JVM and then killed stays a zombie that its parent cannot reap until
	 * it is released; once released, the parent reaps it.
	 * @throws IOException          if a process cannot start or its status cannot be read
	 * @throws InterruptedException if the test is interrupted
	 * @throws ExecutionException   if the tracing thread fails
	 * @throws TimeoutException     if the tracing thread does not report
	 */
	@Test
	public void releasesKilledTracee() throws IOException, InterruptedException, ExecutionException,
		TimeoutException
	{
		Process shell = new ProcessBuilder("sh", "-c", "sleep 60 & echo $!; wait $!; echo reaped").
			redirectErrorStream(true).start();
		CountDownLatch finished = new CountDownLatch(1);
		try
		{
			BufferedReader output = new BufferedReader(new InputStreamReader(shell.getInputStream(),
				StandardCharsets.UTF_8));
			long tracee = Long.parseLong(output.readLine().strip());
			CompletableFuture<Long> seized = new CompletableFuture<>();
			Thread tracer = Thread.ofPlatform().daemon().start(() ->
			{
				try
				{
					seized.complete(seize(tracee));
					finished.await();
				}
				catch (Throwable failure)
				{
					seized.completeExceptionally(failure);
				}
			});
			requireThat(seized.get(30, TimeUnit.SECONDS), "seizeResult").isEqualTo(0L);
			ProcessHandle.of(tracee).orElseThrow().destroyForcibly();
			requireThat(awaitZombie(tracee), "traceeState").isEqualTo("Z");

			requireThat(LinuxTracedZombies.release(tracee), "released").isTrue();
			requireThat(shell.waitFor(30, TimeUnit.SECONDS), "parentReaped").isTrue();
			requireThat(new String(shell.getInputStream().readAllBytes(), StandardCharsets.UTF_8), "parentOutput").
				contains("reaped");
			requireThat(tracer.isAlive(), "tracerStillTracing").isTrue();
		}
		finally
		{
			finished.countDown();
			shell.destroyForcibly();
			shell.waitFor(10, TimeUnit.SECONDS);
		}
	}

	/**
	 * Traces a process from the calling thread without stopping it.
	 * @param pid the process ID
	 * @return 0 on success, or the negated {@code errno}
	 * @throws Throwable if the downcall fails
	 */
	private static long seize(long pid) throws Throwable
	{
		Linker linker = Linker.nativeLinker();
		MethodHandle ptrace = linker.downcallHandle(linker.defaultLookup().find("ptrace").orElseThrow(),
			FunctionDescriptor.of(ValueLayout.JAVA_LONG, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT,
				ValueLayout.ADDRESS, ValueLayout.ADDRESS), Linker.Option.captureCallState("errno"),
			Linker.Option.firstVariadicArg(1));
		try (Arena arena = Arena.ofConfined())
		{
			MemorySegment state = arena.allocate(Linker.Option.captureStateLayout());
			long result = (long) ptrace.invokeExact(state, PTRACE_SEIZE, (int) pid, MemorySegment.NULL,
				MemorySegment.NULL);
			if (result == 0)
				return 0;
			return -state.get(ValueLayout.JAVA_INT, Linker.Option.captureStateLayout().
				byteOffset(MemoryLayout.PathElement.groupElement("errno")));
		}
	}

	/**
	 * Waits for a killed process to become a zombie.
	 * @param pid the process ID
	 * @return its last observed state letter
	 * @throws IOException if its status cannot be read
	 */
	private static String awaitZombie(long pid) throws IOException
	{
		Instant deadline = Instant.now().plus(Duration.ofSeconds(30));
		String state;
		do
		{
			state = Files.readAllLines(Path.of("/proc", Long.toString(pid), "status")).stream().
				filter(line -> line.startsWith("State:")).findFirst().orElseThrow().substring("State:".length()).
				strip().substring(0, 1);
			Thread.onSpinWait();
		}
		while (!state.equals("Z") && Instant.now().isBefore(deadline));
		return state;
	}
}
