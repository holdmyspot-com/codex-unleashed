package com.holdmyspot.codexunleashed.distribution;

import java.io.File;
import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.testng.annotations.Test;
import tools.jackson.databind.json.JsonMapper;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertTrue;

/**
 * Executes the maintained Cargo Shear command with an isolated checkout and a Java tool fixture.
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
	 * Accepts clean output, rejects warning output even with a zero tool status, and prevents Python invocation.
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
			Path checkout = Files.createDirectory(root.resolve("upstream"));
			Path cargo = Files.createDirectory(checkout.resolve("codex-rs"));
			Files.writeString(cargo.resolve("Cargo.toml"), "fixture");
			List<Map<String, String>> findings = List.of(Map.of("severity", "warning", "message", "known orphan"));
			JsonMapper mapper = JsonMapper.builder().build();
			Path report = root.resolve("shear-report.json");
			Path adapters = Files.createDirectory(root.resolve("process adapters"));
			JavaCommandFixtures.writeLauncher(adapters.resolve("python3"), RejectedCommandFixture.class);
			Path shear = JavaCommandFixtures.writeNativeLauncher(adapters.resolve("cargo-shear"),
				CargoShearReportFixture.class);
			Path image = root.resolve("runtime with spaces");
			DistributionMain.main(new String[]{System.getProperty("tooling.runtime.modules"), image.toString()});
			Path captures = Files.createDirectory(root.resolve("captures"));
			String command = "\"$CODEX_UNLEASHED_TOOLING\" check-cargo-shear upstream" +
				" --cargo-shear \"$CARGO_SHEAR_TEST_EXECUTABLE\"";
			for (int scenario = 0; scenario < 3; ++scenario)
			{
				boolean warning = scenario > 0;
				int expectedStatus = 0;
				List<Map<String, String>> reportedFindings = List.of();
				if (warning)
				{
					expectedStatus = 1;
					reportedFindings = findings;
				}
				Files.writeString(report, mapper.writeValueAsString(Map.of(
					"summary", Map.of("errors", 0, "warnings", expectedStatus),
					"findings", reportedFindings)) + "\n");
				Path log = root.resolve("workflow.log");
				ProcessBuilder builder = NativeCommands.createBuilder("bash", "-eu", "-c", command);
				builder.directory(root.toFile()).redirectErrorStream(true).redirectOutput(log.toFile());
				builder.environment().put("CODEX_UNLEASHED_TOOLING", image.resolve("bin/codex-tooling").toString());
				builder.environment().put("PATH", adapters + File.pathSeparator +
					builder.environment().getOrDefault("PATH", ""));
				builder.environment().put("CARGO_SHEAR_TEST_EXECUTABLE", shear.toString());
				builder.environment().put("CARGO_SHEAR_TEST_REPORT", report.toString());
				builder.environment().put("TMPDIR", captures.toString());
				String toolStatus = "0";
				if (scenario == 2)
					toolStatus = "1";
				builder.environment().put("CARGO_SHEAR_TEST_STATUS", toolStatus);
				assertEquals(run(builder, log), expectedStatus, Files.readString(log));
				assertTrue(Files.readString(log).contains("shear diagnostics"));
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
