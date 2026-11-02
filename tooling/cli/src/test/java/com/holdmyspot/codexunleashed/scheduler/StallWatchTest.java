package com.holdmyspot.codexunleashed.scheduler;

import static com.holdmyspot.codexunleashed.scheduler.TestRequirements.requireException;
import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.testng.annotations.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Checks when the stall watch reports and what each report says, using an injected clock instead of real time. */
public final class StallWatchTest
{
	/** Shared writer and reader of the structured reports. */
	private static final JsonMapper JSON = JsonMapper.builder().build();
	/** Decision that the scheduler repeats while it waits. */
	private static final Policy.Decision WAIT = new Policy.Decision(Policy.Control.WAIT, null,
		"Build-tool concurrency limit is occupied");
	/** Threshold used by every test. */
	private static final Duration TEN_MINUTES = Duration.ofMinutes(10);

	/** Creates independent stall-report checks. */
	public StallWatchTest()
	{
	}

	/** Time source that moves only when a test moves it. */
	private static final class FakeClock extends Clock
	{
		/** Current instant. */
		private Instant now = Instant.parse("2026-11-02T12:00:00Z");

		/** Creates a clock at a fixed starting instant. */
		private FakeClock()
		{
		}

		/**
		 * Moves time forward.
		 *
		 * @param amount nonnegative time to add
		 */
		private void advance(Duration amount)
		{
			now = now.plus(amount);
		}

		@Override
		public ZoneId getZone()
		{
			return ZoneOffset.UTC;
		}

		@Override
		public Clock withZone(ZoneId zone)
		{
			return this;
		}

		@Override
		public Instant instant()
		{
			return now;
		}
	}

	/**
	 * Builds a queue snapshot with the supplied unfinished jobs.
	 *
	 * @param progress attempts created plus attempts finished
	 * @param jobs unfinished actions
	 * @param host latest host reading, or null
	 * @return the snapshot
	 */
	private static JobQueue.Snapshot snapshot(long progress, List<Policy.Job> jobs, Controller.Memory host)
	{
		return new JobQueue.Snapshot(jobs, 1, progress,
			Map.of(new Controller.Action("a", 0), new Controller.MemoryDemand(900L, 450L)), host,
			new Policy.Budget(100, 2));
	}

	/**
	 * Builds a running job.
	 *
	 * @param name action name
	 * @param position original position
	 * @param attempt queue-wide attempt number
	 * @return the job
	 */
	private static Policy.Job running(String name, long position, long attempt)
	{
		return new Policy.Job(name, Policy.State.RUNNING, position, 900L, 0, attempt, null, 0);
	}

	/**
	 * A stall report appears once the threshold passes, not before, and again only after another full threshold.
	 */
	@Test
	public void reportsOncePerThresholdWithoutProgress()
	{
		FakeClock clock = new FakeClock();
		List<String> reports = new ArrayList<>();
		StallWatch watch = new StallWatch(clock, TEN_MINUTES, JSON, reports::add, () -> StallWatch.Facts.NONE);
		JobQueue.Snapshot stuck = snapshot(1, List.of(running("a", 0, 0)), null);

		watch.waiting(WAIT, stuck);
		clock.advance(TEN_MINUTES.minusSeconds(1));
		watch.waiting(WAIT, stuck);
		requireThat(reports.size(), "reportsBeforeThreshold").isEqualTo(0);

		clock.advance(Duration.ofSeconds(1));
		watch.waiting(WAIT, stuck);
		requireThat(reports.size(), "reportsAtThreshold").isEqualTo(1);

		clock.advance(Duration.ofMinutes(5));
		watch.waiting(WAIT, stuck);
		requireThat(reports.size(), "reportsHalfwayToSecondThreshold").isEqualTo(1);

		clock.advance(Duration.ofMinutes(5));
		watch.waiting(WAIT, stuck);
		requireThat(reports.size(), "reportsAtSecondThreshold").isEqualTo(2);
		requireThat(JSON.readTree(reports.getLast()).path("stalled_seconds").asLong(), "secondReportAge")
			.isEqualTo(1200L);
	}

	/**
	 * An attempt that starts or finishes ends the stall episode, so the next report waits a full threshold.
	 */
	@Test
	public void progressRestartsTheEpisode()
	{
		FakeClock clock = new FakeClock();
		List<String> reports = new ArrayList<>();
		StallWatch watch = new StallWatch(clock, TEN_MINUTES, JSON, reports::add, () -> StallWatch.Facts.NONE);

		watch.waiting(WAIT, snapshot(1, List.of(running("a", 0, 0)), null));
		clock.advance(Duration.ofMinutes(9));
		watch.waiting(WAIT, snapshot(2, List.of(running("a", 0, 0), running("b", 1, 1)), null));
		clock.advance(Duration.ofMinutes(9));
		watch.waiting(WAIT, snapshot(2, List.of(running("a", 0, 0), running("b", 1, 1)), null));
		requireThat(reports.size(), "reportsNineMinutesAfterProgress").isEqualTo(0);

		clock.advance(Duration.ofMinutes(1));
		watch.waiting(WAIT, snapshot(2, List.of(running("a", 0, 0), running("b", 1, 1)), null));
		requireThat(reports.size(), "reportsTenMinutesAfterProgress").isEqualTo(1);
	}

	/**
	 * A report names every live attempt with its state, age in that state, process, liveness and measured memory, plus
	 * every queued action with its cause, pending registrations, token accounting and memory admission state.
	 */
	@Test
	public void reportDescribesAttemptsQueueTokensAndMemory()
	{
		FakeClock clock = new FakeClock();
		List<String> reports = new ArrayList<>();
		StallWatch.Facts facts = new StallWatch.Facts(
			List.of(new StallWatch.AttemptFacts("a", 0, "running", 4242L, true),
				new StallWatch.AttemptFacts("b", 1, "running", 4343L, false)),
			List.of(777L), new StallWatch.TokenFacts(3, 2, 3, 0));
		StallWatch watch = new StallWatch(clock, TEN_MINUTES, JSON, reports::add, () -> facts);
		Controller.Memory host = new Controller.Memory(500, 7, false);
		Policy.Job blocked = new Policy.Job("b", Policy.State.BLOCKED, 1, 900L, 0, 1L, 0L, 0);
		Policy.Job ready = new Policy.Job("c", Policy.State.READY, 2, null, 0, null, null, 0);
		Policy.Job retry = new Policy.Job("d", Policy.State.RETRY, 3, 50L, 0, null, null, 2);

		watch.waiting(WAIT, snapshot(2, List.of(running("a", 0, 0), running("b", 1, 1), ready, retry), host));
		clock.advance(Duration.ofMinutes(5));
		watch.waiting(WAIT, snapshot(2, List.of(running("a", 0, 0), blocked, ready, retry), host));
		clock.advance(Duration.ofMinutes(5));
		watch.waiting(WAIT, snapshot(2, List.of(running("a", 0, 0), blocked, ready, retry), host));

		requireThat(reports.size(), "reports").isEqualTo(1);
		JsonNode report = JSON.readTree(reports.getFirst());
		requireThat(report.path("event").asString(), "event").isEqualTo("scheduler_stall");
		requireThat(report.path("format_version").asInt(), "formatVersion").isEqualTo(1);
		requireThat(report.path("time").asString(), "time").isEqualTo("2026-11-02T12:10:00Z");
		requireThat(report.path("stalled_seconds").asLong(), "stalledSeconds").isEqualTo(600L);
		requireThat(report.path("threshold_seconds").asLong(), "thresholdSeconds").isEqualTo(600L);
		requireThat(report.path("wait_reason").asString(), "waitReason").isEqualTo(WAIT.reason());
		requireThat(report.path("completions").asLong(), "completions").isEqualTo(1L);

		JsonNode attempts = report.path("attempts");
		requireThat(attempts.size(), "attemptCount").isEqualTo(2);
		JsonNode first = attempts.get(0);
		requireThat(first.path("action").asString(), "firstAction").isEqualTo("a");
		requireThat(first.path("attempt").asLong(), "firstAttempt").isEqualTo(0L);
		requireThat(first.path("state").asString(), "firstState").isEqualTo("running");
		requireThat(first.path("phase").asString(), "firstPhase").isEqualTo("running");
		requireThat(first.path("seconds_in_state").asLong(), "firstSecondsInState").isEqualTo(600L);
		requireThat(first.path("pid").asLong(), "firstPid").isEqualTo(4242L);
		requireThat(first.path("alive").asBoolean(), "firstAlive").isTrue();
		requireThat(first.path("resident_bytes").asLong(), "firstResidentBytes").isEqualTo(450L);
		JsonNode second = attempts.get(1);
		requireThat(second.path("state").asString(), "secondState").isEqualTo("blocked");
		requireThat(second.path("seconds_in_state").asLong(), "secondSecondsInState").isEqualTo(300L);
		requireThat(second.path("alive").asBoolean(), "secondAlive").isFalse();
		requireThat(second.path("resident_bytes").isNull(), "secondResidentUnmeasured").isTrue();

		JsonNode queued = report.path("queued");
		requireThat(queued.size(), "queuedCount").isEqualTo(2);
		requireThat(queued.get(0).path("action").asString(), "readyAction").isEqualTo("c");
		requireThat(queued.get(0).path("state").asString(), "readyState").isEqualTo("ready");
		requireThat(queued.get(0).path("cause").asString(), "readyCause").isEqualTo("concurrency_limit");
		requireThat(queued.get(0).path("reason").asString(), "readyReason").isEqualTo(WAIT.reason());
		requireThat(queued.get(0).path("peak_bytes").isNull(), "readyPeakUnknown").isTrue();
		requireThat(queued.get(1).path("action").asString(), "retryAction").isEqualTo("d");
		requireThat(queued.get(1).path("cause").asString(), "retryCause").isEqualTo("retry_backoff");
		requireThat(queued.get(1).path("peak_bytes").asLong(), "retryPeak").isEqualTo(50L);

		requireThat(report.path("pending_registrations").get(0).path("wrapper_pid").asLong(), "pendingPid")
			.isEqualTo(777L);
		JsonNode tokens = report.path("tokens");
		requireThat(tokens.path("capacity").asInt(), "tokenCapacity").isEqualTo(3);
		requireThat(tokens.path("held").asInt(), "tokensHeld").isEqualTo(2);
		requireThat(tokens.path("outstanding").asInt(), "tokensOutstanding").isEqualTo(3);
		requireThat(tokens.path("available").asInt(), "tokensAvailable").isEqualTo(0);
		JsonNode memory = report.path("memory");
		requireThat(memory.path("available_bytes").asLong(), "availableBytes").isEqualTo(500L);
		requireThat(memory.path("swap_used_bytes").asLong(), "swapUsedBytes").isEqualTo(7L);
		requireThat(memory.path("memory_pressure").asBoolean(), "memoryPressure").isFalse();
		requireThat(memory.path("headroom_bytes").asLong(), "headroomBytes").isEqualTo(100L);
		requireThat(memory.path("usable_bytes").asLong(), "usableBytes").isEqualTo(400L);
	}

	/**
	 * With no live attempt and a memory wait, the queued action is attributed to memory admission.
	 */
	@Test
	public void idleQueueBlamesMemoryAdmission()
	{
		FakeClock clock = new FakeClock();
		List<String> reports = new ArrayList<>();
		StallWatch watch = new StallWatch(clock, TEN_MINUTES, JSON, reports::add, () -> StallWatch.Facts.NONE);
		Policy.Decision memoryWait = new Policy.Decision(Policy.Control.WAIT, null,
			"No memory remains beyond reserved headroom");
		Policy.Job ready = new Policy.Job("c", Policy.State.READY, 0, null, 0, null, null, 0);
		JobQueue.Snapshot idle = snapshot(0, List.of(ready), new Controller.Memory(50, 0, false));

		watch.waiting(memoryWait, idle);
		clock.advance(TEN_MINUTES);
		watch.waiting(memoryWait, idle);

		JsonNode report = JSON.readTree(reports.getFirst());
		requireThat(report.path("attempts").size(), "noAttempts").isEqualTo(0);
		requireThat(report.path("queued").get(0).path("cause").asString(), "cause").isEqualTo("memory_admission");
		requireThat(report.path("queued").get(0).path("reason").asString(), "reason")
			.isEqualTo("No memory remains beyond reserved headroom");
		requireThat(report.path("memory").path("usable_bytes").asLong(), "usableBytes").isEqualTo(0L);
		requireThat(report.path("tokens").isNull(), "noTokenAccounting").isTrue();
	}

	/**
	 * A native fact that cannot be read is named in the report and does not hide the queue state.
	 */
	@Test
	public void unreadablePlatformFactsStillProduceAReport()
	{
		FakeClock clock = new FakeClock();
		List<String> reports = new ArrayList<>();
		StallWatch watch = new StallWatch(clock, TEN_MINUTES, JSON, reports::add, () ->
		{
			throw new IOException("job object query failed");
		});
		JobQueue.Snapshot stuck = snapshot(1, List.of(running("a", 0, 0)), null);

		watch.waiting(WAIT, stuck);
		clock.advance(TEN_MINUTES);
		watch.waiting(WAIT, stuck);

		JsonNode report = JSON.readTree(reports.getFirst());
		requireThat(report.path("platform_facts_error").asString(), "platformFactsError")
			.isEqualTo("job object query failed");
		requireThat(report.path("attempts").get(0).path("action").asString(), "queueStateKept").isEqualTo("a");
		requireThat(report.path("attempts").get(0).path("pid").isNull(), "pidUnknown").isTrue();
	}

	/**
	 * A threshold that is not positive is rejected, since it would report on every wait.
	 */
	@Test
	public void rejectsNonPositiveThreshold()
	{
		requireException(IllegalArgumentException.class, () -> new StallWatch(new FakeClock(), Duration.ZERO, JSON,
			report ->
			{
			}, () -> StallWatch.Facts.NONE));
	}
}
