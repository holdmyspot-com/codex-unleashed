package com.holdmyspot.codexunleashed.distribution;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.testng.annotations.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.dataformat.yaml.YAMLMapper;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertNotEquals;
import static org.testng.Assert.assertTrue;

/**
 * Verifies environment snapshot reuse and the runtime producer dependencies of native CI jobs.
 */
public final class ToolingCacheWorkflowTest
{
	/**
	 * Creates workflow cache tests.
	 */
	public ToolingCacheWorkflowTest()
	{
	}

	/**
	 * Retains VM snapshots across product and tooling edits while still invalidating tooling verification.
	 *
	 * @throws IOException if fixture or command operations fail
	 * @throws InterruptedException if command waiting is interrupted
	 */
	@Test
	public void separatesSnapshotAndVerificationInputs() throws IOException, InterruptedException
	{
		Path root = Files.createTempDirectory("tooling-cache-inputs-");
		try
		{
			Path project = projectRoot();
			Path script = root.resolve(".github/scripts/buildbuddy-tooling-source-key.sh");
			Files.createDirectories(script.getParent());
			Files.copy(project.resolve(".github/scripts/buildbuddy-tooling-source-key.sh"), script);
			List<String> inputs = List.of(".github/scripts/buildbuddy-bootstrap.sh",
				".github/scripts/buildbuddy-tools.sh", "tooling/cli/source.java", "patches/product.patch");
			for (String input : inputs)
			{
				Path path = root.resolve(input);
				Files.createDirectories(path.getParent());
				Files.writeString(path, "original\n");
			}
			run(root, "git", "init", "-q");
			run(root, "git", "add", ".");
			String scriptArgument = script.toString().replace('\\', '/');
			String environment = run(root, "bash", scriptArgument, "environment");
			String verification = run(root, "bash", scriptArgument);
			for (String input : List.of("patches/product.patch", "tooling/cli/source.java"))
			{
				Files.writeString(root.resolve(input), "changed\n");
				assertEquals(run(root, "bash", scriptArgument, "environment"), environment, input);
				String changed = run(root, "bash", scriptArgument);
				assertNotEquals(changed, verification, input);
				verification = changed;
			}
			Files.writeString(root.resolve(".github/scripts/buildbuddy-bootstrap.sh"), "new JDK pin\n");
			assertNotEquals(run(root, "bash", scriptArgument, "environment"), environment);
		}
		finally
		{
			FixtureDirectories.deleteTree(root);
		}
	}

	/**
	 * Requires native consumers to wait for a verified runtime and fail rather than rebuild on a missing cache.
	 *
	 * @throws IOException if maintained workflows cannot be read
	 */
	@Test
	public void gatesParallelNativeConsumers() throws IOException
	{
		Path project = projectRoot();
		JsonNode jobs = YAMLMapper.builder().build().readTree(Files.readString(
			project.resolve(".github/workflows/bazel.yml"))).path("jobs");
		for (String consumer : List.of("test", "clippy", "verify-release-build",
			"test-windows-native-main", "test-windows-callback"))
		{
			JsonNode job = jobs.path(consumer);
			String producer = "prepare-macos-tooling";
			if (consumer.startsWith("test-windows"))
				producer = "prepare-windows-tooling";
			JsonNode needs = job.path("needs");
			boolean depends = needs.isString() && producer.equals(needs.asString());
			for (JsonNode dependency : needs)
				depends |= producer.equals(dependency.asString());
			assertTrue(depends, consumer + " must depend on " + producer);
			boolean requiresCache = false;
			for (JsonNode step : job.path("steps"))
				if ("./.github/actions/prepare-patched-upstream".equals(step.path("uses").asString()))
					requiresCache = "true".equals(step.path("with").path("require-tooling-cache").asString());
			assertTrue(requiresCache, consumer + " must require the producer's runtime cache");
		}
	}

	/**
	 * Locates the project containing the workflow selected by the maintained Maven wrapper.
	 *
	 * @return the project root
	 */
	private static Path projectRoot()
	{
		return Path.of(System.getProperty("tooling.release.workflow")).getParent().getParent().getParent();
	}

	/**
	 * Runs an actual command with isolated output and a bounded process lifetime.
	 *
	 * @param root the fixture working directory and output owner
	 * @param command the executable and literal arguments
	 * @return the successful command's trimmed standard output
	 * @throws IOException if process execution or output access fails
	 * @throws InterruptedException if command waiting is interrupted
	 */
	private static String run(Path root, String... command) throws IOException, InterruptedException
	{
		Path stdout = root.resolve("stdout");
		Path stderr = root.resolve("stderr");
		ProcessBuilder builder = NativeCommands.createBuilder(command).directory(root.toFile());
		builder.redirectOutput(stdout.toFile()).redirectError(stderr.toFile());
		try (Process process = builder.start())
		{
			assertTrue(process.waitFor(30, TimeUnit.SECONDS), "Command timed out: " + List.of(command));
			assertEquals(process.exitValue(), 0, Files.readString(stderr));
		}
		return Files.readString(stdout).strip();
	}
}
