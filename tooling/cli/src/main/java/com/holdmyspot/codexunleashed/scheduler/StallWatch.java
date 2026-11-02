package com.holdmyspot.codexunleashed.scheduler;

import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Explains a scheduler that has stopped making progress.
 * Progress means that an attempt was created or finished. When none occurs for the threshold, the watch writes one
 * structured report, and one more for every further threshold that the stall continues.
 * It runs only while the coordinator loop cycles, so it cannot report a block inside a single native call.
 * Every call belongs to the coordinator thread.
 */
final class StallWatch implements SchedulerCoordinator.WaitObserver
{
	/** Default stall threshold. */
	static final Duration DEFAULT_THRESHOLD = Duration.ofMinutes(10);
	/** Version of the report's JSON shape. */
	private static final int FORMAT_VERSION = 1;
	/** Time source for stall durations and report timestamps. */
	private final Clock clock;
	/** Time without progress that triggers each report. */
	private final Duration threshold;
	/** Writer of each report. */
	private final JsonMapper json;
	/** Receives each complete report as one line. */
	private final Consumer<String> sink;
	/** Supplier of platform facts that the queue does not hold. */
	private final Details details;
	/** When each unfinished attempt or queued action entered its current state. */
	private final Map<String, Entered> entered = new HashMap<>();
	/** Progress count at the previous wait, or null before the first wait. */
	private Long lastProgress;
	/** When progress last changed. */
	private Instant lastProgressAt;
	/** When the stall next earns a report. */
	private Instant nextReportAt;

	/**
	 * Creates a watch whose first stall episode begins at its first wait.
	 *
	 * @param clock time source for stall durations and report timestamps
	 * @param threshold positive time without progress that triggers each report
	 * @param json mapper that writes each report
	 * @param sink receives each complete report as one line of JSON
	 * @param details supplies platform facts that the queue does not hold
	 * @throws NullPointerException if an argument is null
	 * @throws IllegalArgumentException if {@code threshold} is not positive
	 */
	StallWatch(Clock clock, Duration threshold, JsonMapper json, Consumer<String> sink, Details details)
	{
		this.clock = Objects.requireNonNull(clock, "clock");
		this.threshold = Objects.requireNonNull(threshold, "threshold");
		this.json = Objects.requireNonNull(json, "json");
		this.sink = Objects.requireNonNull(sink, "sink");
		this.details = Objects.requireNonNull(details, "details");
		if (threshold.isNegative() || threshold.isZero())
			throw new IllegalArgumentException("Stall threshold must be positive: " + threshold);
	}

	@Override
	public void waiting(Policy.Decision decision, JobQueue.Snapshot snapshot)
	{
		Instant now = clock.instant();
		track(snapshot, now);
		if (lastProgress == null || lastProgress != snapshot.progress())
		{
			lastProgress = snapshot.progress();
			lastProgressAt = now;
			nextReportAt = now.plus(threshold);
			return;
		}
		if (now.isBefore(nextReportAt))
			return;
		sink.accept(report(decision, snapshot, now));
		nextReportAt = nextReportAt.plus(threshold);
	}

	/**
	 * Notes when each unfinished attempt or queued action was first seen in its current state.
	 *
	 * @param snapshot the queue state
	 * @param now the current instant
	 */
	private void track(JobQueue.Snapshot snapshot, Instant now)
	{
		Set<String> current = new HashSet<>();
		for (Policy.Job job : snapshot.jobs())
		{
			String key = key(job);
			current.add(key);
			Entered previous = entered.get(key);
			if (previous == null || previous.state() != job.state())
				entered.put(key, new Entered(job.state(), now));
		}
		entered.keySet().retainAll(current);
	}

	/**
	 * Builds one complete report.
	 *
	 * @param decision the waiting decision
	 * @param snapshot the queue state
	 * @param now the current instant
	 * @return the report as one line of JSON
	 */
	private String report(Policy.Decision decision, JobQueue.Snapshot snapshot, Instant now)
	{
		Facts facts = Facts.NONE;
		String factsError = null;
		try
		{
			facts = details.read();
		}
		catch (IOException | RuntimeException failure)
		{
			factsError = failure.getMessage() == null ? failure.getClass().getName() : failure.getMessage();
		}

		ObjectNode report = json.createObjectNode();
		report.put("format_version", FORMAT_VERSION);
		report.put("event", "scheduler_stall");
		report.put("time", now.toString());
		report.put("stalled_seconds", Duration.between(lastProgressAt, now).toSeconds());
		report.put("threshold_seconds", threshold.toSeconds());
		report.put("wait_reason", decision.reason());
		report.put("completions", snapshot.completions());
		if (factsError != null)
			report.put("platform_facts_error", factsError);
		report.set("attempts", attempts(snapshot, facts, now));
		report.set("queued", queued(decision, snapshot, now));
		ArrayNode pending = report.putArray("pending_registrations");
		for (long pid : facts.pendingRegistrationPids())
			pending.addObject().put("wrapper_pid", pid);
		TokenFacts tokens = facts.tokens();
		if (tokens == null)
			report.putNull("tokens");
		else
		{
			ObjectNode node = report.putObject("tokens");
			node.put("capacity", tokens.capacity());
			node.put("held", tokens.held());
			node.put("outstanding", tokens.outstanding());
			put(node, "available", tokens.available() == null ? null : Long.valueOf(tokens.available()));
		}
		report.set("memory", memory(snapshot));
		return report.toString();
	}

	/**
	 * Lists every live attempt, then every retained owner that the queue no longer lists as live.
	 *
	 * @param snapshot the queue state
	 * @param facts the platform facts
	 * @param now the current instant
	 * @return attempt entries in queue order
	 */
	private ArrayNode attempts(JobQueue.Snapshot snapshot, Facts facts, Instant now)
	{
		Map<String, AttemptFacts> owners = new HashMap<>();
		for (AttemptFacts owner : facts.attempts())
			owners.put(owner.name() + "/" + owner.sequence(), owner);
		ArrayNode attempts = json.createArrayNode();
		for (Policy.Job job : snapshot.jobs())
		{
			if (job.startedAt() == null)
				continue;
			String key = key(job);
			AttemptFacts owner = owners.remove(key);
			ObjectNode node = attempts.addObject();
			node.put("action", job.name());
			node.put("attempt", job.startedAt());
			node.put("state", state(job.state()));
			node.put("seconds_in_state", Duration.between(entered.get(key).since(), now).toSeconds());
			describe(node, owner);
			Controller.MemoryDemand demand = snapshot.demands().get(new Controller.Action(job.name(), job.position()));
			if (demand == null || demand.residentLowerBoundBytes() == 0)
				node.putNull("resident_bytes");
			else
				node.put("resident_bytes", demand.residentLowerBoundBytes());
			put(node, "peak_bytes", job.peakBytes());
		}
		for (AttemptFacts owner : owners.values())
		{
			ObjectNode node = attempts.addObject();
			node.put("action", owner.name());
			node.put("attempt", owner.sequence());
			node.put("state", "retained");
			node.putNull("seconds_in_state");
			describe(node, owner);
			node.putNull("resident_bytes");
			node.putNull("peak_bytes");
		}
		return attempts;
	}

	/**
	 * Adds the platform facts of one attempt owner.
	 *
	 * @param node the attempt entry
	 * @param owner the owner's facts, or null when the platform supplied none
	 */
	private static void describe(ObjectNode node, AttemptFacts owner)
	{
		if (owner == null)
		{
			node.putNull("phase");
			node.putNull("pid");
			node.putNull("alive");
			return;
		}
		node.put("phase", owner.phase());
		put(node, "pid", owner.pid());
		if (owner.alive() == null)
			node.putNull("alive");
		else
			node.put("alive", owner.alive());
	}

	/**
	 * Lists every action that waits for admission with the reason it waits.
	 *
	 * @param decision the waiting decision
	 * @param snapshot the queue state
	 * @param now the current instant
	 * @return queued entries in queue order
	 */
	private ArrayNode queued(Policy.Decision decision, JobQueue.Snapshot snapshot, Instant now)
	{
		boolean stopping = false;
		boolean retrying = false;
		long live = 0;
		for (Policy.Job job : snapshot.jobs())
		{
			switch (job.state())
			{
				case STOPPING ->
				{
					stopping = true;
					++live;
				}
				case RUNNING, BLOCKED -> ++live;
				case RETRY -> retrying = true;
				case READY ->
				{
				}
			}
		}
		ArrayNode queued = json.createArrayNode();
		for (Policy.Job job : snapshot.jobs())
		{
			if (job.startedAt() != null)
				continue;
			String cause;
			if (job.state() == Policy.State.RETRY)
				cause = "retry_backoff";
			else if (stopping)
				cause = "reaping";
			else if (live >= snapshot.budget().jobs())
				cause = "concurrency_limit";
			else if (retrying)
				cause = "behind_retry";
			else
				cause = "memory_admission";
			ObjectNode node = queued.addObject();
			node.put("action", job.name());
			node.put("state", state(job.state()));
			node.put("cause", cause);
			node.put("reason", decision.reason());
			node.put("seconds_in_state", Duration.between(entered.get(key(job)).since(), now).toSeconds());
			put(node, "peak_bytes", job.peakBytes());
			if (job.state() == Policy.State.RETRY)
				node.put("retry_after_completions", job.retryAfter());
		}
		return queued;
	}

	/**
	 * Describes the host memory that admission compares against.
	 *
	 * @param snapshot the queue state
	 * @return the memory entry, or null before the first host reading
	 */
	private ObjectNode memory(JobQueue.Snapshot snapshot)
	{
		Controller.Memory host = snapshot.host();
		if (host == null)
			return null;
		ObjectNode node = json.createObjectNode();
		long headroom = snapshot.budget().headroomBytes();
		node.put("available_bytes", host.availableBytes());
		node.put("swap_used_bytes", host.swapUsedBytes());
		node.put("memory_pressure", host.memoryPressure());
		node.put("headroom_bytes", headroom);
		node.put("usable_bytes", Math.max(0, host.availableBytes() - headroom));
		return node;
	}

	/**
	 * Names the state in the report's lower-case vocabulary.
	 *
	 * @param state the policy state
	 * @return the lower-case state name
	 */
	private static String state(Policy.State state)
	{
		return state.name().toLowerCase(Locale.ROOT);
	}

	/**
	 * Writes a nullable number.
	 *
	 * @param node the entry
	 * @param name the member name
	 * @param value the value, or null
	 */
	private static void put(ObjectNode node, String name, Long value)
	{
		if (value == null)
			node.putNull(name);
		else
			node.put(name, value.longValue());
	}

	/**
	 * Builds the key that identifies one attempt, or one action that has no attempt.
	 *
	 * @param job the job
	 * @return the key
	 */
	private static String key(Policy.Job job)
	{
		return job.name() + "/" + (job.startedAt() == null ? "-" : job.startedAt().toString());
	}

	/**
	 * Remembers when something entered its current state.
	 *
	 * @param state the state
	 * @param since when it entered the state
	 */
	private record Entered(Policy.State state, Instant since)
	{
	}

	/** Supplies platform facts that the queue does not hold. */
	@FunctionalInterface
	interface Details
	{
		/**
		 * Reads the platform facts without changing scheduling state.
		 *
		 * @return the current facts
		 * @throws IOException if a native fact cannot be read
		 */
		Facts read() throws IOException;
	}

	/**
	 * Platform facts for one report.
	 *
	 * @param attempts every attempt owner that still exists
	 * @param pendingRegistrationPids wrapper pids that connected but have not completed registration
	 * @param tokens jobserver token accounting, or null when the platform has none
	 */
	record Facts(List<AttemptFacts> attempts, List<Long> pendingRegistrationPids, TokenFacts tokens)
	{
		/** No platform facts. */
		static final Facts NONE = new Facts(List.of(), List.of(), null);

		/** Copies the collections. */
		Facts
		{
			attempts = List.copyOf(attempts);
			pendingRegistrationPids = List.copyOf(pendingRegistrationPids);
		}
	}

	/**
	 * Platform facts about one attempt owner.
	 *
	 * @param name action name
	 * @param sequence queue-wide attempt number
	 * @param phase lifecycle phase of the owner
	 * @param pid root process id, or null before a process exists
	 * @param alive whether the process or its job object can still run, or null when unknown
	 */
	record AttemptFacts(String name, long sequence, String phase, Long pid, Boolean alive)
	{
	}

	/**
	 * Jobserver token accounting.
	 *
	 * @param capacity explicit tokens in the pool, excluding Cargo's implicit slot
	 * @param held tokens that the controller definitely holds for running images
	 * @param outstanding held tokens plus acquisitions whose result is uncertain
	 * @param available capacity not outstanding to the controller, which excludes tokens that Cargo itself holds, or null
	 *   when unknown
	 */
	record TokenFacts(int capacity, int held, int outstanding, Integer available)
	{
	}
}
