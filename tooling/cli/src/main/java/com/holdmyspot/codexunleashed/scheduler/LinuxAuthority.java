package com.holdmyspot.codexunleashed.scheduler;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * Owns a retained Linux memory reader in a private mount namespace.
 * The caller needs cgroup-v2 delegation and CAP_SYS_ADMIN in the mount's owning user namespace. Workload supervisors
 * and authenticated sandbox containment are borrowed: register each gated attempt before releasing it, retain its group through sampling, and unregister
 * only after its workload has stopped. Calls and workload-group topology changes must be single-thread confined.
 * Untrusted mount or syscall filtering, concurrent renaming, and migration outside registered trees are unsupported.
 */
public final class LinuxAuthority implements LinuxMeasurements.Source, AutoCloseable
{
	/**
	 * Exclusive IPC directory prefix owned and removed by one authority.
	 */
	private static final String DIRECTORY_PREFIX = "linux-authority-";
	/**
	 * Helper readiness receipt, published only after final mount selection and library initialization.
	 */
	static final String READY_FILE = "ready.properties";
	/**
	 * Maximum delay between bounded protocol observations.
	 */
	static final Duration POLL_INTERVAL = Duration.ofMillis(10);
	/**
	 * Reader rejection reason for verified missing immediate workload leaves.
	 */
	public static final String MISSING_LEAF = "registered-leaf-removed";
	/** Reader-verified process membership churn invalidating a registered workload's residency sample. */
	static final String CHANGED_MEMBERS = "registered-membership-changed";

	/**
	 * Reads the owning supervisor's genuine retained execution-impossible barrier.
	 */
	@FunctionalInterface
	public interface ZeroBarrier
	{
		/**
		 * Observes actual zero or its retained earlier observation.
		 * @return true only after genuine zero was observed
		 * @throws IOException if that barrier cannot be established
		 */
		boolean observed() throws IOException;
	}

	/**
	 * Immutable launch and filesystem boundaries for one native reader.
	 * @param java executable JDK 27 launcher
	 * @param unshare util-linux executable supporting a private nonpropagating mount namespace
	 * @param library libcgroup-v3 shared object
	 * @param scratch existing directory for exclusive transient helper IPC
	 * @param cgroupRoot existing cgroup-v2 mountpoint to replace privately with a nonrecursive clone
	 * @param procRoot proc filesystem matching the reader's unchanged PID and cgroup namespaces
	 * @param admissionParent group under which this scheduler admits workloads
	 * @param helperParent delegated group in which the authority may create its own helper group
	 * @param timeout positive bounded wait for each request and each cleanup attempt
	 */
	public record Settings(Path java, Path unshare, Path library, Path scratch, Path cgroupRoot, Path procRoot,
		Path admissionParent, Path helperParent, Duration timeout)
	{
		/**
		 * Validates absolute normalized paths and a positive timeout before starting a process.
		 * @param java JDK executable
		 * @param unshare util-linux executable
		 * @param library native library
		 * @param scratch owned IPC parent
		 * @param cgroupRoot cgroup mountpoint
		 * @param procRoot matching proc filesystem
		 * @param admissionParent workload admission group
		 * @param helperParent helper delegation parent
		 * @param timeout positive timeout representable as signed nanoseconds
		 * @throws NullPointerException if a setting is null
		 * @throws IllegalArgumentException if paths are relative or unnormalized, groups are outside cgroupRoot,
		 * or timeout is nonpositive or exceeds signed nanoseconds
		 */
		public Settings
		{
			for (Path path : new Path[] {java, unshare, library, scratch, cgroupRoot, procRoot, admissionParent, helperParent})
			{
				Objects.requireNonNull(path, "settings path");
				if (!path.isAbsolute() || !path.normalize().equals(path))
					throw new IllegalArgumentException("Authority paths must be absolute and normalized: " + path);
			}
			Objects.requireNonNull(timeout, "timeout");
			if (timeout.isNegative() || timeout.isZero())
				throw new IllegalArgumentException("Authority timeout must be positive");
			try
			{
				timeout.toNanos();
			}
			catch (ArithmeticException failure)
			{
				throw new IllegalArgumentException("Authority timeout exceeds signed nanoseconds", failure);
			}
			if (!admissionParent.startsWith(cgroupRoot) || !helperParent.startsWith(cgroupRoot))
				throw new IllegalArgumentException("Admission and helper parents must be below the cgroup mountpoint");
		}
	}

	/**
	 * Retains cleanup ownership when acquisition failed and cleanup has not yet succeeded.
	 * @see #owner()
	 */
	public static final class AcquisitionFailure extends IOException
	{
		/**
		 * Partially acquired authority whose close must be retried.
		 */
		private final LinuxAuthority owner;

		/**
		 * Preserves the original acquisition and cleanup diagnostics with their live owner.
		 * @param cause acquisition failure with suppressed cleanup failure
		 * @param owner partially acquired resources
		 */
		private AcquisitionFailure(Throwable cause, LinuxAuthority owner)
		{
			super("Authority acquisition failed and cleanup remains pending", cause);
			this.owner = owner;
		}

		/**
		 * Returns the retained cleanup owner; do not use it for sampling.
		 * @return authority requiring another close attempt
		 */
		public LinuxAuthority owner()
		{
			return owner;
		}
	}

	/**
	 * Borrowed launch settings, retained through helper cleanup.
	 */
	private final Settings settings;
	/**
	 * Exact live attempts acknowledged by the reader.
	 */
	private final Map<LinuxMeasurements.Attempt, Registration> registrations = new HashMap<>();
	/**
	 * Exclusive transient IPC directory, or null before allocation.
	 */
	private Path directory;
	/**
	 * Owned helper cancellation group, or null before allocation.
	 */
	private LinuxCgroup helperGroup;
	/**
	 * Direct retained Java reader process, or null before launch.
	 */
	private Process helper;
	/**
	 * Monotonic request identity; responses cannot satisfy a later request.
	 */
	private long requestSequence;
	/**
	 * Registry revision accepted by the last completed mutation.
	 */
	private long generation;
	/**
	 * Indicates whether an incomplete protocol operation prevents further sampling.
	 */
	private boolean broken;
	/**
	 * Indicates whether all owned resources were successfully released.
	 */
	private boolean closed;

	/**
	 * Retains validated settings without allocating native resources.
	 * @param settings immutable native launch boundaries
	 */
	private LinuxAuthority(Settings settings)
	{
		this.settings = Objects.requireNonNull(settings, "settings");
	}

	/**
	 * Creates and contains a privileged helper before releasing its setup gate.
	 * Readiness requires a clean private mount, actual hierarchy-root evidence, and matching libcgroup selection.
	 * @param settings immutable native launch boundaries
	 * @return retained ready authority
	 * @throws IOException if acquisition fails; AcquisitionFailure retains ownership when cleanup also fails
	 */
	public static LinuxAuthority open(Settings settings) throws IOException
	{
		LinuxAuthority owner = new LinuxAuthority(settings);
		try
		{
			owner.directory = Files.createTempDirectory(settings.scratch(), DIRECTORY_PREFIX);
			owner.helperGroup = LinuxCgroup.create(settings.helperParent(), settings.timeout());
			ArrayList<String> command = new ArrayList<>();
			command.addAll(java.util.List.of(settings.unshare().toString(), "--mount", "--propagation", "private", "--",
				settings.java().toString(), "-Djava.io.tmpdir=" + owner.directory));
			Module module = LinuxAuthority.class.getModule();
			if (module.isNamed())
			{
				command.add("--enable-native-access=" + module.getName());
				String modulePath = System.getProperty("jdk.module.path");
				if (modulePath != null && !modulePath.isBlank())
					command.addAll(java.util.List.of("--module-path", modulePath));
				command.addAll(java.util.List.of("--module", module.getName() + "/" + LinuxAuthorityReader.class.getName()));
			}
			else
			{
				// Class-path code has no narrower native-access grant than ALL-UNNAMED.
				command.addAll(java.util.List.of("--enable-native-access=ALL-UNNAMED", "-cp",
					System.getProperty("java.class.path"), LinuxAuthorityReader.class.getName()));
			}
			command.addAll(java.util.List.of(owner.directory.toString(), settings.cgroupRoot().toString(),
				settings.procRoot().toString(), settings.admissionParent().toString(), settings.library().toString(),
				owner.helperGroup.directory().toString(), Long.toString(ProcessHandle.current().pid())));
			ProcessBuilder builder = new ProcessBuilder(command).redirectOutput(owner.directory.resolve("reader.stdout").toFile())
				.redirectError(owner.directory.resolve("reader.stderr").toFile());
			builder.environment().clear();
			owner.helper = builder.start();
			Files.writeString(owner.helperGroup.directory().resolve("cgroup.procs"), owner.helper.pid() + "\n");
			boolean contained = Files.readAllLines(owner.helperGroup.directory().resolve("cgroup.procs"))
				.contains(Long.toString(owner.helper.pid()));
			if (!contained)
				throw new IOException("Reader was not contained before releasing its setup gate");
			owner.helper.getOutputStream().write(1);
			owner.helper.getOutputStream().flush();
			owner.requireSuccess(owner.await(owner.directory.resolve(READY_FILE)));
			return owner;
		}
		catch (IOException | RuntimeException | Error failure)
		{
			owner.broken = true;
			try
			{
				owner.close();
			}
			catch (IOException cleanup)
			{
				failure.addSuppressed(cleanup);
				throw new AcquisitionFailure(failure, owner);
			}
			throw failure;
		}
	}

	/**
	 * Retains an exact gated supervisor attempt in the native reader until registration close succeeds.
	 * @param name nonblank stable action name
	 * @param sequence nonnegative attempt ordinal
	 * @param supervisor borrowed live owner, whose root and owned group must still exist
	 * @return registration to close after workload termination and before the next measurement batch
	 * @throws IOException if authority or actual ancestry cannot be verified
	 */
	public Registration register(String name, long sequence, LinuxSupervisor supervisor) throws IOException
	{
		LinuxMeasurements.Attempt attempt = new LinuxMeasurements.Attempt(name, sequence);
		Objects.requireNonNull(supervisor, "supervisor");
		return register(attempt, supervisor.directory(), supervisor.rootPid(), supervisor::observeExecutionImpossible);
	}

	/**
	 * Retains an authenticated sandbox child's host cgroup in the native reader without acquiring child wait authority.
	 * The caller retains containment until workload termination, then closes registration before another batch.
	 * @param name nonblank stable action name
	 * @param sequence nonnegative attempt ordinal
	 * @param containment authenticated live child containment, borrowed until registration removal succeeds
	 * @return registration retaining the actual containment and its genuine populated-zero barrier
	 * @throws NullPointerException if an input is null
	 * @throws IllegalArgumentException if attempt identity is invalid or already registered
	 * @throws IOException if child identity, containment, authority or actual ancestry cannot be verified
	 */
	Registration register(String name, long sequence, LinuxBazelContainment containment) throws IOException
	{
		LinuxMeasurements.Attempt attempt = new LinuxMeasurements.Attempt(name, sequence);
		Objects.requireNonNull(containment, "containment");
		containment.requireContained();
		return registerSandbox(attempt, containment);
	}

	/**
	 * Publishes one concrete retained workload owner through the existing native registration protocol.
	 * The bound barrier retains its authentic supervisor or sandbox containment through registration removal.
	 * @param attempt validated action attempt identity
	 * @param directory actual group retained by the concrete owner
	 * @param rootPid actual root process retained by the concrete owner
	 * @param zeroBarrier bound operation on that concrete owner, retaining its lifetime
	 * @return acknowledged native registration
	 * @throws IllegalArgumentException if the attempt is already registered
	 * @throws IOException if the authority or native registration cannot be verified
	 */
	private Registration register(LinuxMeasurements.Attempt attempt, Path directory, long rootPid,
		ZeroBarrier zeroBarrier) throws IOException
	{
		requireOpen();
		if (registrations.containsKey(attempt))
			throw new IllegalArgumentException("Attempt is already registered: " + attempt);
		long nextGeneration = Math.incrementExact(generation);
		Properties request = operation("register");
		request.setProperty("name", attempt.name());
		request.setProperty("attempt", Long.toString(attempt.sequence()));
		request.setProperty("directory", directory.toString());
		request.setProperty("pid", Long.toString(rootPid));
		request(request);
		Registration registration = new Registration(attempt, zeroBarrier);
		registrations.put(attempt, registration);
		generation = nextGeneration;
		return registration;
	}

    /**
     * Registers the exact authenticated sandbox root in its direct owned group using a distinct private operation.
     * The legacy supervisor operation and its same-namespace/outer-leaf topology requirements remain unchanged.
     * @param attempt stable queue identity
     * @param containment original host-owned authenticated sandbox capability
     * @return acknowledged registration borrowing this exact containment through actual zero
     * @throws IOException if native identity or reader acknowledgement cannot be established
     */
    private Registration registerSandbox(LinuxMeasurements.Attempt attempt, LinuxBazelContainment containment)
        throws IOException
    {
        requireOpen();
        if (registrations.containsKey(attempt)) throw new IllegalArgumentException("Attempt is already registered: " + attempt);
        long nextGeneration = Math.incrementExact(generation);
        Properties request = operation("register-sandbox");
        request.setProperty("name", attempt.name());
        request.setProperty("attempt", Long.toString(attempt.sequence()));
        request.setProperty("directory", containment.directory().toString());
        request.setProperty("pid", Long.toString(containment.rootPid()));
        containment.registrationFields().forEach(request::setProperty);
        request(request);
        Registration registration = new Registration(attempt, containment::observeExecutionImpossible);
        registrations.put(attempt, registration);
        generation = nextGeneration;
        return registration;
    }

    /**
     * Supplies successful complete reader-process and owned-storage cleanup evidence for failed registration owners.
     * An uncertain registration may retain a reader FD without returning a Registration. Only this terminal flag,
     * published after actual reader wait and owned group cleanup, permits its caller to remove borrowed containment.
     * @return true only after complete original reader cleanup succeeded
     */
    boolean isClosed() { return closed; }

	/**
	 * Owns one registry entry while borrowing its concrete workload owner through a bound zero-barrier operation.
	 */
	public final class Registration implements AutoCloseable
	{
		/**
		 * Exact action attempt acknowledged by the native reader.
		 */
		private final LinuxMeasurements.Attempt attempt;
		/**
		 * Bound genuine zero-barrier operation retaining the borrowed supervisor or authenticated sandbox containment.
		 */
		private final ZeroBarrier zeroBarrier;
		/**
		 * Indicates whether the native reader acknowledged removal of this registration.
		 */
		private boolean removed;

		/**
		 * Retains the acknowledged attempt and its concrete borrowed workload owner.
		 * @param attempt exact registry identity
		 * @param zeroBarrier bound operation retaining the actual borrowed workload owner
		 */
		private Registration(LinuxMeasurements.Attempt attempt, ZeroBarrier zeroBarrier)
		{
			this.attempt = attempt;
			this.zeroBarrier = zeroBarrier;
		}

		/**
		 * Removes the reader's retained group handle after the caller has stopped this attempt, including when the
		 * workload owner already removed the finished group. Close before the next measurement batch.
		 * A failed request retains ownership and prevents sampling until authority cleanup.
		 * @throws IOException if removal cannot be acknowledged
		 */
		@Override
		public void close() throws IOException
		{
			if (removed)
				return;
			requireOpen();
			long nextGeneration = Math.incrementExact(generation);
			Properties request = operation("unregister");
			request.setProperty("name", attempt.name());
			request.setProperty("attempt", Long.toString(attempt.sequence()));
			request(request);
			registrations.remove(attempt);
			generation = nextGeneration;
			removed = true;
		}
	}

	@Override
	public LinuxMeasurements.Observation read() throws IOException
	{
		Properties response = request(operation("host"));
		try
		{
			LinuxMemory.Sample memory = new LinuxMemory.Sample(number(response, "total"), number(response, "hostAvailable"),
				number(response, "swapTotal"), number(response, "swapUsed"), number(response, "swapOut"), number(response, "stall"));
			return new LinuxMeasurements.Observation(memory, number(response, "available"),
				Objects.requireNonNull(response.getProperty("boot"), "boot"), generation, Set.copyOf(registrations.keySet()));
		}
		catch (IllegalArgumentException | NullPointerException failure)
		{
			broken = true;
			throw new IOException("Reader returned an invalid host observation", failure);
		}
	}

	@Override
	public long readResidentBytes(LinuxMeasurements.Attempt attempt) throws IOException
	{
		if (!registrations.containsKey(attempt))
			throw new IOException("Attempt is not registered: " + attempt);
		Properties request = operation("resident");
		request.setProperty("name", attempt.name());
		request.setProperty("attempt", Long.toString(attempt.sequence()));
		try
		{
			return number(request(request), "resident");
		}
		catch (IllegalArgumentException failure)
		{
			broken = true;
			throw new IOException("Reader returned invalid resident bytes for " + attempt, failure);
		}
	}

	@Override
	public void verify(long generation) throws IOException
	{
		if (this.generation != generation)
			throw new IOException("Registered workload generation changed during sampling");
		request(operation("verify"));
	}

	/**
	 * Exchanges one ordinal-bound request; any unacknowledged operation poisons the session.
	 * @param values request fields
	 * @return successful response fields
	 * @throws IOException if the helper rejects or cannot complete the request
	 */
	private Properties request(Properties values) throws IOException
	{
		requireOpen();
		long sequence = Math.incrementExact(requestSequence);
		requestSequence = sequence;
		Properties result;
		try
		{
			Path request = directory.resolve("request-" + sequence + ".properties");
			Path response = directory.resolve("response-" + sequence + ".properties");
			publish(request, values);
			result = await(response);
			Files.delete(response);
			Files.delete(request);
		}
		catch (IOException | RuntimeException failure)
		{
			broken = true;
			throw failure;
		}
		// A complete rejection leaves authority retained for a new batch. Only an unacknowledged exchange poisons it.
		Map<LinuxMeasurements.Attempt, ZeroBarrier> barriers = new HashMap<>();
		for (Registration registration : registrations.values())
			barriers.put(registration.attempt, registration.zeroBarrier);
		try
		{
			if (CHANGED_MEMBERS.equals(result.getProperty("reason")) &&
				(!"resident".equals(values.getProperty("operation")) ||
				!Objects.equals(values.getProperty("name"), result.getProperty("name.0")) ||
				!Objects.equals(values.getProperty("attempt"), result.getProperty("attempt.0"))))
				throw new IOException("Membership rejection differs from the requested resident attempt");
			requireSuccess(result, values.getProperty("operation", ""), barriers);
		}
		catch (IOException failure)
		{
			if (!Set.of("ok", "unavailable").contains(result.getProperty("status", "")))
				broken = true;
			throw failure;
		}
		return result;
	}

	/**
	 * Waits for one atomically published receipt without restarting or replacing the helper.
	 * @param receipt expected owned response file
	 * @return parsed response
	 * @throws IOException if the helper exits, receipt reading fails, or the request times out
	 */
	private Properties await(Path receipt) throws IOException
	{
		long started = System.nanoTime();
		while (!Files.exists(receipt))
		{
			if (!helper.isAlive())
				throw new IOException("Memory reader exited with status " + helper.exitValue() + "; stderr: " +
					Files.readString(directory.resolve("reader.stderr")));
			if (System.nanoTime() - started >= settings.timeout().toNanos())
				throw new IOException("Memory reader did not publish " + receipt.getFileName() + " within " + settings.timeout());
			pause();
		}
		return load(receipt);
	}

	/**
	 * Rejects non-success replies without manufacturing a numeric reading.
	 * @param response native response fields
	 * @throws IOException if the response is unsuccessful or malformed
	 */
	private void requireSuccess(Properties response) throws IOException
	{
		switch (response.getProperty("status", ""))
		{
			case "ok", "unavailable" -> { }
			default -> broken = true;
		}
		requireSuccess(response, "", Map.of());
	}

	/**
	 * Validates a complete reader reply without interpreting diagnostics as protocol metadata.
	 * @param response acknowledged reader reply
	 * @param operation request operation
	 * @param barriers exact registered owners' zero observations
	 * @throws IOException if the reply is rejected or malformed
	 */
	private static void requireSuccess(Properties response, String operation,
		Map<LinuxMeasurements.Attempt, ZeroBarrier> barriers) throws IOException
	{
		switch (response.getProperty("status", ""))
		{
			case "ok" -> { }
			case "unavailable" ->
			{
				String diagnostic = "Memory authority observation unavailable: " + response.getProperty("error");
				if (CHANGED_MEMBERS.equals(response.getProperty("reason")))
				{
					LinuxMeasurements.Attempt attempt;
					try
					{
						if (!operation.equals("resident") || !response.stringPropertyNames().equals(
							Set.of("status", "reason", "error", "count", "name.0", "attempt.0")) ||
							!"1".equals(response.getProperty("count")))
							throw new IllegalArgumentException("Membership rejection fields or operation differ");
						attempt = new LinuxMeasurements.Attempt(response.getProperty("name.0"), number(response, "attempt.0"));
						if (!barriers.containsKey(attempt))
							throw new IllegalArgumentException("Membership rejection names an unregistered attempt");
					}
					catch (IllegalArgumentException | NullPointerException failure)
					{
						throw new IOException("Membership rejection has invalid attempt metadata", failure);
					}
					throw new ObservationRejected(ObservationRejected.Operation.RESIDENT, ObservationRejected.Reason.MEMBERSHIP_CHANGED,
						java.util.List.of(new ObservationRejected.Attempt(attempt.name(), attempt.sequence())), new IOException(diagnostic));
				}
				if (!MISSING_LEAF.equals(response.getProperty("reason")))
					throw new IOException(diagnostic);
				ObservationRejected.Operation observation = switch (operation)
				{
					case "host" -> ObservationRejected.Operation.HOST;
					case "resident" -> ObservationRejected.Operation.RESIDENT;
					case "verify" -> ObservationRejected.Operation.ACCEPT;
					default -> throw new IOException("Missing-leaf rejection is invalid for operation " + operation);
				};
				var attempts = new ArrayList<LinuxMeasurements.Attempt>();
				try
				{
					long count = number(response, "count");
					if (count < 1 || count > barriers.size())
						throw new IllegalArgumentException("Missing-leaf count exceeds current registrations");
					var expected = new HashSet<String>(Set.of("status", "reason", "error", "count"));
					for (long index = 0; index < count; index++)
					{
						String name = "name." + index;
						String ordinal = "attempt." + index;
						expected.add(name);
						expected.add(ordinal);
						attempts.add(new LinuxMeasurements.Attempt(response.getProperty(name), number(response, ordinal)));
					}
					if (!response.stringPropertyNames().equals(expected) || new HashSet<>(attempts).size() != attempts.size())
						throw new IllegalArgumentException("Missing-leaf fields or attempt identities differ from the declared list");
				}
				catch (IllegalArgumentException | NullPointerException failure)
				{
					throw new IOException("Missing-leaf rejection has invalid attempt metadata", failure);
				}
				for (var attempt : attempts)
					if (!barriers.containsKey(attempt))
						throw new IOException("Missing-leaf rejection names an unregistered attempt: " + attempt);
				for (var attempt : attempts)
				{
					boolean observed;
					try { observed = barriers.get(attempt).observed(); }
					catch (IOException failure)
					{
						throw new IOException("Missing-leaf zero observation failed: " + attempt, failure);
					}
					if (!observed) throw new IOException("Missing-leaf rejection lacks retained zero authority: " + attempt);
				}
				throw new ObservationRejected(observation, ObservationRejected.Reason.COMPLETED_LEAVES,
					attempts.stream().map(attempt -> new ObservationRejected.Attempt(attempt.name(), attempt.sequence())).toList(),
					new IOException(diagnostic));
			}
			default ->
			{
				throw new IOException("Memory reader returned an invalid response status: " + response.getProperty("status"));
			}
		}
	}

	/**
	 * Narrow typed access to the production reply decision for focused checks.
	 */
	public static final class SharedSecrets
	{
		/**
		 * Prevents construction.
		 */
		private SharedSecrets() { }
		/**
		 * Exercises the actual reply decision with controlled retained-owner observations.
		 * @param response acknowledged reply
		 * @param operation request operation
		 * @param barriers registered retained-zero suppliers
		 * @throws IOException if the reply is rejected
		 */
		public static void requireSuccess(Properties response, String operation,
			Map<LinuxMeasurements.Attempt, ZeroBarrier> barriers) throws IOException
		{
			LinuxAuthority.requireSuccess(response, operation, barriers);
		}
	}

	/**
	 * Requires a ready authority; close remains available after an incomplete request.
	 * @throws IOException if authority was closed, poisoned, or lost its helper
	 */
	private void requireOpen() throws IOException
	{
		if (closed || broken || helper == null || !helper.isAlive())
			throw new IOException("Memory authority is closed, unavailable, or has an incomplete request");
	}

	/**
	 * Creates the request discriminator shared with the retained reader.
	 * @param operation protocol operation
	 * @return request fields
	 */
	static Properties operation(String operation)
	{
		Properties values = new Properties();
		values.setProperty("operation", operation);
		return values;
	}

	/**
	 * Parses a required canonical nonnegative signed-long count.
	 * @param values response fields
	 * @param name field name
	 * @return exact count
	 */
	public static long number(Properties values, String name)
	{
		String text = values.getProperty(name);
		long value = Long.parseLong(text);
		if (value < 0 || !Long.toString(value).equals(text))
			throw new IllegalArgumentException("Invalid nonnegative count for " + name + ": " + text);
		return value;
	}

	/**
	 * Publishes a transient IPC document atomically in its owned directory.
	 * @param path destination in the exclusive IPC directory
	 * @param values protocol fields
	 * @throws IOException if writing or atomic publication fails
	 */
	static void publish(Path path, Properties values) throws IOException
	{
		Path temporary = path.resolveSibling(path.getFileName() + ".pending");
		try (Writer writer = Files.newBufferedWriter(temporary))
		{
			values.store(writer, null);
		}
		Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE);
	}

	/**
	 * Reads a complete transient IPC document.
	 * @param path published document
	 * @return parsed protocol fields
	 * @throws IOException if the document cannot be decoded or read
	 */
	static Properties load(Path path) throws IOException
	{
		Properties values = new Properties();
		try (Reader reader = Files.newBufferedReader(path))
		{
			values.load(reader);
		}
		catch (IllegalArgumentException failure)
		{
			throw new IOException("Invalid memory-reader protocol document: " + path, failure);
		}
		return values;
	}

	/**
	 * Waits between observations, preserving interruption as a failed request.
	 * @throws IOException if the caller is interrupted
	 */
	static void pause() throws IOException
	{
		try
		{
			Thread.sleep(POLL_INTERVAL);
		}
		catch (InterruptedException failure)
		{
			Thread.currentThread().interrupt();
			throw new IOException("Memory authority wait was interrupted", failure);
		}
	}

	/**
	 * Kills only the owned helper group, collects the direct process status, and removes owned scratch.
	 * Cleanup is retryable: unsuccessful cleanup retains the owner and remaining resources.
	 * @throws IOException if containment, reaping, or removal remains incomplete
	 */
	@Override
	public void close() throws IOException
	{
		if (closed)
			return;
		broken = true;
		if (helper != null && helper.isAlive())
		{
			if (helperGroup != null)
				helperGroup.terminate();
			helper.destroyForcibly();
		}
		if (helper != null)
		{
			try
			{
				if (!helper.waitFor(settings.timeout().toNanos(), TimeUnit.NANOSECONDS))
					throw new IOException("Memory reader has not terminated within " + settings.timeout());
			}
			catch (InterruptedException failure)
			{
				Thread.currentThread().interrupt();
				throw new IOException("Interrupted while collecting memory reader status", failure);
			}
		}
		if (helperGroup != null)
		{
			helperGroup.close();
			helperGroup = null;
		}
		if (directory != null)
		{
			try (var entries = Files.walk(directory))
			{
				for (Path entry : entries.sorted(java.util.Comparator.reverseOrder()).toList())
					Files.delete(entry);
			}
			directory = null;
		}
		for (Registration registration : registrations.values())
			registration.removed = true;
		registrations.clear();
		closed = true;
	}
}
