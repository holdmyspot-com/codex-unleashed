package com.holdmyspot.codexunleashed.scheduler;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * Writes the scheduler's primary failure as soon as the coordinator records it, before cleanup begins.
 * A cleanup that never returns would otherwise hold the failure in memory, leaving the build log silent about why
 * the build stopped. Every other event is ignored. Every call belongs to the coordinator thread.
 */
public final class FailureReport implements SchedulerCoordinator.Observer
{
	/** Receives each complete report, including the failure's stack trace. */
	private final Consumer<String> sink;

	/**
	 * Creates a report that writes to the given sink.
	 * @param sink receives one report per recorded failure; it must not throw
	 * @throws NullPointerException if {@code sink} is null
	 */
	public FailureReport(Consumer<String> sink)
	{
		this.sink = Objects.requireNonNull(sink, "sink");
	}

	/**
	 * Writes a failure event's stack trace and ignores every other event.
	 * @param event the coordinator event name
	 * @param payload the event payload; for {@code failure}, the run's primary failure
	 */
	@Override
	public void record(String event, Object payload)
	{
		if (!event.equals("failure") || !(payload instanceof Throwable failure))
			return;
		StringWriter trace = new StringWriter();
		failure.printStackTrace(new PrintWriter(trace));
		sink.accept("Scheduler failed; cleaning up before reporting the failure:" + System.lineSeparator() + trace);
	}
}
