package com.holdmyspot.codexunleashed.scheduler.test;

import com.holdmyspot.codexunleashed.scheduler.LinuxAllocationTrace;
import com.holdmyspot.codexunleashed.tooling.DefaultToolingScope;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
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
	/**
	 * A static program that exits with its number of seccomp filters. Given {@code deny}, it exits with 3 after a denied
	 * 16 GiB mmap; given {@code spawn}, it exits with the status of a dynamic shell it forks and runs.
	 */
	private static final String SECCOMP_SOURCE = """
		#include <stdio.h>
		#include <stdlib.h>
		#include <string.h>
		#include <sys/mman.h>
		#include <sys/wait.h>
		#include <unistd.h>
		int main(int argc, char **argv)
		{
		    if (argc > 1 && strcmp(argv[1], "spawn") == 0)
		    {
		        pid_t child = fork();
		        if (child == 0)
		        {
		            execl("/bin/sh", "sh", "-c", "exit 5", (char *) 0);
		            _exit(127);
		        }
		        int status;
		        waitpid(child, &status, 0);
		        return WIFEXITED(status) ? WEXITSTATUS(status) : 126;
		    }
		    if (argc > 1)
		        return mmap(0, 1UL << 34, PROT_READ | PROT_WRITE, MAP_PRIVATE | MAP_ANONYMOUS, -1, 0) == MAP_FAILED ? 3 : 4;
		    FILE *status = fopen("/proc/self/status", "r");
		    char line[256];
		    int mode = -1;
		    while (status != 0 && fgets(line, sizeof line, status) != 0)
		        if (strncmp(line, "Seccomp_filters:", 16) == 0) mode = atoi(line + 16);
		    return mode;
		}
		""";
	/** The address-space limit under which the fixture's 16 GiB mmap is denied. */
	private static final long ADDRESS_SPACE_BYTES = 1L << 30;

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

	/**
	 * A static image runs under the tracer's seccomp filter, so only its memory and exec calls stop.
	 * @throws IOException if the fixture cannot be compiled or tracing fails
	 * @throws InterruptedException if the test is interrupted
	 */
	@Test
	public void filtersStaticImage() throws IOException, InterruptedException
	{
		TestRequirements.requireLinux();
		int inherited = Files.readAllLines(Path.of("/proc/self/status")).stream()
			.filter(line -> line.startsWith("Seccomp_filters:"))
			.mapToInt(line -> Integer.parseInt(line.substring(16).strip()))
			.findFirst()
			.orElseThrow();
		LinuxAllocationTrace.Outcome outcome = runStatic(List.of(), 0, new AtomicInteger());
		requireThat(outcome.denied(), "denied").isFalse();
		requireThat(outcome.root().waitStatus(), "rootWaitStatus").isEqualTo((inherited + 1) << 8);
	}

	/**
	 * A filtered static image's dynamic child inherits the filter and still runs to completion under the tracer.
	 * @throws IOException if the fixture cannot be compiled or tracing fails
	 * @throws InterruptedException if the test is interrupted
	 */
	@Test
	public void runsChildOfFilteredStaticImage() throws IOException, InterruptedException
	{
		LinuxAllocationTrace.Outcome outcome = runStatic(List.of("spawn"), 0, new AtomicInteger());
		requireThat(outcome.denied(), "denied").isFalse();
		requireThat(outcome.root().waitStatus(), "rootWaitStatus").isEqualTo(5 << 8);
	}

	/**
	 * A static image's denied mmap is still held and reported under the seccomp filter.
	 * @throws IOException if the fixture cannot be compiled or tracing fails
	 * @throws InterruptedException if the test is interrupted
	 */
	@Test
	public void reportsDenialInFilteredStaticImage() throws IOException, InterruptedException
	{
		AtomicInteger denials = new AtomicInteger();
		LinuxAllocationTrace.Outcome outcome = runStatic(List.of("deny"), ADDRESS_SPACE_BYTES, denials);
		requireThat(denials.get(), "denials").isEqualTo(1);
		requireThat(outcome.denied(), "denied").isTrue();
	}

	/**
	 * Compiles the static seccomp fixture into a test-owned directory and runs it under the tracer.
	 * @param arguments fixture arguments
	 * @param addressSpaceBytes child address-space limit, or zero
	 * @param denials counts reported denials, each checked to be the fixture's mmap
	 * @return the traced outcome
	 * @throws IOException if the fixture cannot be compiled or tracing fails
	 * @throws InterruptedException if the test is interrupted
	 */
	private static LinuxAllocationTrace.Outcome runStatic(List<String> arguments, long addressSpaceBytes,
		AtomicInteger denials) throws IOException, InterruptedException
	{
		if (System.getProperty("os.name").equals("Linux") == false)
			throw new SkipException("Allocation tracing requires Linux");
		Path work = Files.createTempDirectory("trace-seccomp-");
		try (var scope = new DefaultToolingScope())
		{
			Path source = work.resolve("seccomp.c");
			Files.writeString(source, SECCOMP_SOURCE);
			Path executable = work.resolve("seccomp");
			Process compiler = new ProcessBuilder("cc", "-static", "-O1", "-o", executable.toString(),
				source.toString()).redirectErrorStream(true).start();
			String diagnostics = new String(compiler.getInputStream().readAllBytes());
			if (compiler.waitFor() != 0)
				throw new SkipException("The C compiler could not build the static fixture: " + diagnostics);
			List<String> command = new ArrayList<>(List.of(executable.toString()));
			command.addAll(arguments);
			return LinuxAllocationTrace.run(scope.getNativeLinker(), command, Map.of("PATH", "/usr/bin:/bin"), work,
				addressSpaceBytes, denial ->
				{
					requireThat(denial.requestedBytes(), "requestedBytes").isEqualTo(1L << 34);
					denials.incrementAndGet();
				});
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
