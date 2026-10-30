package com.holdmyspot.codexunleashed.scheduler;

import java.io.IOException;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;

/**
 * Stages complete Linux memory batches until the controller accepts them.
 * Calls are single-thread confined; the borrowed source must retain every registered attempt through acceptance.
 */
public final class LinuxMeasurements implements Controller.Measurements
{
	/**
	 * Stable action and attempt ordinal, independent of submission order.
	 * @param name nonblank action identity
	 * @param sequence nonnegative process-attempt ordinal
	 */
	record Attempt(String name, long sequence)
	{
		/**
		 * Creates an attempt identity.
		 * @param name nonblank action identity
		 * @param sequence nonnegative attempt ordinal
		 * @throws NullPointerException if name is null
		 * @throws IllegalArgumentException if name is blank or sequence is negative
		 */
		Attempt
		{
			Objects.requireNonNull(name, "name");
			if (name.isBlank() || sequence < 0)
				throw new IllegalArgumentException("Attempt requires a nonblank name and nonnegative sequence");
		}
	}

	/**
	 * One validated host observation with the exact attempts that must be sampled before acceptance.
	 * @param memory fresh host counters
	 * @param availableBytes effective remaining bytes through every complete ancestor chain
	 * @param bootId kernel boot identity used to reject counter resets
	 * @param generation registration generation used to detect changes during a batch
	 * @param attempts live registered attempts, copied on construction
	 */
	record Observation(LinuxMemory.Sample memory, long availableBytes, String bootId, long generation,
		Set<Attempt> attempts)
	{
		/**
		 * Creates a complete observation; unavailable native readings must throw before constructing this value.
		 * @param memory fresh host counters
		 * @param availableBytes nonnegative effective bytes, at most host available bytes
		 * @param bootId nonblank kernel boot identity
		 * @param generation nonnegative registration generation
		 * @param attempts exact live registered attempts
		 * @throws NullPointerException if memory, bootId, attempts, or an attempt is null
		 * @throws IllegalArgumentException if effective bytes exceed host availability, a count is negative, or bootId is blank
		 */
		Observation
		{
			Objects.requireNonNull(memory, "memory");
			Objects.requireNonNull(bootId, "bootId");
			attempts = Set.copyOf(attempts);
			if (availableBytes < 0 || availableBytes > memory.availableBytes() || bootId.isBlank() || generation < 0)
				throw new IllegalArgumentException("Complete observation has invalid bytes, boot identity or generation");
		}
	}

	/**
	 * Supplies validated native readings without owning the cumulative-counter baseline.
	 */
	interface Source
	{
		/**
		 * Reads fresh host counters and complete cgroup limits.
		 * @return complete observation including the live registry
		 * @throws IOException if authority, mapping or a required reading is unavailable
		 */
		Observation read() throws IOException;

		/**
		 * Reads fresh residency for one exactly registered attempt.
		 * @param attempt live action and attempt ordinal
		 * @return nonnegative resident bytes, including a measured zero
		 * @throws IOException if the attempt or required observations are unavailable
		 */
		long readResidentBytes(Attempt attempt) throws IOException;

		/**
		 * Checks the authority and unchanged registration generation before committing a batch.
		 * @param generation generation captured by the host observation
		 * @throws IOException if registration or authority changed
		 */
		void verify(long generation) throws IOException;
	}

	/**
	 * Borrowed native authority; its owner closes it after this adapter is no longer used.
	 */
	private final Source source;
	/**
	 * Cumulative counters from the last accepted complete batch.
	 */
	private Observation accepted;
	/**
	 * Host counters awaiting every residency read and final authority verification, or null without a batch.
	 */
	private Observation pending;
	/**
	 * Attempts successfully sampled in the pending batch.
	 */
	private final Set<Attempt> sampled = new HashSet<>();

	/**
	 * Retains a borrowed source and a validated initial counter baseline.
	 * @param source borrowed native readings
	 * @param accepted initial complete observation
	 */
	private LinuxMeasurements(Source source, Observation accepted)
	{
		this.source = Objects.requireNonNull(source, "source");
		this.accepted = Objects.requireNonNull(accepted, "accepted");
	}

	/**
	 * Primes the baseline from a complete native reading before admission starts.
	 * @param source borrowed authority retained through all sampling calls
	 * @return primed measurements adapter
	 * @throws IOException if the initial observation or authority verification fails
	 */
	static LinuxMeasurements open(Source source) throws IOException
	{
		Observation initial = source.read();
		source.verify(initial.generation());
		return new LinuxMeasurements(source, initial);
	}

	/**
	 * Primes counters from a ready retained authority before admitting workloads.
	 * @param source borrowed native authority
	 * @return primed measurements adapter
	 * @throws IOException if complete native sampling or verification fails
	 */
	public static LinuxMeasurements open(LinuxAuthority source) throws IOException
	{
		return open((Source) source);
	}

	@Override
	public Controller.Memory readHost() throws IOException
	{
		discard();
		Observation current = source.read();
		boolean reset = !accepted.bootId().equals(current.bootId()) ||
			current.memory().swapOutPages() < accepted.memory().swapOutPages() ||
			current.memory().stallMicros() < accepted.memory().stallMicros();
		if (reset)
			throw new IOException("Kernel boot identity or cumulative counters changed backwards; open a new measurement session");
		boolean pressure = current.memory().swapOutPages() > accepted.memory().swapOutPages() ||
			current.memory().stallMicros() > accepted.memory().stallMicros();
		pending = current;
		return new Controller.Memory(current.availableBytes(), current.memory().swapUsedBytes(), pressure);
	}

	@Override
	public long readResidentBytes(Policy.Job job) throws IOException
	{
		try
		{
			if (pending == null)
				throw new IOException("No complete host observation is staged");
			if (job.startedAt() == null)
				throw new IOException("Job has no live attempt ordinal: " + job.name());
			Attempt attempt = new Attempt(job.name(), job.startedAt());
			if (!pending.attempts().contains(attempt))
				throw new IOException("Attempt is absent from the staged workload registry: " + attempt);
			long resident = source.readResidentBytes(attempt);
			if (resident < 0)
				throw new IOException("Native residency is negative for " + attempt);
			sampled.add(attempt);
			return resident;
		}
		catch (IOException | RuntimeException failure)
		{
			discard();
			throw failure;
		}
	}

	@Override
	public void acceptReadings() throws IOException
	{
		try
		{
			if (pending == null || !sampled.equals(pending.attempts()))
				throw new IOException("Every staged live attempt must have a successful residency reading before acceptance");
			source.verify(pending.generation());
			accepted = pending;
		}
		finally
		{
			discard();
		}
	}

	/**
	 * Discards an unaccepted batch while retaining the last accepted counter baseline.
	 */
	private void discard()
	{
		pending = null;
		sampled.clear();
	}
}
