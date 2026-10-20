package com.holdmyspot.codexunleashed.tooling;

import com.holdmyspot.codexunleashed.tooling.CompilerScheduler.Action;
import com.holdmyspot.codexunleashed.tooling.CompilerScheduler.Fail;
import com.holdmyspot.codexunleashed.tooling.CompilerScheduler.Kill;
import com.holdmyspot.codexunleashed.tooling.CompilerScheduler.Pause;
import com.holdmyspot.codexunleashed.tooling.CompilerScheduler.Resume;
import com.holdmyspot.codexunleashed.tooling.CompilerScheduler.Retry;
import com.holdmyspot.codexunleashed.tooling.CompilerScheduler.Start;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Stream;

/**
 * Hosts a {@link CompilerScheduler} for the rustc invocations of one build on Linux.
 * <p>
 * The build runs with the variables from {@link #environment()}, which make Cargo invoke a compiler wrapper. Each
 * wrapper asks the service for permission over a FIFO, runs its compiler in a new process group, and reports when the
 * compiler runs and exits. The service pauses, resumes, and kills those process groups with job-control signals as the
 * scheduler decides, and feeds the scheduler free-memory readings from a background thread.
 */
public final class CompilerSchedulerService implements AutoCloseable
{
	/**
	 * Reads the memory, in bytes, that the build can still use.
	 */
	@FunctionalInterface
	public interface MemorySource
	{
		/**
		 * Reads the free memory.
		 *
		 * @return the free memory, in bytes
		 * @throws IOException if the memory cannot be read
		 * @throws InterruptedException if the read is interrupted
		 */
		long read() throws IOException, InterruptedException;
	}

	/**
	 * A compiler wrapper that asked to start and has not exited.
	 */
	private static final class Wrapper
	{
		private final String crate;
		/**
		 * The reply FIFO, opened when the wrapper reads it.
		 */
		private final OutputStream reply;
		/**
		 * The compiler's process group, or zero before the compiler runs.
		 */
		private long processGroup;

		/**
		 * Creates a wrapper.
		 *
		 * @param crate the crate that the compiler builds
		 * @param reply the open reply FIFO
		 */
		Wrapper(String crate, OutputStream reply)
		{
			this.crate = crate;
			this.reply = reply;
		}
	}

	/**
	 * The status that a shell reports for a command that a kill signal ended.
	 */
	private static final int KILLED_STATUS = 128 + 9;
	/**
	 * The highest value of {@code /proc/<pid>/oom_score_adj}.
	 */
	private static final int MAXIMUM_OOM_SCORE_ADJUSTMENT = 1000;
	/**
	 * How long closing waits for the request reader to end.
	 */
	private static final Duration JOIN_TIMEOUT = Duration.ofSeconds(5);

	private final long reserve;
	private final CompilerScheduler scheduler;
	private final MemorySource memory;
	private final Duration interval;
	private final PrintStream log;
	private final Path directory;
	private final Path wrapperScript;
	private final Path requests;
	/**
	 * Keeps the request FIFO open for writing, so that reading it never reaches the end of the file while the
	 * service runs, and stops the reader when the service closes.
	 */
	private final OutputStream requestHolder;
	/**
	 * Guards the scheduler and the wrappers.
	 */
	private final Object lock = new Object();
	/**
	 * The wrappers that have not exited, by the wrapper's process identifier.
	 */
	private final Map<Long, Wrapper> wrappers = new HashMap<>();
	private final Thread requestReader;
	private final Thread memoryMonitor;
	/**
	 * The most recent free-memory reading, in bytes.
	 */
	private long freeMemory;
	/**
	 * How many compilers have started, which ranks later compilers as likelier victims of the kernel's OOM killer.
	 */
	private int starts;
	/**
	 * Indicates whether the service has stopped handling requests and readings.
	 */
	private boolean closed;

	/**
	 * Starts a scheduler service.
	 *
	 * @param reserve the free memory, in bytes, below which compilers are paused or killed
	 * @param memory reads the free memory
	 * @param interval the delay between free-memory readings
	 * @param log receives the scheduler's decisions about killed compilers
	 * @throws NullPointerException if {@code memory}, {@code interval}, or {@code log} are null
	 * @throws IllegalArgumentException if {@code reserve} is not positive or exceeds half of {@link Long#MAX_VALUE},
	 *                                  or {@code interval} is negative
	 * @throws IOException if the wrapper or its FIFO cannot be created
	 */
	public CompilerSchedulerService(long reserve, MemorySource memory, Duration interval, PrintStream log)
		throws IOException
	{
		this.scheduler = new CompilerScheduler(reserve);
		this.reserve = reserve;
		this.memory = Objects.requireNonNull(memory, "memory");
		this.interval = Objects.requireNonNull(interval, "interval");
		this.log = Objects.requireNonNull(log, "log");
		if (interval.isNegative())
			throw new IllegalArgumentException("interval must not be negative: " + interval);
		this.directory = Files.createTempDirectory("compiler-scheduler-",
			PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
		try
		{
			this.wrapperScript = directory.resolve("rustc-wrapper");
			Path source = directory.resolve("compiler-wrapper.sh");
			try (InputStream script = CompilerSchedulerService.class.getResourceAsStream("compiler-wrapper.sh"))
			{
				if (script == null)
					throw new IOException("The compiler wrapper resource is missing");
				Files.copy(script, source);
			}
			// A process that another JVM thread forks at the same time inherits any descriptor open for writing, and
			// exec fails with ETXTBSY until it closes, so an external process writes the executable copy.
			SystemCommands.run(List.of("install", "-m", "700", source.toString(), wrapperScript.toString()));
			this.requests = directory.resolve("requests");
			SystemCommands.run(List.of("mkfifo", "-m", "600", requests.toString()));
		}
		catch (IOException | RuntimeException failure)
		{
			deleteDirectory(directory);
			throw failure;
		}
		// Opening a FIFO blocks until it has a reader and a writer, so the reader thread opens it for reading first.
		this.requestReader = Thread.ofPlatform().name("compiler-scheduler-requests").daemon().
			start(this::readRequests);
		try
		{
			this.requestHolder = Files.newOutputStream(requests);
		}
		catch (IOException | RuntimeException failure)
		{
			requestReader.interrupt();
			deleteDirectory(directory);
			throw failure;
		}
		this.memoryMonitor = Thread.ofPlatform().name("compiler-scheduler-memory").daemon().
			start(this::monitorMemory);
	}

	/**
	 * Returns the variables that make a build's rustc invocations use this service.
	 *
	 * @return {@code RUSTC_WRAPPER} and the wrapper's connection to the service
	 */
	public Map<String, String> environment()
	{
		return Map.of("RUSTC_WRAPPER", wrapperScript.toString(),
			"CODEX_COMPILER_SCHEDULER_DIR", directory.toString());
	}

	/**
	 * Reads wrapper requests until the service closes.
	 */
	private void readRequests()
	{
		try (BufferedReader reader = Files.newBufferedReader(requests, StandardCharsets.UTF_8))
		{
			while (true)
			{
				String line = reader.readLine();
				if (line == null)
					return;
				handle(line.split(" "));
			}
		}
		catch (IOException failure)
		{
			synchronized (lock)
			{
				if (!closed)
					log.println("compiler scheduler: stopped reading requests: " + failure.getMessage());
			}
		}
	}

	/**
	 * Applies one wrapper request.
	 *
	 * @param fields the request's words
	 * @throws IOException if a reply or signal cannot be delivered
	 */
	private void handle(String[] fields) throws IOException
	{
		synchronized (lock)
		{
			if (!closed)
				apply(fields);
		}
	}

	/**
	 * Applies one wrapper request while the service runs.
	 *
	 * @param fields the request's words
	 * @throws IOException if a reply or signal cannot be delivered
	 */
	private void apply(String[] fields) throws IOException
	{
		try
		{
			long id = Long.parseLong(fields[1]);
			switch (fields[0])
			{
				case "request" ->
				{
					// The wrapper holds its reply FIFO open before sending the request, so this open does not block.
					wrappers.put(id, new Wrapper(fields[3], Files.newOutputStream(Path.of(fields[2]),
						StandardOpenOption.WRITE)));
					perform(scheduler.request(id));
				}
				case "started" ->
				{
					Wrapper wrapper = wrappers.get(id);
					if (wrapper == null)
						return;
					wrapper.processGroup = Long.parseLong(fields[2]);
					scheduler.started(id);
					// A reading below the reserve is ignored while a compiler starts, because the starting compiler
					// cannot yet be paused. Apply it now so that pressure does not wait for the next reading.
					if (freeMemory < reserve)
						perform(scheduler.observe(freeMemory));
				}
				case "exited" -> exited(id, Integer.parseInt(fields[2]) == KILLED_STATUS);
				default -> log.println("compiler scheduler: ignored request " + String.join(" ", fields));
			}
		}
		catch (ArrayIndexOutOfBoundsException | IllegalArgumentException failure)
		{
			log.println("compiler scheduler: ignored request " + String.join(" ", fields) + ": " +
				failure.getMessage());
		}
	}

	/**
	 * Records that a wrapper's compiler exited, or that the wrapper itself ended.
	 *
	 * @param id the wrapper's process identifier
	 * @param killed {@code true} if a kill signal ended the compiler
	 * @throws IOException if a reply or signal cannot be delivered
	 */
	private void exited(long id, boolean killed) throws IOException
	{
		Wrapper wrapper = wrappers.get(id);
		if (wrapper == null)
			return;
		wrapper.processGroup = 0;
		List<Action> actions = scheduler.exited(id, killed);
		boolean retried = actions.stream().anyMatch(action -> action instanceof Retry retry && retry.compiler() == id);
		perform(actions);
		if (!retried)
			forget(id);
	}

	/**
	 * Closes a wrapper's reply FIFO and stops tracking it.
	 *
	 * @param id the wrapper's process identifier
	 * @throws IOException if the reply FIFO cannot be closed
	 */
	private void forget(long id) throws IOException
	{
		Wrapper wrapper = wrappers.remove(id);
		if (wrapper != null)
			wrapper.reply.close();
	}

	/**
	 * Feeds free-memory readings to the scheduler until the service closes.
	 */
	private void monitorMemory()
	{
		try
		{
			while (true)
			{
				long reading = memory.read();
				observe(reading);
				if (!interval.isZero())
					Thread.sleep(interval);
			}
		}
		catch (InterruptedException _)
		{
			// The service is closing.
		}
		catch (IOException failure)
		{
			synchronized (lock)
			{
				if (!closed)
					log.println("compiler scheduler: stopped reading free memory: " + failure.getMessage());
			}
		}
	}

	/**
	 * Applies a free-memory reading after forgetting wrappers that ended without reporting it.
	 *
	 * @param reading the free memory, in bytes
	 * @throws IOException if a reply or signal cannot be delivered
	 */
	private void observe(long reading) throws IOException
	{
		synchronized (lock)
		{
			if (!closed)
				applyReading(reading);
		}
	}

	/**
	 * Applies a free-memory reading while the service runs.
	 *
	 * @param reading the free memory, in bytes
	 * @throws IOException if a reply or signal cannot be delivered
	 */
	private void applyReading(long reading) throws IOException
	{
		for (Long id : List.copyOf(wrappers.keySet()))
		{
			if (ProcessHandle.of(id).map(ProcessHandle::isAlive).orElse(false))
				continue;
			log.println("compiler scheduler: the wrapper for " + wrappers.get(id).crate + " ended without reporting it");
			perform(scheduler.exited(id, false));
			forget(id);
		}
		freeMemory = reading;
		perform(scheduler.observe(reading));
	}

	/**
	 * Performs the scheduler's actions.
	 *
	 * @param actions the actions, in order
	 * @throws IOException if a reply or signal cannot be delivered
	 */
	private void perform(List<Action> actions) throws IOException
	{
		for (Action action : actions)
		{
			switch (action)
			{
				case Start(long id) ->
				{
					++starts;
					reply(id, "start " + Math.min(starts, MAXIMUM_OOM_SCORE_ADJUSTMENT));
				}
				case Pause(long id) -> signal(id, "STOP");
				case Resume(long id) -> signal(id, "CONT");
				case Kill(long id) -> signal(id, "KILL");
				case Retry(long id) ->
				{
					log.println("compiler scheduler: " + wrappers.get(id).crate + " was killed to free memory; it " +
						"will rerun alone");
					reply(id, "retry");
				}
				case Fail(long id) ->
				{
					Wrapper wrapper = wrappers.get(id);
					log.println("compiler scheduler: " + wrapper.crate + " was killed while it compiled alone; it " +
						"needs more memory than the build has");
					reply(id, "fail");
				}
			}
		}
	}

	/**
	 * Sends a reply to a wrapper.
	 *
	 * @param id the wrapper's process identifier
	 * @param message the reply
	 * @throws IOException if the reply cannot be written
	 */
	private void reply(long id, String message) throws IOException
	{
		Wrapper wrapper = wrappers.get(id);
		wrapper.reply.write((message + "\n").getBytes(StandardCharsets.UTF_8));
		wrapper.reply.flush();
	}

	/**
	 * Sends a signal to a compiler's process group.
	 *
	 * @param id the wrapper's process identifier
	 * @param signal the signal's name
	 * @throws IOException if the signal cannot be sent
	 */
	private void signal(long id, String signal) throws IOException
	{
		long processGroup = wrappers.get(id).processGroup;
		if (processGroup == 0)
			throw new IllegalStateException("The compiler for " + wrappers.get(id).crate + " has no process group");
		// A process group that already exited needs no signal, so the status is ignored.
		SystemCommands.execute(List.of("kill", "-s", signal, "--", "-" + processGroup), directory,
			System.getenv());
	}

	/**
	 * Stops the service, continues every paused compiler, and tells waiting wrappers that the service stopped.
	 *
	 * @throws IOException if the service's files cannot be removed
	 */
	@Override
	public void close() throws IOException
	{
		synchronized (lock)
		{
			if (closed)
				return;
			closed = true;
			for (Map.Entry<Long, Wrapper> entry : wrappers.entrySet())
			{
				if (entry.getValue().processGroup != 0)
					signal(entry.getKey(), "CONT");
				entry.getValue().reply.close();
			}
			wrappers.clear();
		}
		memoryMonitor.interrupt();
		// Once no wrapper holds the request FIFO open either, the reader reaches the end of the file.
		requestHolder.close();
		try
		{
			// A wrapper that outlived its build may still hold the request FIFO open; the daemon reader then ends
			// with the process.
			requestReader.join(JOIN_TIMEOUT);
			memoryMonitor.join();
		}
		catch (InterruptedException _)
		{
			Thread.currentThread().interrupt();
		}
		deleteDirectory(directory);
	}

	/**
	 * Removes a directory tree.
	 *
	 * @param root the directory
	 * @throws IOException if removal fails
	 */
	private static void deleteDirectory(Path root) throws IOException
	{
		try (Stream<Path> paths = Files.walk(root))
		{
			for (Path path : paths.sorted(Comparator.reverseOrder()).toList())
				Files.deleteIfExists(path);
		}
		catch (NoSuchFileException _)
		{
			// Already removed.
		}
		catch (UncheckedIOException failure)
		{
			throw failure.getCause();
		}
	}
}
