package com.holdmyspot.codexunleashed.tooling;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import org.testng.annotations.Test;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertTrue;
import static org.testng.Assert.expectThrows;

/** Verifies retained Bazel startup, credential, host, cache and argument-boundary decisions. */
public final class BazelCommandsTest
{
	/** Creates Bazel command tests. */
	public BazelCommandsTest()
	{
	}

	/**
	 * Routes local Rust compiler actions through the scheduler's private socket mount while retaining literal targets.
	 *
	 * @throws IOException if planning fails
	 */
	@Test
	public void localCompilerSchedulingUsesSandbox() throws IOException
	{
		List<String> result = command(List.of("build", "--", "//cli:codex"),
			Map.of("CODEX_COMPILER_SCHEDULER_DIRECTORY", "/owned/ipc first"));
		assertEquals(result, List.of("bazel", "build",
			"--action_env=CODEX_COMPILER_SCHEDULER_SOCKET=/var/tmp/s",
			"--host_action_env=CODEX_COMPILER_SCHEDULER_SOCKET=/var/tmp/s",
			"--sandbox_add_mount_pair=/owned/ipc first:/var/tmp",
			"--strategy=Rustc=linux-sandbox", "--strategy=RustcMetadata=linux-sandbox", "--", "//cli:codex"));
	}

	/**
	 * Selects native local execution without Linux mount flags on macOS and Windows.
	 *
	 * @throws IOException if planning fails
	 */
	@Test
	public void nativePlatformStrategies() throws IOException
	{
		for (String platform : List.of("Mac OS X", "Windows 11"))
		{
			List<String> result = BazelCommands.plan(List.of("build"),
				Map.of("CODEX_COMPILER_SCHEDULER_DIRECTORY", "/owned/ipc"), Path.of("."), platform).command();
			String strategy = "local";
			if (platform.equals("Mac OS X"))
				strategy = "darwin-sandbox";
			assertTrue(result.contains("--strategy=Rustc=" + strategy));
			assertTrue(result.contains("--strategy=RustcMetadata=" + strategy));
			assertTrue(result.contains("--action_env=CODEX_COMPILER_SCHEDULER_SOCKET=" +
				Path.of("/owned/ipc").resolve("s")));
			assertFalse(result.stream().anyMatch(value -> value.startsWith("--sandbox_add_mount_pair=")));
		}
	}

	/**
	 * Keeps remote execution first and clears the local socket only at remote execution time, leaving payloads intact.
	 *
	 * @throws IOException if planning fails
	 */
	@Test
	public void remoteCompilersKeepRemoteExecution() throws IOException
	{
		String header = "--remote_exec_header=x-buildbuddy-platform.secret-env-overrides=";
		List<String> result = command(List.of("run", "--config=ci-linux", header + "OTHER=value", "//cli:codex",
			"--", header + "payload"),
			Map.of("BUILDBUDDY_API_KEY", "token", "CODEX_COMPILER_SCHEDULER_DIRECTORY", "/owned/ipc"));
		assertTrue(result.contains("--config=buildbuddy-generic-rbe"));
		assertTrue(result.contains("--strategy=Rustc=remote,linux-sandbox"));
		assertTrue(result.contains("--strategy=RustcMetadata=remote,linux-sandbox"));
		assertTrue(result.contains(header + "OTHER=value,CODEX_COMPILER_SCHEDULER_SOCKET="));
		assertEquals(result.subList(result.indexOf("--") + 1, result.size()), List.of(header + "payload"));
	}

	/**
	 * Leaves non-building commands untouched even when the invoking environment contains a scheduler directory.
	 *
	 * @throws IOException if planning fails
	 */
	@Test
	public void queriesDoNotScheduleCompilers() throws IOException
	{
		assertEquals(command(List.of("query", "//..."),
			Map.of("CODEX_COMPILER_SCHEDULER_DIRECTORY", "/owned/ipc")), List.of("bazel", "query", "//..."));
	}

	/**
	 * Preserves repeated and base64 execution-time overrides while clearing a conflicting remote scheduler socket last.
	 *
	 * @throws IOException if planning fails
	 */
	@Test
	public void schedulerPreservesRemoteOverrides() throws IOException
	{
		String plain = "x-buildbuddy-platform.secret-env-overrides=";
		String encoded = "x-buildbuddy-platform.secret-env-overrides-base64=";
		String existing = Base64.getEncoder().encodeToString("OTHER=fixture".
			getBytes(java.nio.charset.StandardCharsets.UTF_8));
		String cleared = Base64.getEncoder().encodeToString("CODEX_COMPILER_SCHEDULER_SOCKET=".
			getBytes(java.nio.charset.StandardCharsets.UTF_8));
		List<String> result = command(List.of("build", "--config=ci-linux", "--remote_exec_header", plain + "FIRST=one",
			"--remote_exec_header=" + plain + "SECOND=two", "--remote_exec_header", encoded + existing, "//cli:codex"),
			Map.of("BUILDBUDDY_API_KEY", "token", "CODEX_COMPILER_SCHEDULER_DIRECTORY", "/owned/ipc"));
		assertTrue(result.contains("--remote_exec_header=" + plain +
			"FIRST=one,SECOND=two,CODEX_COMPILER_SCHEDULER_SOCKET="));
		assertTrue(result.contains("--remote_exec_header=" + encoded + existing + "," + cleared));
	}

	/**
	 * Removes remote CI options only before the executable payload separator when credentials are absent.
	 *
	 * @throws IOException if planning fails
	 */
	@Test
	public void keylessInvocationUsesLocalOptions() throws IOException
	{
		assertEquals(command(List.of("build", "--config=ci-linux", "--", "--config=ci-v8"), Map.of()),
			List.of("bazel", "build", "--", "--config=ci-v8"));
		assertFalse(BazelCommands.plan(List.of("build"), Map.of(), Path.of("."), "Linux").remoteConfig().isPresent());
	}

	/**
	 * Retains program arguments without allowing them to select remote execution.
	 *
	 * @throws IOException if planning fails
	 */
	@Test
	public void payloadDoesNotSelectRemoteExecution() throws IOException
	{
		List<String> arguments = List.of("run", "//cli:codex", "--", "--config=ci-v8");
		assertEquals(command(arguments, Map.of()), List.of("bazel", "run", "//cli:codex", "--", "--config=ci-v8"));
		assertEquals(BazelCommands.plan(arguments, Map.of("BUILDBUDDY_API_KEY", "token"), Path.of("."), "Linux").
			remoteConfig().orElseThrow(), "buildbuddy-generic");
	}

	/**
	 * Selects the upstream host for authenticated upstream push runs.
	 *
	 * @throws IOException if planning fails
	 */
	@Test
	public void upstreamPushUsesOpenaiExecution() throws IOException
	{
		Map<String, String> environment = Map.of("BUILDBUDDY_API_KEY", "token", "GITHUB_ACTIONS", "true",
			"GITHUB_REPOSITORY", "openai/codex", "GITHUB_EVENT_NAME", "push");
		assertEquals(command(List.of("build", "--config=ci-linux", "--", "//cli:codex"), environment),
			List.of("bazel", "--noexperimental_remote_repo_contents_cache", "build", "--config=buildbuddy-openai-rbe",
				"--remote_header=x-buildbuddy-api-key=token",
				"--remote_local_fallback", "--config=ci-linux", "--", "//cli:codex"));
	}

	/**
	 * Keeps Windows execution-platform ownership in the Windows CI configuration.
	 *
	 * @throws IOException if planning fails
	 */
	@Test
	public void windowsConfigOverridesSharedDefaults() throws IOException
	{
		assertEquals(command(List.of("build", "--config=ci-windows-cross", "//cli:codex"),
			Map.of("BUILDBUDDY_API_KEY", "token")), List.of("bazel", "build",
				"--config=buildbuddy-generic-rbe-windows-cross",
				"--remote_header=x-buildbuddy-api-key=token", "--remote_local_fallback", "--config=ci-windows-cross",
				"//cli:codex"));
	}

	/**
	 * Preserves host selection and keeps payload configuration text out of Windows platform selection.
	 *
	 * @throws IOException if planning fails
	 */
	@Test
	public void windowsSelectionRespectsHostAndPayload() throws IOException
	{
		Map<String, String> environment = Map.of("BUILDBUDDY_API_KEY", "token", "GITHUB_ACTIONS", "true",
			"GITHUB_REPOSITORY", "openai/codex", "GITHUB_EVENT_NAME", "push");
		assertEquals(BazelCommands.plan(List.of("build", "--config=ci-windows-cross"), environment, Path.of("."), "Linux").
			remoteConfig().orElseThrow(), "buildbuddy-openai-rbe-windows-cross");
		assertEquals(BazelCommands.plan(List.of("run", "--config=ci-linux", "//cli:codex", "--",
			"--config=ci-windows-cross"), environment, Path.of("."), "Linux").remoteConfig().orElseThrow(),
			"buildbuddy-openai-rbe");
		assertEquals(command(List.of("build", "--config=ci-windows-cross"), Map.of()), List.of("bazel", "build"));
	}

	/**
	 * Inserts remote options before literal query expressions.
	 *
	 * @throws IOException if planning fails
	 */
	@Test
	public void remoteOptionsPrecedeQueryExpression() throws IOException
	{
		String expression = "kind(\"rust_library rule\", //codex-rs/...)";
		for (String query : List.of("query", "cquery", "aquery"))
			assertEquals(command(List.of(query, "--config=ci-windows-cross", "--output=label", expression),
				Map.of("BUILDBUDDY_API_KEY", "token")), List.of("bazel", query,
					"--config=buildbuddy-generic-rbe-windows-cross",
					"--remote_header=x-buildbuddy-api-key=token", "--remote_local_fallback", "--config=ci-windows-cross",
					"--output=label", expression));
	}

	/**
	 * Requires a literal false fork field before selecting the upstream PR host.
	 *
	 * @throws IOException if fixture access or planning fails
	 */
	@Test
	public void sameRepositoryPullRequestUsesOpenai() throws IOException
	{
		assertEquals(prConfiguration("false", "openai/codex", true), "buildbuddy-openai-rbe");
	}

	/**
	 * Routes fork pull requests to the generic host.
	 *
	 * @throws IOException if fixture access or planning fails
	 */
	@Test
	public void forkPullRequestUsesGenericHost() throws IOException
	{
		assertEquals(prConfiguration("true", "openai/codex", true), "buildbuddy-generic-rbe");
	}

	/**
	 * Refuses upstream credentials for runs in other repositories or outside GitHub Actions.
	 *
	 * @throws IOException if fixture access or planning fails
	 */
	@Test
	public void otherRepositoryUsesGenericHost() throws IOException
	{
		assertEquals(prConfiguration("false", "contributor/codex", true), "buildbuddy-generic-rbe");
		assertEquals(prConfiguration("false", "openai/codex", false), "buildbuddy-generic-rbe");
	}

	/**
	 * Treats absent, malformed, trailing, scalar and nonboolean PR proof as untrusted.
	 *
	 * @throws IOException if fixture access or planning fails
	 */
	@Test
	public void invalidPullRequestProofFailsClosed() throws IOException
	{
		for (String value : List.of("null", "0", "\"false\"", "{}", "false} {} {"))
			assertEquals(prConfiguration(value, "openai/codex", true), "buildbuddy-generic-rbe");
		Map<String, String> environment = Map.of("BUILDBUDDY_API_KEY", "token", "GITHUB_ACTIONS", "true",
			"GITHUB_REPOSITORY", "openai/codex", "GITHUB_EVENT_NAME", "pull_request");
		assertEquals(BazelCommands.plan(List.of("build"), environment, Path.of("."), "Linux").remoteConfig().orElseThrow(),
			"buildbuddy-generic");
	}

	/**
	 * Refuses empty event proof and preserves operational failure for invalid UTF-8.
	 *
	 * @throws IOException if fixture access or planning fails
	 */
	@Test
	public void distinguishesMissingAndInvalidEvent() throws IOException
	{
		Path event = Files.createTempFile("bazel-empty-event-", ".json");
		try
		{
			Map<String, String> environment = Map.of("BUILDBUDDY_API_KEY", "token", "GITHUB_ACTIONS", "true",
				"GITHUB_REPOSITORY", "openai/codex", "GITHUB_EVENT_NAME", "pull_request", "GITHUB_EVENT_PATH",
				event.getFileName().toString());
			assertEquals(BazelCommands.plan(List.of("build"), environment,
				event.getParent(), "Linux").remoteConfig().orElseThrow(),
				"buildbuddy-generic");
			Files.write(event, new byte[]{(byte) 255});
			expectThrows(IOException.class, () -> BazelCommands.plan(List.of("build"), environment,
				event.getParent(), "Linux"));
			Files.delete(event);
			assertEquals(BazelCommands.plan(List.of("build"), environment,
				event.getParent(), "Linux").remoteConfig().orElseThrow(),
				"buildbuddy-generic");
		}
		finally
		{
			Files.deleteIfExists(event);
		}
	}

	/**
	 * Preserves the selected Bazel executable and rejects a missing command.
	 *
	 * @throws IOException if planning fails
	 */
	@Test
	public void usesConfiguredExecutable() throws IOException
	{
		assertEquals(command(List.of("info", "execution_root"), Map.of("CODEX_BAZEL_BIN", "fake-bazel")),
			List.of("fake-bazel", "info", "execution_root"));
		expectThrows(IllegalArgumentException.class, () -> command(List.of("--batch"), Map.of()));
	}

	/**
	 * Adds CI startup options while respecting explicit output-root and repository-cache choices.
	 *
	 * @throws IOException if planning fails
	 */
	@Test
	public void honorsExplicitStartupChoices() throws IOException
	{
		Map<String, String> environment = Map.of("BAZEL_OUTPUT_USER_ROOT", "managed/output", "GITHUB_ACTIONS", "true");
		assertEquals(command(List.of("build", "//codex-rs/..."), environment), List.of("bazel",
			"--output_user_root=managed/output", "--noexperimental_remote_repo_contents_cache", "build", "//codex-rs/..."));
		for (String choice : List.of("--experimental_remote_repo_contents_cache",
			"--noexperimental_remote_repo_contents_cache"))
			assertEquals(command(List.of("--output_user_root=explicit", choice, "build"), environment),
				List.of("bazel", "--output_user_root=explicit", choice, "build"));
	}

	/**
	 * Appends configured local cache options while preserving supplied cache selections.
	 *
	 * @throws IOException if planning fails
	 */
	@Test
	public void honorsConfiguredLocalCaches() throws IOException
	{
		Map<String, String> environment = Map.of("BAZEL_REPO_CONTENTS_CACHE", "managed/contents",
			"BAZEL_REPOSITORY_CACHE", "managed/repository");
		assertEquals(command(List.of("build", "--config=local", "//codex-rs/..."), environment),
			List.of("bazel", "build", "--config=local", "//codex-rs/...", "--repo_contents_cache=managed/contents",
				"--repository_cache=managed/repository"));
		assertEquals(command(List.of("build", "--repo_contents_cache=explicit"), environment),
			List.of("bazel", "build", "--repo_contents_cache=explicit", "--repository_cache=managed/repository"));
	}

	/**
	 * Inserts cache options before program arguments and bounds the selected disk cache.
	 *
	 * @throws IOException if planning fails
	 */
	@Test
	public void cachesPrecedeProgramArguments() throws IOException
	{
		assertEquals(command(List.of("run", "//cli:codex", "--", "--program-arg"),
			Map.of("BAZEL_DISK_CACHE", "managed/disk")), List.of("bazel", "run", "//cli:codex",
				"--disk_cache=managed/disk", "--experimental_disk_cache_gc_max_size=768M",
				"--experimental_disk_cache_gc_max_age=14d", "--", "--program-arg"));
		assertEquals(command(List.of("build"), Map.of("BAZEL_DISK_CACHE", "managed/disk",
			"BAZEL_DISK_CACHE_MAX_SIZE", "", "BAZEL_DISK_CACHE_MAX_AGE", "1d")), List.of("bazel", "build",
				"--disk_cache=managed/disk", "--experimental_disk_cache_gc_max_size=",
				"--experimental_disk_cache_gc_max_age=1d"));
	}

	/**
	 * Keeps remote and repository caching on hosted Windows without the default disk-cache write race.
	 *
	 * @throws IOException if planning fails
	 */
	@Test
	public void hostedWindowsRetainsRemoteCaches() throws IOException
	{
		Map<String, String> environment = Map.of("GITHUB_ACTIONS", "true", "RUNNER_OS", "Windows",
			"BUILDBUDDY_API_KEY", "token", "BAZEL_DISK_CACHE", "managed/disk",
			"BAZEL_REPOSITORY_CACHE", "managed/repository");
		assertEquals(command(List.of("run", "--config=ci-windows-cross", "//cli:codex", "--",
			"--disk_cache=payload"), environment), List.of("bazel", "--noexperimental_remote_repo_contents_cache",
				"run", "--config=buildbuddy-generic-rbe-windows-cross", "--remote_header=x-buildbuddy-api-key=token",
				"--remote_local_fallback", "--config=ci-windows-cross", "//cli:codex",
				"--repository_cache=managed/repository", "--disk_cache=", "--", "--disk_cache=payload"));
	}

	/**
	 * Preserves caller-selected disk-cache settings on hosted Windows.
	 *
	 * @throws IOException if planning fails
	 */
	@Test
	public void hostedWindowsPreservesExplicitDiskCache() throws IOException
	{
		Map<String, String> environment = Map.of("GITHUB_ACTIONS", "true", "RUNNER_OS", "Windows",
			"BUILDBUDDY_API_KEY", "token", "BAZEL_DISK_CACHE", "managed/disk");
		for (String choice : List.of("", "explicit/disk"))
			assertEquals(command(List.of("build", "--disk_cache=" + choice), environment), List.of("bazel",
				"--noexperimental_remote_repo_contents_cache", "build", "--config=buildbuddy-generic",
				"--remote_header=x-buildbuddy-api-key=token", "--disk_cache=" + choice,
				"--experimental_disk_cache_gc_max_size=768M", "--experimental_disk_cache_gc_max_age=14d"));
		assertEquals(command(List.of("build", "--disk_cache", "explicit/disk"), environment), List.of("bazel",
			"--noexperimental_remote_repo_contents_cache", "build", "--config=buildbuddy-generic",
			"--remote_header=x-buildbuddy-api-key=token", "--disk_cache", "explicit/disk",
			"--experimental_disk_cache_gc_max_size=768M", "--experimental_disk_cache_gc_max_age=14d"));
	}

	/**
	 * Retains configured disk caches on Unix runners and Windows invocations without remote credentials.
	 *
	 * @throws IOException if planning fails
	 */
	@Test
	public void otherHostsRetainDefaultDiskCache() throws IOException
	{
		for (String operatingSystem : List.of("Linux", "macOS"))
			assertEquals(command(List.of("build"), Map.of("GITHUB_ACTIONS", "true", "RUNNER_OS", operatingSystem,
				"BUILDBUDDY_API_KEY", "token", "BAZEL_DISK_CACHE", "managed/disk")), List.of("bazel",
					"--noexperimental_remote_repo_contents_cache", "build", "--config=buildbuddy-generic",
					"--remote_header=x-buildbuddy-api-key=token", "--disk_cache=managed/disk",
					"--experimental_disk_cache_gc_max_size=768M", "--experimental_disk_cache_gc_max_age=14d"));
		assertEquals(command(List.of("build"), Map.of("GITHUB_ACTIONS", "true", "RUNNER_OS", "Windows",
			"BAZEL_DISK_CACHE", "managed/disk")), List.of("bazel", "--noexperimental_remote_repo_contents_cache",
				"build", "--disk_cache=managed/disk", "--experimental_disk_cache_gc_max_size=768M",
				"--experimental_disk_cache_gc_max_age=14d"));
	}

	/**
	 * Bounds hosted macOS CI execution while preserving explicit jobs and literal program arguments.
	 *
	 * @throws IOException if planning fails
	 */
	@Test
	public void hostedMacosBoundsDefaultConcurrency() throws IOException
	{
		Map<String, String> environment = Map.of("GITHUB_ACTIONS", "true", "RUNNER_OS", "macOS",
			"BUILDBUDDY_API_KEY", "token");
		for (String operation : List.of("build", "test", "cquery"))
		{
			List<String> arguments = List.of(operation, "--config=ci-macos", "//third_party/v8:pair", "--", "--jobs=99");
			assertEquals(command(arguments, environment), List.of("bazel", "--noexperimental_remote_repo_contents_cache",
				operation, "--config=buildbuddy-generic-rbe", "--remote_header=x-buildbuddy-api-key=token",
				"--remote_local_fallback", "--config=ci-macos", "//third_party/v8:pair", "--local_resources=cpu=2", "--",
				"--jobs=99"));
		}
		for (List<String> option : List.of(List.of("--jobs=4"), List.of("--jobs", "4"), List.of("-j4"),
			List.of("-j", "4"), List.of("--local_resources=cpu=4")))
		{
			List<String> arguments = new ArrayList<>(List.of("build", "--config=ci-macos"));
			arguments.addAll(option);
			assertFalse(command(arguments, environment).contains("--jobs=2"));
			assertFalse(command(arguments, environment).contains("--local_resources=cpu=2"));
		}
		assertEquals(command(List.of("build", "--config=ci-macos"),
			Map.of("GITHUB_ACTIONS", "true", "RUNNER_OS", "macOS")),
			List.of("bazel", "--noexperimental_remote_repo_contents_cache", "build", "--jobs=2"));
		assertFalse(command(List.of("build", "--config=ci-macos"), Map.of("RUNNER_OS", "macOS")).
			contains("--jobs=2"));
		assertFalse(command(List.of("build", "--config=ci-linux"), environment).contains("--jobs=2"));
	}

	/**
	 * Selects an invocation from explicit arguments and environment.
	 *
	 * @param arguments Bazel arguments
	 * @param environment the caller configuration
	 * @return exact direct process argv
	 * @throws IOException if event access fails
	 */
	private static List<String> command(List<String> arguments, Map<String, String> environment) throws IOException
	{
		return BazelCommands.plan(arguments, environment, Path.of("."), "Linux").command();
	}

	/**
	 * Plans a pull-request invocation using owned event evidence.
	 *
	 * @param fork the exact JSON field text
	 * @param repository the workflow repository
	 * @param actions whether the GitHub Actions marker is present
	 * @return selected remote configuration
	 * @throws IOException if fixture access or planning fails
	 */
	private static String prConfiguration(String fork, String repository, boolean actions) throws IOException
	{
		Path event = Files.createTempFile("bazel-event-", ".json");
		try
		{
			Files.writeString(event, "{\"pull_request\":{\"head\":{\"repo\":{\"fork\":" + fork + "}}}}");
			Map<String, String> environment = Map.of("BUILDBUDDY_API_KEY", "token", "GITHUB_ACTIONS",
				Boolean.toString(actions), "GITHUB_REPOSITORY", repository, "GITHUB_EVENT_NAME", "pull_request",
				"GITHUB_EVENT_PATH", event.toString());
			return BazelCommands.plan(List.of("build", "--config=ci-v8"), environment, event.getParent(), "Linux").
				remoteConfig().orElseThrow();
		}
		finally
		{
			Files.delete(event);
		}
	}
}
