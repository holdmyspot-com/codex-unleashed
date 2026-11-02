package com.holdmyspot.codexunleashed.scheduler;

import com.holdmyspot.codexunleashed.tooling.DefaultToolingScope;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import org.testng.annotations.Test;

import static com.holdmyspot.codexunleashed.scheduler.TestRequirements.requireException;
import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

/**
 * Verifies that a compiler attempt's standard-error pipe delivers its output to Cargo through the relay.
 * <p>
 * Each test opens the pipe's write end through {@code /proc/self/fd} to represent the compiler process that received
 * it; closing that stream represents the compiler's exit.
 */
public final class LinuxCompilerErrorPipeTest
{
	/**
	 * Creates pipe tests.
	 */
	public LinuxCompilerErrorPipeTest()
	{
	}

	/**
	 * Ending a retried attempt forwards its complete lines and drops its incomplete last line once the compiler exits.
	 * @throws IOException if the pipe fails
	 */
	@Test
	public void endingSupersededAttemptForwardsCompleteLines() throws IOException
	{
		LinuxCargoJobserverTest.requireLinux();
		var cargo = new ByteArrayOutputStream();
		try (var scope = new DefaultToolingScope())
		{
			var pipe = new LinuxCompilerErrorPipe(scope.getLinuxBorrowedStreamBindings(), new CompilerErrorRelay(cargo));
			try (var compiler = Files.newOutputStream(Path.of("/proc/self/fd", Integer.toString(pipe.writeEnd())),
				StandardOpenOption.WRITE))
			{
				pipe.closeWriteEnd();
				compiler.write("warning: unused\npartial".getBytes(StandardCharsets.UTF_8));
			}
			pipe.end(true, Duration.ofSeconds(10));
		}
		requireThat(cargo.toString(StandardCharsets.UTF_8), "cargo").isEqualTo("warning: unused\n");
	}

	/**
	 * Ending an attempt whose compiler still holds its standard error fails after the timeout instead of waiting.
	 * @throws IOException if the pipe fails
	 */
	@Test
	public void endingAttemptWithOpenPipeFails() throws IOException
	{
		LinuxCargoJobserverTest.requireLinux();
		try (var scope = new DefaultToolingScope())
		{
			var pipe = new LinuxCompilerErrorPipe(scope.getLinuxBorrowedStreamBindings(),
				new CompilerErrorRelay(new ByteArrayOutputStream()));
			try (var compiler = Files.newOutputStream(Path.of("/proc/self/fd", Integer.toString(pipe.writeEnd())),
				StandardOpenOption.WRITE))
			{
				pipe.closeWriteEnd();
				requireException(IOException.class, () -> pipe.end(false, Duration.ofMillis(50)));
			}
			pipe.end(false, Duration.ofSeconds(10));
		}
	}
}
