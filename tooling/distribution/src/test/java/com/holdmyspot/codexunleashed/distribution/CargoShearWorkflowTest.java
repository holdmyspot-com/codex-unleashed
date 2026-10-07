package com.holdmyspot.codexunleashed.distribution;

import java.io.File;
import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.testng.annotations.Test;
import tools.jackson.databind.json.JsonMapper;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertTrue;

/**
 * Executes the maintained Cargo Shear workflow with a real isolated Git checkout and a Java tool fixture.
 */
public final class CargoShearWorkflowTest
{
	/**
	 * Creates the workflow tests.
	 */
	public CargoShearWorkflowTest()
	{
	}

	/**
	 * Accepts verified warning output, rejects changed baseline sources, and prevents Python invocation.
	 * The maintained command receives its supported Cargo Shear executable override for the process fixture.
	 *
	 * @throws IOException if fixture access, runtime linking, or command execution fails
	 * @throws InterruptedException if a command wait is interrupted
	 * @throws URISyntaxException if a Java fixture class location is invalid
	 */
	@Test
	public void checksReleaseWarnings() throws IOException, InterruptedException, URISyntaxException
	{
		Path root = Files.createTempDirectory("shear-workflow-");
		try
		{
			Path configuration = Files.writeString(root.resolve("empty-git-config"), "");
			Path template = Files.createDirectory(root.resolve("empty-git-template"));
			Path checkout = Files.createDirectory(root.resolve("upstream"));
			Path cargo = Files.createDirectory(checkout.resolve("codex-rs"));
			Files.writeString(cargo.resolve("Cargo.toml"), "fixture");
			Path source = Files.writeString(cargo.resolve("source.txt"), "unchanged");
			git(root, configuration, template, "init", "-q", checkout.toString());
			git(root, configuration, template, "-C", checkout.toString(), "add", "codex-rs");
			git(root, configuration, template, "-C", checkout.toString(), "-c", "user.name=Fixture",
				"-c", "user.email=fixture@example.invalid", "-c", "commit.gpgsign=false", "commit", "-qm", "fixture");
			String sha = git(root, configuration, template, "-C", checkout.toString(), "rev-parse", "HEAD").strip();
			List<Map<String, String>> findings = List.of(Map.of("severity", "warning", "message", "known orphan"));
			JsonMapper mapper = JsonMapper.builder().build();
			Path baseline = Files.createDirectories(root.resolve(".github/scripts")).resolve("cargo-shear-baseline.json");
			Files.writeString(baseline, mapper.writeValueAsString(Map.of("source_sha", sha,
				"source_paths", List.of("codex-rs/source.txt"), "findings", findings)));
			Path report = root.resolve("shear-report.json");
			Files.writeString(report, mapper.writeValueAsString(Map.of("summary", Map.of("errors", 0, "warnings", 1),
				"findings", findings)) + "\n");
			Path adapters = Files.createDirectory(root.resolve("process adapters"));
			JavaCommandFixtures.writeLauncher(adapters.resolve("python3"), RejectedCommandFixture.class);
			Path shear = JavaCommandFixtures.writeNativeLauncher(adapters.resolve("cargo-shear"),
				CargoShearReportFixture.class);
			Path image = root.resolve("runtime with spaces");
			DistributionMain.main(new String[]{System.getProperty("tooling.runtime.modules"), image.toString()});
			Path captures = Files.createDirectory(root.resolve("captures"));
			Path workflow = Path.of(System.getProperty("tooling.release.workflow")).resolveSibling("rust-ci.yml");
			String command = WorkflowCommands.readStepCommand(workflow, "cargo_shear", "cargo shear") +
				" --cargo-shear \"$CARGO_SHEAR_TEST_EXECUTABLE\"";
			for (boolean changed : new boolean[]{false, true})
			{
				int expectedStatus = 0;
				if (changed)
				{
					Files.writeString(source, "changed");
					expectedStatus = 1;
				}
				Path log = root.resolve("workflow.log");
				ProcessBuilder builder = NativeCommands.createBuilder("bash", "-eu", "-c", command);
				builder.directory(root.toFile()).redirectErrorStream(true).redirectOutput(log.toFile());
				builder.environment().put("CODEX_UNLEASHED_TOOLING", image.resolve("bin/codex-tooling").toString());
				builder.environment().put("PATH", adapters + File.pathSeparator +
					builder.environment().getOrDefault("PATH", ""));
				builder.environment().put("CARGO_SHEAR_TEST_EXECUTABLE", shear.toString());
				builder.environment().put("CARGO_SHEAR_TEST_REPORT", report.toString());
				builder.environment().put("TMPDIR", captures.toString());
				isolateGit(builder, configuration, template);
				assertEquals(run(builder, log), expectedStatus, Files.readString(log));
				assertTrue(Files.readString(log).contains("shear diagnostics"));
				assertEquals(Files.readString(log).contains("Only verified, unchanged upstream release warnings remain."),
					!changed);
			}
			try (Stream<Path> files = Files.list(captures))
			{
				assertEquals(files.count(), 0L);
			}
		}
		finally
		{
			FixtureDirectories.deleteTree(root);
		}
	}

	/**
	 * Executes a real Git operation with isolated configuration and template paths.
	 *
	 * @param root the fixture working directory
	 * @param configuration the empty Git configuration file
	 * @param template the empty repository template directory
	 * @param arguments the Git arguments
	 * @return the command's output
	 * @throws IOException if startup or output access fails
	 * @throws InterruptedException if the Git wait is interrupted
	 */
	private static String git(Path root, Path configuration, Path template, String... arguments)
		throws IOException, InterruptedException
	{
		List<String> command = new ArrayList<>(List.of("git"));
		command.addAll(Arrays.asList(arguments));
		Path log = root.resolve("git.log");
		ProcessBuilder builder = NativeCommands.createBuilder(command);
		builder.directory(root.toFile()).redirectErrorStream(true).redirectOutput(log.toFile());
		isolateGit(builder, configuration, template);
		assertEquals(run(builder, log), 0, Files.readString(log));
		return Files.readString(log);
	}

	/**
	 * Supplies explicit, fixture-owned Git configuration instead of consuming the user's hooks or configuration.
	 *
	 * @param builder the child process builder
	 * @param configuration the empty Git configuration file
	 * @param template the empty repository template directory
	 */
	private static void isolateGit(ProcessBuilder builder, Path configuration, Path template)
	{
		builder.environment().put("GIT_CONFIG_NOSYSTEM", "1");
		builder.environment().put("GIT_CONFIG_GLOBAL", configuration.toString());
		builder.environment().put("GIT_TEMPLATE_DIR", template.toString());
	}

	/**
	 * Retains the command's terminal status and closes its process resources.
	 *
	 * @param builder the configured child command
	 * @param log the redirected output path
	 * @return the completed exit status
	 * @throws IOException if process startup or close fails
	 * @throws InterruptedException if the command wait is interrupted
	 */
	private static int run(ProcessBuilder builder, Path log) throws IOException, InterruptedException
	{
		try (Process process = builder.start())
		{
			try
			{
				assertTrue(process.waitFor(30, TimeUnit.SECONDS), "Command timed out: " + log);
				return process.exitValue();
			}
			finally
			{
				if (process.isAlive())
					process.destroyForcibly().waitFor();
			}
		}
	}
}
