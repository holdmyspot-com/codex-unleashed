package com.holdmyspot.codexunleashed.tooling;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Comparator;
import java.util.stream.Stream;
import org.testng.annotations.Test;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertTrue;
import static org.testng.Assert.expectThrows;

/**
 * Verifies external-command execution with a portable Java child process.
 */
public final class SystemCommandsTest
{
	/**
	 * Creates the external-command tests.
	 */
	public SystemCommandsTest()
	{
	}

	/**
	 * Preserves literal argument boundaries without invoking a shell.
	 *
	 * @throws IOException if the fixture cannot run
	 * @throws URISyntaxException if the fixture class location is invalid
	 */
	@Test
	public void preservesArguments() throws IOException, URISyntaxException
	{
		for (String payload : List.of("spaces $literal; `literal` \"quoted\"", "\"quoted\"", "", "a\\", "a\\\"b"))
			assertEquals(SystemCommands.run(fixtureCommand("echo", payload)), payload);
	}

	/**
	 * Captures both streams and nonzero status without blocking on a full error pipe, then removes capture files.
	 *
	 * @throws IOException if fixture creation, execution, or cleanup fails
	 * @throws URISyntaxException if the fixture class location is invalid
	 */
	@Test
	public void capturesStreamsAndStatus() throws IOException, URISyntaxException
	{
		Path root = Files.createTempDirectory("command-capture-");
		try
		{
			Path temporaryDirectory = Files.createDirectory(root.resolve("captures"));
			String payload = "literal λ $quoted";
			SystemCommands.Result result = SystemCommands.capture(fixtureCommand("capture", payload), root,
				temporaryDirectory);
			assertEquals(result.status(), 7);
			assertEquals(result.stdout().codePoints().toArray(), (root.toRealPath() + "\n" + payload).codePoints().toArray());
			assertEquals(result.stderr(), "diagnostic".repeat(20_000));
			List<String> malformed = fixtureCommand("invalid-utf8", "");
			expectThrows(IOException.class, () -> SystemCommands.capture(malformed, root, temporaryDirectory));
			try (Stream<Path> captures = Files.list(temporaryDirectory))
			{
				assertEquals(captures.count(), 0L);
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
	 */
	@Test
	public void reportsNonzeroExit() throws URISyntaxException
	{
		List<String> command = fixtureCommand("fail", "");
		IOException failure = expectThrows(IOException.class, () -> SystemCommands.run(command));
		assertTrue(failure.getMessage().contains("status 7"));
	}

	/**
	 * Rejects malformed UTF-8 instead of silently replacing damaged inventory data.
	 *
	 * @throws URISyntaxException if the fixture class location is invalid
	 */
	@Test
	public void rejectsMalformedOutput() throws URISyntaxException
	{
		List<String> command = fixtureCommand("invalid-utf8", "");
		expectThrows(IOException.class, () -> SystemCommands.run(command));
	}

	/**
	 * Constructs the fixture command using the current JDK and test class location.
	 *
	 * @param mode the fixture mode
	 * @param payload the literal argument payload
	 * @return the executable and its direct arguments
	 * @throws URISyntaxException if the fixture class location is invalid
	 */
	private static List<String> fixtureCommand(String mode, String payload) throws URISyntaxException
	{
		return JavaFixtures.command(CommandFixture.class, mode, payload);
	}
}
