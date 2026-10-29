package com.holdmyspot.codexunleashed.scheduler;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Owns a dedicated Linux action supervisor and an independent cgroup containing all its helpers.
 * The controller remains outside that cgroup and needs cgroup-management authority. Instances are single-thread
 * confined. Workload status is accepted only after the supervisor has collected the entire tree and exited.
 */
public final class LinuxSupervisor implements AutoCloseable
{
	/** Published stages, each written once by its designated producer in the private attempt directory. */
	private enum Phase
	{
		LAUNCH, READY, RELEASE, RELEASED, TERMINATE, RESULT
	}

	/** XML property identifying the exclusive attempt across every stage. */
	private static final String ATTEMPT = "attempt";
	/** XML property identifying the published stage. */
	private static final String PHASE = "phase";
	/** XML property identifying the gate and exec-replaced workload root. */
	private static final String ROOT_PID = "root.pid";
	/** XML property containing the final root exit status. */
	private static final String ROOT_EXIT = "root.exit";
	/** XML property distinguishing forced termination from natural completion. */
	private static final String TERMINATION = "termination";
	/** XML property prefix for adopted child PID and collected status pairs. */
	private static final String ADOPTED_PREFIX = "adopted.";
	/** Time between nonblocking worker observations and controller stage reads. */
	private static final Duration POLL_INTERVAL = Duration.ofMillis(10);

	/** Immutable workload context; its cgroup parent is used only to acquire the independent outer group. */
	private final LinuxProcessTree.Launch launch;
	/** Startup, control acknowledgement and cleanup wait limit; this is not a build runtime limit. */
	private final long timeoutNanos;
	/** Identity generated before resource acquisition and checked in every published stage. */
	private final String attempt = UUID.randomUUID().toString();
	/** Controller-owned outer subtree; null until acquired. */
	private LinuxCgroup outer;
	/** Indicates that the outer owner completed cleanup, even if later storage removal needs a retry. */
	private boolean outerClosed;
	/** Controller-owned attempt state and supervisor log; null until acquired. */
	private Path scratch;
	/** Retained direct child, including before cgroup placement succeeds. */
	private Process supervisor;
	/** Workload root identity received from the prepared supervisor. */
	private long rootPid;
	/** Indicates that the one-time release request has been published. */
	private boolean releaseRequested;
	/** Accepted result, retained after successful close; null while completion is unproven. */
	private Result completion;
	/** Indicates that all owned resources have been released. */
	private boolean closed;

	/**
	 * Failed preparation whose unfinished cleanup remains owned by this exception.
	 * The original preparation failure is the cause; cleanup failures are suppressed on that cause.
	 * Call close again after restoring the failed cleanup prerequisite. It never releases the failed workload.
	 */
	public static final class PreparationFailure extends IOException implements AutoCloseable
	{
		/** Owner retained until cleanup succeeds. */
		private final LinuxSupervisor owner;

		/**
		 * Retains failed acquisition and its recovery owner.
		 * @param cause the original preparation failure, including its suppressed cleanup failures
		 * @param owner the partially acquired resource owner
		 */
		private PreparationFailure(Throwable cause, LinuxSupervisor owner)
		{
			super("Supervisor preparation failed and cleanup requires retry", cause);
			this.owner = owner;
		}

		/**
		 * Retries only cleanup of the failed acquisition; repeated successful close has no effect.
		 * @throws IOException if owned resources still cannot be released
		 */
		@Override
		public void close() throws IOException
		{
			owner.close();
		}
	}

	/**
	 * Collected workload outcomes; this result is never invented after supervisor death.
	 * @param rootExit the actual root process exit status
	 * @param adoptedExits adopted child PIDs mapped to ordinary exit codes or negative terminating signals
	 * @param terminationRequested indicates whether the process owner requested termination
	 */
	public record Result(int rootExit, Map<Long, Integer> adoptedExits, boolean terminationRequested)
	{
		/**
		 * Copies outcomes and validates the ordinary root status and positive child identities.
		 * @param rootExit the actual root process exit status
		 * @param adoptedExits adopted child PIDs mapped to their collected statuses
		 * @param terminationRequested indicates whether the process owner requested termination
		 * @throws IllegalArgumentException if the root status is outside 0 through 255 or a child PID is nonpositive
		 * @throws NullPointerException if the map, a PID or a status is null
		 */
		public Result
		{
			if (rootExit < 0 || rootExit > 255)
				throw new IllegalArgumentException("Linux root exit status must be between 0 and 255: " + rootExit);
			adoptedExits = Map.copyOf(adoptedExits);
			if (adoptedExits.keySet().stream().anyMatch(pid -> pid <= 0))
				throw new IllegalArgumentException("Adopted child PIDs must be positive");
		}
	}

	/**
	 * Validates immutable inputs before acquiring a cgroup, storage or process.
	 * @param launch the explicit workload and trusted Java context
	 * @param timeout the positive startup/control/cleanup timeout representable as signed nanoseconds
	 */
	private LinuxSupervisor(LinuxProcessTree.Launch launch, Duration timeout)
	{
		this.launch = Objects.requireNonNull(launch, "launch may not be null");
		Objects.requireNonNull(timeout, "timeout may not be null");
		if (timeout.isNegative() || timeout.isZero())
			throw new IllegalArgumentException("Supervisor timeout must be positive: " + timeout);
		try
		{
			timeoutNanos = timeout.toNanos();
		}
		catch (ArithmeticException failure)
		{
			throw new IllegalArgumentException("Supervisor timeout exceeds signed nanoseconds: " + timeout, failure);
		}
	}

	/**
	 * Prepares one gated workload in a dedicated supervisor contained before it may create helpers.
	 * Failed acquisition terminates and reaps owned work; cleanup failures are suppressed on the original failure.
	 * The launch classpath must contain this class and LinuxProcessTree, from the same class directory or tooling JAR.
	 * @param launch the explicit workload and trusted Java context, with elevated cgroup-management authority
	 * @param timeout the positive startup/control/cleanup wait limit, not a build runtime limit
	 * @return a prepared owner whose workload is still gated
	 * @throws NullPointerException if an input is null
	 * @throws IllegalArgumentException if timeout is nonpositive or cannot be represented as signed nanoseconds
	 * @throws UnsupportedOperationException if the cgroup parent is not on cgroup v2
	 * @throws IOException if acquisition, containment or preparation fails
	 * @throws PreparationFailure if preparation and its cleanup both fail; close that exception to retry cleanup
	 */
	public static LinuxSupervisor prepare(LinuxProcessTree.Launch launch, Duration timeout) throws IOException
	{
		LinuxSupervisor owner = new LinuxSupervisor(launch, timeout);
		try
		{
			owner.outer = LinuxCgroup.create(launch.cgroupParent(), timeout);
			owner.scratch = Files.createTempDirectory(launch.scratchParent().toRealPath(), "java-supervisor-");
			owner.writeLaunch();
			ProcessBuilder builder = new ProcessBuilder(launch.javaExecutable().toString(),
				"--enable-native-access=ALL-UNNAMED", "-cp", launch.classPath().toString(),
				LinuxSupervisor.class.getName(), owner.scratch.toString());
			builder.environment().clear();
			builder.directory(launch.directory().toFile());
			builder.redirectErrorStream(true);
			builder.redirectOutput(owner.scratch.resolve("supervisor.log").toFile());
			owner.supervisor = builder.start();
			Path members = owner.outer.directory().resolve("cgroup.procs");
			String pid = Long.toString(owner.supervisor.pid());
			Files.writeString(members, pid, StandardOpenOption.WRITE);
			if (Files.readAllLines(members).contains(pid) == false)
				throw new IOException("Supervisor is absent from its controller-owned cgroup: " + pid);
			owner.supervisor.getOutputStream().write(1);
			owner.supervisor.getOutputStream().flush();
			Properties ready = owner.awaitPhase(Phase.READY);
			try
			{
				owner.rootPid = Long.parseLong(required(ready, ROOT_PID));
				Path actionGroup = Path.of(required(ready, "action.group"));
				Path gateScratch = Path.of(required(ready, "gate.scratch"));
				boolean ownedPreparation = owner.rootPid > 0 &&
					owner.outer.directory().equals(actionGroup.getParent()) && owner.scratch.equals(gateScratch.getParent());
				if (ownedPreparation == false)
					throw new IOException("Supervisor readiness does not identify its owned preparation");
			}
			catch (IllegalArgumentException failure)
			{
				throw new IOException("Supervisor readiness contains invalid process identities or paths", failure);
			}
			return owner;
		}
		catch (IOException | RuntimeException | Error failure)
		{
			try
			{
				owner.close();
			}
			catch (IOException | RuntimeException cleanup)
			{
				failure.addSuppressed(cleanup);
				throw new PreparationFailure(failure, owner);
			}
			throw failure;
		}
	}

	/**
	 * Requests one release and waits for the supervisor's acknowledgement.
	 * A failed acknowledgement leaves a published request owned by this instance; close remains available.
	 * @throws IllegalStateException if closed or release was already requested
	 * @throws IOException if publication, acknowledgement or supervisor execution fails
	 */
	public void release() throws IOException
	{
		requireOpen();
		if (releaseRequested)
			throw new IllegalStateException("Workload release was already requested");
		publish(scratch, Phase.RELEASE, attempt, new Properties());
		releaseRequested = true;
		awaitPhase(Phase.RELEASED);
	}

	/**
	 * Observes completion without imposing a build runtime limit or sending a termination request.
	 * @return true only after validated outcomes and an exit-zero, reaped supervisor; accepted completion is retained
	 * @throws IllegalStateException if closed without previously accepted completion
	 * @throws IOException if supervisor execution or its result fails
	 */
	public boolean observeCompletion() throws IOException
	{
		if (completion != null)
			return true;
		requireOpen();
		try
		{
			if (supervisor.waitFor(0, TimeUnit.NANOSECONDS) == false)
				return false;
		}
		catch (InterruptedException failure)
		{
			Thread.currentThread().interrupt();
			throw new IOException("Interrupted while observing supervisor completion", failure);
		}
		if (supervisor.exitValue() != 0)
			throw supervisorFailure();
		Properties result = read(scratch, Phase.RESULT, attempt);
		Map<Long, Integer> adopted = new HashMap<>();
		try
		{
			if (Long.parseLong(required(result, ROOT_PID)) != rootPid)
				throw new IOException("Supervisor result identifies a different workload root");
			for (String key : result.stringPropertyNames())
				if (key.startsWith(ADOPTED_PREFIX))
					adopted.put(Long.parseLong(key.substring(ADOPTED_PREFIX.length())),
						Integer.parseInt(result.getProperty(key)));
			boolean terminated = switch (required(result, TERMINATION))
			{
				case "true" -> true;
				case "false" -> false;
				default -> throw new IOException("Supervisor result has an invalid termination outcome");
			};
			completion = new Result(Integer.parseInt(required(result, ROOT_EXIT)), adopted, terminated);
		}
		catch (IllegalArgumentException failure)
		{
			throw new IOException("Supervisor result has invalid process outcomes", failure);
		}
		return true;
	}

	/**
	 * Returns accepted outcomes, including after successful close.
	 * @return the retained root, adopted-child and termination outcomes
	 * @throws IllegalStateException if completion has not been accepted
	 */
	public Result result()
	{
		if (completion == null)
			throw new IllegalStateException("Whole-tree completion has not been accepted");
		return completion;
	}

	/**
	 * Requests whole-tree termination and waits for collected outcomes within the control timeout.
	 * Natural completion racing with the request remains a natural result. A timeout leaves cleanup with this owner.
	 * @throws IllegalStateException if closed without previously accepted completion
	 * @throws IOException if control, supervision or status collection fails or exceeds the timeout
	 */
	public void terminate() throws IOException
	{
		if (observeCompletion())
			return;
		if (Files.exists(file(scratch, Phase.TERMINATE)) == false)
			publish(scratch, Phase.TERMINATE, attempt, new Properties());
		long started = System.nanoTime();
		while (observeCompletion() == false)
		{
			if ((System.nanoTime() - started) >= timeoutNanos)
				throw new IOException("Supervisor termination exceeded its control timeout: " + scratch);
			pause();
		}
	}

	/** {@return the retained supervisor PID} */
	public long pid() { return supervisor.pid(); }
	/** {@return the prepared gate and exec-replaced workload root PID} */
	public long rootPid() { return rootPid; }
	/** {@return the controller-owned outer cgroup's original absolute path} */
	public Path directory() { return outer.directory(); }
	/** {@return the controller-owned attempt directory's original absolute path} */
	public Path scratch() { return scratch; }

	/**
	 * Terminates remaining owned work, reaps the direct child, removes owned groups and then removes owned storage.
	 * Repeated close after success has no effect. Failed cleanup retains ownership for retry; caller interruption is
	 * preserved. Closing unfinished work supplies no invented workload result.
	 * @throws IOException if termination, reaping or removal fails
	 */
	@Override
	public void close() throws IOException
	{
		if (closed)
			return;
		boolean interrupted = Thread.interrupted();
		try
		{
			IOException failure = null;
			if (supervisor != null)
			{
				try { supervisor.getOutputStream().close(); }
				catch (IOException cleanup) { failure = cleanup; }
			}
			if (outer != null && outerClosed == false)
			{
				try { outer.terminate(); }
				catch (IOException cleanup) { failure = combine(failure, cleanup); }
			}
			if (supervisor != null)
			{
				supervisor.destroyForcibly();
				long started = System.nanoTime();
				boolean reaped = false;
				while (reaped == false)
				{
					long remaining = timeoutNanos - (System.nanoTime() - started);
					if (remaining <= 0)
					{
						failure = combine(failure, new IOException("Supervisor remains alive during close: " + pid()));
						break;
					}
					try { reaped = supervisor.waitFor(remaining, TimeUnit.NANOSECONDS); }
					catch (InterruptedException cleanup) { interrupted = true; }
				}
			}
			if (outer != null && outerClosed == false)
			{
				try
				{
					outer.close();
					outerClosed = true;
				}
				catch (IOException cleanup) { failure = combine(failure, cleanup); }
			}
			if (failure != null)
				throw failure;
			if (scratch != null)
			{
				try (var paths = Files.walk(scratch))
				{
					for (Path path : paths.sorted(Comparator.reverseOrder()).toList())
						Files.delete(path);
				}
			}
			closed = true;
		}
		finally
		{
			if (interrupted)
				Thread.currentThread().interrupt();
		}
	}

	/** Publishes immutable workload inputs before starting the supervisor. */
	private void writeLaunch() throws IOException
	{
		Properties values = new Properties();
		values.setProperty("cgroup.parent", outer.directory().toString());
		values.setProperty("java", launch.javaExecutable().toString());
		values.setProperty("class.path", launch.classPath().toString());
		values.setProperty("directory", launch.directory().toString());
		values.setProperty("uid", Integer.toString(launch.uid()));
		values.setProperty("gid", Integer.toString(launch.gid()));
		values.setProperty("command.count", Integer.toString(launch.command().size()));
		for (int index = 0; index < launch.command().size(); index++)
			values.setProperty("command." + index, launch.command().get(index));
		for (var entry : launch.environment().entrySet())
			values.setProperty("environment." + entry.getKey(), entry.getValue());
		publish(scratch, Phase.LAUNCH, attempt, values);
	}

	/** Waits for an identity-matching stage while preserving supervisor failure details. */
	private Properties awaitPhase(Phase phase) throws IOException
	{
		long started = System.nanoTime();
		while (Files.exists(file(scratch, phase)) == false)
		{
			if (supervisor.isAlive() == false)
				throw supervisorFailure();
			if ((System.nanoTime() - started) >= timeoutNanos)
				throw new IOException("Supervisor did not publish " + phase + " within its control timeout: " + scratch);
			pause();
		}
		return read(scratch, phase, attempt);
	}

	/** Reports a dead supervisor without treating its exit code as a workload result. */
	private IOException supervisorFailure()
	{
		IOException failure = new IOException("Dedicated supervisor exited " + supervisor.exitValue() +
			" before the required handoff: " + scratch);
		try (InputStream input = Files.newInputStream(scratch.resolve("supervisor.log")))
		{
			String log = new String(input.readNBytes(8192), StandardCharsets.UTF_8);
			if (log.isEmpty() == false)
				failure.addSuppressed(new IOException("Supervisor diagnostic: " + log));
		}
		catch (IOException diagnostic) { failure.addSuppressed(diagnostic); }
		return failure;
	}

	/** Rejects controls after resources have been released. */
	private void requireOpen()
	{
		if (closed)
			throw new IllegalStateException("Linux supervisor owner is closed");
	}

	/** Retains the first cleanup failure and attaches later failures. */
	private static IOException combine(IOException first, IOException next)
	{
		if (first == null)
			return next;
		first.addSuppressed(next);
		return first;
	}

	/** Returns a stage location in the private attempt directory. */
	private static Path file(Path scratch, Phase phase)
	{
		return scratch.resolve(phase.name().toLowerCase(java.util.Locale.ROOT) + ".xml");
	}

	/** Publishes one complete XML properties document from an exclusively created sibling staging file. */
	private static void publish(Path scratch, Phase phase, String attempt, Properties values) throws IOException
	{
		values.setProperty(ATTEMPT, attempt);
		values.setProperty(PHASE, phase.name());
		Path destination = file(scratch, phase);
		Path pending = destination.resolveSibling(destination.getFileName() + ".pending");
		try (OutputStream output = Files.newOutputStream(pending, StandardOpenOption.CREATE_NEW))
		{
			values.storeToXML(output, "Dedicated supervisor handoff", StandardCharsets.UTF_8);
		}
		Files.move(pending, destination, StandardCopyOption.ATOMIC_MOVE);
	}

	/** Reads the selected JDK format and checks stage and attempt identity before consuming values. */
	private static Properties read(Path scratch, Phase phase, String attempt) throws IOException
	{
		Properties values = new Properties();
		try (InputStream input = Files.newInputStream(file(scratch, phase)))
		{
			values.loadFromXML(input);
		}
		if (phase.name().equals(required(values, PHASE)) == false)
			throw new IOException("Supervisor handoff has the wrong phase: " + phase);
		String actual = required(values, ATTEMPT);
		if (actual.isEmpty() || (attempt != null && attempt.equals(actual) == false))
			throw new IOException("Supervisor handoff has the wrong attempt identity");
		return values;
	}

	/** Rejects absent fields while preserving an explicitly empty string. */
	private static String required(Properties values, String key) throws IOException
	{
		String value = values.getProperty(key);
		if (value == null)
			throw new IOException("Supervisor handoff is missing required field: " + key);
		return value;
	}

	/** Waits between observations without losing interruption. */
	private static void pause() throws IOException
	{
		try { Thread.sleep(POLL_INTERVAL); }
		catch (InterruptedException failure)
		{
			Thread.currentThread().interrupt();
			throw new IOException("Interrupted during supervisor handoff", failure);
		}
	}

	/** Reconstructs the already validated launch representation in the dedicated process. */
	private static LinuxProcessTree.Launch readLaunch(Path scratch, Properties values) throws IOException
	{
		try
		{
			int count = Integer.parseInt(required(values, "command.count"));
			if (count <= 0)
				throw new IOException("Supervisor launch requires at least one command argument");
			List<String> command = new ArrayList<>();
			for (int index = 0; index < count; index++)
				command.add(required(values, "command." + index));
			Map<String, String> environment = new HashMap<>();
			for (String key : values.stringPropertyNames())
				if (key.startsWith("environment."))
					environment.put(key.substring("environment.".length()), values.getProperty(key));
			return new LinuxProcessTree.Launch(Path.of(required(values, "cgroup.parent")), scratch,
				Path.of(required(values, "java")), Path.of(required(values, "class.path")), command,
				environment, Path.of(required(values, "directory")), Integer.parseInt(required(values, "uid")),
				Integer.parseInt(required(values, "gid")));
		}
		catch (IllegalArgumentException failure)
		{
			throw new IOException("Supervisor launch contains invalid workload inputs", failure);
		}
	}

	/**
	 * Runs only in the dedicated privileged child JVM started by prepare.
	 * It waits for controller-confirmed placement before creating a helper and observes controller pipe loss throughout.
	 * @param args exactly the absolute private attempt directory
	 * @throws Exception if configuration, placement permission, process ownership or publication fails
	 */
	public static void main(String[] args) throws Exception
	{
		if (args.length != 1)
			throw new IllegalArgumentException("Dedicated supervisor requires its private attempt directory");
		Path scratch = Path.of(args[0]);
		if (scratch.isAbsolute() == false)
			throw new IllegalArgumentException("Supervisor attempt directory must be absolute");
		Properties configuration = read(scratch, Phase.LAUNCH, null);
		String attempt = required(configuration, ATTEMPT);
		if (System.in.read() != 1)
			throw new IOException("Controller did not grant contained supervisor startup");
		AtomicReference<IOException> controllerFailure = new AtomicReference<>();
		Thread.ofPlatform().daemon(true).name("supervisor-controller-input").start(() ->
		{
			try
			{
				int value = System.in.read();
				controllerFailure.set(new IOException("Controller input ended or changed: " + value));
			}
			catch (IOException failure) { controllerFailure.set(failure); }
		});
		Result result;
		long rootPid;
		try (LinuxProcessTree tree = LinuxProcessTree.prepare(readLaunch(scratch, configuration)))
		{
			rootPid = tree.rootPid();
			Properties ready = new Properties();
			ready.setProperty(ROOT_PID, Long.toString(rootPid));
			ready.setProperty("action.group", tree.group().toString());
			ready.setProperty("gate.scratch", tree.scratch().toString());
			publish(scratch, Phase.READY, attempt, ready);
			boolean released = false;
			boolean terminationRequested = false;
			while (true)
			{
				IOException lost = controllerFailure.get();
				if (lost != null)
					throw new IOException("Controller connection was lost; terminating the owned workload", lost);
				if (terminationRequested == false && Files.exists(file(scratch, Phase.TERMINATE)))
				{
					read(scratch, Phase.TERMINATE, attempt);
					tree.terminate();
					terminationRequested = true;
				}
				else if (released == false && terminationRequested == false && Files.exists(file(scratch, Phase.RELEASE)))
				{
					read(scratch, Phase.RELEASE, attempt);
					tree.release();
					released = true;
					publish(scratch, Phase.RELEASED, attempt, new Properties());
				}
				if (tree.observeCompletion())
				{
					if (released == false && tree.terminationRequested() == false)
						throw new IOException("Trusted gate ended before workload release");
					result = new Result(tree.awaitRootExit(), tree.adoptedExits(), tree.terminationRequested());
					break;
				}
				pause();
			}
		}
		Properties values = new Properties();
		values.setProperty(ROOT_PID, Long.toString(rootPid));
		values.setProperty(ROOT_EXIT, Integer.toString(result.rootExit()));
		values.setProperty(TERMINATION, Boolean.toString(result.terminationRequested()));
		for (var entry : result.adoptedExits().entrySet())
			values.setProperty(ADOPTED_PREFIX + entry.getKey(), entry.getValue().toString());
		publish(scratch, Phase.RESULT, attempt, values);
	}
}
