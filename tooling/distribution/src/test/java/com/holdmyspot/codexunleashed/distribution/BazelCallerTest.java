package com.holdmyspot.codexunleashed.distribution;

import java.io.File;
import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.testng.annotations.Test;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertTrue;

/** Executes maintained query and CI wrappers with a fresh runtime and a controlled native Bazel child. */
public final class BazelCallerTest
{
	/** Creates caller tests. */
	public BazelCallerTest()
	{
	}

	/**
	 * Preserves query payloads, cache choices, Windows-style environment arguments and child failure status.
	 *
	 * @throws IOException if fixture, runtime or process operations fail
	 * @throws InterruptedException if waiting is interrupted
	 * @throws URISyntaxException if a fixture class location is invalid
	 */
	@Test
	public void executesMaintainedBazelWrappers() throws IOException, InterruptedException, URISyntaxException
	{
		Path root = Files.createTempDirectory("bazel-callers-");
		try
		{
			Path adapters = Files.createDirectory(root.resolve("process adapters"));
			Path bazel = JavaCommandFixtures.writeNativeLauncher(adapters.resolve("bazel"), BazelChildFixture.class);
			JavaCommandFixtures.writeLauncher(adapters.resolve("python3"), RejectedCommandFixture.class);
			JavaCommandFixtures.writeLauncher(adapters.resolve("python"), RejectedCommandFixture.class);
			JavaCommandFixtures.writeLauncher(adapters.resolve("java"), RejectedCommandFixture.class);
			Path image = root.resolve("runtime with spaces");
			DistributionMain.main(new String[]{System.getProperty("tooling.runtime.modules"), image.toString()});
			Map<String, String> environment = new HashMap<>(System.getenv());
			if (File.separatorChar == '\\')
				environment.put("MSYS2_ARG_CONV_EXCL", "*");
			for (String key : List.of("BAZEL_DISK_CACHE", "BAZEL_OUTPUT_USER_ROOT", "GITHUB_ACTIONS",
				"BUILDBUDDY_API_KEY", "CODEX_BAZEL_EXECUTION_LOG_COMPACT_DIR", "NODE_OPTIONS"))
				environment.remove(key);
			Path temporary = Files.createDirectory(root.resolve("captures"));
			environment.put("TMPDIR", temporary.toString());
			environment.put("XDG_CACHE_HOME", Files.createDirectory(root.resolve("xdg")).toString());
			environment.put("CODEX_BAZEL_BIN", bazel.toString());
			environment.put("CODEX_UNLEASHED_TOOLING", image.resolve("bin/codex-tooling").toString());
			environment.put("PATH", adapters + File.pathSeparator + environment.getOrDefault("PATH", ""));
			environment.put("BAZEL_FIXTURE_ARGUMENTS", root.resolve("arguments.txt").toString());
			environment.put("BAZEL_REPOSITORY_CACHE", Files.createDirectory(root.resolve("repository cache")).toString());
			String windowsPath = "C:\\Program Files\\PowerShell\\7;C:\\Program Files\\Git\\bin";
			environment.put("CODEX_BAZEL_WINDOWS_PATH", windowsPath);
			String systemRoot = environment.getOrDefault("SystemRoot", "E:\\Windows Root");
			environment.put("SystemRoot", systemRoot);
			String processorArchitecture = environment.getOrDefault("PROCESSOR_ARCHITECTURE", "AMD64");
			environment.put("PROCESSOR_ARCHITECTURE", processorArchitecture);
			String voiceRepository = Files.createDirectory(root.resolve("voice inputs")).toString();
			environment.put("VOICE_WINDOWS_BAZEL_REPOSITORY", voiceRepository);
			Path scripts = Path.of(System.getProperty("tooling.release.workflow")).getParent().getParent().resolve("scripts");
			String expression = "kind(\"rust_library rule\", //codex-rs/...)";
			for (String operatingSystem : List.of("Linux", "Windows", "macOS"))
			{
				environment.put("RUNNER_OS", operatingSystem);
				if (operatingSystem.equals("Windows"))
				{
					environment.remove("SystemRoot");
					environment.put("SYSTEMROOT", systemRoot);
				}
				for (String credential : List.of("", "fixture-token"))
				{
					environment.put("BUILDBUDDY_API_KEY", credential);
					environment.put("BAZEL_FIXTURE_STATUS", "0");
					assertEquals(run(root, environment, NativeCommands.scriptBuilder(scripts.resolve("run-bazel-query-ci.sh"),
						"--output=label", "--", expression)), 0);
					List<String> query = arguments(root);
					assertEquals(query.getFirst(), "query");
					assertTrue(query.contains(expression));
					assertTrue(query.contains("--repository_cache=" + environment.get("BAZEL_REPOSITORY_CACHE")));
					assertFalse(query.contains("--config=ci-linux"));
					assertFalse(query.contains("--remote_local_fallback"));
					environment.put("BAZEL_FIXTURE_STATUS", "37");
					assertEquals(run(root, environment, NativeCommands.scriptBuilder(scripts.resolve("run-bazel-ci.sh"),
						"--", "test", "--", "//fixture:test")), 37);
					List<String> test = arguments(root);
					assertTrue(test.contains("test"));
					assertEquals(test.getLast(), "//fixture:test");

					String jobs = "2";
					if (operatingSystem.equals("macOS"))
						jobs = "1";
					if (credential.isEmpty() || operatingSystem.equals("Windows"))
						assertTrue(test.contains("--jobs=" + jobs));
					else
					{
						assertFalse(test.stream().anyMatch(argument -> argument.startsWith("--jobs=")),
							"Local runner limits must not replace remote action concurrency: " + test);
						assertTrue(test.contains("--local_resources=cpu=" + jobs));
					}
					assertTrue(test.contains("--local_test_jobs=" + jobs),
						"Local test concurrency must respect the CI job limit; received arguments " + test);

					if (operatingSystem.equals("Windows"))
					{
						assertTrue(test.contains("--test_env=PATH=" + windowsPath));
						assertTrue(test.contains("--action_env=SystemRoot=" + systemRoot),
							"Expected native Windows directory " + systemRoot + "; received arguments " + test);
						assertTrue(test.contains("--host_action_env=SystemRoot=" + systemRoot));
						assertTrue(test.contains("--action_env=PROCESSOR_ARCHITECTURE=" + processorArchitecture));
						assertTrue(test.contains("--host_action_env=PROCESSOR_ARCHITECTURE=" + processorArchitecture));
						assertTrue(test.contains("--inject_repository=voice_windows_tools=" + voiceRepository),
							"Native voice actions require the provisioned and verified Windows tool repository");
						assertTrue(test.contains("--//third_party/voice:windows_installed_tools=@voice_windows_tools//:tools"));
						assertTrue(test.contains("--extra_toolchains=//third_party/voice:windows_pkg_config_toolchain," +
							"//third_party/voice:windows_cmake_toolchain"));
					}
					assertEquals(test.contains("--remote_header=x-buildbuddy-api-key=fixture-token"), !credential.isEmpty());
					if (operatingSystem.equals("Windows") && !credential.isEmpty())
					{
						environment.put("GITHUB_ACTIONS", "true");
						environment.put("BAZEL_DISK_CACHE", root.resolve("disk cache").toString());
						assertEquals(run(root, environment, NativeCommands.scriptBuilder(scripts.resolve("run-bazel-ci.sh"),
							"--windows-cross-compile", "--", "build", "--", "//fixture:test")), 37);
						List<String> crossBuild = arguments(root);
						assertTrue(crossBuild.indexOf("--noremote_local_fallback") >
							crossBuild.indexOf("--remote_local_fallback"),
							"Linux remote actions must never fall back to the Windows host");
						assertTrue(crossBuild.contains("--config=ci-windows-cross"));
						assertFalse(crossBuild.stream().anyMatch(argument -> argument.startsWith("--jobs=")),
							"Windows cross builds retain remote configuration concurrency: " + crossBuild);
						assertTrue(crossBuild.contains("--local_resources=cpu=2"));
						assertTrue(crossBuild.contains("--remote_header=x-buildbuddy-api-key=fixture-token"));
						assertTrue(crossBuild.contains("--disk_cache="));
						assertTrue(crossBuild.contains("--repository_cache=" + environment.get("BAZEL_REPOSITORY_CACHE")));
						assertFalse(crossBuild.contains("--action_env=SystemRoot=" + systemRoot),
							"Linux remote build tools must not inherit the Windows directory");
						assertFalse(crossBuild.contains("--action_env=PROCESSOR_ARCHITECTURE=" + processorArchitecture));
						assertFalse(crossBuild.contains("--inject_repository=voice_windows_tools=" + voiceRepository));
						assertFalse(crossBuild.stream().anyMatch(argument -> argument.startsWith("--experimental_disk_cache_gc_")));
						environment.remove("GITHUB_ACTIONS");
						environment.remove("BAZEL_DISK_CACHE");
					}
				}
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
	 * Reads exact direct argv from the child rather than inferring them from console rendering.
	 *
	 * @param root the owned fixture
	 * @return captured arguments
	 * @throws IOException if captured output cannot be read
	 */
	private static List<String> arguments(Path root) throws IOException
	{
		return Arrays.asList(Files.readString(root.resolve("arguments.txt")).split("\0", -1));
	}

	/**
	 * Executes the whole maintained Bash wrapper with independent input and captured output.
	 *
	 * @param root the owned process directory
	 * @param environment explicit caller configuration
	 * @param builder the selected native script command
	 * @return the actual exit status
	 * @throws IOException if process or file operations fail
	 * @throws InterruptedException if waiting is interrupted
	 */
	private static int run(Path root, Map<String, String> environment, ProcessBuilder builder)
		throws IOException, InterruptedException
	{
		Path log = root.resolve("console.log");
		Path input = Files.writeString(root.resolve("stdin"), "");
		builder.directory(root.toFile()).redirectInput(input.toFile()).redirectErrorStream(true).
			redirectOutput(log.toFile());
		builder.environment().clear();
		builder.environment().putAll(environment);
		try (Process process = builder.start())
		{
			try
			{
				assertTrue(process.waitFor(30, TimeUnit.SECONDS), "Wrapper timed out: " + builder.command());
				int status = process.exitValue();
				assertTrue(Files.readString(log).contains("fixture bazel output"), Files.readString(log));
				return status;
			}
			finally
			{
				if (process.isAlive())
					process.destroyForcibly().waitFor();
			}
		}
	}
}
