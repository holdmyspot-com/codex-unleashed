package com.holdmyspot.codexunleashed.tooling;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.testng.annotations.Test;
import tools.jackson.databind.json.JsonMapper;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertTrue;

/** Verifies literal native-child arguments and ordinary exit status through the public CLI. */
public final class BazelCommandTest
{
	/** Creates CLI tests. */
	public BazelCommandTest()
	{
	}

	/**
	 * Runs a real Node child that accepts one spaced Windows-style argument and exits with status 37.
	 *
	 * @throws IOException if fixture or process operations fail
	 */
	@Test
	public void forwardsLiteralArgumentsAndStatus() throws IOException
	{
		Path root = Files.createTempDirectory("bazel-command-");
		try
		{
			Map<String, String> environment = new HashMap<>(System.getenv());
			for (String key : List.of("BAZEL_OUTPUT_USER_ROOT", "BUILDBUDDY_API_KEY", "GITHUB_ACTIONS",
				"BAZEL_REPO_CONTENTS_CACHE", "BAZEL_REPOSITORY_CACHE", "BAZEL_DISK_CACHE", "NODE_OPTIONS"))
				environment.remove(key);
			String node = SystemCommands.capture(List.of("node", "-p", "process.execPath"), root, root, environment).
				stdout().strip();
			String spaced = "--test_env=PATH=C:\\Program Files\\PowerShell\\7;C:\\Program Files\\Git\\bin";
			JsonMapper mapper = JsonMapper.builder().build();
			Path preload = Files.writeString(root.resolve("bazel-fixture.cjs"), """
				if (process.argv.at(-1) !== %s) process.exit(91);
				console.log('literal arguments intact');
				process.exit(37);
				""".formatted(mapper.writeValueAsString(spaced)));
			environment.put("CODEX_BAZEL_BIN", node);
			environment.put("NODE_OPTIONS", "--require=" + mapper.writeValueAsString(preload.toString()));
			SystemCommands.Result result = cli(root, environment, List.of("run-bazel-with-buildbuddy", "run",
				"//fixture:child", "--", spaced));
			assertEquals(result.status(), 37, result.stdout() + result.stderr());
			assertEquals(result.stdout(), "literal arguments intact\n");
			assertTrue(result.stderr().contains("BuildBuddy key unavailable; using local Bazel configuration."));
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
	 * Starts the real named-module CLI with explicit directory, temporary storage and environment.
	 *
	 * @param root owned process and capture directory
	 * @param environment explicit child environment
	 * @param arguments public command arguments
	 * @return completed child output and status
	 * @throws IOException if process operations fail
	 */
	private static SystemCommands.Result cli(Path root, Map<String, String> environment, List<String> arguments)
		throws IOException
	{
		List<String> command = new ArrayList<>(List.of(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
			"-Djava.io.tmpdir=" + root, "--module-path", System.getProperty("jdk.module.path"), "--module",
			"com.holdmyspot.codexunleashed.tooling/com.holdmyspot.codexunleashed.tooling.Main"));
		command.addAll(arguments);
		return SystemCommands.capture(command, root, root, environment);
	}
}
