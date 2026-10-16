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
	 * Runs the built callback binary and propagates its status, preserving spaces and CRLF output paths.
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
			Files.createDirectory(root.resolve("upstream"));
			Path project = Path.of(System.getProperty("tooling.release.workflow")).getParent().getParent().getParent();
			String command = WorkflowCommands.readStepCommand(project.resolve(".github/workflows/bazel.yml"),
				"test-windows-shard", "Execute Windows GNU stack assembly callback");
			Path bazel = JavaCommandFixtures.writeNativeLauncher(root.resolve("bazel"), WindowsStackAssemblyFixture.class);
			Path callback = JavaCommandFixtures.writeNativeLauncher(root.resolve("stack callback"),
				WindowsStackAssemblyFixture.class);
			Path image = root.resolve("runtime");
			DistributionMain.main(new String[] {System.getProperty("tooling.runtime.modules"), image.toString()});
			Map<String, String> environment = new HashMap<>(System.getenv());
			environment.remove("MSYS2_ARG_CONV_EXCL");
			environment.put("GITHUB_WORKSPACE", project.toString());
			environment.put("GITHUB_SHA", "fixture-source");
			environment.put("GITHUB_ACTIONS", "true");
			environment.put("RUNNER_OS", "Linux");
			environment.put("CODEX_UNLEASHED_TOOLING", image.resolve("bin/codex-tooling").toString());
			environment.put("CODEX_BAZEL_BIN", bazel.toString());
			environment.put("BUILDBUDDY_API_KEY", "fixture-token");
			environment.put("STACK_FIXTURE_EVIDENCE", root.toString());
			environment.put("STACK_FIXTURE_BINARY", callback.toString().replace('\\', '/'));
			environment.put("STACK_FIXTURE_STATUS", Integer.toString(status));
			environment.put("TMPDIR", root.toString());
			Path console = root.resolve("console");
			ProcessBuilder builder = NativeCommands.createBuilder(java.util.List.of("bash", "-c", command)).
				directory(root.toFile()).redirectErrorStream(true).redirectOutput(console.toFile());
			builder.environment().clear();
			builder.environment().putAll(environment);
			try (Process process = builder.start())
			{
				process.getOutputStream().close();
				assertEquals(process.waitFor(), status, Files.readString(console));
			}
			assertTrue(Files.readString(root.resolve("build")).contains("@v8//:codex_windows_stack_assembly_test"));
			assertTrue(Files.readString(root.resolve("cquery")).contains("@v8//:codex_windows_stack_assembly_test"));
			for (String boundary : java.util.List.of("build", "cquery"))
			{
				java.util.List<String> arguments = Arrays.asList(Files.readString(root.resolve(boundary)).split("\0", -1));
				assertTrue(arguments.contains("--shell_executable=/bin/bash"), arguments.toString());
				assertTrue(arguments.contains("--action_env=PATH=/usr/bin:/bin"), arguments.toString());
				assertTrue(arguments.contains("--host_action_env=PATH=/usr/bin:/bin"), arguments.toString());
				assertTrue(arguments.contains("--local_test_jobs=2"), arguments.toString());
				assertEquals(Files.readString(root.resolve(boundary + "-path-conversion")),
					"--shell_executable=;--action_env=PATH=;--host_action_env=PATH=",
					"Linux remote-action arguments must bypass MSYS path conversion at the native child boundary");
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
			Path workflow = project.resolve(".github/workflows/bazel.yml");
			String override = System.getProperty("tooling.bazel.workflow");
			if (override != null)
				workflow = Path.of(override);
			String command = WorkflowCommands.readStepCommand(workflow, "test-windows-shard", "bazel test shard");
			Path bazel = JavaCommandFixtures.writeNativeLauncher(root.resolve("bazel"), BazelChildFixture.class);
			Path image = root.resolve("runtime");
			DistributionMain.main(new String[] {System.getProperty("tooling.runtime.modules"), image.toString()});
			Map<String, String> environment = new HashMap<>(System.getenv());
			environment.put("GITHUB_WORKSPACE", project.toString());
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
			ProcessBuilder builder = NativeCommands.createBuilder(java.util.List.of("bash", "-c", command)).
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
