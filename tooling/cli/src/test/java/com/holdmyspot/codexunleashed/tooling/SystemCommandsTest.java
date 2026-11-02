package com.holdmyspot.codexunleashed.tooling;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.testng.SkipException;
import org.testng.annotations.Test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

/**
 * Verifies the standard input that captured external commands receive.
 */
public final class SystemCommandsTest
{
	/** Creates a resource-free test instance; each method owns its temporary directory. */
	public SystemCommandsTest()
	{
	}

	/**
	 * A captured command reads end-of-input from standard input instead of the caller's standard input, so a child
	 * that reads or probes standard input neither consumes the caller's input nor waits on it.
	 * @throws IOException if the command cannot run or its capture cannot be cleaned up
	 */
	@Test
	public void captureSuppliesEmptyStandardInput() throws IOException
	{
		if (Path.of("/bin/sh").toFile().canExecute() == false)
			throw new SkipException("The fixture command requires a POSIX shell");
		Path temporary = Files.createTempDirectory("system-commands-");
		try
		{
			SystemCommands.Result result = SystemCommands.capture(
				// -ef compares the device and inode that standard input refers to; readlink output differs by platform.
				List.of("/bin/sh", "-c", "if [ /dev/stdin -ef /dev/null ]; then echo null; else echo other; fi"),
				temporary, temporary, Map.of());
			requireThat(result.status(), "status").isEqualTo(0);
			requireThat(result.stdout().strip(), "stdin").isEqualTo("null");
		}
		finally
		{
			Files.delete(temporary);
		}
	}
}
