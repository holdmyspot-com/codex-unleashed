package com.holdmyspot.codexunleashed.distribution;

import java.io.File;
import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.testng.annotations.Test;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertTrue;

/** Executes maintained query and CI wrappers with a fresh runtime and a controlled native Bazel child. */
public final class BazelCallerTest
{
	/** Creates caller tests. */
	public BazelCallerTest()
	{
	}

	/**
	 * Preserves query payloads, cache choices, Windows-style environment arguments and child failure status.
	 *
	 * @throws IOException if fixture, runtime or process operations fail
	 * @throws InterruptedException if waiting is interrupted
	 * @throws URISyntaxException if a fixture class location is invalid
	 */
	@Test
	public void executesMaintainedBazelWrappers() throws IOException, InterruptedException, URISyntaxException
	{
		Path root = Files.createTempDirectory("bazel-callers-");
		try
		{
			Path adapters = Files.createDirectory(root.resolve("process adapters"));
			Path bazel = JavaCommandFixtures.writeNativeLauncher(adapters.resolve("bazel"), BazelChildFixture.class);
			JavaCommandFixtures.writeLauncher(adapters.resolve("python3"), RejectedCommandFixture.class);
			JavaCommandFixtures.writeLauncher(adapters.resolve("python"), RejectedCommandFixture.class);
			JavaCommandFixtures.writeLauncher(adapters.resolve("java"), RejectedCommandFixture.class);
			Path image = root.resolve("runtime with spaces");
			DistributionMain.main(new String[]{System.getProperty("tooling.runtime.modules"), image.toString()});
			Map<String, String> environment = new HashMap<>(System.getenv());
			for (String key : List.of("BAZEL_DISK_CACHE", "BAZEL_OUTPUT_USER_ROOT", "GITHUB_ACTIONS",
				"BUILDBUDDY_API_KEY", "CODEX_BAZEL_EXECUTION_LOG_COMPACT_DIR", "NODE_OPTIONS"))
				environment.remove(key);
			Path temporary = Files.createDirectory(root.resolve("captures"));
			environment.put("TMPDIR", temporary.toString());
			environment.put("XDG_CACHE_HOME", Files.createDirectory(root.resolve("xdg")).toString());
			environment.put("CODEX_BAZEL_BIN", bazel.toString());
			environment.put("CODEX_UNLEASHED_TOOLING", image.resolve("bin/codex-tooling").toString());
			environment.put("PATH", adapters + File.pathSeparator + environment.getOrDefault("PATH", ""));
			environment.put("BAZEL_FIXTURE_ARGUMENTS", root.resolve("arguments.txt").toString());
			environment.put("BAZEL_REPOSITORY_CACHE", Files.createDirectory(root.resolve("repository cache")).toString());
			String windowsPath = "C:\\Program Files\\PowerShell\\7;C:\\Program Files\\Git\\bin";
			environment.put("CODEX_BAZEL_WINDOWS_PATH", windowsPath);
			Path scripts = Path.of(System.getProperty("tooling.release.workflow")).getParent().getParent().resolve("scripts");
			String expression = "kind(\"rust_library rule\", //codex-rs/...)";
			for (String operatingSystem : List.of("Linux", "Windows"))
			{
				environment.put("RUNNER_OS", operatingSystem);
				for (String credential : List.of("", "fixture-token"))
				{
					environment.put("BUILDBUDDY_API_KEY", credential);
					environment.put("BAZEL_FIXTURE_STATUS", "0");
					assertEquals(run(root, environment, NativeCommands.scriptBuilder(scripts.resolve("run-bazel-query-ci.sh"),
						"--output=label", "--", expression)), 0);
					List<String> query = arguments(root);
					assertEquals(query.getFirst(), "query");
					assertTrue(query.contains(expression));
					assertTrue(query.contains("--repository_cache=" + environment.get("BAZEL_REPOSITORY_CACHE")));
					assertFalse(query.contains("--config=ci-linux"));
					assertFalse(query.contains("--remote_local_fallback"));
					environment.put("BAZEL_FIXTURE_STATUS", "37");
					assertEquals(run(root, environment, NativeCommands.scriptBuilder(scripts.resolve("run-bazel-ci.sh"),
						"--", "test", "--", "//fixture:test")), 37);
					List<String> test = arguments(root);
					assertTrue(test.contains("test"));
					assertEquals(test.getLast(), "//fixture:test");
					if (operatingSystem.equals("Windows"))
						assertTrue(test.contains("--test_env=PATH=" + windowsPath));
					assertEquals(test.contains("--remote_header=x-buildbuddy-api-key=fixture-token"), !credential.isEmpty());
				}
			}
			try (Stream<Path> paths = Files.list(temporary))
			{
				assertEquals(paths.count(), 0L);
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
	 * Reads exact direct argv from the child rather than inferring them from console rendering.
	 *
	 * @param root the owned fixture
	 * @return captured arguments
	 * @throws IOException if captured output cannot be read
	 */
	private static List<String> arguments(Path root) throws IOException
	{
		return Arrays.asList(Files.readString(root.resolve("arguments.txt")).split("\0", -1));
	}

	/**
	 * Executes the whole maintained Bash wrapper with independent input and captured output.
	 *
	 * @param root the owned process directory
	 * @param environment explicit caller configuration
	 * @param builder the selected native script command
	 * @return the actual exit status
	 * @throws IOException if process or file operations fail
	 * @throws InterruptedException if waiting is interrupted
	 */
	private static int run(Path root, Map<String, String> environment, ProcessBuilder builder)
		throws IOException, InterruptedException
	{
		Path log = root.resolve("console.log");
		Path input = Files.writeString(root.resolve("stdin"), "");
		builder.directory(root.toFile()).redirectInput(input.toFile()).redirectErrorStream(true).
			redirectOutput(log.toFile());
		builder.environment().clear();
		builder.environment().putAll(environment);
		try (Process process = builder.start())
		{
			try
			{
				assertTrue(process.waitFor(30, TimeUnit.SECONDS), "Wrapper timed out: " + builder.command());
				int status = process.exitValue();
				assertTrue(Files.readString(log).contains("fixture bazel output"), Files.readString(log));
				return status;
			}
			finally
			{
				if (process.isAlive())
					process.destroyForcibly().waitFor();
			}
		}
	}
}
