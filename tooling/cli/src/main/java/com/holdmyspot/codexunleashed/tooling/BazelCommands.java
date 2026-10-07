package com.holdmyspot.codexunleashed.tooling;

import java.io.IOException;
import java.nio.charset.CharacterCodingException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Plans Bazel execution from explicit workflow evidence, credentials and cache settings. */
public final class BazelCommands
{
	private static final Set<String> REMOTE_EXECUTION = Set.of("--config=ci-linux", "--config=ci-macos",
		"--config=ci-v8", "--config=ci-windows-cross");
	private static final Set<String> CONTENTS_CACHE_STARTUP = Set.of("--experimental_remote_repo_contents_cache",
		"--noexperimental_remote_repo_contents_cache");

	/** Prevents construction. */
	private BazelCommands()
	{
	}

	/**
	 * Contains one consistent selection of direct arguments and its diagnostic configuration.
	 *
	 * @param command the direct argv, including the executable
	 * @param remoteConfig selected remote configuration, absent when credentials are empty or missing
	 * @param openaiHost whether trusted workflow evidence selects the upstream host
	 */
	public record Invocation(List<String> command, Optional<String> remoteConfig, boolean openaiHost)
	{
		/**
		 * Copies argument storage and requires a non-null configuration selection.
		 *
		 * @param command the direct argv
		 * @param remoteConfig selected remote configuration
		 * @param openaiHost the selected host
		 * @throws NullPointerException if the command, a command element or configuration is null
		 */
		public Invocation
		{
			command = List.copyOf(command);
			Objects.requireNonNull(remoteConfig, "remoteConfig");
		}
	}

	/**
	 * Preserves startup options, inserts remote defaults after the command, and leaves program arguments literal.
	 *
	 * @param arguments Bazel arguments without the executable
	 * @param environment the caller's explicit environment
	 * @param workingDirectory directory used to resolve relative event filenames
	 * @return the complete immutable invocation
	 * @throws NullPointerException if an argument, argument element, environment entry or directory is null
	 * @throws IllegalArgumentException if no Bazel command is present
	 * @throws IOException if workflow evidence has invalid UTF-8
	 */
	public static Invocation plan(List<String> arguments, Map<String, String> environment, Path workingDirectory)
		throws IOException
	{
		arguments = List.copyOf(arguments);
		environment = Map.copyOf(environment);
		Objects.requireNonNull(workingDirectory, "workingDirectory");
		int commandIndex = 0;
		while (commandIndex < arguments.size() && arguments.get(commandIndex).startsWith("-"))
			commandIndex += 1;
		if (commandIndex == arguments.size())
			throw new IllegalArgumentException("expected a Bazel command");
		List<String> startup = arguments.subList(0, commandIndex);
		List<String> command = new ArrayList<>();
		command.add(environment.getOrDefault("CODEX_BAZEL_BIN", "bazel"));
		String outputRoot = environment.get("BAZEL_OUTPUT_USER_ROOT");
		if (present(outputRoot) && startup.stream().noneMatch(value -> value.startsWith("--output_user_root=")))
			command.add("--output_user_root=" + outputRoot);
		if (environment.getOrDefault("GITHUB_ACTIONS", "").equals("true") &&
			startup.stream().noneMatch(CONTENTS_CACHE_STARTUP::contains))
			command.add("--noexperimental_remote_repo_contents_cache");

		String key = environment.get("BUILDBUDDY_API_KEY");
		int separator = separator(arguments);
		boolean remoteExecution = arguments.subList(0, separator).stream().anyMatch(REMOTE_EXECUTION::contains);
		boolean openai = present(key) && trusted(environment, workingDirectory);
		Optional<String> config = Optional.empty();
		List<String> configured = new ArrayList<>();
		if (present(key))
		{
			String selected = "buildbuddy-generic";
			if (openai)
				selected = "buildbuddy-openai";
			if (remoteExecution)
				selected += "-rbe";
			config = Optional.of(selected);
			configured.addAll(arguments.subList(0, commandIndex + 1));
			configured.add("--config=" + selected);
			configured.add("--remote_header=x-buildbuddy-api-key=" + key);
			if (remoteExecution)
				configured.add("--remote_local_fallback");
			configured.addAll(arguments.subList(commandIndex + 1, arguments.size()));
		}
		else
		{
			for (int index = 0; index < arguments.size(); index += 1)
				if (index >= separator || !REMOTE_EXECUTION.contains(arguments.get(index)))
					configured.add(arguments.get(index));
		}

		int configuredSeparator = separator(configured);
		command.addAll(configured.subList(0, configuredSeparator));
		addCache(command, configured, configuredSeparator, environment, "BAZEL_REPO_CONTENTS_CACHE",
			"--repo_contents_cache=");
		addCache(command, configured, configuredSeparator, environment, "BAZEL_REPOSITORY_CACHE", "--repository_cache=");
		addCache(command, configured, configuredSeparator, environment, "BAZEL_DISK_CACHE", "--disk_cache=");
		if (present(environment.get("BAZEL_DISK_CACHE")))
		{
			command.add("--experimental_disk_cache_gc_max_size=" +
				environment.getOrDefault("BAZEL_DISK_CACHE_MAX_SIZE", "768M"));
			command.add("--experimental_disk_cache_gc_max_age=" +
				environment.getOrDefault("BAZEL_DISK_CACHE_MAX_AGE", "14d"));
		}
		command.addAll(configured.subList(configuredSeparator, configured.size()));
		return new Invocation(command, config, openai);
	}

	/**
	 * Appends a configured cache only when the caller has no explicit pre-separator selection.
	 *
	 * @param command assembled direct argv
	 * @param configured caller and remote arguments
	 * @param separator the program-argument boundary
	 * @param environment explicit settings
	 * @param key the cache environment key
	 * @param prefix its Bazel option prefix
	 */
	private static void addCache(List<String> command, List<String> configured, int separator,
		Map<String, String> environment, String key, String prefix)
	{
		String value = environment.get(key);
		if (present(value) && configured.subList(0, separator).stream().noneMatch(argument -> argument.startsWith(prefix)))
			command.add(prefix + value);
	}

	/**
	 * Locates the first literal program-argument separator.
	 *
	 * @param arguments the direct arguments
	 * @return separator index, or argument count when absent
	 */
	private static int separator(List<String> arguments)
	{
		int result = arguments.indexOf("--");
		if (result < 0)
			return arguments.size();
		return result;
	}

	/**
	 * Distinguishes absent and empty settings from nonempty values without trimming caller text.
	 *
	 * @param value the optional setting
	 * @return whether a nonempty value is present
	 */
	private static boolean present(String value)
	{
		return value != null && !value.isEmpty();
	}

	/**
	 * Requires exact upstream workflow identity and literal non-fork proof for pull requests.
	 *
	 * @param environment explicit workflow evidence
	 * @param workingDirectory event path resolution directory
	 * @return whether the workflow is trusted
	 * @throws IOException if event text has invalid UTF-8
	 */
	private static boolean trusted(Map<String, String> environment, Path workingDirectory) throws IOException
	{
		if (!environment.getOrDefault("GITHUB_ACTIONS", "").equals("true") ||
			!environment.getOrDefault("GITHUB_REPOSITORY", "").equals("openai/codex"))
			return false;
		if (!environment.getOrDefault("GITHUB_EVENT_NAME", "").equals("pull_request"))
			return true;
		String path = environment.get("GITHUB_EVENT_PATH");
		if (!present(path))
			return false;
		String text;
		try
		{
			text = Files.readString(workingDirectory.resolve(path));
		}
		catch (CharacterCodingException failure)
		{
			throw failure;
		}
		catch (IOException _)
		{
			return false;
		}
		try
		{
			JsonNode document = JsonMapper.builder().enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).
				build().readTree(text);
			JsonNode fork = document.path("pull_request").path("head").path("repo").path("fork");
			return fork.isBoolean() && !fork.booleanValue();
		}
		catch (JacksonException _)
		{
			return false;
		}
	}
}
