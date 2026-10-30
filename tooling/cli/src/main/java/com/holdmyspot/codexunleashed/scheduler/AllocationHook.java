package com.holdmyspot.codexunleashed.scheduler;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashSet;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.locks.LockSupport;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;
import tools.jackson.core.StreamReadConstraints;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.core.json.JsonFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Owns one verified process image's allocation script and attachment on its constructing thread.
 * <p>
 * The attempt must establish process identity and containment before starting this owner. Its native connection enables
 * child gating; the attempt must service the borrowed manager's gated-child events before releasing owned children. Its selected
 * batch supplies grants; this owner verifies observed installation membership, posts once and requires an exact native
 * acknowledgement. It borrows the manager, shared wait collection and peer pump. The attempt must stop admissions and
 * establish that the entire owned workload cannot execute before permitting instrumentation destruction.
 * <p>
 * Acknowledged permission permits one native retry and does not establish allocation success. Callback errors, script
 * faults, uncertain posts and control deadlines permanently withdraw automatic permission authority. A silent lost
 * blocked frame is not detected by empty polling; this owner makes no loss-free transport guarantee.
 */
final class AllocationHook
{
	/**
	 * Same copied-message bound as the allocation codec, enforced before inspecting Frida error frames.
	 */
	private static final int MAX_MESSAGE_CHARACTERS = 8192;
	/**
	 * Fair per-installation drain limit; the coordinator services other live installations between rounds.
	 */
	private static final int MESSAGES_PER_PUMP = 64;
	/**
	 * Maximum idle delay between owner-thread control observations, in nanoseconds.
	 */
	private static final long POLL_NANOS = 1_000_000;
	/**
	 * Packaged Linux program; no scratch path or external binding JAR participates in installation.
	 */
	private static final String LINUX_RESOURCE = "linux-allocation-hook.js";
	/**
	 * Bounded parser for generated controls and Frida error envelopes; normal frames still use the strict codec.
	 */
	private static final JsonMapper JSON = JsonMapper.builder(JsonFactory.builder()
		.enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
		.streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(4).maxStringLength(MAX_MESSAGE_CHARACTERS)
			.maxDocumentLength(MAX_MESSAGE_CHARACTERS).build()).build()).build();
	/**
	 * Thread exclusively owning Java transitions and native operations.
	 */
	private final Thread owner = Thread.currentThread();
	/**
	 * Owned connection retains each partial native acquisition until safe cleanup.
	 */
	private final Connection connection;
	/**
	 * Borrowed attempt-wide batching state, never stopped or cleared by this installation.
	 */
	private final AllocationWaits waits;
	/**
	 * Resource-free exact readiness and posted-permission acknowledgement owner.
	 */
	private final AllocationPermissions permissions;
	/**
	 * Waits observed from this installation; a caller cannot grant an unreported native identity.
	 */
	private final Set<AllocationWaits.Wait> observed = new HashSet<>();
	/**
	 * Complete program with Jackson-serialized immutable identity initialization.
	 */
	private final String source;
	/**
	 * Positive startup/acknowledgement time budget, in monotonic nanoseconds; never a build runtime limit.
	 */
	private final long timeoutNanos;
	/**
	 * Monotonic time source, with System.nanoTime at the native composition boundary.
	 */
	private final LongSupplier nanoTime;
	/**
	 * Start is attempted at most once, including acquisition failures.
	 */
	private boolean startAttempted;
	/**
	 * Native start returned; readiness remains a separate required protocol event.
	 */
	private boolean started;
	/**
	 * Permanent control-authority withdrawal, independent of native ownership release.
	 */
	private boolean stopped;
	/**
	 * True only after native destruction returns successfully.
	 */
	private boolean closed;
	/**
	 * First operational failure retained for later diagnostics and control rejection.
	 */
	private IOException failure;
	/**
	 * First uncertain native cleanup failure; a later no-op cannot erase it.
	 */
	private IOException closeFailure;

	/**
	 * Creates a resource-free native owner; the caller retains manager lifetime and process verification responsibility.
	 * @param attempt correlation identity unique to this workload attempt
	 * @param installation positive generation never reused within the attempt
	 * @param pid already verified positive unsigned-32-bit process identity
	 * @param manager borrowed active manager on this thread
	 * @param waits borrowed attempt-wide batching collection
	 * @param program platform allocation resource source without embedded NUL characters
	 * @param timeout positive, signed-nanosecond startup and permission acknowledgement limit
	 * @throws NullPointerException if a reference input is null
	 * @throws IllegalArgumentException if an identity, source or duration is invalid
	 */
	AllocationHook(UUID attempt, long installation, long pid, FridaRuntime.Manager manager, AllocationWaits waits,
		String program, Duration timeout)
	{
		this(attempt, installation, pid, new NativeConnection(manager, pid), waits, program, timeout, System::nanoTime);
	}

	/**
	 * Binds protocol and lifecycle decisions to an owned connection without acquiring native resources.
	 * @param attempt correlation identity unique to this workload attempt
	 * @param installation positive generation never reused within the attempt
	 * @param pid verified positive unsigned-32-bit process identity
	 * @param connection resource-free transport whose ownership transfers after construction succeeds
	 * @param waits borrowed attempt-wide batching collection
	 * @param program platform allocation resource source without embedded NUL characters
	 * @param timeout positive, signed-nanosecond startup and permission acknowledgement limit
	 * @param nanoTime monotonic nanosecond source used only for elapsed control waits
	 * @throws NullPointerException if a reference input is null
	 * @throws IllegalArgumentException if an identity, source or duration is invalid
	 */
	AllocationHook(UUID attempt, long installation, long pid, Connection connection, AllocationWaits waits,
		String program, Duration timeout, LongSupplier nanoTime)
	{
		AllocationMessages messages = new AllocationMessages(attempt, installation, pid);
		this.connection = Objects.requireNonNull(connection, "connection may not be null");
		this.waits = Objects.requireNonNull(waits, "waits may not be null");
		this.nanoTime = Objects.requireNonNull(nanoTime, "nanoTime may not be null");
		Objects.requireNonNull(program, "program may not be null");
		Objects.requireNonNull(timeout, "timeout may not be null");
		if (program.indexOf('\0') >= 0)
			throw new IllegalArgumentException("Allocation program may not contain NUL characters");
		try
		{
			this.timeoutNanos = timeout.toNanos();
		}
		catch (ArithmeticException cause)
		{
			throw new IllegalArgumentException("Allocation control timeout must fit signed nanoseconds", cause);
		}
		if (timeoutNanos <= 0)
			throw new IllegalArgumentException("Allocation control timeout must be positive");
		this.source = "const allocationIdentity = " + JSON.createObjectNode().put("attempt", attempt.toString())
			.put("installation", Long.toString(installation)).put("pid", Long.toString(pid)) + ";\n" + program;
		this.permissions = new AllocationPermissions(messages, permission -> connection.post(JSON.readTree(permission)));
	}

	/**
	 * Reads the module's Linux source before installation; loading it performs no native operation.
	 * @return complete packaged source
	 * @throws IOException if the module resource is missing or unreadable
	 */
	static String linuxProgram() throws IOException
	{
		try (InputStream input = AllocationHook.class.getResourceAsStream(LINUX_RESOURCE))
		{
			if (input == null)
				throw new IOException("Packaged Linux allocation hook is missing: " + LINUX_RESOURCE);
			return new String(input.readAllBytes(), StandardCharsets.UTF_8);
		}
	}

	/**
	 * Attempts native attachment and script loading once; partial acquisition remains owned after failure.
	 * @throws IllegalStateException if called off-thread or start was already attempted
	 * @throws IOException if stopped, failed or native acquisition/loading fails
	 */
	void start() throws IOException
	{
		requireOwner();
		requireAuthority();
		if (startAttempted)
			throw new IllegalStateException("Allocation hook start may be attempted once");
		startAttempted = true;
		try
		{
			connection.start(source);
			started = true;
		}
		catch (IOException | RuntimeException cause)
		{
			throw failed(new IOException("Starting the native allocation hook failed", cause));
		}
		catch (Error cause)
		{
			failed(new IOException("Starting the native allocation hook did not complete", cause));
			throw cause;
		}
	}

	/**
	 * Awaits this held installation's readiness while servicing copied messages and other live attempts.
	 * @param peers owner-thread operation servicing all other live attempts and held children without queue reentry
	 * @throws NullPointerException if {@code peers} is null
	 * @throws IllegalStateException if called off-thread or before successful native start
	 * @throws IOException if delivery, peer service or the readiness deadline fails
	 */
	void awaitReady(Pump peers) throws IOException
	{
		await(permissions::ready, peers, "Native allocation readiness deadline exceeded");
	}

	/**
	 * Accepts at most 64 copied messages in order, reporting authentic denials to the shared batching owner.
	 * @throws IllegalStateException if called off-thread or before successful native start
	 * @throws IOException if stopped, previously failed, decoding fails or native delivery reports failure
	 */
	void pump() throws IOException
	{
		requireStarted();
		try
		{
			for (int count = 0; count < MESSAGES_PER_PUMP; count++)
			{
				String frame = connection.pollMessage().orElse(null);
				if (frame == null)
					return;
				if (frame.length() > MAX_MESSAGE_CHARACTERS)
					throw new IOException("Native allocation message exceeds " + MAX_MESSAGE_CHARACTERS + " characters");
				JsonNode envelope = JSON.readTree(frame);
				if (envelope != null && envelope.path("type").isString() && envelope.path("type").asString().equals("error"))
					throw new IOException("Native allocation script error: " + envelope.path("description").asString() +
						"; stack=" + envelope.path("stack").asString());
				AllocationMessages.Event event = permissions.read(frame);
				if (event instanceof AllocationMessages.Blocked blocked)
				{
					observed.add(blocked.identity());
					waits.blocked(blocked.identity());
				}
			}
		}
		catch (IOException | RuntimeException cause)
		{
			throw failed(new IOException("Native allocation delivery failed: " + cause.getMessage(), cause));
		}
		catch (Error cause)
		{
			failed(new IOException("Native allocation delivery did not complete", cause));
			throw cause;
		}
	}

	/**
	 * Posts one observed wait selected by the caller's frozen AllocationWaits batch and awaits its exact accepted response.
	 * A throwing post is never retried. Newly reported denials remain in the shared collection's following batch.
	 * @param wait native identity selected by the owning attempt's current frozen batch
	 * @param peers owner-thread pump for peers and held children without JobQueue reentry
	 * @throws NullPointerException if an input is null
	 * @throws IllegalStateException if called off-thread or before native start
	 * @throws IOException if the wait was not observed here, permission/delivery fails or its acknowledgement times out
	 */
	void grant(AllocationWaits.Wait wait, Pump peers) throws IOException
	{
		Objects.requireNonNull(wait, "wait may not be null");
		Objects.requireNonNull(peers, "peers may not be null");
		requireStarted();
		if (observed.contains(wait) == false)
			throw failed(new IOException("Allocation permission requires an observed wait from this installation: " + wait));
		try
		{
			permissions.grant(wait);
			await(() -> permissions.acknowledged(wait), peers, "Native allocation permission acknowledgement deadline exceeded: " + wait);
		}
		catch (IOException | RuntimeException cause)
		{
			throw failed(new IOException("Native allocation permission failed: " + wait, cause));
		}
		catch (Error cause)
		{
			failed(new IOException("Native allocation permission did not complete: " + wait, cause));
			throw cause;
		}
	}

	/**
	 * Permanently withdraws grants without waking waiters or releasing native resources; repeated calls are harmless.
	 * The attempt independently stops its shared AllocationWaits before initiating whole-tree cancellation.
	 * @throws IllegalStateException if called off-thread
	 */
	void stop()
	{
		requireOwner();
		stopped = true;
		permissions.stop();
	}

	/**
	 * Withdraws grants, requires a trusted whole-workload execution barrier, then closes script and session.
	 * An unknown barrier retains native owners and permits another barrier observation. Native cleanup failure is latched;
	 * later calls cannot pretend cleanup succeeded. The borrowed manager, waits and runtime are never closed here.
	 * @param barrier trusted attempt-owned observation proving the whole contained workload cannot execute again
	 * @throws NullPointerException if {@code barrier} is null
	 * @throws IllegalStateException if called off-thread
	 * @throws IOException if the barrier is false/unknown or native cleanup fails
	 */
	void close(ExecutionBarrier barrier) throws IOException
	{
		Objects.requireNonNull(barrier, "barrier may not be null");
		requireOwner();
		if (closed)
			return;
		stop();
		if (closeFailure != null)
			throw new IOException("Native allocation cleanup remains unresolved", closeFailure);
		if (barrier.reached() == false)
			throw new IOException("Native allocation cleanup requires whole-workload execution to be impossible");
		try
		{
			connection.close();
			closed = true;
		}
		catch (IOException | RuntimeException cause)
		{
			closeFailure = new IOException("Native allocation cleanup failed", cause);
			throw closeFailure;
		}
		catch (Error cause)
		{
			closeFailure = new IOException("Native allocation cleanup did not complete", cause);
			throw cause;
		}
	}

	/**
	 * Returns the unresolved permission retained through uncertain post/delivery effects.
	 * @return posted native wait, or null before posting and after its matching accepted response
	 * @throws IllegalStateException if called off-thread
	 */
	AllocationWaits.Wait pending() { requireOwner(); return permissions.pending(); }

	/**
	 * Returns the first operational failure without clearing authority or native ownership state.
	 * @return first failure, or null before an operational failure; stop alone is not failure
	 * @throws IllegalStateException if called off-thread
	 */
	IOException failure() { requireOwner(); return failure; }

	/**
	 * Services a control wait without imposing a runtime limit on ordinary workload execution.
	 * @param complete owner-thread protocol completion observation
	 * @param peers borrowed fair peer service operation
	 * @param timeoutMessage contextual failure after the positive control limit
	 * @throws NullPointerException if {@code peers} is null
	 * @throws IllegalStateException if called off-thread or before native start
	 * @throws IOException if delivery/peer service fails, interruption occurs or the control deadline expires
	 */
	private void await(BooleanSupplier complete, Pump peers, String timeoutMessage) throws IOException
	{
		Objects.requireNonNull(peers, "peers may not be null");
		requireStarted();
		long began = nanoTime.getAsLong();
		try
		{
			while (complete.getAsBoolean() == false)
			{
				pump();
				peers.pump();
				requireAuthority();
				if (complete.getAsBoolean())
					return;
				if (Thread.currentThread().isInterrupted())
					throw new IOException("Interrupted while awaiting native allocation control");
				long elapsed = nanoTime.getAsLong() - began;
				if (elapsed >= timeoutNanos)
					throw new IOException(timeoutMessage);
				LockSupport.parkNanos(Math.min(POLL_NANOS, timeoutNanos - elapsed));
			}
		}
		catch (IOException | RuntimeException cause)
		{
			throw failed(new IOException("Native allocation control wait failed: " + cause.getMessage(), cause));
		}
		catch (Error cause)
		{
			failed(new IOException("Native allocation control wait did not complete", cause));
			throw cause;
		}
	}

	/**
	 * Retains the first operational error and withdraws permission authority before another native effect.
	 * @param cause operational failure to retain if no earlier failure exists
	 * @return first retained failure
	 */
	private IOException failed(IOException cause)
	{
		if (failure == null)
			failure = cause;
		permissions.transportFailed(failure);
		return failure;
	}

	/**
	 * Requires the native start result without inferring readiness from it.
	 * @throws IllegalStateException if called off-thread or before native start returns
	 * @throws IOException if authority has stopped or failed
	 */
	private void requireStarted() throws IOException
	{
		requireOwner();
		requireAuthority();
		if (started == false)
			throw new IllegalStateException("Allocation hook native start has not completed");
	}

	/**
	 * Rejects native controls after permanent stop or an earlier operational failure.
	 * @throws IOException if stopped, closed or previously failed
	 */
	private void requireAuthority() throws IOException
	{
		if (failure != null)
			throw new IOException("Native allocation authority is withdrawn", failure);
		if (stopped || closed)
			throw new IOException("Native allocation owner is stopped");
	}

	/**
	 * Requires all lifecycle and protocol transitions on the constructing thread.
	 * @throws IllegalStateException if called by another thread
	 */
	private void requireOwner()
	{
		if (Thread.currentThread() != owner)
			throw new IllegalStateException("Allocation hook belongs to its constructing thread");
	}

	/**
	 * Owned external connection boundary; callbacks only queue copied frames for later owner-thread polling.
	 * A failed operation retains partial native acquisition until close after the attempt's execution barrier.
	 */
	interface Connection
	{
		/**
		 * Attaches and loads the supplied program, retaining any partially acquired native owners.
		 * @param source complete identity-initialized program
		 * @throws IOException if native acquisition/loading fails
		 */
		void start(String source) throws IOException;
		/**
		 * Consumes one frame copied by the existing protected native receiver.
		 * @return next copied native frame, or empty when none is queued
		 * @throws IOException if callback delivery failed
		 */
		Optional<String> pollMessage() throws IOException;
		/**
		 * Posts one generated control without claiming native receipt.
		 * @param permission codec-produced JSON control
		 * @throws IOException if posting fails or has uncertain effects
		 */
		void post(JsonNode permission) throws IOException;
		/**
		 * Releases the owned script and attachment after the caller establishes the execution barrier.
		 * @throws IOException if owned native destruction fails; caller has already established the execution barrier
		 */
		void close() throws IOException;
	}

	/**

	 * Services peer installations and gated child events on the controller thread.

	 */
	@FunctionalInterface
	interface Pump
	{
		/**
		 * Services peers without delivering notifications into an active queue control.
		 * @throws IOException if peer service cannot establish a valid control observation
		 */
		void pump() throws IOException;
	}

	/**

	 * Trusted kernel-backed whole-workload observation, independent of root-only status and result reaping.

	 */
	@FunctionalInterface
	interface ExecutionBarrier
	{
		/**
		 * Observes the trusted whole-workload execution barrier while preserving unknown as failure.
		 * @return true only when owned workload execution is impossible
		 * @throws IOException if the observation is unknown
		 */
		boolean reached() throws IOException;
	}

	/**
	 * Owns only one manager attachment and script, retaining each before its first fallible native operation.
	 */
	private static final class NativeConnection implements Connection
	{
		/**
		 * Borrowed manager, never closed by this connection.
		 */
		private final FridaRuntime.Manager manager;
		/**
		 * Verified process identity supplied by the attempt before any native attachment.
		 */
		private final long pid;
		/**
		 * Owned partial or active attachment, null before reservation and after successful release.
		 */
		private FridaRuntime.Manager.Session session;
		/**
		 * Owned partial or active script, null before reservation and after successful release.
		 */
		private FridaRuntime.Manager.Session.Script script;

		/**
		 * Retains borrowed context without reserving native owners.
		 * @param manager active manager borrowed on the controller thread
		 * @param pid process identity whose ownership the attempt has verified
		 * @throws NullPointerException if {@code manager} is null
		 */
		private NativeConnection(FridaRuntime.Manager manager, long pid)
		{
			this.manager = Objects.requireNonNull(manager, "manager may not be null");
			this.pid = pid;
		}

		@Override
		public void start(String source) throws IOException
		{
			session = manager.newSession(pid);
			session.start();
			session.enableChildGating();
			script = session.newScript(source);
			script.start();
		}

		@Override
		public Optional<String> pollMessage() throws IOException { return script.pollMessage(); }

		@Override
		public void post(JsonNode permission) { script.post(permission); }

		@Override
		public void close() throws IOException
		{
			if (script != null)
			{
				script.close();
				script = null;
			}
			if (session != null)
			{
				session.close();
				session = null;
			}
		}
	}
}
