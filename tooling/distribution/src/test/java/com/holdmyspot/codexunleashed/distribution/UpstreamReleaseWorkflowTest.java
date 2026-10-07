package com.holdmyspot.codexunleashed.distribution;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.testng.annotations.Test;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertTrue;

/**
 * Executes the maintained upstream detection step through a standalone runtime with isolated API fixtures.
 */
public final class UpstreamReleaseWorkflowTest
{
	/**
	 * Creates the workflow tests.
	 */
	public UpstreamReleaseWorkflowTest()
	{
	}

	/**
	 * Detects both release states and preserves existing output when the inventory request fails.
	 *
	 * @throws IOException if runtime linking, fixture access, or process startup fails
	 * @throws InterruptedException if the workflow wait is interrupted
	 * @throws URISyntaxException if a fixture class location is invalid
	 */
	@Test
	public void detectsStableReleases() throws IOException, InterruptedException, URISyntaxException
	{
		Path root = Files.createTempDirectory("upstream-release-workflow-");
		try
		{
			Path adapters = Files.createDirectory(root.resolve("adapters"));
			JavaCommandFixtures.writeLauncher(adapters.resolve("gh"), UpstreamReleaseGitHubFixture.class);
			JavaCommandFixtures.writeLauncher(adapters.resolve("python3"), RejectedCommandFixture.class);
			Path image = root.resolve("runtime with spaces");
			DistributionMain.main(new String[]{System.getProperty("tooling.runtime.modules"), image.toString()});
			Path workflow = Path.of(System.getProperty("tooling.release.workflow")).resolveSibling(
				"upstream-release-check.yml");
			String command = WorkflowCommands.readStepCommand(workflow, "detect", "Compare release tags (no compilation)");
			Path output = root.resolve("workflow output");
			Path summary = root.resolve("step summary");
			Path log = root.resolve("workflow.log");
			for (String mode : new String[]{"needed", "current", "failure"})
			{
				Files.writeString(output, "existing output\n");
				Files.writeString(summary, "existing summary\n");
				ProcessBuilder builder = new ProcessBuilder("bash", "-eu", "-c", command);
				builder.directory(root.toFile()).redirectErrorStream(true).redirectOutput(log.toFile());
				builder.environment().put("PATH", adapters.toString());
				builder.environment().put("CODEX_UNLEASHED_TOOLING", image.resolve("bin/codex-tooling").toString());
				builder.environment().put("GITHUB_REPOSITORY", "holdmyspot-com/codex-unleashed");
				builder.environment().put("GITHUB_OUTPUT", output.toString());
				builder.environment().put("GITHUB_STEP_SUMMARY", summary.toString());
				builder.environment().put("RELEASE_FIXTURE_MODE", mode);
				builder.environment().put("TMPDIR", root.toString());
				int status;
				try (Process process = builder.start())
				{
					assertTrue(process.waitFor(30, TimeUnit.SECONDS), "Workflow timed out");
					status = process.exitValue();
				}
				if ("failure".equals(mode))
				{
					assertEquals(status, 1, Files.readString(log));
					assertTrue(Files.readString(log).contains("GitHub rate limit"));
					assertEquals(Files.readString(output), "existing output\n");
					assertEquals(Files.readString(summary), "existing summary\n");
				}
				else
				{
					assertEquals(status, 0, Files.readString(log));
					assertEquals(Files.readString(output), "existing output\nupstream_tag=rust-v0.160.1\nneeded=" +
						"needed".equals(mode) + "\n");
					assertTrue(Files.readString(summary).contains("- Upstream: `rust-v0.160.1`"));
				}
			}
		}
		finally
		{
			try (Stream<Path> files = Files.walk(root))
			{
				for (Path file : files.sorted(Comparator.reverseOrder()).toList())
					Files.delete(file);
			}
		}
	}
}
