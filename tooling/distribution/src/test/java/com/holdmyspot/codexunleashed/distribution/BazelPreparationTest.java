package com.holdmyspot.codexunleashed.distribution;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.testng.annotations.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.dataformat.yaml.YAMLMapper;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertTrue;

/** Checks the maintained Bazel preparation contract with a real offline Cargo fixture. */
public final class BazelPreparationTest
{
	/** Creates preparation tests. */
	public BazelPreparationTest()
	{
	}

	/**
	 * Runs the maintained metadata command and preserves the resolved dependency while refreshing local versions.
	 *
	 * @throws IOException if action, fixture, command or cleanup operations fail
	 * @throws InterruptedException if process waiting is interrupted
	 */
	@Test
	public void refreshesReleasePackageWithCargo() throws IOException, InterruptedException
	{
		String command = Files.readString(action()).lines().map(String::strip).
			filter(line -> line.startsWith("cargo metadata ")).findFirst().orElseThrow();
		Path root = Files.createTempDirectory("bazel-preparation-");
		try
		{
			Files.createDirectories(root.resolve("src"));
			Files.writeString(root.resolve("src/lib.rs"), "");
			Files.createDirectories(root.resolve("dependency/src"));
			Files.writeString(root.resolve("dependency/src/lib.rs"), "");
			Files.writeString(root.resolve("dependency/Cargo.toml"),
				"[package]\nname = \"dependency\"\nversion = \"0.12.28\"\nedition = \"2021\"\n");
			Files.writeString(root.resolve("Cargo.toml"),
				"[package]\nname = \"release-package\"\nversion = \"0.159.2\"\nedition = \"2021\"\n" +
				"[dependencies]\ndependency = { path = \"dependency\", version = \"0.12.28\" }\n");
			Files.writeString(root.resolve("Cargo.lock"), "version = 4\n[[package]]\n" +
				"name = \"release-package\"\nversion = \"0.0.0\"\ndependencies = [\"dependency\"]\n" +
				"[[package]]\nname = \"dependency\"\nversion = \"0.12.28\"\n");
			Map<String, String> environment = new HashMap<>(System.getenv());
			environment.put("CARGO_HOME", Files.createDirectory(root.resolve("cargo-home")).toString());
			environment.put("CARGO_TARGET_DIR", root.resolve("cargo-target").toString());
			environment.put("CARGO_NET_OFFLINE", "true");
			environment.put("XDG_CACHE_HOME", Files.createDirectory(root.resolve("xdg")).toString());
			environment.put("TMPDIR", root.toString());
			run(root, environment, List.of("bash", "-eu", "-c", command));
			String metadata = run(root, environment, List.of("cargo", "metadata", "--offline", "--locked",
				"--format-version", "1"));
			JsonNode packages = JsonMapper.builder().build().readTree(metadata).path("packages");
			Map<String, String> versions = new HashMap<>();
			for (JsonNode entry : packages)
				versions.put(entry.path("name").asString(), entry.path("version").asString());
			assertEquals(versions, Map.of("release-package", "0.159.2", "dependency", "0.12.28"));
		}
		finally
		{
			FixtureDirectories.deleteTree(root);
		}
	}

	/**
	 * Retains the original policy against source edits and dependency re-resolution during setup.
	 *
	 * @throws IOException if the maintained action cannot be read
	 */
	@Test
	public void preservesSourcesAndResolution() throws IOException
	{
		String text = Files.readString(action());
		for (String mutation : List.of("write_text(", "generate-lockfile", "validate_lockfile = False",
			"rm -f MODULE.bazel.lock"))
			assertFalse(text.contains(mutation), "Bazel setup must preserve sources and lockfiles: " + mutation);
	}

	/**
	 * Retains shared setup, repository cache identity, cache restore and compact execution logs.
	 *
	 * @throws IOException if the maintained action cannot be read
	 */
	@Test
	public void retainsCachesAndExecutionLogs() throws IOException
	{
		String text = Files.readString(action());
		for (String required : List.of("./.github/actions/setup-bazel-ci", "actions/cache/restore@",
			"hashFiles('upstream/MODULE.bazel'", "CODEX_BAZEL_EXECUTION_LOG_COMPACT_DIR"))
			assertTrue(text.contains(required), "Bazel setup requires " + required);
	}

	/**
	 * Shares dependency archives across jobs while retaining target and resolved-input boundaries.
	 *
	 * @throws IOException if action, command or cleanup operations fail
	 * @throws InterruptedException if process waiting is interrupted
	 */
	@Test
	public void sharesRepositoryCachesAcrossJobs() throws IOException, InterruptedException
	{
		JsonNode steps = YAMLMapper.builder().build().readTree(Files.readString(action())).path("runs").path("steps");
		JsonNode cacheStep = null;
		for (JsonNode step : steps)
			if (step.path("id").asString().equals("cache_bazel_repository_key"))
				cacheStep = step;
		assertTrue(cacheStep != null, "Maintained action must compute the repository cache key");
		String command = cacheStep.path("run").asString();
		Path root = Files.createTempDirectory("bazel-cache-identity-");
		try
		{
			Map<String, String> environment = new HashMap<>(System.getenv());
			environment.put("CACHE_UPSTREAM_PREFIX", "upstream-rust-v0.161.0-");
			environment.put("TARGET", "x86_64-unknown-linux-gnu");
			environment.put("CACHE_HASH", "resolved-dependencies");
			Path outputs = root.resolve("github-output");
			environment.put("GITHUB_OUTPUT", outputs.toString().replace('\\', '/'));
			for (String scope : List.of("bazel-test", "bazel-clippy", "bazel-verify-release"))
			{
				environment.put("CACHE_SCOPE", scope);
				Files.writeString(outputs, "");
				run(root, environment, List.of("bash", "-eu", "-c", command));
				String text = Files.readString(outputs);
				assertTrue(text.contains("repository-cache-key=upstream-rust-v0.161.0-bazel-repository-" +
					"x86_64-unknown-linux-gnu-resolved-dependencies\n"), text);
				assertTrue(text.contains("repository-cache-legacy-restore-key=upstream-rust-v0.161.0-" +
					"bazel-cache-" + scope + "-x86_64-unknown-linux-gnu-\n"), text);
			}
			environment.put("TARGET", "aarch64-apple-darwin");
			environment.put("CACHE_HASH", "updated-dependencies");
			Files.writeString(outputs, "");
			run(root, environment, List.of("bash", "-eu", "-c", command));
			assertTrue(Files.readString(outputs).contains("repository-cache-key=upstream-rust-v0.161.0-" +
				"bazel-repository-aarch64-apple-darwin-updated-dependencies\n"));
			String hashInputs = cacheStep.path("env").path("CACHE_HASH").asString();
			assertTrue(hashInputs.contains("upstream/MODULE.bazel.lock") &&
				hashInputs.contains("upstream/codex-rs/Cargo.lock"), hashInputs);
			assertFalse(hashInputs.contains("patches/"), "Source-only patches must not invalidate dependency archives");
		}
		finally
		{
			FixtureDirectories.deleteTree(root);
		}
	}

	/**
	 * Locates the maintained action from the Maven-provided release workflow locator.
	 *
	 * @return project Bazel preparation action
	 */
	private static Path action()
	{
		return Path.of(System.getProperty("tooling.release.workflow")).getParent().getParent().getParent().
			resolve(".github/actions/prepare-bazel-ci/action.yml");
	}

	/**
	 * Executes real Cargo or its maintained shell command with complete environment and owned captures.
	 *
	 * @param root fixture working directory
	 * @param environment complete process environment
	 * @param command executable and arguments
	 * @return captured standard output
	 * @throws IOException if process or capture access fails
	 * @throws InterruptedException if waiting is interrupted
	 */
	private static String run(Path root, Map<String, String> environment, List<String> command)
		throws IOException, InterruptedException
	{
		Path output = root.resolve("stdout");
		Path error = root.resolve("stderr");
		Path input = Files.writeString(root.resolve("stdin"), "");
		ProcessBuilder builder = NativeCommands.createBuilder(command).directory(root.toFile()).
			redirectInput(input.toFile()).
			redirectOutput(output.toFile()).redirectError(error.toFile());
		builder.environment().clear();
		builder.environment().putAll(environment);
		try (Process process = builder.start())
		{
			try
			{
				assertTrue(process.waitFor(30, TimeUnit.SECONDS), "Cargo fixture timed out: " + command);
				assertEquals(process.exitValue(), 0, command + "\n" + Files.readString(error));
				return Files.readString(output);
			}
			finally
			{
				if (process.isAlive())
					process.destroyForcibly().waitFor();
			}
		}
	}
}
