package com.holdmyspot.codexunleashed.distribution;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Map;
import org.testng.annotations.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertTrue;

/**
 * Verifies publication and local manifest commands using the consumer's independent active patch queue.
 */
public final class ReleaseManifestWorkflowTest
{
	/**
	 * Creates the manifest workflow tests.
	 */
	public ReleaseManifestWorkflowTest()
	{
	}

	/**
	 * Runs the maintained publication step, the local producer command, and the unchanged consumer verifier.
	 * The consumer has no builder scratch checkout, and Python and external Java commands are rejected.
	 *
	 * @throws IOException if fixture setup, linking, or file access fails
	 * @throws URISyntaxException if a process fixture's class location is invalid
	 * @throws InterruptedException if a workflow process is interrupted
	 */
	@Test
	public void verifiesWithoutBuilderCheckout() throws IOException, URISyntaxException, InterruptedException
	{
		Path root = Files.createTempDirectory("manifest-workflow-");
		try
		{
			Path workflow = Path.of(System.getProperty("tooling.release.workflow"));
			Path project = workflow.getParent().getParent().getParent();
			Path builder = Files.createDirectory(root.resolve("builder"));
			Path consumer = Files.createDirectory(root.resolve("consumer"));
			for (Path checkout : new Path[]{builder, consumer})
			{
				Path queue = Files.createDirectories(checkout.resolve("patches/owner/repo/issue-1"));
				Files.writeString(queue.resolve("fix.patch"), "active queue patch\n");
			}
			Files.writeString(Files.createDirectories(builder.resolve("upstream-installers/internal/patches")).
				resolve("internal.patch"), "builder scratch patch\n");
			Path scripts = Files.createDirectory(builder.resolve("scripts"));
			for (String name : new String[]{"reproduce-release.sh", "verify-release.sh"})
				Files.copy(project.resolve("scripts").resolve(name), scripts.resolve(name));
			Path release = Files.createDirectory(builder.resolve("release-stage"));
			for (String name : new String[]{"codex-package-fixture.tar.gz", "install.sh", "install.ps1"})
				Files.write(release.resolve(name), new byte[]{0, (byte) 255, 1});
			Path bin = Files.createDirectory(root.resolve("bin"));
			for (String name : new String[]{"python", "python3", "java"})
				JavaCommandFixtures.writeLauncher(bin.resolve(name), RejectedCommandFixture.class);
			Path temporary = Files.createDirectory(root.resolve("temporary"));
			Path runtime = root.resolve("runtime with spaces");
			DistributionMain.main(new String[]{System.getProperty("tooling.runtime.modules"), runtime.toString()});
			Map<String, String> environment = Map.of("PATH", bin + java.io.File.pathSeparator + System.getenv("PATH"),
				"TMPDIR", temporary.toString(), "CODEX_UNLEASHED_TOOLING", runtime.resolve("bin/codex-tooling").toString(),
				"GITHUB_REPOSITORY", "holdmyspot-com/codex-unleashed");
			String command = bindPublication(WorkflowCommands.readStepCommand("publish", "Add reproducibility materials"));
			Path log = root.resolve("process.log");
			assertEquals(run(NativeCommands.createBuilder("bash", "-eu", "-c", command), builder, environment, log),
				0, Files.readString(log));
			assertManifest(release, "github-actions");
			assertEquals(run(NativeCommands.scriptBuilder(scripts.resolve("verify-release.sh"), release.toString(),
				"--patch-repo", consumer.toString()), consumer, environment, log), 0, Files.readString(log));
			assertTrue(Files.readString(log).contains("Release verification succeeded"));

			Path launcher = Files.createDirectories(builder.resolve("tooling/bin")).resolve("codex-tooling");
			Files.copy(project.resolve("tooling/bin/codex-tooling"), launcher, StandardCopyOption.COPY_ATTRIBUTES);
			Path image = builder.resolve(".cat/work/temp/build-caches/maven/target/tooling-distribution/runtime");
			Files.createDirectories(image.getParent());
			Files.createSymbolicLink(image, runtime);
			String script = Files.readString(project.resolve("scripts/build-release.sh"));
			int end = script.lastIndexOf("\necho \"Artifacts written");
			while (end > 0 && script.charAt(end - 1) == '\n')
				--end;
			int start = script.lastIndexOf("\n\n", end - 1) + 2;
			assertTrue(start > 1 && end > start, "Local release producer section is missing");
			Map<String, String> localEnvironment = new java.util.HashMap<>(environment);
			localEnvironment.put("repo_root", builder.toString());
			localEnvironment.put("output_dir", release.toString());
			localEnvironment.put("original_upstream_sha", "test-upstream-commit");
			localEnvironment.put("UPSTREAM_SOURCE_SHA", "test-upstream-commit");
			localEnvironment.put("UPSTREAM_TAG", "rust-v0.160.0");
			localEnvironment.put("PATCHED_TAG", "rust-v0.160.0+7");
			localEnvironment.put("CODEX_UNLEASHED_BUILD_NUMBER", "7");
			localEnvironment.put("PATCH_REPOSITORY", "holdmyspot-com/codex-unleashed");
			localEnvironment.put("UPSTREAM_REPOSITORY", "openai/codex");
			localEnvironment.put("build_date", "2026-10-06T12:34:56Z");
			localEnvironment.put("build_target", "fixture-target");
			String local = script.substring(start, end);
			assertEquals(run(NativeCommands.createBuilder("bash", "-eu", "-c", local), builder, localEnvironment, log),
				0, Files.readString(log));
			assertManifest(release, "local-script");
			assertEquals(run(NativeCommands.scriptBuilder(scripts.resolve("verify-release.sh"), release.toString(),
				"--patch-repo", consumer.toString()), consumer, environment, log), 0, Files.readString(log));
		}
		finally
		{
			FixtureDirectories.deleteTree(root);
		}
	}

	/**
	 * Binds controlled preparation outputs without replacing the maintained command or its options.
	 *
	 * @param command the maintained publication command
	 * @return the executable shell scalar
	 */
	private static String bindPublication(String command)
	{
		Map<String, String> values = Map.ofEntries(Map.entry("upstream_tag", "rust-v0.160.0"),
			Map.entry("upstream_sha", "test-upstream-commit"), Map.entry("patched_tag", "rust-v0.160.0+7"),
			Map.entry("build_number", "7"), Map.entry("build_date", "2026-10-06T12:34:56Z"),
			Map.entry("source_ref", "refs/heads/main"), Map.entry("source_sha", "a".repeat(40)),
			Map.entry("artifact_run_id", "100"), Map.entry("source_run_attempt", "2"),
			Map.entry("supported_targets", "fixture-target"));
		String bound = command;
		for (Map.Entry<String, String> entry : values.entrySet())
			bound = bound.replace("${{ needs.prepare.outputs." + entry.getKey() + " }}", entry.getValue());
		return bound;
	}

	/**
	 * Requires active-queue scope, complete artifact coverage, and unchanged explicit metadata.
	 *
	 * @param release the release directory
	 * @param builderType the expected builder type
	 * @throws IOException if the manifest cannot be read
	 */
	private static void assertManifest(Path release, String builderType) throws IOException
	{
		JsonNode manifest = JsonMapper.builder().build().readTree(Files.readString(
			release.resolve("release-manifest.json")));
		assertEquals(manifest.get("patches").size(), 1);
		assertEquals(manifest.get("patches").get(0).get("path").stringValue(), "patches/owner/repo/issue-1/fix.patch");
		assertEquals(manifest.get("artifacts").size(), 5);
		assertEquals(manifest.get("release").get("builder_type").stringValue(), builderType);
		assertEquals(manifest.get("release").get("patched_tag").stringValue(), "rust-v0.160.0+7");
		assertEquals(manifest.get("release").get("build_date").stringValue(), "2026-10-06T12:34:56Z");
		assertEquals(manifest.get("release").get("supported_targets").get(0).stringValue(), "fixture-target");
		assertEquals(manifest.get("upstream").get("commit").stringValue(), "test-upstream-commit");
		assertEquals(manifest.get("verification").get("consolidated_checksums").stringValue(), "SHA256SUMS");
		assertEquals(manifest.get("verification").get("attestation_bundle").stringValue(), "");
	}

	/**
	 * Runs a real shell or verifier with explicit fixture environment and owned process output.
	 *
	 * @param command the direct process arguments
	 * @param working the process working directory
	 * @param environment the explicit environment overrides
	 * @param log the output capture
	 * @return the process status
	 * @throws IOException if process creation fails
	 * @throws InterruptedException if execution is interrupted
	 */
	private static int run(ProcessBuilder command, Path working, Map<String, String> environment, Path log)
		throws IOException, InterruptedException
	{
		ProcessBuilder builder = command.directory(working.toFile()).
			redirectErrorStream(true).redirectOutput(log.toFile());
		builder.environment().putAll(environment);
		try (Process process = builder.start())
		{
			return process.waitFor();
		}
	}
}
