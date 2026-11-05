package com.holdmyspot.codexunleashed.scheduler.test;

import com.holdmyspot.codexunleashed.scheduler.LinuxProcessDiagnostics;
import org.testng.annotations.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

/**
 * Verifies the process descriptions that collection failures carry.
 */
public final class LinuxProcessDiagnosticsTest
{
	/**
	 * Creates the tests.
	 */
	public LinuxProcessDiagnosticsTest()
	{
	}

	/**
	 * A child is named with its state, parent and tracer.
	 * @throws IOException          if the child cannot start
	 * @throws InterruptedException if the test is interrupted
	 */
	@Test
	public void describesChildStateAndTracer() throws IOException, InterruptedException
	{
		TestRequirements.requireLinux();
		Process sleeping = new ProcessBuilder("sleep", "30").start();
		try
		{
			awaitSleeping(sleeping.pid());
			String description = LinuxProcessDiagnostics.children(ProcessHandle.current().pid());
			requireThat(description, "description").contains("pid=" + sleeping.pid() + " name=sleep state=S " +
				"ppid=" + ProcessHandle.current().pid() + " tracer=0");
		}
		finally
		{
			sleeping.destroyForcibly();
			sleeping.waitFor(10, TimeUnit.SECONDS);
		}
	}

	/**
	 * Waits until a child has executed {@code sleep} and blocked in it, so its reported state is stable.
	 * @param pid the child's process ID
	 * @throws IOException if its status cannot be read
	 */
	private static void awaitSleeping(long pid) throws IOException
	{
		Instant deadline = Instant.now().plus(Duration.ofSeconds(30));
		List<String> status;
		do
		{
			status = Files.readAllLines(Path.of("/proc", Long.toString(pid), "status"));
			Thread.onSpinWait();
		}
		while (!(status.contains("Name:\tsleep") && status.stream().anyMatch(line -> line.startsWith("State:\tS"))) &&
			Instant.now().isBefore(deadline));
	}
}
