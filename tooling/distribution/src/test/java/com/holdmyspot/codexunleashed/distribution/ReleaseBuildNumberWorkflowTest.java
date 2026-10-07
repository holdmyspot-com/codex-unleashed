package com.holdmyspot.codexunleashed.distribution;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Map;
import java.util.stream.Stream;
import org.testng.annotations.Test;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertTrue;

/**
 * Verifies release-specific build numbering through the maintained prepare workflow command.
 */
public final class ReleaseBuildNumberWorkflowTest
{
	/**
	 * Creates the build-number workflow tests.
	 */
	public ReleaseBuildNumberWorkflowTest()
	{
	}

	/**
	 * Keeps each upstream version's numbering independent and accepts explicit build overrides.
	 *
	 * @throws IOException if workflow or fixture access fails
	 * @throws URISyntaxException if the fixture class location is invalid
	 * @throws InterruptedException if a workflow process is interrupted
	 */
	@Test
	public void selectsReleaseSpecificBuilds() throws IOException, URISyntaxException, InterruptedException
	{
		Path root = Files.createTempDirectory("build-number-workflow-");
		try
		{
			Path bin = Files.createDirectory(root.resolve("bin"));
			JavaCommandFixtures.writeLauncher(bin.resolve("gh"), GitHubCommandFixture.class);
			JavaCommandFixtures.writeLauncher(bin.resolve("python3"), RejectedCommandFixture.class);
			JavaCommandFixtures.writeLauncher(bin.resolve("git"), RejectedCommandFixture.class);
			Path patches = Files.createDirectory(root.resolve("patches"));
			Files.writeString(patches.resolve("controlled.patch"), "controlled fixture patch\n");
			Path runtime = root.resolve("runtime with spaces");
			DistributionMain.main(new String[]{System.getProperty("tooling.runtime.modules"), runtime.toString()});
			String command = WorkflowCommands.readStepCommand("prepare", "Determine upstream release");
			for (String[] scenario : new String[][]{{"rust-v0.161.0", "", "1"}, {"rust-v0.160.0", "", "30"},
				{"rust-v0.161.0", "7", "7"}})
			{
				Path output = root.resolve("output");
				Files.writeString(output, "");
				Path log = root.resolve("process.log");
			ProcessBuilder builder = NativeCommands.createBuilder("bash", "-eu", "-c", command).
					redirectErrorStream(true).redirectOutput(log.toFile());
				Path workflow = Path.of(System.getProperty("tooling.release.workflow"));
				builder.directory(root.toFile());
				Map<String, String> environment = builder.environment();
				environment.put("PATH", bin + java.io.File.pathSeparator + environment.get("PATH"));
				environment.put("GITHUB_OUTPUT", output.toString());
				environment.put("CODEX_UNLEASHED_TOOLING", runtime.resolve("bin/codex-tooling").toString());
				environment.put("INPUT_UPSTREAM_TAG", scenario[0]);
				environment.put("INPUT_BUILD_NUMBER", scenario[1]);
				for (String name : new String[]{"INPUT_ARTIFACT_RUN_ID", "INPUT_FOCUS_TARGET", "INPUT_FOCUS_BUNDLE"})
					environment.put(name, "");
				environment.put("GITHUB_EVENT_NAME", "workflow_dispatch");
				environment.put("GITHUB_REF_TYPE", "branch");
				environment.put("GITHUB_REF_NAME", "main");
				environment.put("GITHUB_REPOSITORY", "owner/repo");
				environment.put("GITHUB_RUN_ID", "100");
				environment.put("GITHUB_SHA", "a".repeat(40));
				environment.put("GITHUB_WORKFLOW", "Build release");
				try (Process process = builder.start())
				{
					assertEquals(process.waitFor(), 0, Files.readString(log) + "\nWorkflow: " + workflow);
				}
				assertTrue(Files.readString(output).contains("patched_tag=" + scenario[0] + "+" + scenario[2] + "\n"));
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
}
