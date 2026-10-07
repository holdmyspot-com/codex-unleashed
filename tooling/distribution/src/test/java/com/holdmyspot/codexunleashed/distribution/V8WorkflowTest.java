package com.holdmyspot.codexunleashed.distribution;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import java.util.zip.GZIPInputStream;
import org.testng.annotations.Test;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertTrue;

/** Executes maintained V8 workflow commands through a fresh runtime without Python or system Java. */
public final class V8WorkflowTest
{
	/** Creates workflow tests. */
	public V8WorkflowTest()
	{
	}

	/**
	 * Executes both workflows' build and staging bodies with raw payload and checksum verification.
	 *
	 * @throws IOException if workflow, fixture or process operations fail
	 * @throws InterruptedException if process waiting is interrupted
	 * @throws URISyntaxException if the native fixture class location is invalid
	 * @throws NoSuchAlgorithmException if the platform does not supply SHA-256
	 */
	@Test
	public void executesMaintainedV8Steps() throws IOException, InterruptedException, URISyntaxException,
		NoSuchAlgorithmException
	{
		Path root = Files.createTempDirectory("v8-workflow-");
		try
		{
			Path checkout = Files.createDirectory(root.resolve("upstream"));
			Path adapters = Files.createDirectory(root.resolve("adapters"));
			Path bazel = JavaCommandFixtures.writeNativeLauncher(adapters.resolve("bazel"), V8ChildFixture.class);
			for (String name : List.of("python", "python3", "java"))
				JavaCommandFixtures.writeLauncher(adapters.resolve(name), RejectedCommandFixture.class);
			Map<String, String> environment = new HashMap<>(System.getenv());
			for (String name : List.of("BAZEL_OUTPUT_USER_ROOT", "BUILDBUDDY_API_KEY", "GITHUB_ACTIONS",
				"BAZEL_REPO_CONTENTS_CACHE", "BAZEL_REPOSITORY_CACHE", "BAZEL_DISK_CACHE", "NODE_OPTIONS"))
				environment.remove(name);
			Path captures = Files.createDirectory(root.resolve("captures"));
			environment.put("TMPDIR", captures.toString());
			environment.put("XDG_CACHE_HOME", Files.createDirectory(root.resolve("xdg")).toString());
			environment.put("GIT_CONFIG_NOSYSTEM", "1");
			environment.put("GIT_CONFIG_GLOBAL", Files.writeString(root.resolve("gitconfig"), "").toString());
			environment.put("PATH", adapters + File.pathSeparator + environment.getOrDefault("PATH", ""));
			environment.put("CODEX_BAZEL_BIN", bazel.toString());
			Path log = root.resolve("calls.bin");
			environment.put("V8_FIXTURE_LOG", log.toString());
			Path image = root.resolve("runtime with spaces");
			DistributionMain.main(new String[]{System.getProperty("tooling.runtime.modules"), image.toString()});
			environment.put("CODEX_UNLEASHED_TOOLING", image.resolve("bin/codex-tooling").toString());
			run(checkout, environment, List.of("git", "init", "-q"));
			Files.writeString(checkout.resolve("tracked"), "fixture");
			run(checkout, environment, List.of("git", "add", "tracked"));
			run(checkout, environment, List.of("git", "-c", "user.name=Fixture", "-c",
				"user.email=fixture@example.invalid", "commit", "-qm", "fixture"));
			Path workflows = Path.of(System.getProperty("tooling.release.workflow")).getParent();
			checkBazelPolicy(root, checkout, adapters, workflows, environment);
			for (String workflowName : List.of("v8-canary.yml", "rusty-v8-release.yml"))
			{
				Path workflow = workflows.resolve(workflowName);
				for (boolean sandbox : new boolean[]{false, true})
				{
					environment.put("PLATFORM", "linux_amd64");
					environment.put("TARGET", "x86_64-unknown-linux-gnu");
					environment.put("V8_CPU", "x64");
					environment.put("SANDBOX", Boolean.toString(sandbox));
					environment.put("BAZEL_CONFIG", "ci-v8");
					for (String step : List.of("Build Bazel V8 release pair", "Stage release pair"))
					{
						String command = WorkflowCommands.readStepCommand(workflow, "build", step).
							replace("${{ matrix.bazel_config }}", "ci-v8");
						assertTrue(command.contains("CODEX_UNLEASHED_TOOLING"), command);
						assertFalse(command.contains(".py"), command);
						run(root, environment, List.of("bash", "-eu", "-c", command));
					}
					String profile = "release";
					if (sandbox)
						profile = "ptrcomp_sandbox_release";
					verify(checkout.resolve("dist/x86_64-unknown-linux-gnu"), "x86_64-unknown-linux-gnu", profile, false);
				}
				String target = "x86_64-pc-windows-msvc";
				environment.put("TARGET", target);
				Path gn = Files.createDirectories(root.resolve("upstream-rusty-v8/target/" + target +
					"/release/gn_out/obj"));
				Files.write(gn.resolve("rusty_v8.lib"), new byte[]{0, (byte) 255, 7});
				Files.write(gn.getParent().resolve("src_binding.rs"), new byte[]{1, (byte) 254, 9});
				String stage = WorkflowCommands.readStepCommand(workflow, "build-windows-source",
					"Stage upstream sandbox release pair");
				assertTrue(stage.contains("CODEX_UNLEASHED_TOOLING"), stage);
				assertFalse(stage.contains(".py"), stage);
				run(root, environment, List.of("bash", "-eu", "-c", stage));
				verify(root.resolve("dist/" + target), target, "ptrcomp_sandbox_release", true);
			}
			try (var calls = new DataInputStream(new ByteArrayInputStream(Files.readAllBytes(log))))
			{
				while (calls.available() > 0)
				{
					assertEquals(calls.readUTF(), checkout.toString());
					assertFalse(calls.readUTF().isEmpty());
				}
			}
			try (Stream<Path> paths = Files.list(captures))
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
	 * Executes the maintained checksum step and rejects a missing selector before the retained upstream gate.
	 *
	 * @param root workflow working directory
	 * @param checkout upstream source fixture
	 * @param adapters native fixture commands
	 * @param workflows maintained workflow directory
	 * @param environment complete process environment
	 * @throws IOException if fixture or workflow access fails
	 * @throws InterruptedException if process waiting is interrupted
	 * @throws URISyntaxException if the fixture class location is invalid
	 */
	private static void checkBazelPolicy(Path root, Path checkout, Path adapters, Path workflows,
		Map<String, String> environment) throws IOException, InterruptedException, URISyntaxException
	{
		String command = WorkflowCommands.readStepCommand(workflows.resolve("bazel.yml"), "test",
			"Check rusty_v8 MODULE.bazel checksums");
		assertTrue(command.contains("rusty-v8-bazel . check-consumer-selectors"), command);
		assertTrue(command.contains("just test-github-scripts"), command);
		JavaCommandFixtures.writeLauncher(adapters.resolve("just"), UpstreamChecksFixture.class);
		Path marker = root.resolve("upstream-check-marker");
		environment.put("UPSTREAM_CHECK_MARKER", marker.toString());
		Path v8 = Files.createDirectories(checkout.resolve("third_party/v8"));
		Files.createDirectories(checkout.resolve("codex-rs"));
		Files.writeString(checkout.resolve("codex-rs/Cargo.lock"),
			"[[package]]\nname = \"v8\"\nversion = \"146.4.0\"\n");
		String digest = "1".repeat(64);
		Files.writeString(checkout.resolve("MODULE.bazel"), "http_file(\n" +
			"    name = \"rusty_v8_146_4_0_fixture\",\n" +
			"    downloaded_file_path = \"fixture.a.gz\",\n" +
			"    sha256 = \"" + digest + "\",\n)\n");
		Files.writeString(v8.resolve("rusty_v8_146_4_0.sha256"), digest + "  fixture.a.gz\n");
		Path build = v8.resolve("BUILD.bazel");
		String selectors = """
			selectors = [
			    ":v8_146_4_0_aarch64_apple_darwin_bazel",
			    ":v8_146_4_0_aarch64_pc_windows_gnullvm",
			    ":v8_146_4_0_aarch64_pc_windows_msvc",
			    ":v8_146_4_0_aarch64_unknown_linux_gnu_bazel",
			    ":v8_146_4_0_aarch64_unknown_linux_musl_release_base",
			    ":v8_146_4_0_x86_64_apple_darwin_bazel",
			    ":v8_146_4_0_x86_64_pc_windows_gnullvm",
			    ":v8_146_4_0_x86_64_pc_windows_msvc",
			    ":v8_146_4_0_x86_64_unknown_linux_gnu_bazel",
			    ":v8_146_4_0_x86_64_unknown_linux_musl_release",
			    ":src_binding_release_aarch64_apple_darwin_146_4_0_release",
			    ":src_binding_release_aarch64_pc_windows_gnullvm_146_4_0_release",
			    ":src_binding_release_aarch64_pc_windows_msvc_146_4_0_release",
			    ":src_binding_release_aarch64_unknown_linux_gnu_146_4_0_release",
			    ":src_binding_release_aarch64_unknown_linux_musl_146_4_0_release",
			    ":src_binding_release_x86_64_apple_darwin_146_4_0_release",
			    ":src_binding_release_x86_64_pc_windows_gnullvm_146_4_0_release",
			    ":src_binding_release_x86_64_pc_windows_msvc_146_4_0_release",
			    ":src_binding_release_x86_64_unknown_linux_gnu_146_4_0_release",
			    ":src_binding_release_x86_64_unknown_linux_musl_146_4_0_release",
			]
			""";
		Files.writeString(build, selectors);
		run(root, environment, List.of("bash", "-eu", "-c", command));
		assertEquals(Files.readString(marker), "test-github-scripts");
		Files.delete(marker);
		Files.writeString(build, selectors.replace(":v8_146_4_0_aarch64_apple_darwin_bazel", ""));
		run(root, environment, List.of("bash", "-eu", "-c", command), 1);
		assertFalse(Files.exists(marker));
	}

	/**
	 * Checks staged raw bytes, profile names and independently calculated checksums.
	 *
	 * @param output artifact directory
	 * @param target Cargo target
	 * @param profile expected profile
	 * @param windows whether Windows artifact names apply
	 * @throws IOException if staged artifacts cannot be read
	 * @throws NoSuchAlgorithmException if SHA-256 is unavailable
	 */
	private static void verify(Path output, String target, String profile, boolean windows)
		throws IOException, NoSuchAlgorithmException
	{
		String name = "librusty_v8_" + profile + "_" + target + ".a.gz";
		if (windows)
			name = "rusty_v8_" + profile + "_" + target + ".lib.gz";
		Path archive = output.resolve(name);
		Path binding = output.resolve("src_binding_" + profile + "_" + target + ".rs");
		try (InputStream input = new GZIPInputStream(Files.newInputStream(archive)))
		{
			assertEquals(input.readAllBytes(), new byte[]{0, (byte) 255, 7});
		}
		assertEquals(Files.readAllBytes(binding), new byte[]{1, (byte) 254, 9});
		MessageDigest digest = MessageDigest.getInstance("SHA-256");
		String checksums = HexFormat.of().formatHex(digest.digest(Files.readAllBytes(archive))) + "  " + name +
			System.lineSeparator() + HexFormat.of().formatHex(digest.digest(Files.readAllBytes(binding))) + "  " +
			binding.getFileName() + System.lineSeparator();
		assertEquals(Files.readString(output.resolve("rusty_v8_" + profile + "_" + target + ".sha256")), checksums);
	}

	/**
	 * Executes a real workflow shell with independent EOF input and owned output capture.
	 *
	 * @param root process directory
	 * @param environment complete environment
	 * @param command executable and arguments
	 * @throws IOException if process or output access fails
	 * @throws InterruptedException if waiting is interrupted
	 */
	private static void run(Path root, Map<String, String> environment, List<String> command)
		throws IOException, InterruptedException
	{
		run(root, environment, command, 0);
	}

	/**
	 * Executes a workflow body with its expected exit status and bounded native process cleanup.
	 *
	 * @param root process directory
	 * @param environment complete environment
	 * @param command executable and arguments
	 * @param status expected process status
	 * @throws IOException if process or output access fails
	 * @throws InterruptedException if waiting is interrupted
	 */
	private static void run(Path root, Map<String, String> environment, List<String> command, int status)
		throws IOException, InterruptedException
	{
		Path output = root.resolve("console.log");
		Path input = Files.writeString(root.resolve("stdin"), "");
		ProcessBuilder builder = new ProcessBuilder(command).directory(root.toFile()).redirectInput(input.toFile()).
			redirectErrorStream(true).redirectOutput(output.toFile());
		builder.environment().clear();
		builder.environment().putAll(environment);
		try (Process process = builder.start())
		{
			try
			{
				assertTrue(process.waitFor(30, TimeUnit.SECONDS), "Workflow process timed out: " + command);
				assertEquals(process.exitValue(), status, command + "\n" + Files.readString(output));
			}
			finally
			{
				if (process.isAlive())
					process.destroyForcibly().waitFor();
			}
		}
	}
}
