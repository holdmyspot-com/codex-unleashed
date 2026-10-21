package com.holdmyspot.codexunleashed.tooling;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.stream.Stream;
import org.testng.SkipException;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertTrue;

/**
 * Exercises the compiler scheduler service through its compiler wrapper and real Linux process groups.
 * <p>
 * Each simulated compiler is a POSIX shell that the wrapper launches in place of rustc. It records its process
 * identifier, appends one line per run, and waits for a release file, so the test controls when it exits while the
 * service pauses, resumes, or kills its process group. The test supplies the service's free-memory readings one at a
 * time, so each reading takes effect after the compiler events that precede it.
 */
public final class CompilerSchedulerServiceTest
{
	/**
	 * The free memory, in bytes, below which the tested service pauses or kills compilers.
	 */
	private static final long RESERVE = 1000;
	/**
	 * Free memory, in bytes, that lets the tested service start and resume compilers.
	 */
	private static final long AMPLE = 2 * RESERVE;
	/**
	 * Free memory, in bytes, that makes the tested service pause or kill a compiler.
	 */
	private static final long SHORT = RESERVE - 1;
	/**
	 * How long a test waits for a process to reach an expected state.
	 */
	private static final Duration TIMEOUT = Duration.ofSeconds(20);

	/**
	 * Creates the service tests.
	 */
	public CompilerSchedulerServiceTest()
	{
	}

	/**
	 * Skips the tests outside Linux, where the compiler wrapper's {@code setsid} and process states are unavailable.
	 */
	@BeforeClass
	public void requireLinux()
	{
		if (!System.getProperty("os.name").equals("Linux"))
			throw new SkipException("The compiler scheduler runs only on Linux");
	}

	/**
	 * Under memory pressure, stops the newest compiler's process group but not the oldest, and continues it once memory
	 * is ample again.
	 *
	 * @throws Exception if the fixture cannot be created or a process cannot be observed
	 */
	@Test
	public void pausesAndResumesNewestCompiler() throws Exception
	{
		Path root = Files.createTempDirectory("compiler-scheduler-");
		BlockingQueue<Long> readings = new LinkedBlockingQueue<>();
		List<Process> wrappers = new ArrayList<>();
		try (CompilerSchedulerService service = new CompilerSchedulerService(RESERVE, readings::take,
			Duration.ZERO, new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8)))
		{
			startCompiler(service, root, "oldest", wrappers);
			long oldestPid = awaitPid(root, "oldest");
			startCompiler(service, root, "newest", wrappers);
			readings.put(AMPLE);
			long newestPid = awaitPid(root, "newest");

			readings.put(SHORT);
			await(() -> isStopped(newestPid), "the newest compiler stops");
			assertFalse(isStopped(oldestPid), "the oldest compiler keeps running");

			readings.put(AMPLE);
			await(() -> !isStopped(newestPid), "the newest compiler continues");

			Files.createFile(root.resolve("release"));
			assertEquals(exitStatus(wrappers.get(0)), 0);
			assertEquals(exitStatus(wrappers.get(1)), 0);
		}
		finally
		{
			destroy(wrappers);
			delete(root);
		}
	}

	/**
	 * Kills the paused newest compiler when only the oldest still runs, and reruns it alone after the oldest exits.
	 *
	 * @throws Exception if the fixture cannot be created or a process cannot be observed
	 */
	@Test
	public void rerunsKilledCompilerAlone() throws Exception
	{
		Path root = Files.createTempDirectory("compiler-scheduler-");
		BlockingQueue<Long> readings = new LinkedBlockingQueue<>();
		List<Process> wrappers = new ArrayList<>();
		try (CompilerSchedulerService service = new CompilerSchedulerService(RESERVE, readings::take,
			Duration.ZERO, new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8)))
		{
			startCompiler(service, root, "oldest", wrappers);
			awaitPid(root, "oldest");
			startCompiler(service, root, "newest", wrappers);
			readings.put(AMPLE);
			long firstNewestPid = awaitPid(root, "newest");

			readings.put(SHORT);
			await(() -> isStopped(firstNewestPid), "the newest compiler stops");
			readings.put(SHORT);
			await(() -> ProcessHandle.of(firstNewestPid).isEmpty(), "the newest compiler is killed");
			assertEquals(runs(root, "newest"), 1, "the killed compiler waits for the oldest to exit");

			Files.createFile(root.resolve("release"));
			assertEquals(exitStatus(wrappers.get(0)), 0);
			assertEquals(exitStatus(wrappers.get(1)), 0);
			assertEquals(runs(root, "newest"), 2);
			assertEquals(Files.readAllLines(root.resolve("newest.stdout")), List.of("compiler output"));
			assertEquals(Files.readAllLines(root.resolve("newest.stderr")), List.of("compiler diagnostic"));
		}
		finally
		{
			destroy(wrappers);
			delete(root);
		}
	}

	/**
	 * Reruns a compiler that a kill signal ended once, and fails it with the compiler's status when the rerun is
	 * killed too.
	 *
	 * @throws IOException if the fixture cannot be created
	 */
	@Test
	public void failsCompilerKilledTwice() throws IOException
	{
		Path root = Files.createTempDirectory("compiler-scheduler-");
		try
		{
			ByteArrayOutputStream diagnostics = new ByteArrayOutputStream();
			String compiler = "printf 'run\\n' >> \"$0\"; kill -s KILL $$";
			int status;
			try (PrintStream err = new PrintStream(diagnostics, true, StandardCharsets.UTF_8))
			{
				status = Main.run(new String[]{"schedule-compilers", "--", "bash", "-c",
					"exec \"$RUSTC_WRAPPER\" sh -c \"$0\" \"$1\" --crate-name twice", compiler,
					root.resolve("twice.runs").toString()}, InputStream.nullInputStream(), err, err);
			}
			assertEquals(status, 137);
			assertEquals(Files.readAllLines(root.resolve("twice.runs")).size(), 2);
			String message = diagnostics.toString(StandardCharsets.UTF_8);
			assertTrue(message.contains("twice"), message);
		}
		finally
		{
			delete(root);
		}
	}

	/**
	 * Preserves a failed compiler's output and exit status.
	 *
	 * @throws Exception if the compiler cannot start or finish
	 */
	@Test
	public void preservesFailedCompilerOutput() throws Exception
	{
		Path root = Files.createTempDirectory("compiler-scheduler-");
		List<Process> wrappers = new ArrayList<>();
		try (CompilerSchedulerService service = new CompilerSchedulerService(RESERVE, () -> AMPLE,
			Duration.ofMillis(10), new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8)))
		{
			Map<String, String> environment = service.environment();
			ProcessBuilder builder = new ProcessBuilder(environment.get("RUSTC_WRAPPER"), "sh", "-c",
				"printf 'output\\n'; printf 'diagnostic\\n' >&2; exit 23", "compiler", "--crate-name", "failed").
				redirectOutput(root.resolve("stdout").toFile()).
				redirectError(root.resolve("stderr").toFile());
			builder.environment().putAll(environment);
			wrappers.add(builder.start());
			assertEquals(exitStatus(wrappers.getFirst()), 23);
			assertEquals(Files.readAllLines(root.resolve("stdout")), List.of("output"));
			assertEquals(Files.readAllLines(root.resolve("stderr")), List.of("diagnostic"));
		}
		finally
		{
			destroy(wrappers);
			delete(root);
		}
	}

	/**
	 * Runs a non-compiling rustc invocation, such as a version query, without waiting for the scheduler.
	 *
	 * @throws IOException if the fixture cannot be created
	 */
	@Test
	public void passesThroughQueries() throws IOException
	{
		try (PrintStream err = new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8))
		{
			assertEquals(Main.run(new String[]{"schedule-compilers", "--", "bash", "-c",
				"exec \"$RUSTC_WRAPPER\" sh -c 'exit 5' -vV"}, InputStream.nullInputStream(), err, err), 5);
		}
	}

	/**
	 * Starts a wrapper around a simulated compiler that records each run and waits for the release file.
	 *
	 * @param service the scheduler that the wrapper contacts
	 * @param root the fixture directory
	 * @param name the simulated crate name
	 * @param wrappers receives the started wrapper, which the caller destroys and closes
	 * @throws IOException if the wrapper cannot start
	 */
	private static void startCompiler(CompilerSchedulerService service, Path root, String name,
		List<Process> wrappers) throws IOException
	{
		String compiler = "printf 'compiler output\\n'; printf 'compiler diagnostic\\n' >&2; " +
			"printf 'run\\n' >> \"$0.runs\"; printf '%s\\n' $$ > \"$0.pid.tmp\"; " +
			"mv \"$0.pid.tmp\" \"$0.pid\"; while [ ! -e \"$1\" ]; do sleep 0.02; done";
		Map<String, String> environment = service.environment();
		ProcessBuilder builder = new ProcessBuilder(environment.get("RUSTC_WRAPPER"), "sh", "-c", compiler,
			root.resolve(name).toString(), root.resolve("release").toString(), "--crate-name", name).
			redirectOutput(root.resolve(name + ".stdout").toFile()).
			redirectError(root.resolve(name + ".stderr").toFile());
		builder.environment().putAll(environment);
		wrappers.add(builder.start());
	}

	/**
	 * Waits for a simulated compiler to record its process identifier.
	 *
	 * @param root the fixture directory
	 * @param name the simulated crate name
	 * @return the compiler's process identifier
	 * @throws Exception if the identifier does not appear in time
	 */
	private static long awaitPid(Path root, String name) throws Exception
	{
		Path file = root.resolve(name + ".pid");
		await(() -> Files.exists(file), name + " records its process identifier");
		return Long.parseLong(Files.readString(file).strip());
	}

	/**
	 * Returns how many times a simulated compiler ran.
	 *
	 * @param root the fixture directory
	 * @param name the simulated crate name
	 * @return the number of runs
	 * @throws IOException if the record cannot be read
	 */
	private static int runs(Path root, String name) throws IOException
	{
		return Files.readAllLines(root.resolve(name + ".runs")).size();
	}

	/**
	 * Indicates whether a Linux process is stopped by a signal.
	 *
	 * @param pid the process identifier
	 * @return {@code true} if the process state is {@code T}
	 */
	private static boolean isStopped(long pid)
	{
		try
		{
			String stat = Files.readString(Path.of("/proc", Long.toString(pid), "stat"));
			return stat.substring(stat.lastIndexOf(')') + 2).startsWith("T");
		}
		catch (NoSuchFileException _)
		{
			return false;
		}
		catch (IOException failure)
		{
			throw new AssertionError(failure);
		}
	}

	/**
	 * Waits until a condition holds.
	 *
	 * @param condition the condition
	 * @param description what the condition means, for the failure message
	 * @throws InterruptedException if the wait is interrupted
	 */
	private static void await(BooleanSupplier condition, String description) throws InterruptedException
	{
		Instant deadline = Instant.now().plus(TIMEOUT);
		while (!condition.getAsBoolean())
		{
			if (Instant.now().isAfter(deadline))
				throw new AssertionError("Timed out waiting until " + description);
			Thread.sleep(10);
		}
	}

	/**
	 * Waits for a wrapper to exit.
	 *
	 * @param process the wrapper
	 * @return its exit status
	 * @throws InterruptedException if the wait is interrupted
	 */
	private static int exitStatus(Process process) throws InterruptedException
	{
		if (!process.waitFor(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS))
			throw new AssertionError("Timed out waiting for a compiler wrapper to exit");
		return process.exitValue();
	}

	/**
	 * Ends wrappers that a failed test left running, together with their descendants, and releases their streams.
	 *
	 * @param wrappers the started wrappers
	 * @throws IOException if a wrapper's streams cannot be closed
	 */
	private static void destroy(List<Process> wrappers) throws IOException
	{
		for (Process wrapper : wrappers)
		{
			try (wrapper)
			{
				wrapper.descendants().forEach(ProcessHandle::destroyForcibly);
				wrapper.destroyForcibly();
			}
		}
	}

	/**
	 * Removes the fixture directory.
	 *
	 * @param root the fixture-owned tree
	 * @throws IOException if cleanup fails
	 */
	private static void delete(Path root) throws IOException
	{
		try (Stream<Path> paths = Files.walk(root))
		{
			for (Path path : paths.sorted(Comparator.reverseOrder()).toList())
				Files.delete(path);
		}
	}
}
