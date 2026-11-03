package com.holdmyspot.codexunleashed.scheduler.test;

import com.holdmyspot.codexunleashed.scheduler.LinuxAllocationTrace;
import com.holdmyspot.codexunleashed.tooling.DefaultToolingScope;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import org.testng.SkipException;
import org.testng.annotations.Test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

/**
 * Exercises the sole-tracer operation against native processes whose tasks die while the tracer services their stops.
 */
public final class LinuxAllocationTraceNativeTest
{
	/**
	 * A process whose threads keep entering syscalls while its main thread exits the whole group.
	 * The kernel kills each stopped thread before the tracer finishes servicing its syscall stop.
	 */
	private static final String GROUP_EXIT_SOURCE = """
		#include <pthread.h>
		#include <time.h>
		#include <unistd.h>
		static void *spin(void *unused)
		{
		    (void) unused;
		    for (;;) getppid();
		    return 0;
		}
		int main(void)
		{
		    pthread_t threads[8];
		    for (int index = 0; index < 8; ++index) pthread_create(&threads[index], 0, spin, 0);
		    struct timespec pause = {0, 20000000};
		    nanosleep(&pause, 0);
		    return 7;
		}
		""";
	/**
	 * The number of group exits traced, so that at least one lands while a thread's syscall stop is being serviced.
	 * With the fix for that race reverted, one traced exit exposed it in 9 of 15 local runs, so 20 exits, about
	 * 0.1 s each, miss a regression with a probability near 0.4^20; fewer exits would trade that for a flaky pass.
	 */
	private static final int GROUP_EXIT_ATTEMPTS = 20;

	/** Creates a resource-free test instance; each method owns its scope, directory and native attempts. */
	public LinuxAllocationTraceNativeTest()
	{
	}

	/**
	 * Collects a multithreaded process that exits its whole group while other threads are stopped in syscalls,
	 * reporting the process's own exit status instead of failing on the vanished threads.
	 * @throws IOException if the fixture cannot be compiled or tracing fails
	 * @throws InterruptedException if the test is interrupted
	 */
	@Test
	public void collectsThreadsKilledByGroupExit() throws IOException, InterruptedException
	{
		if (System.getProperty("os.name").equals("Linux") == false)
			throw new SkipException("Allocation tracing requires Linux");
		Path work = Files.createTempDirectory("trace-group-exit-");
		try (var scope = new DefaultToolingScope())
		{
			Path source = work.resolve("group-exit.c");
			Files.writeString(source, GROUP_EXIT_SOURCE);
			Path executable = work.resolve("group-exit");
			Process compiler = new ProcessBuilder("cc", "-O1", "-pthread", "-o", executable.toString(),
				source.toString()).redirectErrorStream(true).start();
			String diagnostics = new String(compiler.getInputStream().readAllBytes());
			if (compiler.waitFor() != 0)
				throw new SkipException("The C compiler could not build the fixture: " + diagnostics);
			for (int attempt = 0; attempt < GROUP_EXIT_ATTEMPTS; ++attempt)
			{
				LinuxAllocationTrace.Outcome outcome = LinuxAllocationTrace.run(scope.getNativeLinker(),
					List.of(executable.toString()), Map.of("PATH", "/usr/bin:/bin"), work, 0, denial ->
					{
						throw new IOException("Unexpected allocation denial: " + denial);
					});
				requireThat(outcome.denied(), "denied").isFalse();
				requireThat(outcome.root().waitStatus(), "rootWaitStatus").isEqualTo(7 << 8);
			}
		}
		finally
		{
			try (var paths = Files.walk(work))
			{
				for (Path path : paths.sorted(Comparator.reverseOrder()).toList())
					Files.delete(path);
			}
		}
	}
}
