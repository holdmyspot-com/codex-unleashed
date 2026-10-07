package com.holdmyspot.codexunleashed.tooling.release;

import com.holdmyspot.codexunleashed.tooling.SystemCommands;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.apache.commons.io.file.PathUtils;
import org.testng.annotations.Test;
import tools.jackson.databind.json.JsonMapper;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertTrue;
import static org.testng.Assert.expectThrows;

/**
 * Exercises npm's Windows entry-point selection with real Node execution and literal arguments on every host.
 */
public final class NpmCommandsTest
{
	/** Creates the entry-point tests. */
	public NpmCommandsTest()
	{
	}

	/**
	 * Runs the adjacent CLI and a global-prefix override without interpreting shell metacharacters.
	 *
	 * @throws IOException if fixture creation, Node execution, or cleanup fails
	 */
	@Test
	public void selectsInstalledCliAndPreservesArguments() throws IOException
	{
		Path root = Files.createTempDirectory("npm entry & ");
		try
		{
			Path bin = Files.createDirectories(root.resolve("node_modules/npm/bin"));
			Files.writeString(bin.resolve("npm-prefix.js"), "process.stdout.write(process.env.NPM_TEST_PREFIX)");
			Path cli = bin.resolve("npm-cli.js");
			Files.writeString(cli, "process.stdout.write(JSON.stringify(process.argv.slice(2)))");
			Map<String, String> environment = new HashMap<>(System.getenv());
			environment.put("NPM_TEST_PREFIX", root.resolve("missing global").toString());
			List<String> command = NpmCommands.windowsCommand(root.resolve("npm.cmd"), root, root, environment);
			assertEquals(command, List.of("node", cli.toString()));
			List<String> payload = List.of("space value", "雪", "$literal; `literal`", "\"quoted\"", "& %PATH%");
			List<String> execution = new ArrayList<>(command);
			execution.addAll(payload);
			SystemCommands.Result result = SystemCommands.capture(execution, root, root, environment);
			assertEquals(result.status(), 0, result.stderr());
			assertEquals(JsonMapper.builder().build().readValue(result.stdout(), List.class), payload);

			Path global = Files.createDirectories(root.resolve("global & prefix"));
			Path override = global.resolve("node_modules/npm/bin/npm-cli.js");
			Files.createDirectories(override.getParent());
			Files.writeString(override, "process.stdout.write('override')");
			environment.put("NPM_TEST_PREFIX", global.toString() + System.lineSeparator());
			assertEquals(NpmCommands.windowsCommand(root.resolve("npm.cmd"), root, root, environment),
				List.of("node", override.toString()));
		}
		finally
		{
			PathUtils.deleteDirectory(root);
		}
	}

	/**
	 * Retains prefix lookup failures instead of invoking a different npm installation.
	 *
	 * @throws IOException if fixture creation or cleanup fails
	 */
	@Test
	public void reportsPrefixFailure() throws IOException
	{
		Path root = Files.createTempDirectory("npm-prefix-failure-");
		try
		{
			Path bin = Files.createDirectories(root.resolve("node_modules/npm/bin"));
			Files.writeString(bin.resolve("npm-prefix.js"), "process.stderr.write('fixture failure');process.exit(7)");
			IOException failure = expectThrows(IOException.class, () -> NpmCommands.windowsCommand(root.resolve("npm.cmd"),
				root, root, System.getenv()));
			assertTrue(failure.getMessage().contains("status 7: fixture failure"));
		}
		finally
		{
			PathUtils.deleteDirectory(root);
		}
	}
}
