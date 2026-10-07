package com.holdmyspot.codexunleashed.distribution;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.testng.annotations.Test;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertTrue;

/** Verifies manifest validation and failure stopping through the maintained repository-check wrapper. */
public final class CargoManifestWorkflowTest
{
	/** Creates workflow tests. */
	public CargoManifestWorkflowTest()
	{
	}

	/**
	 * Executes the maintained wrapper with a fresh runtime and controlled boundaries for its remaining checks.
	 *
	 * @throws IOException if fixture, linking, execution, or cleanup fails
	 * @throws URISyntaxException if rejected-command fixtures cannot be located
	 * @throws InterruptedException if process waiting is interrupted
	 */
	@Test
	public void checksManifestsThroughWrapper() throws IOException, URISyntaxException, InterruptedException
	{
		Path root = Files.createTempDirectory("cargo-manifest-workflow-");
		try
		{
			Path upstream = Files.createDirectory(root.resolve("upstream with spaces"));
			Files.createDirectory(upstream.resolve(".git"));
			Files.createDirectories(upstream.resolve("scripts/install"));
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
			writeCommand(commands.resolve("python3"), """
				#!/bin/bash
				set -euo pipefail
				case "$*" in
				  '-m unittest discover -s scripts/codex_package -p test_*.py'|\
				'-m unittest discover -s scripts/install -p test_*.py')
				    printf '%s\\n' "python3 $*" >> "$CHECK_CALLS" ;;
				  *) echo "Unexpected Python manifest check: $*" >&2; exit 91 ;;
				esac
				""");
			for (String name : List.of("just", "pnpm"))
				writeCommand(commands.resolve(name), "#!/bin/bash\nprintf '%s\\n' \"${0##*/} $*\" >> \"$CHECK_CALLS\"\n");
			Path temporary = Files.createDirectory(root.resolve("temporary"));
			Path calls = root.resolve("calls");
			Path stdout = root.resolve("stdout");
			Path stderr = root.resolve("stderr");
			Map<String, String> environment = new HashMap<>();
			environment.put("PATH", commands + java.io.File.pathSeparator + System.getenv("PATH"));
			environment.put("CODEX_UNLEASHED_TOOLING", runtime.resolve("bin/codex-tooling").toString());
			environment.put("ALLOW_STALE_CODE_MODE_FEATURE_EXCEPTION", "1");
			environment.put("CHECK_CALLS", calls.toString());
			environment.put("TMPDIR", temporary.toString());
			environment.put("XDG_CACHE_HOME", root.resolve("xdg").toString());
			Path project = Path.of(System.getProperty("tooling.release.workflow")).getParent().getParent().getParent();
			List<String> command = List.of("bash", project.resolve("scripts/run-upstream-repo-checks.sh").toString(),
				upstream.toString());
			assertEquals(run(command, root, environment, stdout, stderr), 0, Files.readString(stderr));
			List<String> expected = List.of(
				"python3 -m unittest discover -s scripts/codex_package -p test_*.py",
				"python3 -m unittest discover -s scripts/install -p test_*.py", "just fmt-check", "pnpm run format");
			assertEquals(Files.readAllLines(calls), expected);
			Files.writeString(manifest, definition.replace("version.workspace = true", "version = \"1.0.0\""));
			assertEquals(run(command, root, environment, stdout, stderr), 1);
			assertTrue(Files.readString(stdout).contains("set `version.workspace = true` in `[package]`"));
			assertEquals(Files.readAllLines(calls), expected);

			Files.writeString(manifest, definition);
			Files.writeString(source, "// codex_core::forbidden\n");
			assertEquals(run(command, root, environment, stdout, stderr), 1);
			assertTrue(Files.readString(stdout).contains("codex-rs/tui/src/lib.rs:1 imports `codex_core`"));
			assertEquals(Files.readAllLines(calls), expected);
			Files.writeString(source, "use codex_app_server_client::legacy_core;\n");
			Files.writeString(bazel, flag + "--deny=clippy::a\n");
			assertEquals(run(command, root, environment, stdout, stderr), 1);
			assertTrue(Files.readString(stderr).contains("clippy::a: Cargo has warn, Bazel has deny"));
			assertEquals(Files.readAllLines(calls), expected);
			Files.writeString(bazel, flag + "--warn=clippy::a\n");
			Path projectFixture = Files.createDirectory(root.resolve("project"));
			Files.createDirectory(projectFixture.resolve("scripts"));
			Files.copy(project.resolve("scripts/run-upstream-repo-checks.sh"),
				projectFixture.resolve("scripts/run-upstream-repo-checks.sh"));
			Path fallback = Files.createDirectories(projectFixture.resolve("tooling/bin"));
			writeCommand(fallback.resolve("codex-tooling"), """
				#!/bin/bash
				printf '%s\\n' "$@" >> "$FALLBACK_CALLS"
				exec "$FRESH_TOOLING" "$@"
				""");
			Path fallbackCalls = root.resolve("fallback-calls");
			environment.remove("CODEX_UNLEASHED_TOOLING");
			environment.put("FRESH_TOOLING", runtime.resolve("bin/codex-tooling").toString());
			environment.put("FALLBACK_CALLS", fallbackCalls.toString());
			assertEquals(run(List.of("bash", "scripts/run-upstream-repo-checks.sh", upstream.toString()),
				projectFixture, environment, stdout, stderr), 0, Files.readString(stderr));
			assertEquals(Files.readAllLines(fallbackCalls), List.of("verify-cargo-workspace-manifests", upstream.toString(),
				"--upstream", "verify-tui-core-boundary", upstream.toString(), "verify-bazel-clippy-lints",
				upstream.toString()));
			List<String> finalCalls = Files.readAllLines(calls);
			assertEquals(finalCalls.subList(expected.size(), finalCalls.size()), expected);
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
	 * Writes an executable controlled command fixture.
	 *
	 * @param path the fixture path
	 * @param text the Bash program
	 * @throws IOException if writing or setting permissions fails
	 */
	private static void writeCommand(Path path, String text) throws IOException
	{
		Files.writeString(path, text);
		Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rwx------"));
	}

	/**
	 * Captures all process descriptors with an independent EOF input stream.
	 *
	 * @param command the literal command arguments
	 * @param working the process cwd
	 * @param environment the explicit environment overrides
	 * @param stdout the output capture
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
		builder.environment().remove("CODEX_UNLEASHED_TOOLING");
		builder.environment().putAll(environment);
		try (Process process = builder.start())
		{
			process.getOutputStream().close();
			return process.waitFor();
		}
	}
}
