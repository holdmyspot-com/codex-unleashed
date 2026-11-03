package com.holdmyspot.codexunleashed.scheduler;

import java.io.IOException;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;

/** Owns one Cargo compiler attempt: the wrapper's held child, its containment, registration and instrumentation.
 * <p>
 * Start waits for the wrapper's report of the held child, contains and registers it, retains its tokens, attaches
 * instrumentation and only then releases the child. The outcome is the wrapper's report of the child's wait status,
 * accepted once the containment is empty so that no descendant can still execute. Termination kills the whole
 * containment; the wrapper reaps the child and reports its exit. An outcome that termination aborted makes the
 * wrapper retry; the final status of any other outcome is sent by the session.
 * <p>
 * Only the constructing controller thread may call it. Genuine faults remain sticky even after safe cleanup.
 * Cancellation acknowledges a request; only authentic collected whole-tree results become queue outcomes.
 */
public final class LinuxAttempt implements JobQueue.Attempt<LinuxSupervisor.Result>
{
	/** Exclusive calling thread. */
	private final Thread owner = Thread.currentThread();
	/** Borrowed factory and peer pump. */
	private final LinuxAttempts attempts;
	/** Borrowed acquisition of native owners. */
	private final LinuxAttempts.Natives natives;
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
	/** Containment retained before any instrumentation acquisition. */
	private LinuxAttempts.Containment containment;
	/** Instrumentation owner retained before start. */
	private LinuxAttempts.Instrumentation hooks;
	/** Whole-attempt token owner retained before the child is released. */
	private LinuxCargoTokens.Attempt tokens;
	/** One-time start reservation made before any acquisition. */
	private boolean startAttempted;
	/** Active queue control blocks publication of this attempt's next batch. */
	private boolean controlling;
	/** Terminal grant-authority withdrawal. */
	private boolean stopping;
	/** Whether this attempt asked its containment to kill the workload. */
	private boolean terminationRequested;
	/** Whether instrumentation closed and token ownership was retired. */
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
	 * @param natives borrowed acquisition of native owners
	 * @param name stable action
	 * @param attemptSequence queue ordinal
	 * @param identity native correlation
	 * @param command immutable workload configuration
	 * @param timeout positive control bound
	 */
	LinuxAttempt(LinuxAttempts attempts, LinuxAttempts.Natives natives, String name, long attemptSequence, UUID identity,
		LinuxAttempts.Command command, Duration timeout)
	{
		this.attempts = Objects.requireNonNull(attempts, "attempts");
		this.natives = Objects.requireNonNull(natives, "natives");
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

	/** Contains, registers and instruments the wrapper's held child, then releases it.
	 * A failure before the release aborts the held child instead.
	 * @throws IOException if the child report, containment, registration, instrumentation or release fails
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
		LinuxCargoWrapperConnection wrapper = command.wrapper();
		try
		{
			LinuxCargoProtocol.Child child = wrapper.awaitChild();
			long parent = wrapper.peer().pid();
			containment = natives.contain(name, sequence, command.launch(), child, parent);
			receipt("contained root=" + child.pid() + " wrapper=" + parent + " group=" + containment.directory());
			tokens = natives.retainTokens();
			var root = new AllocationHooks.Root(child.pid(), child.startTime(), parent, containment.directory());
			hooks = natives.instrument(identity, command.launch(), root, tokens, waits, attempts::pumpAll);
			hooks.start(wrapper::release);
			if (wrapper.state() != LinuxCargoWrapperConnection.State.RELEASED)
				throw new IOException("Instrumentation did not release the held attempt root");
		}
		catch (IOException | RuntimeException cause)
		{
			IOException failed = failed("Start", cause);
			abortHeld(failed);
			throw failed;
		}
		catch (Error cause) { failed("Start", cause); abortHeld(cause); throw cause; }
		finally { controlling = false; }
	}

	/** Makes the wrapper kill a child that is still held, so that it never executes.
	 * @param cause start failure that receives any abort failure as suppressed
	 */
	private void abortHeld(Throwable cause)
	{
		LinuxCargoWrapperConnection wrapper = command.wrapper();
		try
		{
			if (!wrapper.isClosed() && wrapper.state() == LinuxCargoWrapperConnection.State.HELD)
				wrapper.abort();
		}
		catch (IOException | RuntimeException abort) { cause.addSuppressed(abort); }
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

	/** Withdraws grants and kills every process of the attempt through its containment.
	 * @throws IOException if cancellation cannot be acknowledged
	 */
	@Override
	public void terminate() throws IOException
	{
		requireOwner();
		stopGrants();
		if (containment != null)
		{
			terminationRequested = true;
			try { containment.terminate(); }
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

	/** Observes the wrapper's report of the child's exit and accepts it once no process of the attempt can execute.
	 * An aborted outcome, one that termination stopped without success, makes the wrapper retry.
	 * @return accepted outcome, or empty while whole-tree execution can still occur
	 * @throws IOException if the wrapper connection is lost or any retained fault prevents accepting an outcome
	 */
	@Override
	public Optional<JobQueue.Outcome<LinuxSupervisor.Result>> poll() throws IOException
	{
		requireOwner();
		requireCertain();
		if (outcome != null)
			return Optional.of(outcome);
		if (containment == null)
			return Optional.empty();
		LinuxCargoWrapperConnection wrapper = command.wrapper();
		try
		{
			wrapper.pump();
			if (wrapper.state() != LinuxCargoWrapperConnection.State.EXITED || containment.isPopulated())
			{
				if (wrapper.isClosed())
					throw new IOException("Cargo wrapper connection was lost before the attempt exited");
				pumpEvents();
				return Optional.empty();
			}
			Throwable tokenFailure = closeInstrumentation();
			if (tokenFailure instanceof Error cause)
				throw cause;
			if (failure != null)
				throw failure;
			var result = new LinuxSupervisor.Result(LinuxCargoProtocol.exitStatus(wrapper.waitStatus()), Map.of(),
				terminationRequested);
			receipt("accepted-result " + result);
			JobQueue.Outcome<LinuxSupervisor.Result> accepted = new JobQueue.Outcome<>(result, result.aborted());
			if (accepted.aborted())
				wrapper.retry();
			outcome = accepted;
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

	/** Closes instrumentation and retires token ownership once no process of the attempt can execute.
	 * @return the token retirement fault, or null if token retirement succeeded
	 * @throws IOException if zero or complete native destruction cannot be established
	 */
	private Throwable closeInstrumentation() throws IOException
	{
		if (instrumentationClosed)
			return null;
		stopGrants();
		if (containment.isPopulated())
			throw new IOException("Instrumentation retains ownership while workload can execute");
		receipt("actual-zero group=" + containment.directory());
		Throwable tokenFailure = closeInstrumentationOwners(() ->
		{
			if (hooks != null)
				hooks.close(() -> !containment.isPopulated());
			receipt("instrumentation-closed");
		}, () ->
		{
			if (tokens != null) tokens.close();
		}, () -> instrumentationClosed = true);
		if (tokenFailure != null)
			failed("Cargo token retirement", tokenFailure);
		return tokenFailure;
	}
	/**
	 * Runs instrumentation closure, logical token retirement and supervisor collection in their established order.
	 * The caller must first establish whole-workload execution impossibility. Token retirement has no native
	 * attachment ownership and must retire its logical owner even when it reports an exception or error.
	 * A token retirement fault is returned only after collection succeeds. If checked collection also fails, the
	 * original token fault is thrown with the collection fault suppressed. An unchecked collection failure keeps
	 * its original type and identity, with the token fault suppressed instead.
	 * The token operation may be empty when the invocation has no extra-token owner.
	 * @param instrumentation closes native attachments completely; failure retains them and prevents later operations
	 * @param tokens retires logical token ownership after attachments close
	 * @param collect publishes COLLECT and records its actual acknowledgement
	 * @return the original token retirement fault after successful collection, or null if retirement succeeded
	 * @throws NullPointerException if an operation is null
	 * @throws IOException if native attachment closure or collection fails
	 * @throws RuntimeException if attachment closure or collection fails unexpectedly, or collection fails after an
	 * unchecked token fault
	 * @throws Error if attachment closure or collection fails fatally, or collection fails after a fatal token fault
	 */
	public static Throwable closeInstrumentationOwners(Close instrumentation, Close tokens, Close collect) throws IOException
	{
		Objects.requireNonNull(instrumentation, "instrumentation");
		Objects.requireNonNull(tokens, "tokens");
		Objects.requireNonNull(collect, "collect");
		instrumentation.close();
		Throwable tokenFailure = null;
		try { tokens.close(); }
		catch (IOException | RuntimeException | Error cause) { tokenFailure = cause; }
		try { collect.close(); }
		catch (IOException cause)
		{
			if (tokenFailure == null)
				throw cause;
			if (tokenFailure != cause)
				tokenFailure.addSuppressed(cause);
			switch (tokenFailure)
			{
				case IOException original -> throw original;
				case RuntimeException original -> throw original;
				case Error original -> throw original;
				default -> throw new IllegalStateException("Unexpected token retirement failure", tokenFailure);
			}
		}
		catch (RuntimeException | Error cause)
		{
			boolean distinctTokenFailure = tokenFailure != null && tokenFailure != cause;
			if (distinctTokenFailure)
				cause.addSuppressed(tokenFailure);
			throw cause;
		}
		return tokenFailure;
	}

	/**
	 * Performs bounded complete cleanup without inventing a queue outcome.
	 * <p>
	 * An attempt without an accepted outcome is killed through its containment first; the wrapper reaps its child.
	 * An attempt with an outcome is already empty, and its wrapper is left waiting for its retry or final status.
	 * A fatal logical token-retirement fault is deferred until dependent owner cleanup has been attempted.
	 * @throws IOException if ownership remains unresolved or a genuine fault was retained
	 * @throws Error if cleanup fails fatally or token retirement reported a fatal fault
	 */
	@Override
	public void close() throws IOException
	{
		requireOwner();
		if (closeFailure != null)
			throw new IOException("Attempt cleanup remains unresolved", closeFailure);
		if (closed)
			return;
		Throwable tokenFailure = null;
		try
		{
			stopGrants();
			if (containment != null)
			{
				if (outcome == null)
				{
					terminationRequested = true;
					containment.terminate();
					long started = System.nanoTime();
					while (containment.isPopulated())
					{
						if (System.nanoTime() - started >= timeout.toNanos())
							throw new IOException("Attempt cleanup awaits actual workload zero");
						pause();
					}
				}
				tokenFailure = closeInstrumentation();
				containment.close();
				receipt("containment-closed");
			}
			attempts.forget(this);
			closed = true;
		}
		catch (IOException | RuntimeException cause)
		{
			closeFailure = failed("Cleanup", cause);
			if (tokenFailure instanceof Error fatal)
			{
				fatal.addSuppressed(cause);
				throw fatal;
			}
			throw closeFailure;
		}
		catch (Error cause) { closeFailure = failed("Cleanup", cause); throw cause; }
		if (tokenFailure instanceof Error cause)
			throw cause;
		if (failure != null)
			throw failure;
	}

	/** One checked owner-release operation; it supplies no fabricated process outcome. */
	@FunctionalInterface
	public interface Close
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
