package com.holdmyspot.codexunleashed.distribution;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertTrue;

/**
 * Executes maintained Windows shard commands across build and native execution boundaries.
 */
public final class WindowsShardWorkflowTest
{
	/**
	 * Creates workflow failure tests.
	 */
	public WindowsShardWorkflowTest()
	{
	}

	/**
	 * Supplies passing and failing callback statuses.
	 *
	 * @return the native executable statuses
	 */
	@DataProvider
	public Object[][] callbackStatuses()
	{
		return new Object[][] {{0}, {37}};
	}

	/**
	 * Runs the staged callback binary and propagates its status, preserving spaces in artifact paths.
	 *
	 * @param status the callback's exit status
	 * @throws IOException if fixture creation, execution, or cleanup fails
	 * @throws InterruptedException if process waiting is interrupted
	 * @throws URISyntaxException if the fixture class location cannot be represented
	 */
	@Test(dataProvider = "callbackStatuses")
	public void executesStackAssemblyCallback(int status) throws IOException, InterruptedException, URISyntaxException
	{
		Path root = Files.createTempDirectory("windows-stack-workflow-");
		try
		{
			Path project = Path.of(System.getProperty("tooling.release.workflow")).getParent().getParent().getParent();
			Path artifacts = Files.createDirectory(root.resolve("staged artifacts"));
			String callbackName = "stack-assembly.exe";
			if (java.io.File.separatorChar == '\\')
				callbackName = "stack-assembly";
			JavaCommandFixtures.writeNativeLauncher(artifacts.resolve(callbackName),
				WindowsStackAssemblyFixture.class);
			Map<String, String> environment = new HashMap<>(System.getenv());
			environment.put("STACK_FIXTURE_EVIDENCE", root.toString());
			environment.put("STACK_FIXTURE_STATUS", Integer.toString(status));
			environment.put("TMPDIR", root.toString());
			Path console = root.resolve("console");
			ProcessBuilder builder = NativeCommands.createBuilder(java.util.List.of("bash",
				project.resolve(".github/scripts/run-windows-callback.sh").toString(), artifacts.toString())).
				directory(root.toFile()).redirectErrorStream(true).redirectOutput(console.toFile());
			builder.environment().clear();
			builder.environment().putAll(environment);
			try (Process process = builder.start())
			{
				process.getOutputStream().close();
				assertEquals(process.waitFor(), status, Files.readString(console));
			}
			assertTrue(Files.isRegularFile(root.resolve("callback")), Files.readString(console));
		}
		finally
		{
			FixtureDirectories.deleteTree(root);
		}
	}

	/**
	 * Stops before building when the query exits unsuccessfully, even after writing partial standard output.
	 *
	 * @throws IOException if fixture creation, execution, or cleanup fails
	 * @throws InterruptedException if process waiting is interrupted
	 * @throws URISyntaxException if the fixture class location cannot be represented
	 */
	@Test
	public void stopsAfterFailedQuery() throws IOException, InterruptedException, URISyntaxException
	{
		Path root = Files.createTempDirectory("windows-shard-workflow-");
		try
		{
			Files.createDirectory(root.resolve("upstream"));
			Path project = Path.of(System.getProperty("tooling.release.workflow")).getParent().getParent().getParent();
			Path script = project.resolve(".github/scripts/buildbuddy-windows-cross.sh");
			Path bazel = JavaCommandFixtures.writeNativeLauncher(root.resolve("bazel"), BazelChildFixture.class);
			Path image = root.resolve("runtime");
			DistributionMain.main(new String[] {System.getProperty("tooling.runtime.modules"), image.toString()});
			Map<String, String> environment = new HashMap<>(System.getenv());
			environment.put("GITHUB_WORKSPACE", project.toString());
			environment.put("project_root", project.toString());
			environment.put("RUNNER_TEMP", root.toString());
			environment.put("BUILDBUDDY_CI_TARGET", "x86_64-pc-windows-gnullvm");
			environment.put("GITHUB_SHA", "fixture-source");
			environment.put("GITHUB_ACTIONS", "true");
			environment.put("RUNNER_OS", "Linux");
			environment.put("CODEX_UNLEASHED_TOOLING", image.resolve("bin/codex-tooling").toString());
			environment.put("CODEX_BAZEL_BIN", bazel.toString());
			environment.put("BAZEL_FIXTURE_ARGUMENTS", root.resolve("arguments").toString());
			environment.put("BAZEL_FIXTURE_STATUS", "37");
			environment.put("BAZEL_TEST_SHARD", "1");
			environment.put("BAZEL_TEST_SHARD_COUNT", "4");
			environment.put("BUILDBUDDY_API_KEY", "");
			environment.put("TMPDIR", root.toString());
			Path console = root.resolve("console");
			ProcessBuilder builder = NativeCommands.createBuilder(java.util.List.of("bash", script.toString())).
				directory(root.toFile()).redirectErrorStream(true).redirectOutput(console.toFile());
			builder.environment().clear();
			builder.environment().putAll(environment);
			try (Process process = builder.start())
			{
				process.getOutputStream().close();
				assertEquals(process.waitFor(), 37, Files.readString(console));
			}
			assertEquals(Arrays.stream(Files.readString(root.resolve("arguments")).split("\0", -1)).
				filter(argument -> !argument.startsWith("-")).findFirst().orElseThrow(), "query");
		}
		finally
		{
			FixtureDirectories.deleteTree(root);
		}
	}
}
