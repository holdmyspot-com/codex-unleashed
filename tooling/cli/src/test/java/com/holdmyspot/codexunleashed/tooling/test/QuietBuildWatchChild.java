package com.holdmyspot.codexunleashed.tooling.test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;

/**
 * Supplies a real Java process for production watchdog boundary tests.
 */
public final class QuietBuildWatchChild
{
	/**
	 * Prevents direct construction of the test child.
	 */
	private QuietBuildWatchChild()
	{
	}

	/**
	 * Publishes this process ID and runs one bounded child behavior.
	 *
	 * @param arguments pid file, behavior, busy duration in milliseconds, exit status and unique marker
	 * @throws IOException if the owned pid file cannot be written
	 * @throws InterruptedException if an idle child is interrupted before the watchdog stops it
	 */
	public static void main(String[] arguments) throws IOException, InterruptedException
	{
		Files.writeString(Path.of(arguments[0]), Long.toString(ProcessHandle.current().pid()));
		switch (arguments[1])
		{
			case "complete" -> System.out.println("watch-child-complete");
			case "busy" -> consumeCpu(Long.parseLong(arguments[2]));
			case "idle" -> new CountDownLatch(1).await();
			default -> throw new IllegalArgumentException("Unsupported watchdog child behavior");
		}
		System.exit(Integer.parseInt(arguments[3]));
	}

	/**
	 * Consumes process CPU until the requested elapsed duration passes, without writing output.
	 *
	 * @param durationMillis CPU-active interval in milliseconds
	 */
	private static void consumeCpu(long durationMillis)
	{
		long durationNanos = durationMillis * 1_000_000L;
		long started = System.nanoTime();
		while (System.nanoTime() - started < durationNanos)
			Thread.onSpinWait();
	}
}
