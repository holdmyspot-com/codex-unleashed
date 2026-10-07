package com.holdmyspot.codexunleashed.tooling;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.testng.annotations.Test;
import static org.testng.Assert.assertEquals;

/**
 * Verifies real external command execution with inherited streams and an explicit complete environment.
 */
public final class SystemCommandsExecutionTest
{
	/**
	 * Creates execution tests.
	 */
	public SystemCommandsExecutionTest()
	{
	}

	/**
	 * Passes literal arguments and the selected working directory without inheriting an undeclared PATH variable.
	 *
	 * @throws IOException if fixture access, child execution, or cleanup fails
	 * @throws URISyntaxException if the portable Java fixture class location is invalid
	 */
	@Test
	public void executesWithExactEnvironment() throws IOException, URISyntaxException
	{
		Path root = Files.createTempDirectory("command-execution-");
		Path output = root.resolve("result.txt");
		try
		{
			String literal = "a space $literal ; character";
			int status = SystemCommands.execute(
				JavaFixtures.command(CommandFixture.class, "execution", "result.txt", literal), root,
				Map.of("COMMAND_EXECUTION_INPUT", "selected-value"));
			assertEquals(status, 0);
			assertEquals(Files.readString(output), root.toRealPath() + "\n" + literal + "\nselected-value\nnull");
		}
		finally
		{
			Files.deleteIfExists(output);
			Files.delete(root);
		}
	}

	/**
	 * Returns an ordinary failed child status for the owning operation to evaluate.
	 *
	 * @throws IOException if fixture access, child execution, or cleanup fails
	 * @throws URISyntaxException if the portable Java fixture class location is invalid
	 */
	@Test
	public void retainsOrdinaryFailureStatus() throws IOException, URISyntaxException
	{
		Path root = Files.createTempDirectory("failed-command-execution-");
		try
		{
			assertEquals(SystemCommands.execute(JavaFixtures.command(CommandFixture.class, "fail"), root, Map.of()), 7);
		}
		finally
		{
			Files.delete(root);
		}
	}
}
