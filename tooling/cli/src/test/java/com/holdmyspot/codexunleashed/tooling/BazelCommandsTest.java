package com.holdmyspot.codexunleashed.tooling;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.testng.annotations.Test;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.expectThrows;

/** Verifies retained Bazel startup, credential, host, cache and argument-boundary decisions. */
public final class BazelCommandsTest
{
	/** Creates Bazel command tests. */
	public BazelCommandsTest()
	{
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
		assertFalse(BazelCommands.plan(List.of("build"), Map.of(), Path.of(".")).remoteConfig().isPresent());
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
		assertEquals(BazelCommands.plan(arguments, Map.of("BUILDBUDDY_API_KEY", "token"), Path.of(".")).
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
	 * Places Windows execution configuration after shared remote defaults.
	 *
	 * @throws IOException if planning fails
	 */
	@Test
	public void windowsConfigOverridesSharedDefaults() throws IOException
	{
		assertEquals(command(List.of("build", "--config=ci-windows-cross", "//cli:codex"),
			Map.of("BUILDBUDDY_API_KEY", "token")), List.of("bazel", "build", "--config=buildbuddy-generic-rbe",
				"--remote_header=x-buildbuddy-api-key=token", "--remote_local_fallback", "--config=ci-windows-cross",
				"//cli:codex"));
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
				Map.of("BUILDBUDDY_API_KEY", "token")), List.of("bazel", query, "--config=buildbuddy-generic-rbe",
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
		assertEquals(BazelCommands.plan(List.of("build"), environment, Path.of(".")).remoteConfig().orElseThrow(),
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
			assertEquals(BazelCommands.plan(List.of("build"), environment, event.getParent()).remoteConfig().orElseThrow(),
				"buildbuddy-generic");
			Files.write(event, new byte[]{(byte) 255});
			expectThrows(IOException.class, () -> BazelCommands.plan(List.of("build"), environment, event.getParent()));
			Files.delete(event);
			assertEquals(BazelCommands.plan(List.of("build"), environment, event.getParent()).remoteConfig().orElseThrow(),
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
				"run", "--config=buildbuddy-generic-rbe", "--remote_header=x-buildbuddy-api-key=token",
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
	 * Selects an invocation from explicit arguments and environment.
	 *
	 * @param arguments Bazel arguments
	 * @param environment the caller configuration
	 * @return exact direct process argv
	 * @throws IOException if event access fails
	 */
	private static List<String> command(List<String> arguments, Map<String, String> environment) throws IOException
	{
		return BazelCommands.plan(arguments, environment, Path.of(".")).command();
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
			return BazelCommands.plan(List.of("build", "--config=ci-v8"), environment, event.getParent()).
				remoteConfig().orElseThrow();
		}
		finally
		{
			Files.delete(event);
		}
	}
}
