package com.holdmyspot.codexunleashed.scheduler;

import java.io.IOException;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;

/** Owns one instrumented workload, its measurement registration and retained native resources.
 * Only the constructing controller thread may call it. Genuine faults remain sticky even after safe cleanup.
 * Cancellation acknowledges a request; only authentic collected whole-tree results become queue outcomes.
 */
final class LinuxAttempt implements JobQueue.Attempt<LinuxSupervisor.Result>
{
	/** Exclusive calling thread. */
	private final Thread owner = Thread.currentThread();
	/** Borrowed factory and peer pump. */
	private final LinuxAttempts attempts;
	/** Borrowed started runtime. */
	private final FridaRuntime runtime;
	/** Borrowed opened authority. */
	private final LinuxAuthority authority;
	/** Stable action name. */
	private final String name;
	/** Queue-wide attempt ordinal. */
	private final long sequence;
	/** Native correlation UUID for this attempt. */
	private final UUID identity;
	/** Immutable workload configuration. */
	private final LinuxAttempts.Command command;
	/** Bound for each complete cancellation/collection operation. */
	private final Duration timeout;
	/** Shared native wait batches, never inferred from process memory. */
	private final AllocationWaits waits = new AllocationWaits();
	/** Supervisor retained before any instrumentation acquisition. */
	private LinuxSupervisor supervisor;
	/** Cleanup owner retained when prepare itself fails partially. */
	private LinuxSupervisor.PreparationFailure preparationFailure;
	/** Exact acknowledged registration, or null when none was returned. */
	private LinuxAuthority.Registration registration;
	/** Instrumentation owner retained before start. */
	private AllocationHooks hooks;
	/** One-time start reservation made before any acquisition. */
	private boolean startAttempted;
	/** Active queue control blocks publication of this attempt's next batch. */
	private boolean controlling;
	/** Terminal grant-authority withdrawal. */
	private boolean stopping;
	/** Whether instrumentation closed and its COLLECT acknowledgement was published successfully. */
	private boolean instrumentationClosed;
	/** Successful exclusive cleanup. */
	private boolean closed;
	/** First operational fault; never cleared by status collection. */
	private IOException failure;
	/** First cleanup fault, retained even after later observations. */
	private IOException closeFailure;
	/** Authentic accepted queue outcome, never fabricated during close. */
	private JobQueue.Outcome<LinuxSupervisor.Result> outcome;

	/** Validates resource-free identity/configuration and borrows build owners.
	 * @param attempts peer coordinator
	 * @param runtime started runtime
	 * @param authority opened authority
	 * @param name stable action
	 * @param attemptSequence queue ordinal
	 * @param identity native correlation
	 * @param command immutable workload configuration
	 * @param timeout positive control bound
	 */
	LinuxAttempt(LinuxAttempts attempts, FridaRuntime runtime, LinuxAuthority authority, String name,
		long attemptSequence, UUID identity, LinuxAttempts.Command command, Duration timeout)
	{
		this.attempts = Objects.requireNonNull(attempts, "attempts");
		this.runtime = Objects.requireNonNull(runtime, "runtime");
		this.authority = Objects.requireNonNull(authority, "authority");
		new LinuxAttempts.Blocked(name, attemptSequence, 0);
		this.name = name;
		sequence = attemptSequence;
		this.identity = Objects.requireNonNull(identity, "identity");
		this.command = Objects.requireNonNull(command, "command");
		this.timeout = LinuxAttempts.checkedTimeout(timeout);
	}

	/** Returns the configured action name.
	 * @return stable action identity
	 */
	String name() { requireOwner(); return name; }
	/** Returns this owner's queue identity.
	 * @return queue attempt ordinal
	 */
	long sequence() { requireOwner(); return sequence; }

	/** Starts the configured contained instrumented workload once.
	 * @throws IOException if acquisition, registration or native release fails
	 */
	@Override
	public void start() throws IOException
	{
		requireOwner();
		requireCertain();
		if (startAttempted || stopping || closed)
			throw new IllegalStateException("Attempt cannot be started again");
		startAttempted = true;
		controlling = true;
		try
		{
			try { supervisor = LinuxSupervisor.prepareInstrumented(command.launch(), command.files(), timeout); }
			catch (LinuxSupervisor.PreparationFailure cause) { preparationFailure = cause; throw cause; }
			registration = authority.register(name, sequence, supervisor);
			receipt("registered root=" + supervisor.rootPid() + " supervisor=" + supervisor.pid() + " group=" + supervisor.directory());
			hooks = new AllocationHooks(identity, runtime, command.launch(), supervisor, waits, timeout, attempts::pumpAll);
			hooks.start();
		}
		catch (IOException | RuntimeException cause) { throw failed("Start", cause); }
		catch (Error cause) { failed("Start", cause); throw cause; }
		finally { controlling = false; }
	}

	/** Acknowledges exactly the selected frozen allocation batch.
	 * @param waitSequence published batch ordinal
	 * @throws IOException if any permission has uncertain effects
	 */
	@Override
	public void resume(long waitSequence) throws IOException
	{
		requireOwner();
		requireCertain();
		if (hooks == null || stopping || controlling || closed)
			throw new IllegalStateException("Attempt cannot resume allocation waits");
		controlling = true;
		try { waits.resume(waitSequence, hooks::grant); }
		catch (IOException | RuntimeException cause) { throw failed("Allocation continuation", cause); }
		catch (Error cause) { failed("Allocation continuation", cause); throw cause; }
		finally { controlling = false; }
	}

	/** Withdraws grants and requests whole-workload cancellation.
	 * @throws IOException if cancellation cannot be acknowledged
	 */
	@Override
	public void terminate() throws IOException
	{
		requireOwner();
		stopGrants();
		if (supervisor != null)
		{
			try { supervisor.requestTermination(); }
			catch (IOException | RuntimeException cause) { throw failed("Cancellation request", cause); }
			catch (Error cause) { failed("Cancellation request", cause); throw cause; }
		}
	}

	/** Services copied frames without entering JobQueue or publishing while a queue control is active.
	 * @throws IOException if a previously retained or new delivery fault is observed
	 */
	void pumpEvents() throws IOException
	{
		requireOwner();
		requireCertain();
		if (hooks == null || stopping || instrumentationClosed || closed)
			return;
		try { hooks.pump(); }
		catch (IOException | RuntimeException cause) { throw failed("Native event delivery", cause); }
		catch (Error cause) { failed("Native event delivery", cause); throw cause; }
	}

	/** Publishes one batch only outside start/resume.
	 * @return next queue batch, or empty
	 * @throws IOException if prior continuation is uncertain
	 */
	OptionalLong pollAllocationWait() throws IOException
	{
		requireOwner();
		if (controlling || stopping || hooks == null || closed)
			return OptionalLong.empty();
		requireCertain();
		return waits.poll();
	}

	/** Observes only authentic complete collected results, preserving first faults.
	 * @return accepted outcome, or empty while whole-tree collection is incomplete
	 * @throws IOException if any retained fault prevents accepting an outcome
	 */
	@Override
	public Optional<JobQueue.Outcome<LinuxSupervisor.Result>> poll() throws IOException
	{
		requireOwner();
		requireCertain();
		if (outcome != null)
			return Optional.of(outcome);
		if (supervisor == null)
			return Optional.empty();
		try
		{
			supervisor.observeCompletion();
			if (supervisor.observeExecutionImpossible() == false)
			{
				pumpEvents();
				return Optional.empty();
			}
			closeInstrumentation();
			if (supervisor.observeCompletion() == false)
				return Optional.empty();
			LinuxSupervisor.Result result = supervisor.result();
			receipt("accepted-result " + result);
			outcome = new JobQueue.Outcome<>(result, result.terminationRequested());
			return Optional.of(outcome);
		}
		catch (IOException | RuntimeException cause) { throw failed("Whole-job observation", cause); }
		catch (Error cause) { failed("Whole-job observation", cause); throw cause; }
	}

	/** Withdraws grant authority before termination, without waking native waiters. */
	private void stopGrants()
	{
		stopping = true;
		waits.stop();
		if (hooks != null)
			hooks.stop();
	}

	/** Closes instrumentation only after actual subtree zero, then publishes COLLECT.
	 * @throws IOException if zero or complete native destruction cannot be established
	 */
	private void closeInstrumentation() throws IOException
	{
		if (instrumentationClosed)
			return;
		stopGrants();
		if (supervisor.observeExecutionImpossible() == false)
			throw new IOException("Instrumentation retains ownership while workload can execute");
		receipt("actual-zero group=" + supervisor.directory());
		if (hooks != null)
			hooks.close(supervisor::observeExecutionImpossible);
		receipt("instrumentation-closed");
		supervisor.finishInstrumentation();
		instrumentationClosed = true;
		receipt("collect-published");
	}

	/** Performs bounded complete cleanup without inventing a queue outcome.
	 * @throws IOException if ownership remains unresolved or a genuine fault was retained
	 */
	@Override
	public void close() throws IOException
	{
		requireOwner();
		if (closeFailure != null)
			throw new IOException("Attempt cleanup remains unresolved", closeFailure);
		if (closed)
			return;
		try
		{
			stopGrants();
			if (preparationFailure != null)
				preparationFailure.close();
			if (supervisor != null)
			{
				if (outcome == null)
				{
					supervisor.requestTermination();
					long started = System.nanoTime();
					while (supervisor.observeExecutionImpossible() == false)
					{
						if (System.nanoTime() - started >= timeout.toNanos())
							throw new IOException("Attempt cleanup awaits actual workload zero");
						pause();
					}
				}
				closeInstrumentation();
				// The supervisor's close collects authentic receipts even after its sticky supervision fault.
				// Unregister must precede removal of final owner storage and the next measurement batch.
				long started = System.nanoTime();
				if (failure == null)
					while (supervisor.observeCompletion() == false)
					{
						if (System.nanoTime() - started >= timeout.toNanos())
							throw new IOException("Attempt cleanup awaits collected whole-job statuses");
						pause();
					}
				closeCollectedOwners(failure != null, () ->
				{
					supervisor.close();
					receipt("cleanup-result " + supervisor.result());
				}, () ->
				{
					if (registration != null)
					{
						registration.close();
						registration = null;
						receipt("unregistered");
					}
				});
			}
			attempts.forget(this);
			closed = true;
		}
		catch (IOException | RuntimeException cause)
		{
			closeFailure = failed("Cleanup", cause);
			throw closeFailure;
		}
		catch (Error cause) { closeFailure = failed("Cleanup", cause); throw cause; }
		if (failure != null)
			throw failure;
	}

	/** Orders real owner closure after instrumentation COLLECT; fault cleanup needs the supervisor's private receipt path.
	 * @param fault whether genuine fault prevents public completion observation
	 * @param supervisor retained supervisor closure
	 * @param registration exact registration closure, or an empty operation if no handle was returned
	 * @throws IOException if an owner cannot close; later dependent closure is not attempted
	 */
	static void closeCollectedOwners(boolean fault, Close supervisor, Close registration) throws IOException
	{
		if (fault)
		{
			supervisor.close();
			registration.close();
		}
		else
		{
			registration.close();
			supervisor.close();
		}
	}

	/** One checked owner-release operation; it supplies no fabricated process outcome. */
	@FunctionalInterface
	interface Close
	{
		/** Releases an already retained owner.
		 * @throws IOException if ownership remains unresolved
		 */
		void close() throws IOException;
	}

	/** Waits without discarding caller interruption.
	 * @throws IOException if interrupted
	 */
	private static void pause() throws IOException
	{
		try { Thread.sleep(10); }
		catch (InterruptedException cause)
		{
			Thread.currentThread().interrupt();
			throw new IOException("Interrupted while retaining unfinished attempt", cause);
		}
	}

	/** Retains the first cause and suppresses later independent failures.
	 * @param operation failed operation
	 * @param cause original cause
	 * @return retained contextual fault
	 */
	private IOException failed(String operation, Throwable cause)
	{
		if (failure == null)
			failure = new IOException(operation + " failed for " + name + "/" + sequence, cause);
		else if (cause != failure)
			failure.addSuppressed(cause);
		return failure;
	}

	/** Prevents automatic controls after the first genuine failure.
	 * @throws IOException if an earlier operation has uncertain effects
	 */
	private void requireCertain() throws IOException
	{
		if (failure != null)
			throw new IOException("Attempt retains an earlier failure", failure);
	}

	/** Emits bounded lifecycle evidence without including workload arguments or environment secrets.
	 * @param event accepted ownership transition
	 */
	private void receipt(String event)
	{
		System.getLogger(LinuxAttempt.class.getName()).log(System.Logger.Level.INFO,
			"Linux attempt " + name + "/" + sequence + " " + event);
	}

	/** Requires single-threaded ownership before effects. */
	private void requireOwner()
	{
		if (Thread.currentThread() != owner)
			throw new IllegalStateException("Linux attempt belongs to its constructing thread");
	}
}
