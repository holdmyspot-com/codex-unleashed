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
 * Instrumented ownership retains the action cgroup until actual zero and explicit hook-close acknowledgement.
 * The controller remains outside that cgroup and needs cgroup-management authority. Instances are single-thread
 * confined. Workload status is accepted only after the supervisor has collected the entire tree and exited.
 */
public final class LinuxSupervisor implements AutoCloseable
{
	/**
	 * Published stages, each written once by its designated producer in the private attempt directory.
	 */
	private enum Phase
	{
		/**
		 * Controller-published workload inputs before supervisor startup.
		 */
		LAUNCH,
		/**
		 * Supervisor-published identity and containment of the prepared gate.
		 */
		READY,
		/**
		 * Controller request to release the prepared gate once.
		 */
		RELEASE,
		/**
		 * Supervisor acknowledgement of the release request.
		 */
		RELEASED,
		/**
		 * Controller request to terminate the owned workload tree.
		 */
		TERMINATE,
		/**
		 * Controller acknowledgement that instrumentation closed after observed zero population.
		 */
		COLLECT,
		/**
		 * First ordinary supervision failure retained while owned cleanup remains possible.
		 */
		FAULT,
		/**
		 * Supervisor-published whole-tree outcome after cleanup.
		 */
		RESULT
	}

	/**
	 * Owns identity-bound control messages and retained kernel evidence for one action.
	 */
	static final class Control
	{
		/**
		 * Private exchange directory owned by the surrounding supervisor owner.
		 */
		private final Path scratch;
		/**
		 * Exclusive attempt identity shared by both exchange participants.
		 */
		private final String attempt;
		/**
		 * Prepared gate and exec-replaced workload identity.
		 */
		private final long rootPid;
		/**
		 * Borrowed action subtree retained until collection is explicitly authorized.
		 */
		private final Path actionGroup;
		/**
		 * Indicates whether hooks must close before collection.
		 */
		private final boolean instrumentationManaged;
		/**
		 * Indicates whether cancellation intent has been retained, including uncertain publication.
		 */
		private boolean terminationIntent;
		/**
		 * Indicates whether this owner sampled actual zero while the action group was retained.
		 */
		private boolean executionImpossible;
		/**
		 * Indicates whether this controller successfully published its hook-close acknowledgement.
		 */
		private boolean collectionPublished;

		/**
		 * Binds the exchange to the validated prepared action.
		 * @param scratch private attempt exchange directory
		 * @param attempt exclusive attempt identity
		 * @param rootPid prepared workload root identity
		 * @param actionGroup action subtree retained by the dedicated supervisor
		 * @param instrumentationManaged indicates whether collection requires hook-close acknowledgement
		 */
		Control(Path scratch, String attempt, long rootPid, Path actionGroup, boolean instrumentationManaged)
		{
			this.scratch = scratch;
			this.attempt = attempt;
			this.rootPid = rootPid;
			this.actionGroup = actionGroup;
			this.instrumentationManaged = instrumentationManaged;
		}

		/**
		 * Publishes cancellation intent without waiting for execution or collection.
		 * Uncertain publication is reconciled against the same immutable request on retry.
		 * @throws IOException if publication or reconciliation fails
		 */
		void requestTermination() throws IOException
		{
			terminationIntent = true;
			publishOnce(Phase.TERMINATE, identities());
		}

		/**
		 * Indicates whether a valid cancellation request is available to the dedicated owner.
		 * @return true when this attempt requested cancellation
		 * @throws IOException if the request cannot be read or identifies another action
		 */
		boolean readTerminationRequest() throws IOException
		{
			if (Files.exists(file(scratch, Phase.TERMINATE)) == false)
				return false;
			validate(read(scratch, Phase.TERMINATE, attempt));
			return true;
		}

		/**
		 * Samples actual subtree population without waiting or collecting statuses.
		 * @return true after this retained action subtree has reported populated zero
		 * @throws IllegalStateException if this is an automatic owner
		 * @throws IOException if the retained group is missing or its population is unreadable or invalid
		 */
		boolean observeExecutionImpossible() throws IOException
		{
			requireInstrumented();
			if (executionImpossible == false)
				executionImpossible = LinuxCgroup.isPopulated(actionGroup) == false;
			return executionImpossible;
		}

		/**
		 * Acknowledges that all hooks closed after the authoritative zero observation.
		 * @throws IllegalStateException if this is automatic or zero has not been observed
		 * @throws IOException if acknowledgement publication fails; ownership remains retained
		 */
		void finishInstrumentation() throws IOException
		{
			requireInstrumented();
			if (executionImpossible == false)
				throw new IllegalStateException("Instrumentation closure requires an observed empty action subtree");
			Properties values = identities();
			values.setProperty("instrumentation.closed", "true");
			values.setProperty("populated", "0");
			publishOnce(Phase.COLLECT, values);
			collectionPublished = true;
		}

		/**
		 * Indicates whether the dedicated supervisor may explicitly collect process statuses.
		 * @return true for automatic ownership or an identity-validated hook-close acknowledgement
		 * @throws IOException if an acknowledgement is malformed or names another action
		 */
		boolean collectionRequested() throws IOException
		{
			if (instrumentationManaged == false)
				return true;
			if (Files.exists(file(scratch, Phase.COLLECT)) == false)
				return false;
			Properties values = read(scratch, Phase.COLLECT, attempt);
			validate(values);
			if ("true".equals(required(values, "instrumentation.closed")) == false ||
				"0".equals(required(values, "populated")) == false)
				throw new IOException("Collection acknowledgement lacks closed instrumentation and zero population");
			return true;
		}

		/**
		 * Creates the action identity carried by each controller request.
		 * @return a new document binding the root and retained action subtree
		 */
		private Properties identities()
		{
			Properties values = new Properties();
			values.setProperty(ROOT_PID, Long.toString(rootPid));
			values.setProperty("action.group", actionGroup.toString());
			return values;
		}

		/**
		 * Rejects messages for another prepared action.
		 * @param values validated phase and attempt document
		 * @throws IOException if managed action identities differ
		 */
		private void validate(Properties values) throws IOException
		{
			if (instrumentationManaged && (Long.toString(rootPid).equals(required(values, ROOT_PID)) == false ||
				actionGroup.toString().equals(required(values, "action.group")) == false))
				throw new IOException("Control message identifies another prepared action");
		}

		/**
		 * Publishes an immutable phase once, reconciling a write whose completion was uncertain.
		 * @param phase exclusive controller phase
		 * @param values immutable action-bound payload for that phase
		 * @throws IOException if a conflicting document exists or publication fails
		 */
		private void publishOnce(Phase phase, Properties values) throws IOException
		{
			values.setProperty(ATTEMPT, attempt);
			values.setProperty(PHASE, phase.name());
			Path destination = file(scratch, phase);
			if (Files.exists(destination))
			{
				if (read(scratch, phase, attempt).equals(values) == false)
					throw new IOException("Published control phase conflicts with its retained request: " + phase);
				return;
			}
			Path pending = destination.resolveSibling(destination.getFileName() + ".pending");
			Files.deleteIfExists(pending);
			publish(scratch, phase, attempt, values);
		}

		/**
		 * Rejects hook-lifetime operations for automatic process ownership.
		 */
		private void requireInstrumented()
		{
			if (instrumentationManaged == false)
				throw new IllegalStateException("Automatic ownership has no instrumentation handoff");
		}
	}

	/**
	 * XML property identifying the exclusive attempt across every stage.
	 */
	private static final String ATTEMPT = "attempt";
	/**
	 * XML property identifying the published stage.
	 */
	private static final String PHASE = "phase";
	/**
	 * XML property identifying the gate and exec-replaced workload root.
	 */
	private static final String ROOT_PID = "root.pid";
	/**
	 * XML property containing the final root exit status.
	 */
	private static final String ROOT_EXIT = "root.exit";
	/**
	 * XML property distinguishing forced termination from natural completion.
	 */
	private static final String TERMINATION = "termination";
	/**
	 * XML property prefix for adopted child PID and collected status pairs.
	 */
	private static final String ADOPTED_PREFIX = "adopted.";
	/**
	 * Time between nonblocking worker observations and controller stage reads.
	 */
	private static final Duration POLL_INTERVAL = Duration.ofMillis(10);

	/**
	 * Immutable workload context; its cgroup parent is used only to acquire the independent outer group.
	 */
	private final LinuxProcessTree.Launch launch;
	/**
	 * Caller-owned workload endpoints; null selects legacy piped input and merged workload logging.
	 */
	private final LinuxProcessTree.StandardFiles files;
	/**
	 * Startup, control acknowledgement and cleanup wait limit; this is not a build runtime limit.
	 */
	private final long timeoutNanos;
	/**
	 * Identity generated before resource acquisition and checked in every published stage.
	 */
	private final String attempt = UUID.randomUUID().toString();
	/**
	 * Controller-owned outer subtree; null until acquired.
	 */
	private LinuxCgroup outer;
	/**
	 * Indicates that the outer owner completed cleanup, even if later storage removal needs a retry.
	 */
	private boolean outerClosed;
	/**
	 * Controller-owned attempt state and supervisor log; null until acquired.
	 */
	private Path scratch;
	/**
	 * Retained direct child, including before cgroup placement succeeds.
	 */
	private Process supervisor;
	/**
	 * Workload root identity received from the prepared supervisor.
	 */
	private long rootPid;
	/**
	 * Indicates that the one-time release request has been published.
	 */
	private boolean releaseRequested;
	/**
	 * Indicates whether the caller selected explicit instrumentation lifetime ownership.
	 */
	private boolean instrumentationManaged;
	/**
	 * Indicates whether preparation returned and the caller may have acquired hooks.
	 */
	private boolean returned;
	/**
	 * Validated action exchange retained after readiness.
	 */
	private Control control;
	/**
	 * First cancellation publication interval; duplicate calls never extend it.
	 */
	private Long cancellationStarted;
	/**
	 * First successful collection publication interval; duplicate calls never extend it.
	 */
	private Long collectionStarted;
	/**
	 * First reported dedicated-owner failure, retained even if safe cleanup later succeeds.
	 */
	private IOException supervisionFault;
	/**
	 * Accepted result, retained after successful close; null while completion is unproven.
	 */
	private Result completion;
	/**
	 * Indicates that all owned resources have been released.
	 */
	private boolean closed;

	/**
	 * Failed preparation whose unfinished cleanup remains owned by this exception.
	 * The original preparation failure is the cause; cleanup failures are suppressed on that cause.
	 * Call close again after restoring the failed cleanup prerequisite. It never releases the failed workload.
	 */
	public static final class PreparationFailure extends IOException implements AutoCloseable
	{
		/**
		 * Owner retained until cleanup succeeds.
		 */
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
	 * @param files caller-owned workload endpoints, or null for legacy standard streams
	 * @param timeout the positive startup/control/cleanup timeout representable as signed nanoseconds
	 */
	private LinuxSupervisor(LinuxProcessTree.Launch launch, LinuxProcessTree.StandardFiles files, Duration timeout)
	{
		this.launch = Objects.requireNonNull(launch, "launch may not be null");
		this.files = files;
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
	 * The prepared workload gate expires 60 seconds after it signals readiness unless released. Release promptly;
	 * later observation or release reports a supervisor failure if the gate has expired.
	 * ATTACHED_LEADER_EXEC retains the released gate for at most 30 seconds after credential drop; the controller
	 * must arrange leader-thread replacement within that interval. See {@link LinuxProcessTree.Launch}.
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
		return prepareOwned(launch, null, timeout, false);
	}

	/**
	 * Acquires the existing supervisor lifecycle for legacy or explicit workload streams.
	 * @param launch the explicit workload and trusted Java context
	 * @param files caller-owned workload endpoints, or null for legacy standard streams
	 * @param timeout the positive startup/control/cleanup wait limit
	 * @param instrumentationManaged indicates whether collection requires caller-confirmed hook closure
	 * @return a prepared owner whose workload is still gated
	 * @throws IOException if acquisition, containment or preparation fails
	 * @throws PreparationFailure if preparation and its cleanup both fail
	 */
	private static LinuxSupervisor prepareOwned(LinuxProcessTree.Launch launch,
		LinuxProcessTree.StandardFiles files, Duration timeout, boolean instrumentationManaged) throws IOException
	{
		LinuxSupervisor owner = new LinuxSupervisor(launch, files, timeout);
		owner.instrumentationManaged = instrumentationManaged;
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
				if (readInstrumentationMode(ready) != instrumentationManaged)
					throw new IOException("Supervisor readiness changed the requested instrumentation ownership");
				owner.control = new Control(owner.scratch, owner.attempt, owner.rootPid, actionGroup,
					instrumentationManaged);
			}
			catch (IllegalArgumentException failure)
			{
				throw new IOException("Supervisor readiness contains invalid process identities or paths", failure);
			}
			owner.returned = true;
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
	 * Prepares a gated workload with caller-owned standard-stream endpoints.
	 * Containment, release, timeout and failed-acquisition ownership follow
	 * {@link #prepare(LinuxProcessTree.Launch, Duration)}. Only the workload gate receives the redirects;
	 * the supervisor retains its controller liveness pipe and diagnostic log. The caller must arrange FIFO
	 * peers so redirected opens can complete. The gate opens endpoints before release; output truncation
	 * may occur even if preparation later fails. The owner never deletes the endpoints.
	 * @param launch the explicit workload and trusted Java context, with elevated cgroup-management authority
	 * @param files nonnull standard-stream endpoints
	 * @param timeout the positive startup/control/cleanup wait limit
	 * @return a prepared owner whose workload is still gated
	 * @throws NullPointerException if an input is null, before resource acquisition
	 * @throws IllegalArgumentException if timeout is nonpositive or cannot be represented as signed nanoseconds
	 * @throws UnsupportedOperationException if the cgroup parent is not on cgroup v2
	 * @throws IOException if acquisition, containment or preparation fails
	 * @throws PreparationFailure if preparation and its cleanup both fail; close that exception to retry cleanup
	 */
	public static LinuxSupervisor prepare(LinuxProcessTree.Launch launch,
		LinuxProcessTree.StandardFiles files, Duration timeout) throws IOException
	{
		Objects.requireNonNull(files, "files may not be null");
		return prepareOwned(launch, files, timeout, false);
	}

	/**
	 * Prepares an action whose cgroup remains available until its instrumentation owner permits collection.
	 * The caller must observe actual zero population, close every hook owner, call finishInstrumentation,
	 * then collect the result and close this owner. Existing gate, stream and failed-acquisition contracts apply.
	 * @param launch immutable workload and trusted runtime inputs
	 * @param files caller-owned nonnull workload stream endpoints
	 * @param timeout positive startup, cancellation and collection control timeout
	 * @return the prepared owner with execution still gated and action-group retention armed
	 * @throws NullPointerException if an input is null
	 * @throws IllegalArgumentException if timeout is nonpositive or exceeds signed nanoseconds
	 * @throws UnsupportedOperationException if the cgroup parent is not cgroup v2
	 * @throws IOException if preparation fails
	 * @throws PreparationFailure if failed preparation retains unfinished cleanup
	 */
	public static LinuxSupervisor prepareInstrumented(LinuxProcessTree.Launch launch,
		LinuxProcessTree.StandardFiles files, Duration timeout) throws IOException
	{
		Objects.requireNonNull(files, "files may not be null");
		return prepareOwned(launch, files, timeout, true);
	}

	/**
	 * Publishes termination intent without waiting for kill acknowledgement, task exit or collected outcomes.
	 * A publication failure leaves this owner responsible for reconciliation and cleanup on retry.
	 * @throws IllegalStateException if closed without an accepted result
	 * @throws IOException if the request cannot be published or reconciled
	 */
	public void requestTermination() throws IOException
	{
		if (completion != null)
			return;
		requireOpen();
		if (cancellationStarted == null)
			cancellationStarted = System.nanoTime();
		control.requestTermination();
	}

	/**
	 * Observes whether this retained action subtree has no live workload process.
	 * Missing groups and root-only exit do not establish this barrier. False leaves hooks and ownership retained.
	 * @return true only after this owner read actual populated zero
	 * @throws IllegalStateException if closed or automatic ownership was selected
	 * @throws IOException if the sample fails or cancellation exceeded its control timeout without zero
	 */
	public boolean observeExecutionImpossible() throws IOException
	{
		requireOpen();
		boolean impossible = control.observeExecutionImpossible();
		if (impossible == false && cancellationStarted != null &&
			(System.nanoTime() - cancellationStarted) >= timeoutNanos)
			throw new IOException("Action population did not reach zero within its cancellation timeout: " + scratch);
		return impossible;
	}

	/**
	 * Acknowledges successful closure of every hook after this owner's populated-zero observation.
	 * Publication does not wait for process collection. Failed publication retains cleanup ownership for retry.
	 * @throws IllegalStateException if closed, automatic or the zero barrier has not been observed
	 * @throws IOException if the immutable acknowledgement cannot be published
	 */
	public void finishInstrumentation() throws IOException
	{
		requireOpen();
		control.finishInstrumentation();
		if (collectionStarted == null)
			collectionStarted = System.nanoTime();
	}

	/**
	 * Requests one release and waits for the supervisor's acknowledgement.
	 * A failed acknowledgement leaves a published request owned by this instance; close remains available.
	 * Acknowledgement does not establish completed credential drop or exec. Attached-mode callers must verify
	 * credentials before dispatching their leader-thread exec and verify the resulting process separately.
	 * @throws IllegalStateException if closed or release was already requested
	 * @throws IOException if publication, acknowledgement or supervisor execution fails
	 */
	public void release() throws IOException
	{
		requireOpen();
		if (releaseRequested)
			throw new IllegalStateException("Workload release was already requested");
		if (control.terminationIntent || control.executionImpossible)
			throw new IllegalStateException("Cancellation or observed zero withdrew workload release authority");
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
		readSupervisionFault();
		boolean complete = observeOwnedCompletion();
		if (supervisionFault != null)
			throw supervisionFault;
		return complete;
	}

	/**
	 * Collects authentic outcomes for cleanup without converting a prior supervision fault into a queue success.
	 * @return true after actual validated completion
	 * @throws IOException if collection or its terminal receipt fails
	 */
	private boolean observeOwnedCompletion() throws IOException
	{
		if (completion != null)
			return true;
		requireOpen();
		try
		{
			if (supervisor.waitFor(0, TimeUnit.NANOSECONDS) == false)
			{
				if (collectionStarted != null && (System.nanoTime() - collectionStarted) >= timeoutNanos)
					throw new IOException("Supervisor collection exceeded its control timeout: " + scratch);
				return false;
			}
		}
		catch (InterruptedException failure)
		{
			Thread.currentThread().interrupt();
			throw new IOException("Interrupted while observing supervisor completion", failure);
		}
		if (supervisor.exitValue() != 0)
			throw supervisorFailure();
		if (instrumentationManaged && control.collectionPublished == false)
			throw new IOException("Instrumented supervisor exited before hook-close acknowledgement: " + scratch);
		Properties result = read(scratch, Phase.RESULT, attempt);
		if (instrumentationManaged)
		{
			control.validate(result);
			if ("true".equals(required(result, "instrumentation.closed")) == false)
				throw new IOException("Instrumented result lacks closed-hook acknowledgement");
		}
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
	 * Retains the dedicated supervisor's first action-bound fault independently of later cleanup.
	 * @throws IOException if the fault document cannot be read or validated
	 */
	private void readSupervisionFault() throws IOException
	{
		if (instrumentationManaged && supervisionFault == null && Files.exists(file(scratch, Phase.FAULT)))
		{
			Properties fault = read(scratch, Phase.FAULT, attempt);
			control.validate(fault);
			supervisionFault = new IOException("Dedicated owner failed " + required(fault, "operation") +
				": " + required(fault, "message"));
		}
	}

	/**
	 * Returns actual collected outcomes, including after successful close.
	 * After a reported supervision fault these outcomes are diagnostic; collection never clears that failure.
	 * A caller must not normalize a failed action into queue success merely because status collection succeeded.
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
	 * Completion already accepted before the request retains its recorded result. If the supervisor handles the
	 * termination request before observing natural completion, it records termination even if the root has exited.
	 * A timeout leaves cleanup with this owner.
	 * @throws IllegalStateException if closed without previously accepted completion
	 * @throws IOException if control, supervision or status collection fails or exceeds the timeout
	 */
	public void terminate() throws IOException
	{
		if (observeCompletion())
			return;
		if (instrumentationManaged && control.collectionPublished == false)
			throw new IllegalStateException("Instrumented termination requires request, zero observation and hook closure");
		requestTermination();
		long started = System.nanoTime();
		while (observeCompletion() == false)
		{
			if ((System.nanoTime() - started) >= timeoutNanos)
				throw new IOException("Supervisor termination exceeded its control timeout: " + scratch);
			pause();
		}
	}

	/**
	 * Returns the retained supervisor PID.
	 *
	 * @return the retained supervisor PID
	 */
	public long pid() { return supervisor.pid(); }
	/**
	 * Returns the prepared gate and exec-replaced workload root PID.
	 *
	 * @return the prepared gate and exec-replaced workload root PID
	 */
	public long rootPid() { return rootPid; }
	/**
	 * Returns the controller-owned outer cgroup's original absolute path.
	 *
	 * @return the controller-owned outer cgroup's original absolute path
	 */
	public Path directory() { return outer.directory(); }
	/**
	 * Returns the controller-owned attempt directory's original absolute path.
	 *
	 * @return the controller-owned attempt directory's original absolute path
	 */
	public Path scratch() { return scratch; }

	/**
	 * Terminates remaining owned work, reaps the direct child, removes owned groups and then removes owned storage.
	 * Repeated close after success has no effect. Failed cleanup retains ownership for retry; caller interruption is
	 * preserved. Closing unfinished work supplies no invented workload result.
	 * A returned instrumented owner first needs actual zero and successful finishInstrumentation. Earlier close
	 * only requests cancellation and throws, retaining its cgroup, supervisor and storage. After acknowledgement,
	 * close permits real status collection despite a previously reported fault. Failed or missing terminal receipts
	 * retain ownership and cannot be repaired by inventing a result or silently deleting action evidence.
	 * @throws IOException if termination, reaping or removal fails
	 */
	@Override
	public void close() throws IOException
	{
		if (closed)
			return;
		if (returned && instrumentationManaged)
		{
			if (control.collectionPublished == false)
			{
				requestTermination();
				throw new IOException("Instrumented cleanup retains ownership until zero and hook closure: " + scratch);
			}
			long started = System.nanoTime();
			readSupervisionFault();
			while (observeOwnedCompletion() == false)
			{
				if ((System.nanoTime() - started) >= timeoutNanos)
					throw new IOException("Instrumented cleanup awaits collected outcomes: " + scratch);
				pause();
			}
		}
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

	/**
	 * Publishes this attempt's workload inputs before starting the supervisor.
	 *
	 * @throws IOException if the launch document cannot be written or atomically published
	 */
	private void writeLaunch() throws IOException
	{
		Properties values = new Properties();
		values.setProperty("instrumentation.managed", Boolean.toString(instrumentationManaged));
		values.setProperty("cgroup.parent", outer.directory().toString());
		values.setProperty("java", launch.javaExecutable().toString());
		values.setProperty("class.path", launch.classPath().toString());
		values.setProperty("directory", launch.directory().toString());
		values.setProperty("uid", Integer.toString(launch.uid()));
		values.setProperty("gid", Integer.toString(launch.gid()));
		launch.executionMode().writeTo(values);
		values.setProperty("command.count", Integer.toString(launch.command().size()));
		for (int index = 0; index < launch.command().size(); index++)
			values.setProperty("command." + index, launch.command().get(index));
		for (var entry : launch.environment().entrySet())
			values.setProperty("environment." + entry.getKey(), entry.getValue());
		if (files != null)
			files.writeTo(values);
		publish(scratch, Phase.LAUNCH, attempt, values);
	}

	/**
	 * Waits up to the configured control timeout for the requested stage of this attempt.
	 *
	 * @param phase the supervisor stage required before the caller can continue
	 * @return the published properties after checking the stage and attempt identity
	 * @throws IOException if the supervisor exits, the timeout expires, the stage cannot be read or validated,
	 * or the waiting thread is interrupted
	 */
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

	/**
	 * Creates a failure identifying the exited supervisor and its available diagnostic output.
	 *
	 * @return the supervisor failure, with log output or a log-read failure attached as a suppressed exception
	 * @throws IllegalThreadStateException if the supervisor has not exited
	 */
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

	/**
	 * Rejects lifecycle operations after this owner has closed successfully.
	 *
	 * @throws IllegalStateException if successful close already occurred
	 */
	private void requireOpen()
	{
		if (closed)
			throw new IllegalStateException("Linux supervisor owner is closed");
	}

	/**
	 * Retains the first cleanup failure and attaches the next failure to it.
	 *
	 * @param first the earlier cleanup failure, or null if none has occurred
	 * @param next the next cleanup failure
	 * @return the next failure when the first is null, otherwise the first with the next suppressed
	 */
	private static IOException combine(IOException first, IOException next)
	{
		if (first == null)
			return next;
		first.addSuppressed(next);
		return first;
	}

	/**
	 * Returns the XML document path for one stage in the attempt directory.
	 *
	 * @param scratch the private attempt directory
	 * @param phase the stage whose document is located
	 * @return the stage document path
	 */
	private static Path file(Path scratch, Phase phase)
	{
		return scratch.resolve(phase.name().toLowerCase(java.util.Locale.ROOT) + ".xml");
	}

	/**
	 * Adds the attempt and stage identities to the supplied properties and atomically publishes their XML document.
	 * A failed publication can leave the exclusively created sibling staging file for owner cleanup.
	 *
	 * @param scratch the private attempt directory
	 * @param phase the stage being published
	 * @param attempt the attempt identity written into the document
	 * @param values the stage properties, updated in place with the attempt and stage identities
	 * @throws IOException if the staging file cannot be exclusively created, written or atomically moved
	 */
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

	/**
	 * Reads a stage's XML properties and validates its stage and attempt identity.
	 *
	 * @param scratch the private attempt directory
	 * @param phase the expected document stage
	 * @param attempt the expected attempt identity, or null to accept any nonempty identity
	 * @return the validated stage properties
	 * @throws IOException if the document cannot be read or decoded, a required identity is absent,
	 * or the stage or attempt identity is invalid
	 */
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

	/**
	 * Returns a required property while preserving an explicitly empty value.
	 *
	 * @param values the stage properties to read
	 * @param key the required property name
	 * @return the property value, including an empty string
	 * @throws IOException if the named property is absent
	 */
	private static String required(Properties values, String key) throws IOException
	{
		String value = values.getProperty(key);
		if (value == null)
			throw new IOException("Supervisor handoff is missing required field: " + key);
		return value;
	}

	/**
	 * Waits 10 milliseconds between observations while preserving the interrupt flag on interruption.
	 *
	 * @throws IOException if the waiting thread is interrupted
	 */
	private static void pause() throws IOException
	{
		try { Thread.sleep(POLL_INTERVAL); }
		catch (InterruptedException failure)
		{
			Thread.currentThread().interrupt();
			throw new IOException("Interrupted during supervisor handoff", failure);
		}
	}

	/**
	 * Reconstructs and validates workload launch inputs from the published properties.
	 *
	 * @param scratch the private attempt directory used as the helper scratch parent
	 * @param values the published launch properties
	 * @return the copied and validated workload launch inputs
	 * @throws IOException if a required field is absent, the command count is nonpositive,
	 * or a launch value is malformed or violates the launch contract
	 */
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
				Integer.parseInt(required(values, "gid")), LinuxProcessTree.ExecutionMode.readFrom(values));
		}
		catch (IllegalArgumentException failure)
		{
			throw new IOException("Supervisor launch contains invalid workload inputs", failure);
		}
	}

	/**
	 * Reads the explicit collection lifetime while preserving automatic legacy documents.
	 * @param values launch or readiness document
	 * @return true when instrumentation owns the collection handoff
	 * @throws IOException if the explicit value is neither true nor false
	 */
	static boolean readInstrumentationMode(Properties values) throws IOException
	{
		return switch (values.getProperty("instrumentation.managed", "false"))
		{
			case "true" -> true;
			case "false" -> false;
			default -> throw new IOException("Invalid instrumentation.managed value");
		};
	}

	/**
	 * Runs only in the dedicated privileged child JVM started by prepare.
	 * It waits for controller-confirmed placement before creating a helper. A background reader records controller
	 * input closure or unexpected input; after preparation, the control loop checks that result between operations
	 * and closes the owned process tree when the connection is lost.
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
		boolean managed = readInstrumentationMode(configuration);
		LinuxProcessTree.Launch launch = readLaunch(scratch, configuration);
		LinuxProcessTree.StandardFiles files = LinuxProcessTree.StandardFiles.readFrom(configuration);
		LinuxProcessTree tree = files == null ? LinuxProcessTree.prepare(launch) :
			LinuxProcessTree.prepare(launch, files);
		boolean collectionAuthorized = false;
		Throwable supervisionFailure = null;
		try
		{
			rootPid = tree.rootPid();
			Control control = new Control(scratch, attempt, rootPid, tree.group(), managed);
			Properties ready = new Properties();
			ready.setProperty(ROOT_PID, Long.toString(rootPid));
			ready.setProperty("action.group", tree.group().toString());
			ready.setProperty("gate.scratch", tree.scratch().toString());
			ready.setProperty("instrumentation.managed", Boolean.toString(managed));
			publish(scratch, Phase.READY, attempt, ready);
			boolean released = false;
			boolean terminationRequested = false;
			while (true)
			{
				IOException lost = controllerFailure.get();
				if (lost != null)
					throw new IOException("Controller connection was lost; terminating the owned workload", lost);
				String operation = "read-termination";
				try
				{
					if (terminationRequested == false && control.readTerminationRequest())
					{
						operation = "request-action-kill";
						if (managed)
							tree.requestTermination();
						else
							tree.terminate();
						terminationRequested = true;
					}
					else if (released == false && terminationRequested == false &&
						Files.exists(file(scratch, Phase.RELEASE)))
					{
						operation = "release-action";
						read(scratch, Phase.RELEASE, attempt);
						tree.release();
						released = true;
						publish(scratch, Phase.RELEASED, attempt, new Properties());
					}
					operation = "read-collection";
					if (control.collectionRequested())
					{
						operation = "verify-collection-zero";
						if (managed && control.observeExecutionImpossible() == false)
							throw new IOException("Collection acknowledgement preceded actual zero population");
						collectionAuthorized = true;
						operation = "collect-process-statuses";
						if (tree.observeCompletion())
						{
							if (released == false && tree.terminationRequested() == false)
								throw new IOException("Trusted gate ended before workload release");
							result = new Result(tree.awaitRootExit(), tree.adoptedExits(), tree.terminationRequested());
							break;
						}
					}
				}
				catch (IOException failure)
				{
					if (managed == false)
						throw failure;
					if (Files.exists(file(scratch, Phase.FAULT)) == false)
					{
						Properties fault = new Properties();
						fault.setProperty(ROOT_PID, Long.toString(rootPid));
						fault.setProperty("action.group", tree.group().toString());
						fault.setProperty("operation", operation);
						fault.setProperty("message", failure.toString());
						publish(scratch, Phase.FAULT, attempt, fault);
						failure.printStackTrace();
					}
				}
				pause();
			}
		}
		catch (Exception | Error failure)
		{
			supervisionFailure = failure;
			throw failure;
		}
		finally
		{
			if (managed == false || collectionAuthorized || controllerFailure.get() != null)
				closeOwned(tree, supervisionFailure);
		}
		Properties values = new Properties();
		values.setProperty(ROOT_PID, Long.toString(rootPid));
		values.setProperty(ROOT_EXIT, Integer.toString(result.rootExit()));
		values.setProperty(TERMINATION, Boolean.toString(result.terminationRequested()));
		if (managed)
		{
			values.setProperty("action.group", tree.group().toString());
			values.setProperty("instrumentation.closed", "true");
		}
		for (var entry : result.adoptedExits().entrySet())
			values.setProperty(ADOPTED_PREFIX + entry.getKey(), entry.getValue().toString());
		publish(scratch, Phase.RESULT, attempt, values);
	}
	/**
	 * Closes the retained process owner at the permitted cleanup boundary.
	 * @param owner the owner whose cleanup is now permitted
	 * @param primary the earlier supervision failure, or null on ordinary completion
	 * @throws Exception if cleanup fails without an earlier supervision failure
	 */
	static void closeOwned(AutoCloseable owner, Throwable primary) throws Exception
	{
		try { owner.close(); }
		catch (Exception | Error cleanup)
		{
			if (primary == null)
				throw cleanup;
			if (primary != cleanup)
				primary.addSuppressed(cleanup);
		}
	}

}
