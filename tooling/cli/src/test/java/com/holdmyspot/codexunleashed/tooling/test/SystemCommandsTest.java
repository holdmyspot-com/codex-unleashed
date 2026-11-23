package com.holdmyspot.codexunleashed.tooling.test;

import com.holdmyspot.codexunleashed.tooling.SystemCommands;
import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.testng.SkipException;
import org.testng.annotations.Test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

/**
 * Verifies the standard input that external commands receive.
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
		 *
		 * @throws IOException if the command cannot run or its capture cannot be cleaned up
	 */
	@Test
	public void captureSuppliesEmptyStandardInput() throws IOException
	{
		if (!Path.of("/bin/sh").toFile().canExecute())
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

	/**
	 * A command run for its output reads end-of-input from standard input instead of the caller's standard input,
	 * so Git for Windows does not wait on an inherited console or pipe at startup.
	 *
	 * @throws IOException if the command cannot run
	 */
	@Test
	public void runSuppliesEmptyStandardInput() throws IOException
	{
		if (!Path.of("/bin/sh").toFile().canExecute())
			throw new SkipException("The fixture command requires a POSIX shell");
		String output = SystemCommands.run(
			// -ef compares the device and inode that standard input refers to; readlink output differs by platform.
			List.of("/bin/sh", "-c", "if [ /dev/stdin -ef /dev/null ]; then echo null; else echo other; fi"));
		requireThat(output.strip(), "stdin").isEqualTo("null");
	}

	/**
	 * Preserves literal argument boundaries without invoking a shell.
	 *
	 * @throws IOException        if the fixture cannot run
	 * @throws URISyntaxException if the fixture class location is invalid
	 */
	@Test
	public void preservesArguments() throws IOException, URISyntaxException
	{
		for (String payload : List.of("spaces $literal; `literal` \"quoted\"", "\"quoted\"", "", "a\\", "a\\\"b"))
			requireThat(SystemCommands.run(JavaFixtures.command(CommandFixture.class, "echo", payload)), "output").
				isEqualTo(payload);
	}

	/**
	 * Captures both streams and nonzero status without blocking on a full error pipe, then removes capture files.
	 *
	 * @throws IOException        if fixture creation, execution, or cleanup fails
	 * @throws URISyntaxException if the fixture class location is invalid
	 */
	@Test
	public void capturesStreamsAndStatus() throws IOException, URISyntaxException
	{
		Path root = Files.createTempDirectory("command-capture-");
		try
		{
			Path temporaryDirectory = Files.createDirectory(root.resolve("captures"));
			String payload = "literal $quoted";
			SystemCommands.Result result = SystemCommands.capture(
				JavaFixtures.command(CommandFixture.class, "capture", payload), root, temporaryDirectory);
			requireThat(result.status(), "status").isEqualTo(7);
			requireThat(result.stdout().codePoints().toArray(), "stdout").
				isEqualTo((root.toRealPath() + "\n" + payload + " \u03bb").codePoints().toArray());
			requireThat(result.stderr(), "stderr").isEqualTo("diagnostic".repeat(20_000));
			List<String> malformed = JavaFixtures.command(CommandFixture.class, "invalid-utf8", "");
			try
			{
				SystemCommands.capture(malformed, root, temporaryDirectory);
				throw new AssertionError("Malformed output was accepted");
			}
			catch (IOException expected)
			{
				requireThat(expected.getMessage(), "message").isNotNull();
			}
			try (Stream<Path> captures = Files.list(temporaryDirectory))
			{
				requireThat(captures.count(), "captureCount").isEqualTo(0L);
			}
		}
		finally
		{
			try (Stream<Path> paths = Files.walk(root))
			{
				for (Path path : paths.sorted(Comparator.reverseOrder()).toList())
					Files.delete(path);
			}
		}
	}

	/**
	 * Reports unsuccessful processes with their exit status.
	 *
	 * @throws URISyntaxException if the fixture class location is invalid
	 * @throws IOException        if the command fails, as expected
	 */
	@Test(expectedExceptions = IOException.class, expectedExceptionsMessageRegExp = ".*status 7.*")
	public void reportsNonzeroExit() throws URISyntaxException, IOException
	{
		SystemCommands.run(JavaFixtures.command(CommandFixture.class, "fail", ""));
	}

	/**
	 * Rejects malformed UTF-8 instead of silently replacing damaged inventory data.
	 *
	 * @throws URISyntaxException if the fixture class location is invalid
	 * @throws IOException        if the command fails to start
	 */
	@Test(expectedExceptions = IOException.class)
	public void rejectsMalformedOutput() throws URISyntaxException, IOException
	{
		SystemCommands.run(JavaFixtures.command(CommandFixture.class, "invalid-utf8", ""));
	}
}
