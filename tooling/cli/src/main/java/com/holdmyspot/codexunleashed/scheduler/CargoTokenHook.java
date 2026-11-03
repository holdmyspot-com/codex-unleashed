package com.holdmyspot.codexunleashed.scheduler;

import com.holdmyspot.codexunleashed.tooling.ToolingScope;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.locks.LockSupport;
import java.util.function.LongSupplier;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Owns one authenticated image's token script connection on its constructing thread.
 * <p>
 * The caller authenticates the held process and independently observes the invocation FIFO's retained descriptor
 * identity before construction. Native start does not establish readiness. Each read reserves an exact byte in the
 * borrowed whole-attempt ledger before its single grant; each write returns the exact byte before its single
 * acknowledgment. Pending reads never block servicing writes or peer images. Any uncertain control or delivery failure
 * permanently withdraws this installation's authority. A lost grant remains reserved until whole-attempt retirement.
 * <p>
 * The attempt retains this owner across image replacement and root exit. Native destruction requires the trusted
 * whole-workload execution barrier. Closing this image never retires its borrowed attempt, closes the FIFO, or closes
 * its tooling scope or manager. The attempt must retire its ledger capability after all descendants cannot execute.
 */
public final class CargoTokenHook
{
	/** Packaged Linux token mediation source, loaded before native installation. */
	private static final String LINUX_RESOURCE = "linux-cargo-tokens.js";
	/** Fair copied-message and successful-grant budget for each owner-thread pump. */
	private static final int OPERATIONS_PER_PUMP = 64;
	/** Maximum copied envelope length, shared with the strict protocol codec. */
	private static final int MAX_MESSAGE_CHARACTERS = 8192;
	/** Maximum readiness polling pause, in nanoseconds. */
	private static final long POLL_NANOS = 1_000_000;
	/** Exclusive lifecycle and FIFO transition thread. */
	private final Thread owner = Thread.currentThread();
	/** Scope-owned envelope mapper borrowed through all owner operations. */
	private final JsonMapper json;
	/** Actual exact native event decoder and control encoder. */
	private final CargoTokenMessages messages;
	/** Owned native connection, including partial acquisition after start failure. */
	private final AllocationHook.Connection connection;
	/** Whole-attempt ledger capability borrowed across this image's entire retained lifetime. */
	private final LinuxCargoTokens.Attempt tokens;
	/** Native reads waiting for nonblocking capacity in copied-message order. */
	private final ArrayDeque<CargoTokenMessages.Read> reads = new ArrayDeque<>();
	/** Complete native program with serialized immutable installation identity. */
	private final String source;
	/** Positive readiness budget in signed monotonic nanoseconds. */
	private final long timeoutNanos;
	/** Monotonic readiness observation clock. */
	private final LongSupplier nanoTime;
	/** Indicates whether native acquisition has been attempted, including failed attempts. */
	private boolean startAttempted = false;
	/** Indicates whether the native start operation returned successfully. */
	private boolean started = false;
	/** Indicates whether exact native readiness has been observed once. */
	private boolean ready = false;
	/** Last accepted transfer sequence; zero precedes the native sequence's first request. */
	private long sequence = 0;
	/** Permanent withdrawal independent of resource destruction. */
	private boolean stopped = false;
	/** Indicates whether native destruction returned successfully. */
	private boolean closed = false;
	/** First operational failure, retained through later observations and cleanup. */
	private IOException failure = null;
	/** First native destruction failure, which prevents a later no-op from claiming successful cleanup. */
	private IOException closeFailure = null;

	/**
	 * Creates a resource-free native image owner borrowing the authenticated attempt's ledger and manager.
	 * The caller retains the held image until readiness, services native gated children, and establishes whole-workload
	 * execution impossibility before closing this owner. Construction performs no native acquisition.
	 * @param scope borrowed shared values, retained open through cleanup
	 * @param attempt workload correlation identity
	 * @param installation positive generation never reused within this attempt
	 * @param pid authenticated unsigned-32-bit process identifier
	 * @param fifo independently observed identity of the invocation's retained FIFO descriptor
	 * @param manager active native manager borrowed on this thread through cleanup
	 * @param tokens borrowed whole-attempt token capability bound to this attempt's authenticated images
	 * @param program native program without embedded NUL characters
	 * @param timeout positive readiness duration representable in signed nanoseconds
	 * @throws NullPointerException if a reference input is null
	 * @throws IllegalArgumentException if an identity, source or readiness duration is invalid
	 */
	public CargoTokenHook(ToolingScope scope, UUID attempt, long installation, long pid, LinuxFifoBindings.Identity fifo,
		FridaRuntime.Manager manager, LinuxCargoTokens.Attempt tokens, String program, Duration timeout)
	{
		this(scope, attempt, installation, pid, fifo, AllocationHook.newNativeConnection(manager, pid), tokens, program,
			timeout, System::nanoTime);
	}

	/**
	 * Creates a resource-free image owner with borrowed attempt accounting and an owned connection.
	 * @param scope borrowed shared values, retained open through all owner operations
	 * @param attempt workload correlation identity
	 * @param installation positive generation never reused within this attempt
	 * @param pid authenticated unsigned-32-bit process identifier
	 * @param fifo independently observed identity of the invocation's retained FIFO descriptor
	 * @param connection resource-free transport whose ownership transfers after construction succeeds
	 * @param tokens borrowed whole-attempt token capability bound only to this attempt's authenticated images
	 * @param program native program without embedded NUL characters
	 * @param timeout positive readiness duration representable in signed nanoseconds
	 * @param nanoTime monotonic readiness clock
	 * @throws NullPointerException if a reference input is null
	 * @throws IllegalArgumentException if an identity, source or readiness duration is invalid
	 */
	public CargoTokenHook(ToolingScope scope, UUID attempt, long installation, long pid, LinuxFifoBindings.Identity fifo,
		AllocationHook.Connection connection, LinuxCargoTokens.Attempt tokens, String program, Duration timeout,
		LongSupplier nanoTime)
	{
		this.messages = new CargoTokenMessages(scope, attempt, installation, pid);
		this.json = scope.getAllocationEnvelopeMapper();
		this.connection = Objects.requireNonNull(connection, "connection may not be null");
		this.tokens = Objects.requireNonNull(tokens, "tokens may not be null");
		this.nanoTime = Objects.requireNonNull(nanoTime, "nanoTime may not be null");
		Objects.requireNonNull(fifo, "fifo may not be null");
		Objects.requireNonNull(program, "program may not be null");
		Objects.requireNonNull(timeout, "timeout may not be null");
		if (program.indexOf('\0') >= 0)
			throw new IllegalArgumentException("Cargo token program may not contain NUL characters");
		try { this.timeoutNanos = timeout.toNanos(); }
		catch (ArithmeticException cause)
		{
			throw new IllegalArgumentException("Cargo token readiness duration must fit signed nanoseconds", cause);
		}
		if (timeoutNanos <= 0)
			throw new IllegalArgumentException("Cargo token readiness duration must be positive");
		this.source = "const cargoTokenIdentity = " + json.createObjectNode().put("attempt", attempt.toString())
			.put("installation", Long.toString(installation)).put("pid", Long.toString(pid))
			.put("fifo", fifo.major() + ":" + fifo.minor() + ":" + Long.toUnsignedString(fifo.inode())) + ";\n" + program;
	}

	/**
	 * Reads the packaged Linux mediation source without acquiring native resources.
	 * @return complete packaged source
	 * @throws IOException if the module resource is missing or unreadable
	 */
	static String linuxProgram() throws IOException
	{
		try (InputStream input = CargoTokenHook.class.getResourceAsStream(LINUX_RESOURCE))
		{
			if (input == null)
				throw new IOException("Packaged Linux Cargo token hook is missing: " + LINUX_RESOURCE);
			return new String(input.readAllBytes(), StandardCharsets.UTF_8);
		}
	}

	/**
	 * Attempts native start once, retaining partial acquisitions after any failure.
	 * @throws IllegalStateException if called off-thread or after a previous start attempt
	 * @throws IOException if stopped or native acquisition fails
	 */
	public void start() throws IOException
	{
		requireOwner();
		requireAuthority();
		if (startAttempted)
			throw new IllegalStateException("Cargo token hook start may be attempted once");
		startAttempted = true;
		try { connection.start(source); started = true; }
		catch (IOException | RuntimeException | Error cause)
		{
			IOException retained = failed("Starting the native Cargo token hook failed", cause);
			if (cause instanceof Error error)
				throw error;
			throw retained;
		}
	}

	/**
	 * Awaits authentic readiness while servicing copied frames and peer owners on the controller thread.
	 * @param peers services other owners and gated children without reentering this owner or the job queue
	 * @throws NullPointerException if {@code peers} is null
	 * @throws IllegalStateException if called off-thread or before successful start
	 * @throws IOException if delivery, peer service, interruption or the readiness deadline fails
	 */
	public void awaitReady(AllocationHook.Pump peers) throws IOException
	{
		Objects.requireNonNull(peers, "peers may not be null");
		requireStarted();
		long began = nanoTime.getAsLong();
		try
		{
			while (ready == false)
			{
				pump();
				peers.pump();
				requireAuthority();
				if (ready)
					return;
				if (Thread.currentThread().isInterrupted())
					throw new IOException("Interrupted while awaiting native Cargo token readiness");
				long elapsed = nanoTime.getAsLong() - began;
				if (elapsed >= timeoutNanos)
					throw new IOException("Native Cargo token readiness deadline exceeded");
				LockSupport.parkNanos(Math.min(POLL_NANOS, timeoutNanos - elapsed));
			}
		}
		catch (IOException | RuntimeException | Error cause)
		{
			IOException retained = failed("Native Cargo token readiness failed", cause);
			if (cause instanceof Error error)
				throw error;
			throw retained;
		}
	}

	/**
	 * Drains up to 64 copied frames and grants up to 64 available reads without blocking on FIFO capacity.
	 * Write returns remain serviceable while any number of earlier reads await capacity. Transfer sequences must be
	 * consecutive; replay or a missing request fails before its FIFO effect. A failed post is never repeated.
	 * @throws IllegalStateException if called off-thread or before successful start
	 * @throws IOException if stopped, framing, ordering, native delivery, FIFO transfer or posting fails
	 */
	public void pump() throws IOException
	{
		requireStarted();
		try
		{
			for (int count = 0; count < OPERATIONS_PER_PUMP; count++)
			{
				String frame = connection.pollMessage().orElse(null);
				if (frame == null)
					break;
				if (frame.length() > MAX_MESSAGE_CHARACTERS)
					throw new IOException("Native Cargo token message exceeds " + MAX_MESSAGE_CHARACTERS + " characters");
				JsonNode envelope = json.readTree(frame);
				if (envelope != null && envelope.path("type").asString().equals("error"))
					throw new IOException("Native Cargo token script error: " + envelope.path("description").asString() +
						"; stack=" + envelope.path("stack").asString());
				accept(messages.read(frame));
			}
			for (int count = 0; count < OPERATIONS_PER_PUMP && reads.isEmpty() == false; count++)
			{
				var token = tokens.tryBorrow();
				if (token.isEmpty())
					return;
				CargoTokenMessages.Read read = reads.removeFirst();
				connection.post(messages.grant(read, token.getAsInt()));
			}
		}
		catch (IOException | RuntimeException | Error cause)
		{
			IOException retained = failed("Native Cargo token delivery failed", cause);
			if (cause instanceof Error error)
				throw error;
			throw retained;
		}
	}

	/**
	 * Applies a decoded event before any request-specific native effect.
	 * @param event exact decoded event from this installation
	 * @throws IOException if readiness, ordering or the reported native state is invalid
	 */
	private void accept(CargoTokenMessages.Event event) throws IOException
	{
		if (event instanceof CargoTokenMessages.Ready)
		{
			if (ready)
				throw new IOException("Native Cargo token readiness was repeated");
			ready = true;
			return;
		}
		if (ready == false)
			throw new IOException("Native Cargo token event preceded readiness");
		switch (event)
		{
			case CargoTokenMessages.Read read ->
			{
				acceptSequence(read.request().sequence());
				reads.addLast(read);
			}
			case CargoTokenMessages.Write write ->
			{
				acceptSequence(write.request().sequence());
				tokens.returnToken((byte) write.value());
				connection.post(messages.acknowledge(write));
			}
			default -> throw new IOException("Native Cargo token operation requires workload termination: " + event);
		}
	}

	/**
	 * Reserves the next native sequence without accepting duplicates, gaps or signed overflow.
	 * @param next positive decoded transfer sequence
	 * @throws IOException if {@code next} does not immediately follow the previous transfer
	 */
	private void acceptSequence(long next) throws IOException
	{
		if (sequence == Long.MAX_VALUE || next != sequence + 1)
			throw new IOException("Native Cargo token transfer sequence is not consecutive: " + next);
		sequence = next;
	}

	/**
	 * Permanently withdraws all controls without releasing native resources or borrowed bytes.
	 * @throws IllegalStateException if called off-thread
	 */
	void stop() { requireOwner(); stopped = true; }

	/**
	 * Stops controls and destroys only this connection after the trusted whole-workload execution barrier.
	 * A false or unknown barrier retains the connection. Native cleanup failure is sticky. The attempt capability
	 * remains borrowed and must be retired separately after every descendant can no longer execute.
	 * @param barrier trusted kernel-backed observation of whole-workload execution impossibility
	 * @throws NullPointerException if {@code barrier} is null
	 * @throws IllegalStateException if called off-thread
	 * @throws IOException if the barrier is false or unknown, or native cleanup fails
	 */
	public void close(AllocationHook.ExecutionBarrier barrier) throws IOException
	{
		Objects.requireNonNull(barrier, "barrier may not be null");
		requireOwner();
		if (closed)
			return;
		stop();
		if (closeFailure != null)
			throw new IOException("Native Cargo token cleanup remains unresolved", closeFailure);
		if (barrier.reached() == false)
			throw new IOException("Native Cargo token cleanup requires whole-workload execution to be impossible");
		closeConnection();
	}

	/**
	 * Observes this exact image attachment while retaining the borrowed attempt capability.
	 * @return true only after an independent supported detach observation
	 * @throws IOException if the observation is uncertain
	 * @throws IllegalStateException if called off-thread
	 */
	public boolean detached() throws IOException
	{
		requireOwner();
		return !closed && closeFailure == null && connection.detached();
	}

	/**
	 * Stops this image's protocol and destroys only its independently detached attachment.
	 * The borrowed attempt capability and token bytes remain owned through whole-workload retirement.
	 * @throws IOException if detach is unproven or destruction is uncertain
	 * @throws IllegalStateException if called off-thread
	 */
	public void closeDetached() throws IOException
	{
		requireOwner();
		if (closed) return;
		stop();
		if (!detached()) throw new IOException("Exact token attachment detachment is unproven", closeFailure);
		closeConnection();
	}

	/**
	 * Releases a connection once its independent lifetime boundary is established.
	 * @throws IOException if native destruction remains uncertain
	 */
	private void closeConnection() throws IOException
	{
		try { connection.close(); closed = true; }
		catch (IOException | RuntimeException | Error cause)
		{
			closeFailure = new IOException("Native Cargo token cleanup failed", cause);
			if (cause instanceof Error error)
				throw error;
			throw closeFailure;
		}
	}

	/**
	 * Returns the first operational failure without clearing it or changing native ownership.
	 * @return retained first failure, or null when no operational failure was observed
	 * @throws IllegalStateException if called off-thread
	 */
	public IOException failure() { requireOwner(); return failure; }

	/**
	 * Retains the first operational failure and stops controls before another native effect.
	 * @param context operation that failed
	 * @param cause original checked or unchecked failure
	 * @return first retained failure
	 */
	private IOException failed(String context, Throwable cause)
	{
		if (failure == null)
			failure = new IOException(context + ": " + cause.getMessage(), cause);
		stopped = true;
		return failure;
	}

	/**
	 * Requires owner-thread native start completion without inferring readiness.
	 * @throws IllegalStateException if called off-thread or before successful start
	 * @throws IOException if authority was withdrawn
	 */
	private void requireStarted() throws IOException
	{
		requireOwner();
		requireAuthority();
		if (started == false)
			throw new IllegalStateException("Cargo token native start has not completed");
	}

	/**
	 * Rejects operations after failure or permanent stop.
	 * @throws IOException if failed, stopped or closed
	 */
	private void requireAuthority() throws IOException
	{
		if (failure != null)
			throw new IOException("Native Cargo token authority is withdrawn", failure);
		if (stopped || closed)
			throw new IOException("Native Cargo token owner is stopped");
	}

	/**
	 * Confines lifecycle and FIFO decisions to the constructing controller thread.
	 * @throws IllegalStateException if called from another thread
	 */
	private void requireOwner()
	{
		if (Thread.currentThread() != owner)
			throw new IllegalStateException("Cargo token hook belongs to its constructing thread");
	}

}
