package com.holdmyspot.codexunleashed.distribution;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.testng.annotations.Test;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertTrue;

/**
 * Verifies the standalone license command against real offline Cargo metadata and locked dependency state.
 */
public final class LicenseMetadataCommandTest
{
	/**
	 * Creates the license command tests.
	 */
	public LicenseMetadataCommandTest()
	{
	}

	/**
	 * Copies resolved dependency bytes through the CLI and maintained release jobs, preserves Cargo diagnostics,
	 * and reports strict missing evidence without writing a successful workflow handoff.
	 * Cargo caches and process captures remain within the owned fixture under Maven's temporary cache.
	 *
	 * @throws IOException if fixture setup, linking, or process access fails
	 * @throws URISyntaxException if a rejected command fixture's class location is invalid
	 * @throws InterruptedException if a process is interrupted
	 */
	@Test
	public void collectsLockedDependencyMetadata() throws IOException, URISyntaxException, InterruptedException
	{
		Path root = Files.createTempDirectory("cargo-license-command-");
		try
		{
			Path workspace = Files.createDirectory(root.resolve("workspace"));
			Path dependency = Files.createDirectory(root.resolve("dependency"));
			Files.writeString(workspace.resolve("Cargo.toml"), """
				[package]
				name = "workspace-app"
				version = "0.1.0"
				edition = "2024"
				[workspace]
				[dependencies]
				external-crate = { path = "../dependency" }
				""");
			Files.writeString(Files.createDirectory(workspace.resolve("src")).resolve("lib.rs"), "");
			String dependencyManifest = """
				[package]
				name = "external-crate"
				version = "1.2.3"
				edition = "2024"
				license = "MIT"
				""";
			Files.writeString(dependency.resolve("Cargo.toml"), dependencyManifest);
			Files.writeString(Files.createDirectory(dependency.resolve("src")).resolve("lib.rs"), "");
			byte[] payload = {0, (byte) 255, 10};
			Files.write(dependency.resolve("LICENSE-MIT"), payload);
			Path temporary = Files.createDirectory(root.resolve("temporary"));
			Map<String, String> environment = Map.of(
				"CARGO_HOME", Files.createDirectory(root.resolve("cargo-home")).toString(),
				"CARGO_TARGET_DIR", root.resolve("cargo-target").toString(), "CARGO_NET_OFFLINE", "true",
				"XDG_CACHE_HOME", Files.createDirectory(root.resolve("xdg")).toString(), "TMPDIR", temporary.toString());
			Path log = root.resolve("process.log");
			assertEquals(run(List.of("cargo", "generate-lockfile", "--offline", "--manifest-path", "Cargo.toml"),
				workspace, environment, log), 0, Files.readString(log));
			Path runtime = root.resolve("runtime with spaces");
			DistributionMain.main(new String[]{System.getProperty("tooling.runtime.modules"), runtime.toString()});
			Path launcher = runtime.resolve("bin/codex-tooling");
			Path output = root.resolve("licenses");
			List<String> command = List.of(launcher.toString(), "collect-third-party-licenses", "--manifest=Cargo.toml",
				"--output", output.toString(), "--require-license-evidence");
			assertEquals(run(command, workspace, environment, log), 0, Files.readString(log));
			assertEquals(Files.readAllBytes(output.resolve("external-crate-1.2.3/LICENSE-MIT")), payload);
			assertFalse(Files.exists(output.resolve("workspace-app-0.1.0")));
			String notices = Files.readString(output.resolve("THIRD_PARTY_NOTICES.md"));
			assertTrue(notices.contains("`external-crate 1.2.3` — `MIT`"));
			Path bin = Files.createDirectory(root.resolve("rejected-commands"));
			for (String name : new String[]{"python", "python3", "java"})
				JavaCommandFixtures.writeLauncher(bin.resolve(name), RejectedCommandFixture.class);
			Path runnerTemp = Files.createDirectory(root.resolve("runner-temp"));
			Path workflowEnvironment = Files.writeString(root.resolve("workflow-env"), "");
			Path workflow = Path.of(System.getProperty("tooling.release.workflow"));
			Map<String, String> workflowOverrides = new HashMap<>(environment);
			workflowOverrides.put("PATH", bin + java.io.File.pathSeparator + System.getenv("PATH"));
			workflowOverrides.put("RUNNER_TEMP", runnerTemp.toString());
			workflowOverrides.put("GITHUB_WORKSPACE", workflow.getParent().getParent().getParent().toString());
			workflowOverrides.put("GITHUB_ENV", workflowEnvironment.toString());
			workflowOverrides.put("CODEX_UNLEASHED_TOOLING", launcher.toString());
			workflowOverrides.put("TARGET", "fixture-target");
			workflowOverrides.put("BUNDLE", "fixture-bundle");
			List<String> jobs = List.of("build-unix", "build-windows-binaries");
			for (String job : jobs)
			{
				String step = WorkflowCommands.readStepCommand(job, "Prepare third-party Cargo licenses");
				assertEquals(run(List.of("bash", "-eu", "-c", step), workspace, workflowOverrides, log), 0,
					Files.readString(log));
				assertEquals(Files.readAllBytes(runnerTemp.resolve(
					"codex-package-rust-licenses-fixture-target-fixture-bundle/external-crate-1.2.3/LICENSE-MIT")), payload);
			}
			String handoff = Files.readString(workflowEnvironment);
			assertEquals(handoff, "CODEX_PACKAGE_RUST_LICENSES_DIR=" +
				runnerTemp.resolve("codex-package-rust-licenses-fixture-target-fixture-bundle") + "\n");
			assertEquals(run(List.of(launcher.toString(), "collect-third-party-licenses", "--help"), workspace,
				environment, log), 0, Files.readString(log));
			List<String> explicitFlag = new ArrayList<>(command.subList(0, command.size() - 1));
			explicitFlag.add("--require-license-evidence=false");
			assertEquals(run(explicitFlag, workspace, environment, log), 2, Files.readString(log));

			Files.writeString(dependency.resolve("Cargo.toml"), dependencyManifest.replace("1.2.3", "1.2.4"));
			Path failedOutput = root.resolve("failed-cargo-output");
			List<String> failedCommand = new ArrayList<>(command);
			failedCommand.set(4, failedOutput.toString());
			assertEquals(run(failedCommand, workspace, environment, log), 1, Files.readString(log));
			assertTrue(Files.readString(log).contains("--locked"), Files.readString(log));
			assertFalse(Files.exists(failedOutput));

			Files.writeString(dependency.resolve("Cargo.toml"), dependencyManifest.replace("license = \"MIT\"\n", ""));
			Files.delete(dependency.resolve("LICENSE-MIT"));
			assertEquals(run(command, workspace, environment, log), 1, Files.readString(log));
			assertTrue(Files.readString(log).contains("Missing license evidence for: external-crate 1.2.3"));
			assertTrue(Files.readString(output.resolve("THIRD_PARTY_NOTICES.md")).
				contains("No license payload file was present"));
			assertEquals(run(command.subList(0, command.size() - 1), workspace, environment, log), 0,
				Files.readString(log));
			for (String job : jobs)
			{
				String step = WorkflowCommands.readStepCommand(job, "Prepare third-party Cargo licenses");
				assertEquals(run(List.of("bash", "-eu", "-c", step), workspace, workflowOverrides, log), 1,
					Files.readString(log));
				assertTrue(Files.readString(log).contains("Missing license evidence for: external-crate 1.2.3"));
				assertEquals(Files.readString(workflowEnvironment), handoff);
			}
			try (Stream<Path> paths = Files.list(temporary))
			{
				assertEquals(paths.count(), 0L);
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

	/**
	 * Executes real Cargo or the standalone runtime with explicit owned storage and captured diagnostics.
	 *
	 * @param command the direct process arguments
	 * @param working the working directory
	 * @param environment the storage and offline overrides
	 * @param log the output capture
	 * @return the process status
	 * @throws IOException if process creation fails
	 * @throws InterruptedException if execution is interrupted
	 */
	private static int run(List<String> command, Path working, Map<String, String> environment, Path log)
		throws IOException, InterruptedException
	{
		ProcessBuilder builder = new ProcessBuilder(command).directory(working.toFile()).
			redirectErrorStream(true).redirectOutput(log.toFile());
		builder.environment().putAll(environment);
		try (Process process = builder.start())
		{
			return process.waitFor();
		}
	}
}
