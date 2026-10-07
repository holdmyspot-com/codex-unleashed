package com.holdmyspot.codexunleashed.distribution;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.testng.annotations.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertTrue;

/** Executes source-cache release steps through a freshly linked runtime and actual offline Cargo. */
public final class SourceCacheWorkflowTest
{
	/** Creates workflow tests. */
	public SourceCacheWorkflowTest()
	{
	}

	/**
	 * Executes the maintained Windows helper audit command against an actual native Cargo package.
	 *
	 * @throws IOException if fixture, runtime or process operations fail
	 * @throws InterruptedException if process waiting is interrupted
	 */
	@Test
	public void auditsActualHelperLibraryReuse() throws IOException, InterruptedException
	{
		Path root = Files.createTempDirectory("helper-cache-audit-");
		try
		{
			Path workspace = Files.createDirectories(root.resolve("workspace/src")).getParent();
			Files.writeString(workspace.resolve("Cargo.toml"),
				"[package]\nname = \"codex-windows-sandbox\"\nversion = \"0.1.0\"\nedition = \"2021\"\n");
			Files.writeString(workspace.resolve("src/lib.rs"), "pub fn value() -> u8 { 7 }\n");
			Files.writeString(workspace.resolve("src/main.rs"),
				"fn main() { let unused_warning = 1; println!(\"{}\", codex_windows_sandbox::value()); }\n");
			Map<String, String> environment = new HashMap<>(System.getenv());
			for (String name : List.of("cargo-home", "target", "xdg", "captures", "reports"))
				Files.createDirectory(root.resolve(name));
			environment.put("CARGO_HOME", root.resolve("cargo-home").toString());
			environment.put("CARGO_TARGET_DIR", root.resolve("target").toString());
			environment.put("CARGO_NET_OFFLINE", "true");
			environment.put("XDG_CACHE_HOME", root.resolve("xdg").toString());
			environment.put("TMPDIR", root.resolve("captures").toString());
			environment.put("RUNNER_TEMP", root.resolve("reports").toString());
			environment.put("GIT_CEILING_DIRECTORIES", root.toString());
			environment.put("FIXTURE_WORKSPACE", workspace.toString());
			Path project = Path.of(System.getProperty("tooling.release.workflow")).getParent().getParent().getParent();
			environment.put("GITHUB_WORKSPACE", project.toString());
			String target = run(root, environment, List.of("rustc", "-vV")).lines().
				filter(line -> line.startsWith("host: ")).findFirst().orElseThrow().substring(6);
			Path image = root.resolve("runtime with spaces");
			DistributionMain.main(new String[]{System.getProperty("tooling.runtime.modules"), image.toString()});
			String launcher = image.resolve("bin/codex-tooling").toString();
			run(root, environment, List.of(launcher, "clean-cached-release-binaries", workspace.toString(), target,
				"codex-windows-sandbox"));
			run(root, environment, List.of("cargo", "build", "--offline", "--release", "--target", target,
				"--manifest-path", workspace.resolve("Cargo.toml").toString()));
			run(root, environment, List.of(launcher, "clean-cached-release-binaries", "--record-source-inputs",
				workspace.toString(), target));
			run(root, environment, List.of(launcher, "clean-cached-release-binaries", workspace.toString(), target,
				"codex-windows-sandbox"));
			String command = WorkflowCommands.readStepCommand("build-windows-binaries",
				"Build Windows sandbox helper binaries").
				replace("${{ matrix.target }}", target).replace("${{ matrix.bundle }}", "primary").
				replace("${{ matrix.helper_binaries }}", "codex-windows-sandbox");
			run(root, environment, List.of("bash", "-eu", "-c", "cd \"$FIXTURE_WORKSPACE\"\n" + command));
			assertTrue(Files.readString(root.resolve("process.stderr")).contains("unused_warning"));
			Path report = root.resolve("reports/cargo-cache-reuse-" + target + "-primary.jsonl");
			int libraries = 0;
			int binaries = 0;
			JsonNode last = null;
			for (String line : Files.readAllLines(report))
			{
				last = JsonMapper.builder().build().readTree(line);
				if (!last.path("reason").asString().equals("compiler-artifact"))
					continue;
				if (last.path("target").path("name").asString().equals("codex_windows_sandbox"))
				{
					assertEquals(last.path("target").path("kind").get(0).asString(), "lib");
					assertTrue(last.path("fresh").booleanValue());
					libraries += 1;
				}
				if (last.path("target").path("kind").get(0).asString().equals("bin"))
				{
					assertFalse(last.path("fresh").booleanValue());
					binaries += 1;
				}
			}
			assertEquals(libraries, 1);
			assertEquals(binaries, 1);
			assertEquals(last, JsonMapper.builder().build().readTree("{\"reason\":\"build-finished\",\"success\":true}"));
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
	 * Preserves cache reuse and exact native timestamps through both jobs' maintained Bash commands.
	 *
	 * @throws IOException if fixture, runtime or process operations fail
	 * @throws InterruptedException if process waiting is interrupted
	 */
	@Test
	public void executesBothReleaseCacheCallers() throws IOException, InterruptedException
	{
		Path root = Files.createTempDirectory("source-cache-workflow-");
		try
		{
			Path workspace = Files.createDirectories(root.resolve("upstream/codex-rs/src")).getParent();
			Path source = workspace.resolve("src/lib.rs");
			Files.writeString(workspace.resolve("Cargo.toml"), """
				[package]
				name = "release-demo"
				version = "0.1.0"
				edition = "2021"
				[workspace]
				""");
			Files.writeString(source, "pub fn value() -> u8 { 7 }\n");
			Files.writeString(workspace.resolve("src/main.rs"),
				"fn main() { println!(\"{}\", release_demo::value()); }\n");
			Map<String, String> environment = new HashMap<>(System.getenv());
			for (String name : List.of("cargo-home", "target", "xdg", "captures"))
				Files.createDirectory(root.resolve(name));
			environment.put("CARGO_HOME", root.resolve("cargo-home").toString());
			environment.put("CARGO_TARGET_DIR", root.resolve("target").toString());
			environment.put("CARGO_NET_OFFLINE", "true");
			environment.put("XDG_CACHE_HOME", root.resolve("xdg").toString());
			environment.put("TMPDIR", root.resolve("captures").toString());
			environment.put("GIT_CEILING_DIRECTORIES", root.toString());
			String target = run(root, environment, List.of("rustc", "-vV")).lines().
				filter(line -> line.startsWith("host: ")).findFirst().
				orElseThrow(() -> new IOException("rustc did not report its native host")).substring(6);
			Path image = root.resolve("runtime with spaces");
			DistributionMain.main(new String[]{System.getProperty("tooling.runtime.modules"), image.toString()});
			environment.put("CODEX_UNLEASHED_TOOLING", image.resolve("bin/codex-tooling").toString());
			String originalNanos = timestamp(root, environment, source);
			Path snapshot = root.resolve("target/.codex-source-inputs/" + target + ".json");
			for (String job : List.of("build-unix", "build-windows-binaries"))
			{
				String prepare = command(job, "Rebuild release binaries from cached dependencies", target);
				String record = command(job, "Record successfully compiled source inputs", target);
				run(root, environment, List.of("bash", "-eu", "-c", prepare));
				build(root, environment, workspace, target);
				run(root, environment, List.of("bash", "-eu", "-c", record));
				assertTrue(Files.isRegularFile(snapshot));
				Files.setLastModifiedTime(source, FileTime.from(Instant.ofEpochSecond(1)));
				run(root, environment, List.of("bash", "-eu", "-c", prepare));
				assertFalse(Files.exists(root.resolve("target").resolve(target).resolve("release/release-demo")));
				assertEquals(timestamp(root, environment, source), originalNanos);
				String output = build(root, environment, workspace, target);
				boolean librarySeen = false;
				for (String line : output.lines().toList())
				{
					JsonNode artifact = JsonMapper.builder().build().readTree(line);
					if (artifact.path("reason").asString().equals("compiler-artifact") &&
						artifact.path("target").path("name").asString().equals("release_demo"))
					{
						assertTrue(artifact.path("fresh").booleanValue(), output);
						librarySeen = true;
					}
				}
				assertTrue(librarySeen, output);
				run(root, environment, List.of("bash", "-eu", "-c", record));
			}

			Files.delete(snapshot);
			run(root, environment, List.of("touch", "-d", "2300-01-01T00:00:00.123456789Z", source.toString()));
			String futureNanos = timestamp(root, environment, source);
			String prepare = command("build-unix", "Rebuild release binaries from cached dependencies", target);
			String record = command("build-unix", "Record successfully compiled source inputs", target);
			run(root, environment, List.of("bash", "-eu", "-c", prepare));
			build(root, environment, workspace, target);
			run(root, environment, List.of("bash", "-eu", "-c", record));
			Files.setLastModifiedTime(source, FileTime.from(Instant.ofEpochSecond(1)));
			run(root, environment, List.of("bash", "-eu", "-c", prepare));
			assertEquals(timestamp(root, environment, source), futureNanos);
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
	 * Reads nanoseconds using an independent native Node filesystem consumer.
	 *
	 * @param root the process directory
	 * @param environment explicit cache paths
	 * @param source the source filename
	 * @return exact native epoch nanoseconds
	 * @throws IOException if process or file access fails
	 * @throws InterruptedException if waiting is interrupted
	 */
	private static String timestamp(Path root, Map<String, String> environment, Path source)
		throws IOException, InterruptedException
	{
		return run(root, environment, List.of("node", "-e",
			"console.log(require('node:fs').statSync(process.argv[1], {bigint:true}).mtimeNs.toString())",
			source.toString())).strip();
	}

	/**
	 * Resolves matrix substitutions in a maintained step while requiring the Java launcher.
	 *
	 * @param job the release job
	 * @param step its step name
	 * @param target the actual native target
	 * @return the executable Bash command
	 * @throws IOException if workflow access fails
	 */
	private static String command(String job, String step, String target) throws IOException
	{
		String value = WorkflowCommands.readStepCommand(job, step);
		assertTrue(value.contains("CODEX_UNLEASHED_TOOLING"), value);
		assertFalse(value.contains("python"), value);
		return value.replace("${{ matrix.target }}", target).replace("${{ matrix.binaries }}", "release-demo").
			replace("${{ matrix.helper_binaries }}", "");
	}

	/**
	 * Compiles the actual fixture release package offline.
	 *
	 * @param root the process directory
	 * @param environment explicit cache paths
	 * @param workspace the manifest workspace
	 * @param target the native target
	 * @return Cargo artifact JSON
	 * @throws IOException if process access fails
	 * @throws InterruptedException if waiting is interrupted
	 */
	private static String build(Path root, Map<String, String> environment, Path workspace, String target)
		throws IOException, InterruptedException
	{
		return run(root, environment, List.of("cargo", "build", "--offline", "--release", "--target", target,
			"--manifest-path", workspace.resolve("Cargo.toml").toString(), "--message-format=json"));
	}

	/**
	 * Captures a real process using fixture-owned files and independent EOF input.
	 *
	 * @param root the fixture directory
	 * @param environment its effective environment
	 * @param arguments direct process arguments
	 * @return standard output
	 * @throws IOException if process or file access fails
	 * @throws InterruptedException if waiting is interrupted
	 */
	private static String run(Path root, Map<String, String> environment, List<String> arguments)
		throws IOException, InterruptedException
	{
		Path output = root.resolve("process.stdout");
		Path errors = root.resolve("process.stderr");
		Path input = Files.writeString(root.resolve("process.stdin"), "");
		ProcessBuilder builder = new ProcessBuilder(arguments).directory(root.toFile()).
			redirectInput(input.toFile()).redirectOutput(output.toFile()).redirectError(errors.toFile());
		builder.environment().clear();
		builder.environment().putAll(environment);
		try (Process process = builder.start())
		{
			try
			{
				assertTrue(process.waitFor(60, TimeUnit.SECONDS), "Process timed out: " + arguments);
				assertEquals(process.exitValue(), 0, arguments + "\n" + Files.readString(output) + Files.readString(errors));
			}
			finally
			{
				if (process.isAlive())
					process.destroyForcibly().waitFor();
			}
		}
		return Files.readString(output);
	}
}
