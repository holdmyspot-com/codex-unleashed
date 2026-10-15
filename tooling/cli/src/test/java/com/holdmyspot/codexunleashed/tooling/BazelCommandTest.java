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
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;
import tools.jackson.databind.json.JsonMapper;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertTrue;

/** Verifies literal native-child arguments and ordinary exit status through the public CLI. */
public final class BazelCommandTest
{
	private static final String LAUNCHER_WARNING = "OpenJDK 64-Bit Server VM warning: " +
		"Options -Xverify:none and -noverify were deprecated in JDK 13 and will likely be removed in a future release.";

	/** Creates CLI tests. */
	public BazelCommandTest()
	{
	}

	/**
	 * Supplies complete and partial diagnostics at EOF and ordinary successful and failed statuses.
	 *
	 * @return child diagnostic tails and exit statuses
	 */
	@DataProvider
	public Object[][] diagnosticTailCases()
	{
		return new Object[][]{{"", 0}, {LAUNCHER_WARNING, 37}, {"OpenJDK 64-Bit", 37}, {LAUNCHER_WARNING + "\r", 37}};
	}

	/**
	 * Runs a real Node child with literal arguments, near-miss diagnostics, exact warnings, and an ordinary status.
	 *
	 * @param diagnosticTail the final diagnostic bytes before EOF
	 * @param status the child's ordinary status
	 * @throws IOException if fixture or process operations fail
	 */
	@Test(dataProvider = "diagnosticTailCases")
	public void forwardsLiteralArgumentsAndStatus(String diagnosticTail, int status) throws IOException
	{
		Path root = Files.createTempDirectory("bazel-command-");
		try
		{
			Map<String, String> environment = new HashMap<>(System.getenv());
			for (String key : List.of("BAZEL_OUTPUT_USER_ROOT", "BUILDBUDDY_API_KEY", "GITHUB_ACTIONS",
				"BAZEL_REPO_CONTENTS_CACHE", "BAZEL_REPOSITORY_CACHE", "BAZEL_DISK_CACHE", "NODE_OPTIONS"))
				environment.put(key, "");
			String node = SystemCommands.capture(List.of("node", "-p", "process.execPath"), root, root, environment).
				stdout().strip();
			String spaced = "--test_env=PATH=C:\\Program Files\\PowerShell\\7;C:\\Program Files\\Git\\bin";
			JsonMapper mapper = JsonMapper.builder().build();
			Path preload = Files.writeString(root.resolve("bazel-fixture.cjs"), """
				if (process.argv.at(-1) !== %s) process.exit(91);
				const warning = %s;
				process.stderr.write(warning + '\\n' + warning + '\\r\\n');
				process.stderr.write(warning + ' extra\\nwarning: unrelated\\nprefix ' + warning + '\\n');
				console.log('literal arguments intact');
				process.stderr.write(%s);
				process.exit(%d);
				""".formatted(mapper.writeValueAsString(spaced), mapper.writeValueAsString(LAUNCHER_WARNING),
				mapper.writeValueAsString(diagnosticTail), status));
			environment.put("CODEX_BAZEL_BIN", node);
			environment.put("NODE_OPTIONS", "--require=" + mapper.writeValueAsString(preload.toString()));
			SystemCommands.Result result = cli(root, environment, List.of("run-bazel-with-buildbuddy", "run",
				"//fixture:child", "--", spaced));
			assertEquals(result.status(), status, result.stdout() + result.stderr());
			assertEquals(result.stdout(), "literal arguments intact\n");
			assertTrue(result.stderr().contains("BuildBuddy key unavailable; using local Bazel configuration."));
			String expectedTail = diagnosticTail;
			if (diagnosticTail.equals(LAUNCHER_WARNING))
				expectedTail = "";
			assertEquals(result.stderr(), "BuildBuddy key unavailable; using local Bazel configuration." +
				System.lineSeparator() + LAUNCHER_WARNING + " extra\nwarning: unrelated\nprefix " + LAUNCHER_WARNING +
				"\n" + expectedTail);
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
