package com.holdmyspot.codexunleashed.distribution;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.testng.annotations.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertTrue;

/**
 * Executes both maintained source provenance steps with a bundled runtime and real Git repositories.
 */
public final class SourceProvenanceWorkflowTest
{
	/**
	 * Creates the source provenance workflow tests.
	 */
	public SourceProvenanceWorkflowTest()
	{
	}

	/**
	 * Publishes verified reports and rejects unrelated edits without replacing existing reports or indexes.
	 * Python and external Java commands are rejected, so only the bundled runtime can run the producer.
	 *
	 * @throws IOException if fixture setup, linking, or workflow access fails
	 * @throws URISyntaxException if a process fixture's class location is invalid
	 * @throws InterruptedException if a fixture or workflow process is interrupted
	 */
	@Test
	public void auditsBothReleaseJobs() throws IOException, URISyntaxException, InterruptedException
	{
		Path root = Files.createTempDirectory("source-provenance-workflow-");
		try
		{
			Path bin = Files.createDirectory(root.resolve("bin"));
			for (String name : new String[]{"python", "python3", "java"})
				JavaCommandFixtures.writeLauncher(bin.resolve(name), RejectedCommandFixture.class);
			Path config = Files.writeString(root.resolve("empty-config"), "");
			Path template = Files.createDirectory(root.resolve("empty-template"));
			Map<String, String> gitEnvironment = Map.of("GIT_CONFIG_NOSYSTEM", "1", "GIT_CONFIG_GLOBAL",
				config.toString(), "GIT_TEMPLATE_DIR", template.toString());
			Path runtime = root.resolve("runtime with spaces");
			DistributionMain.main(new String[]{System.getProperty("tooling.runtime.modules"), runtime.toString()});
			for (String job : new String[]{"build-unix", "build-windows-binaries"})
			{
				Path working = Files.createDirectory(root.resolve(job));
				Path temporary = Files.createDirectory(working.resolve("temporary"));
				Path checkout = Files.createDirectory(working.resolve("upstream"));
				git(checkout, gitEnvironment, "init", "--initial-branch=main");
				Files.writeString(checkout.resolve("source.txt"), "before\n");
				Files.writeString(checkout.resolve(".gitignore"), "ignored.bin\ntarget/\n");
				commit(checkout, gitEnvironment, "baseline");
				String baseline = git(checkout, gitEnvironment, "rev-parse", "HEAD").strip();
				Files.writeString(checkout.resolve("source.txt"), "after é\n");
				Files.write(checkout.resolve("ignored.bin"), new byte[]{0, (byte) 255, 1});
				git(checkout, gitEnvironment, "add", "--force", "ignored.bin");
				commit(checkout, gitEnvironment, "patched");
				String patchedTree = git(checkout, gitEnvironment, "rev-parse", "HEAD^{tree}").strip();
				Path patch = Files.createDirectories(working.resolve("patches")).resolve("source.patch");
				Files.writeString(patch, git(checkout, gitEnvironment, "format-patch", "-1", "--stdout", "--binary"));
				git(checkout, gitEnvironment, "reset", "--hard", baseline);
				git(checkout, gitEnvironment, "apply", patch.toString());
				byte[] index = Files.readAllBytes(checkout.resolve(".git/index"));
				String command = WorkflowCommands.readStepCommand(job, "Record source provenance").
					replace("${{ matrix.target }}", "fixture-target").replace("${{ matrix.bundle }}", "fixture-bundle");
				Path report = working.resolve("source-provenance-fixture-target-fixture-bundle.json");
				Path log = working.resolve("workflow.log");
				assertEquals(runWorkflow(command, working, temporary, bin, runtime, gitEnvironment, log), 0,
					Files.readString(log));
				assertEquals(Files.readAllBytes(checkout.resolve(".git/index")), index);
				JsonNode document = JsonMapper.builder().build().readTree(Files.readString(report));
				assertTrue(document.get("source_verified").booleanValue());
				assertEquals(document.get("patched_tree").stringValue(), patchedTree);
				assertEquals(document.get("upstream").get("commit").stringValue(), baseline);
				assertEquals(document.get("upstream").get("repository").stringValue(), "openai/codex");
				assertEquals(document.get("upstream").get("ref").stringValue(), "rust-v0.160.0");
				assertEquals(document.get("workflow").get("path").stringValue(), ".github/workflows/build-release.yml");
				assertEquals(document.get("workflow").get("sha").stringValue(), "a".repeat(40));
				assertEquals(document.get("workflow").get("run_id").stringValue(), "7");
				assertEquals(document.get("patches").size(), 1);
				assertEquals(document.get("changed_paths").size(), 2);
				assertFalse(Files.exists(working.resolve("source-provenance.json")));
				assertTemporaryEmpty(temporary);

				String defaultsCommand = "\"$CODEX_UNLEASHED_TOOLING\" generate-source-provenance " +
					"--upstream-checkout=missing --upstream-checkout=upstream --patch-r=. --output=default-report.json";
				assertEquals(runWorkflow(defaultsCommand, working, temporary, bin, runtime, gitEnvironment, log), 0,
					Files.readString(log));
				JsonNode defaults = JsonMapper.builder().build().readTree(Files.readString(
					working.resolve("default-report.json")));
				assertEquals(defaults.get("upstream").get("repository").stringValue(), "openai/codex");
				assertEquals(defaults.get("upstream").get("ref").stringValue(), "");
				assertEquals(defaults.get("workflow").get("run_id").stringValue(), "");
				assertEquals(defaults.get("workflow").get("sha").stringValue(), "");
				assertEquals(defaults.get("workflow").get("path").stringValue(), "");
				assertEquals(Files.readAllBytes(checkout.resolve(".git/index")), index);
				assertTemporaryEmpty(temporary);

				byte[] reportBytes = Files.readAllBytes(report);
				Files.writeString(checkout.resolve("unexpected.txt"), "unexplained source");
				Files.writeString(working.resolve("source-provenance.json"), "previous staging report");
				assertEquals(runWorkflow(command, working, temporary, bin, runtime, gitEnvironment, log), 1);
				assertTrue(Files.readString(log).contains("Source audit failed"), Files.readString(log));
				assertEquals(Files.readAllBytes(checkout.resolve(".git/index")), index);
				assertEquals(Files.readAllBytes(report), reportBytes);
				assertEquals(Files.readString(working.resolve("source-provenance.json")), "previous staging report");
				assertTemporaryEmpty(temporary);
			}
		}
		finally
		{
			FixtureDirectories.deleteTree(root);
		}
	}

	/**
	 * Runs the maintained shell command with explicit metadata and fixture-owned temporary storage.
	 *
	 * @param command the actual maintained workflow command
	 * @param working the fixture workflow checkout
	 * @param temporary the producer's temporary storage
	 * @param bin the rejected runtime adapters
	 * @param runtime the fresh standalone runtime
	 * @param gitEnvironment the isolated Git configuration
	 * @param log the process output destination
	 * @return the workflow process status
	 * @throws IOException if process creation fails
	 * @throws InterruptedException if the process is interrupted
	 */
	private static int runWorkflow(String command, Path working, Path temporary, Path bin, Path runtime,
		Map<String, String> gitEnvironment, Path log) throws IOException, InterruptedException
	{
		ProcessBuilder builder = NativeCommands.createBuilder("bash", "-eu", "-c", command).directory(working.toFile()).
			redirectErrorStream(true).redirectOutput(log.toFile());
		Map<String, String> environment = builder.environment();
		environment.putAll(gitEnvironment);
		environment.put("PATH", bin + java.io.File.pathSeparator + environment.get("PATH"));
		environment.put("TMPDIR", temporary.toString());
		environment.put("CODEX_UNLEASHED_TOOLING", runtime.resolve("bin/codex-tooling").toString());
		environment.put("UPSTREAM_REF", "rust-v0.160.0");
		environment.put("WORKFLOW_PATH", ".github/workflows/build-release.yml");
		environment.put("WORKFLOW_SHA", "a".repeat(40));
		environment.put("WORKFLOW_RUN_ID", "7");
		try (Process process = builder.start())
		{
			return process.waitFor();
		}
	}

	/**
	 * Commits a fixture source state with explicit identity and signing policy.
	 *
	 * @param checkout the fixture Git checkout
	 * @param environment the isolated Git configuration
	 * @param message the commit message
	 * @throws IOException if Git fails
	 * @throws InterruptedException if Git is interrupted
	 */
	private static void commit(Path checkout, Map<String, String> environment, String message)
		throws IOException, InterruptedException
	{
		git(checkout, environment, "add", ".");
		git(checkout, environment, "-c", "user.name=Fixture", "-c", "user.email=fixture@example.invalid",
			"-c", "commit.gpgsign=false", "commit", "-qm", message);
	}

	/**
	 * Runs real Git with direct arguments and captures its output beneath the owned fixture.
	 *
	 * @param checkout the fixture Git checkout
	 * @param environment the isolated Git configuration
	 * @param arguments the literal Git arguments
	 * @return the unmodified command output
	 * @throws IOException if Git fails
	 * @throws InterruptedException if Git is interrupted
	 */
	private static String git(Path checkout, Map<String, String> environment, String... arguments)
		throws IOException, InterruptedException
	{
		List<String> command = new ArrayList<>(List.of("git", "-C", checkout.toString()));
		command.addAll(Arrays.asList(arguments));
		Path log = checkout.getParent().resolve("git.log");
		ProcessBuilder builder = NativeCommands.createBuilder(command).redirectErrorStream(true).
			redirectOutput(log.toFile());
		builder.environment().putAll(environment);
		try (Process process = builder.start())
		{
			int status = process.waitFor();
			String output = Files.readString(log);
			if (status != 0)
				throw new IOException("Git fixture failed: " + output);
			return output;
		}
	}

	/**
	 * Requires removal of both audit indexes and command captures after success or rejection.
	 *
	 * @param temporary the producer's temporary storage
	 * @throws IOException if the directory cannot be inspected
	 */
	private static void assertTemporaryEmpty(Path temporary) throws IOException
	{
		try (Stream<Path> paths = Files.list(temporary))
		{
			assertEquals(paths.count(), 0L);
		}
	}
}
