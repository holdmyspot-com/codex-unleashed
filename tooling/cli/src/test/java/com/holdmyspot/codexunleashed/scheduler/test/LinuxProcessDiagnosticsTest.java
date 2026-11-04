package com.holdmyspot.codexunleashed.scheduler.test;

import com.holdmyspot.codexunleashed.scheduler.LinuxProcessDiagnostics;
import org.testng.annotations.Test;

import java.io.IOException;
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
		Process sleeping = new ProcessBuilder("sleep", "30").start();
		try
		{
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
}
