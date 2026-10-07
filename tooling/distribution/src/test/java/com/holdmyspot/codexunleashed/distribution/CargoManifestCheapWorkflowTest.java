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
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertNotEquals;

/** Executes the cheap checker's maintained policy stage with controlled remaining-check boundaries. */
public final class CargoManifestCheapWorkflowTest
{
	/** Creates cheap checker tests. */
	public CargoManifestCheapWorkflowTest()
	{
	}

	/**
	 * Retains the generated terminal frame's padding without permitting source whitespace or blank EOF lines.
	 *
	 * @throws IOException if fixture or process operations fail
	 * @throws InterruptedException if process waiting is interrupted
	 */
	@Test
	public void checksSnapshotWhitespaceStage() throws IOException, InterruptedException
	{
		Path root = Files.createTempDirectory("cheap-whitespace-");
		try
		{
			Path upstream = Files.createDirectory(root.resolve("upstream with spaces"));
			Path snapshot = upstream.resolve("codex-rs/tui/src/snapshots/" +
				"codex_tui__startup_draft__layout__tests__owned_startup_layout.snap");
			Files.createDirectories(snapshot.getParent());
			Files.writeString(snapshot, "original frame\n");
			Path rust = snapshot.getParent().getParent().resolve("lib.rs");
			Files.writeString(rust, "fn main() {}\n");
			Path stdout = root.resolve("stdout");
			Path stderr = root.resolve("stderr");
			Path marker = root.resolve("success");
			Map<String, String> environment = Map.of("upstream_checkout", upstream.toString(),
				"WHITESPACE_SUCCESS_MARKER", marker.toString(), "GIT_CONFIG_GLOBAL", "/dev/null",
				"GIT_CONFIG_NOSYSTEM", "1");
			assertEquals(run("git init -q --initial-branch=main\ngit add .\n" +
				"git -c user.name=Fixture -c user.email=fixture@example.invalid -c commit.gpgsign=false " +
				"commit -qm fixture", upstream, environment, stdout, stderr), 0, Files.readString(stderr));
			Path project = Path.of(System.getProperty("tooling.release.workflow")).getParent().getParent().getParent();
			String script = Files.readString(project.resolve("scripts/check-upstream-release-cheap.sh"));
			int apply = script.indexOf("\"${repo_root}/scripts/apply-patches.sh\" \"${upstream_checkout}\"");
			int first = script.indexOf('\n', apply);
			int last = script.indexOf("\npushd", first);
			assertTrue(apply >= 0 && first >= 0 && last > first);
			String stage = script.substring(first, last) + "\nprintf reached > \"$WHITESPACE_SUCCESS_MARKER\"";

			String padded = "rendered terminal frame    \n";
			Files.writeString(snapshot, padded);
			assertEquals(run(stage, upstream, environment, stdout, stderr), 0, Files.readString(stdout));
			assertEquals(Files.readString(snapshot), padded);
			assertTrue(Files.exists(marker));
			Files.delete(marker);
			Files.writeString(rust, "fn main() {}  \n");
			assertNotEquals(run(stage, upstream, environment, stdout, stderr), 0);
			assertTrue(Files.readString(stdout).contains("lib.rs:1: trailing whitespace"));
			assertFalse(Files.exists(marker));
			Files.writeString(rust, "fn main() {}\n");
			Files.writeString(snapshot, padded + "\n");
			assertNotEquals(run(stage, upstream, environment, stdout, stderr), 0);
			assertTrue(Files.readString(stdout).contains("new blank line at EOF"));
			assertFalse(Files.exists(marker));
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
	 * Executes resource asset validation through the maintained function and stops on mismatch or malformed input.
	 *
	 * @throws IOException if fixture, runtime or process operations fail
	 * @throws URISyntaxException if rejected-command fixtures cannot be located
	 * @throws InterruptedException if process waiting is interrupted
	 */
	@Test
	public void checksResourceInventoryStage() throws IOException, URISyntaxException, InterruptedException
	{
		Path root = Files.createTempDirectory("resource-manifest-cheap-");
		try
		{
			Path runtime = root.resolve("runtime with spaces");
			DistributionMain.main(new String[]{System.getProperty("tooling.runtime.modules"), runtime.toString()});
			Path commands = Files.createDirectory(root.resolve("commands"));
			for (String name : List.of("java", "python", "python3"))
				JavaCommandFixtures.writeLauncher(commands.resolve(name), RejectedCommandFixture.class);
			Path project = Path.of(System.getProperty("tooling.release.workflow")).getParent().getParent().getParent();
			String source = Files.readString(project.resolve("scripts/check-upstream-release-cheap.sh"));
			int start = source.indexOf("check_resource_manifest() {");
			int end = source.indexOf("\ncheck_resource_manifest scripts/", start);
			assertTrue(start >= 0 && end > start);
			String stage = "gh() { printf '%s' '{\"assets\":[{\"name\":\"archive.tar.gz\",\"size\":1," +
				"\"digest\":\"sha256:first\"}]}'; }\n" + source.substring(start, end) +
				"\ncheck_resource_manifest fixture.dotslash fixtures/resource release-tag\n" +
				"printf reached > \"$RESOURCE_SUCCESS_MARKER\"";
			Path manifest = root.resolve("fixture.dotslash");
			String definition = "#!dotslash\n{\"platforms\":{\"linux-x86_64\":{\"size\":1,\"digest\":\"first\", " +
				"\"providers\":[{\"type\":\"github-release\",\"name\":\"archive.tar.gz\"}]}}}";
			Files.writeString(manifest, definition);
			Path success = root.resolve("success");
			Path stdout = root.resolve("stdout");
			Path stderr = root.resolve("stderr");
			Map<String, String> environment = Map.of("PATH", commands + java.io.File.pathSeparator + System.getenv("PATH"),
				"CODEX_UNLEASHED_TOOLING", runtime.resolve("bin/codex-tooling").toString(), "repo_root", root.toString(),
				"RESOURCE_SUCCESS_MARKER", success.toString(), "TMPDIR", root.toString(), "XDG_CACHE_HOME",
				root.resolve("xdg").toString());
			assertEquals(run(stage, root, environment, stdout, stderr), 0, Files.readString(stderr));
			assertEquals(Files.readString(stderr), "");
			assertTrue(Files.exists(success));
			Files.delete(success);
			Files.writeString(manifest, definition.replace("\"size\":1", "\"size\":2"));
			assertEquals(run(stage, root, environment, stdout, stderr), 1);
			assertTrue(Files.readString(stderr).contains("disagrees with fixtures/resource@release-tag"));
			assertFalse(Files.exists(success));
			Files.writeString(manifest, "#!dotslash\n{");
			assertEquals(run(stage, root, environment, stdout, stderr), 1);
			assertTrue(Files.readString(stderr).contains("Invalid resource manifest"));
			assertFalse(Files.exists(success));
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
	 * Verifies the actual policy stage's Java handoff, native TOML types, and failure stopping through a fresh image.
	 *
	 * @throws IOException if fixture, linking, execution, or cleanup fails
	 * @throws URISyntaxException if rejected-command fixtures cannot be located
	 * @throws InterruptedException if process waiting is interrupted
	 */
	@Test
	public void checksThroughCheapPolicyStage() throws IOException, URISyntaxException, InterruptedException
	{
		Path root = Files.createTempDirectory("cargo-manifest-cheap-");
		try
		{
			Path upstream = Files.createDirectory(root.resolve("upstream with spaces"));
			Files.createDirectories(upstream.resolve("codex-rs/v8-poc"));
			Files.writeString(upstream.resolve("codex-rs/Cargo.toml"), "[workspace.lints.clippy]\na = 'warn'\n");
			Path bazel = upstream.resolve(".bazelrc");
			String flag = "build:clippy --@rules_rust//rust/settings:clippy_flag=";
			Files.writeString(bazel, flag + "--warn=clippy::a\n");
			Path manifest = upstream.resolve("codex-rs/v8-poc/Cargo.toml");
			String definition = """
				[package]
				name = "codex-v8-poc"
				version.workspace = true
				edition.workspace = true
				license.workspace = true
				[lints]
				workspace = true
				[features]
				sandbox = ["v8/v8_enable_sandbox"]
				""";
			Files.writeString(manifest, definition);
			Path tui = Files.createDirectories(upstream.resolve("codex-rs/tui/src"));
			Files.writeString(tui.getParent().resolve("Cargo.toml"), definition.replace("codex-v8-poc", "codex-tui").
				replace("[features]\nsandbox = [\"v8/v8_enable_sandbox\"]\n", ""));
			Path source = tui.resolve("lib.rs");
			Files.writeString(source, "use codex_app_server_client::legacy_core;\n");
			Path runtime = root.resolve("runtime with spaces");
			DistributionMain.main(new String[]{System.getProperty("tooling.runtime.modules"), runtime.toString()});
			Path commands = Files.createDirectory(root.resolve("commands"));
			for (String name : List.of("java", "python", "codex"))
				JavaCommandFixtures.writeLauncher(commands.resolve(name), RejectedCommandFixture.class);
			JavaCommandFixtures.writeLauncher(commands.resolve("python3"), RejectedCommandFixture.class);
			Path temporary = Files.createDirectory(root.resolve("temporary"));
			Path calls = root.resolve("calls");
			Path stdout = root.resolve("stdout");
			Path stderr = root.resolve("stderr");
			Path project = Path.of(System.getProperty("tooling.release.workflow")).getParent().getParent().getParent();
			String script = Files.readString(project.resolve("scripts/check-upstream-release-cheap.sh"));
			int first = script.indexOf("export CODEX_REPO_ROOT=\"${upstream_checkout}\"");
			int last = script.indexOf("\npopd >/dev/null", first);
			assertTrue(first >= 0 && last > first, "Maintained policy stage boundaries");
			Map<String, String> environment = new HashMap<>();
			environment.put("PATH", commands + java.io.File.pathSeparator + System.getenv("PATH"));
			environment.put("CODEX_UNLEASHED_TOOLING", runtime.resolve("bin/codex-tooling").toString());
			environment.put("repo_root", project.toString());
			environment.put("upstream_checkout", upstream.toString());
			environment.put("CHEAP_CALLS", calls.toString());
			environment.put("TMPDIR", temporary.toString());
			environment.put("XDG_CACHE_HOME", root.resolve("xdg").toString());
			String stage = script.substring(first, last);
			assertEquals(run(stage, upstream, environment, stdout, stderr), 0, Files.readString(stderr));
			assertTrue(Files.readString(stdout).contains("[workspace.lints.clippy]."));
			assertFalse(Files.exists(calls));
			Files.writeString(manifest, definition.replace("version.workspace = true", "version = 2000-01-01"));
			assertEquals(run(stage, upstream, environment, stdout, stderr), 1);
			assertTrue(Files.readString(stdout).contains("set `version.workspace = true` in `[package]`"));
			assertFalse(Files.exists(calls));
			Files.writeString(manifest, definition);
			Files.writeString(source, "// codex_core::forbidden\n");
			assertEquals(run(stage, upstream, environment, stdout, stderr), 1);
			assertTrue(Files.readString(stdout).contains("codex-rs/tui/src/lib.rs:1 imports `codex_core`"));
			assertFalse(Files.exists(calls));
			Files.writeString(source, "use codex_app_server_client::legacy_core;\n");
			Files.writeString(bazel, flag + "--deny=clippy::a\n");
			assertEquals(run(stage, upstream, environment, stdout, stderr), 1);
			assertTrue(Files.readString(stderr).contains("clippy::a: Cargo has warn, Bazel has deny"));
			assertFalse(Files.exists(calls));
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
	 * Captures the maintained Bash stage with independent EOF input.
	 *
	 * @param stage the actual policy stage
	 * @param working the upstream cwd
	 * @param environment the explicit environment overrides
	 * @param stdout the output capture
	 * @param stderr the diagnostic capture
	 * @return the process status
	 * @throws IOException if startup or input closing fails
	 * @throws InterruptedException if waiting is interrupted
	 */
	private static int run(String stage, Path working, Map<String, String> environment, Path stdout, Path stderr)
		throws IOException, InterruptedException
	{
		ProcessBuilder builder = new ProcessBuilder("bash", "-eu", "-c", stage).directory(working.toFile()).
			redirectOutput(stdout.toFile()).redirectError(stderr.toFile());
		builder.environment().putAll(environment);
		try (Process process = builder.start())
		{
			process.getOutputStream().close();
			return process.waitFor();
		}
	}
}
