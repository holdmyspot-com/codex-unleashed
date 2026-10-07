package com.holdmyspot.codexunleashed.distribution;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.testng.annotations.Test;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertTrue;

/** Verifies the maintained blob-size step through a fresh runtime and committed Git objects. */
public final class BlobSizeWorkflowTest
{
	/** Creates workflow tests. */
	public BlobSizeWorkflowTest()
	{
	}

	/**
	 * Executes the actual policy step without Python or external Java, preserving reports and repository sources.
	 *
	 * @throws IOException if fixture, linking, execution, or cleanup fails
	 * @throws URISyntaxException if rejected-command fixture paths cannot be represented
	 * @throws InterruptedException if process waiting is interrupted
	 */
	@Test
	public void checksThroughMaintainedWorkflow() throws IOException, URISyntaxException, InterruptedException
	{
		Path root = Files.createTempDirectory("blob-size-workflow-");
		try
		{
			Path repository = Files.createDirectory(root.resolve("repository"));
			Path stdout = root.resolve("stdout");
			Path stderr = root.resolve("stderr");
			Map<String, String> environment = new HashMap<>();
			environment.put("GIT_CONFIG_NOSYSTEM", "1");
			environment.put("GIT_CONFIG_GLOBAL", root.resolve("absent-config").toString());
			for (List<String> arguments : List.of(List.of("init", "-q", "--template="),
				List.of("config", "user.name", "Fixture"), List.of("config", "user.email", "fixture@example.invalid"),
				List.of("config", "commit.gpgsign", "false")))
			{
				List<String> command = new java.util.ArrayList<>(List.of("git"));
				command.addAll(arguments);
				assertEquals(run(command, repository, environment, stdout, stderr), 0, Files.readString(stderr));
			}
			Files.createDirectory(repository.resolve(".github"));
			Files.writeString(repository.resolve(".github/blob-size-allowlist.txt"), "allowed.bin # fixture\n");
			String base = commit(repository, environment, stdout, stderr);
			Files.writeString(repository.resolve("at-limit.txt"), "a".repeat(512_000));
			Files.write(repository.resolve("allowed.bin"), new byte[512_001]);
			Files.writeString(repository.resolve("blocked 雪.txt"), "b".repeat(512_001));
			String head = commit(repository, environment, stdout, stderr);
			Path runtime = root.resolve("runtime with spaces");
			DistributionMain.main(new String[]{System.getProperty("tooling.runtime.modules"), runtime.toString()});
			Path commands = Files.createDirectory(root.resolve("commands"));
			for (String name : List.of("java", "python", "python3", "codex"))
				JavaCommandFixtures.writeLauncher(commands.resolve(name), RejectedCommandFixture.class);
			Path temporary = Files.createDirectory(root.resolve("temporary"));
			Path summary = root.resolve("summary");
			environment.put("PATH", commands + java.io.File.pathSeparator + System.getenv("PATH"));
			environment.put("CODEX_UNLEASHED_TOOLING", runtime.resolve("bin/codex-tooling").toString());
			environment.put("BASE_SHA", base);
			environment.put("HEAD_SHA", head);
			environment.put("GITHUB_STEP_SUMMARY", summary.toString());
			environment.put("TMPDIR", temporary.toString());
			environment.put("XDG_CACHE_HOME", root.resolve("xdg").toString());
			Path workflow = Path.of(System.getProperty("tooling.release.workflow")).resolveSibling("blob-size-policy.yml");
			String command = WorkflowCommands.readStepCommand(workflow, "check", "Check changed blob sizes");
			assertEquals(run(List.of("bash", "-c", command), repository, environment, stdout, stderr), 1,
				Files.readString(stderr));
			assertTrue(Files.readString(stdout).contains("Checked 3 changed file(s) against the 512000-byte limit."));
			String report = Files.readString(summary);
			assertTrue(report.contains("Violations: `1`"));
			assertTrue(report.contains("| `allowed.bin` | binary | `512001` bytes (500.0 KiB) | allowlisted |"));
			assertTrue(report.contains("| `at-limit.txt` | non-binary | `512000` bytes (500.0 KiB) | ok |"));
			environment.put("BASE_SHA", head);
			assertEquals(run(List.of("bash", "-c", command), repository, environment, stdout, stderr), 0,
				Files.readString(stderr));
			assertEquals(Files.readString(stdout), "No changed files were detected.\n");
			assertTrue(Files.readString(summary).endsWith("No changed files were detected.\n"));
			assertEquals(run(List.of("git", "status", "--porcelain"), repository, environment, stdout, stderr), 0);
			assertEquals(Files.readString(stdout), "");
			try (Stream<Path> files = Files.list(temporary))
			{
				assertEquals(files.count(), 0L);
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

	/**
	 * Commits all fixture files and returns the resulting revision.
	 *
	 * @param repository the isolated repository
	 * @param environment the fixture environment
	 * @param stdout the raw output capture
	 * @param stderr the diagnostic capture
	 * @return the commit SHA
	 * @throws IOException if process or capture access fails
	 * @throws InterruptedException if waiting is interrupted
	 */
	private static String commit(Path repository, Map<String, String> environment, Path stdout, Path stderr)
		throws IOException, InterruptedException
	{
		for (List<String> command : List.of(List.of("git", "add", "-A"), List.of("git", "commit", "-qm", "fixture"),
			List.of("git", "rev-parse", "HEAD")))
			assertEquals(run(command, repository, environment, stdout, stderr), 0, Files.readString(stderr));
		return Files.readString(stdout).strip();
	}

	/**
	 * Captures a native process with independent EOF input and owned output files.
	 *
	 * @param command the literal process arguments
	 * @param working the process directory
	 * @param environment the explicit environment overrides
	 * @param stdout the raw output capture
	 * @param stderr the diagnostic capture
	 * @return the process status
	 * @throws IOException if startup or input closing fails
	 * @throws InterruptedException if waiting is interrupted
	 */
	private static int run(List<String> command, Path working, Map<String, String> environment, Path stdout, Path stderr)
		throws IOException, InterruptedException
	{
		ProcessBuilder builder = new ProcessBuilder(command).directory(working.toFile()).redirectOutput(stdout.toFile()).
			redirectError(stderr.toFile());
		builder.environment().putAll(environment);
		try (Process process = builder.start())
		{
			process.getOutputStream().close();
			return process.waitFor();
		}
	}
}
