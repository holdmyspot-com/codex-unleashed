package com.holdmyspot.codexunleashed.tooling;

import java.io.IOException;
import java.nio.charset.CharacterCodingException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
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
	/** Private IPC directory supplied by the compiler scheduler's invocation owner. */
	static final String SCHEDULER_DIRECTORY = "CODEX_COMPILER_SCHEDULER_DIRECTORY";
	private static final String WINDOWS_CROSS_CONFIG = "--config=ci-windows-cross";
	private static final Set<String> REMOTE_EXECUTION = Set.of("--config=ci-linux", "--config=ci-macos",
		"--config=ci-v8", WINDOWS_CROSS_CONFIG);
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
	 * @param operatingSystem the invoking JVM operating system name
	 * @return the complete immutable invocation
	 * @throws NullPointerException if an argument, argument element, environment entry or directory is null
	 * @throws IllegalArgumentException if no Bazel command is present
	 * @throws IOException if workflow evidence has invalid UTF-8
	 */
	public static Invocation plan(List<String> arguments, Map<String, String> environment, Path workingDirectory,
		String operatingSystem)
		throws IOException
	{
		arguments = List.copyOf(arguments);
		environment = Map.copyOf(environment);
		Objects.requireNonNull(workingDirectory, "workingDirectory");
		Objects.requireNonNull(operatingSystem, "operatingSystem");
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
			String configurationPrefix = "buildbuddy-generic";
			if (openai)
				configurationPrefix = "buildbuddy-openai";

			StringBuilder configuration = new StringBuilder(configurationPrefix);
			if (remoteExecution)
				configuration.append("-rbe");
			if (arguments.subList(0, separator).contains(WINDOWS_CROSS_CONFIG))
				configuration.append("-windows-cross");
			String selected = configuration.toString();
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
		if (environment.getOrDefault("GITHUB_ACTIONS", "").equals("true") &&
			environment.getOrDefault("RUNNER_OS", "").equals("macOS") &&
			arguments.subList(0, separator).contains("--config=ci-macos") &&
			Set.of("build", "test", "run", "coverage", "cquery", "aquery").contains(arguments.get(commandIndex)) &&
			arguments.subList(commandIndex + 1, separator).stream().noneMatch(argument ->
				argument.equals("--jobs") || argument.startsWith("--jobs=") || argument.startsWith("-j") ||
					argument.startsWith("--local_resources=cpu=")))
		{
			if (config.isPresent())
				command.add("--local_resources=cpu=2");
			else
				command.add("--jobs=2");
		}

		addCache(command, configured, configuredSeparator, environment, "BAZEL_REPO_CONTENTS_CACHE",
			"--repo_contents_cache=");
		addCache(command, configured, configuredSeparator, environment, "BAZEL_REPOSITORY_CACHE", "--repository_cache=");
		addDiskCache(command, configured, configuredSeparator, environment, config.isPresent());
		String schedulerDirectory = environment.get(SCHEDULER_DIRECTORY);
		if (present(schedulerDirectory) && buildsCompilers(arguments))
			addCompilerScheduler(command, schedulerDirectory, config.isPresent() && remoteExecution, operatingSystem);
		command.addAll(configured.subList(configuredSeparator, configured.size()));
		return new Invocation(command, config, openai);
	}

	/**
	 * Identifies commands that execute compiler actions, excluding startup options and executable payloads.
	 *
	 * @param arguments Bazel arguments without its executable
	 * @return whether the selected command executes compiler actions
	 */
	static boolean buildsCompilers(List<String> arguments)
	{
		return arguments.stream().filter(argument -> !argument.startsWith("-")).findFirst().
			map(command -> Set.of("build", "test", "run", "coverage").contains(command)).orElse(false);
	}

	/**
	 * Requires sandboxed local Rust actions and leaves remote workers under their own resource controls.
	 *
	 * @param command arguments before the executable payload separator
	 * @param directory the caller-owned private socket directory mounted into local sandboxes
	 * @param remoteExecution whether the invocation enables BuildBuddy remote execution
	 * @param operatingSystem the invoking JVM operating system name
	 * @throws IOException if the operating system has no supported local compiler strategy
	 */
	private static void addCompilerScheduler(List<String> command, String directory, boolean remoteExecution,
		String operatingSystem) throws IOException
	{
		String socket = Path.of(directory).resolve("s").toString();
		String strategies;
		if (operatingSystem.equals("Linux"))
		{
			socket = "/var/tmp/s";
			strategies = "linux-sandbox";
		}
		else if (operatingSystem.equals("Mac OS X"))
			strategies = "darwin-sandbox";
		else if (operatingSystem.startsWith("Windows"))
			strategies = "local";
		else
			throw new IOException("Compiler scheduling does not support operating system: " + operatingSystem);
		command.add("--action_env=CODEX_COMPILER_SCHEDULER_SOCKET=" + socket);
		command.add("--host_action_env=CODEX_COMPILER_SCHEDULER_SOCKET=" + socket);
		if (operatingSystem.equals("Linux"))
			command.add("--sandbox_add_mount_pair=" + directory + ":/var/tmp");
		if (remoteExecution)
			strategies = "remote," + strategies;
		command.add("--strategy=Rustc=" + strategies);
		command.add("--strategy=RustcMetadata=" + strategies);
		if (remoteExecution)
		{
			String override = "CODEX_COMPILER_SCHEDULER_SOCKET=";
			appendRemoteOverride(command, "secret-env-overrides", override, true);
			appendRemoteOverride(command, "secret-env-overrides-base64",
				Base64.getEncoder().encodeToString(override.getBytes(java.nio.charset.StandardCharsets.UTF_8)), false);
		}
	}

	/**
	 * Preserves existing execution-time environment overrides and appends the scheduler override last.
	 *
	 * @param command mutable arguments before the executable payload separator
	 * @param property the BuildBuddy platform header name
	 * @param value the appended environment assignment in the header's encoding
	 * @param required whether to create the header when the caller did not supply one
	 */
	private static void appendRemoteOverride(List<String> command, String property, String value, boolean required)
	{
		String header = "x-buildbuddy-platform." + property + "=";
		String option = "--remote_exec_header=" + header;
		List<String> values = new ArrayList<>();
		for (int index = 0; index < command.size();)
		{
			String argument = command.get(index);
			if (argument.startsWith(option))
			{
				values.add(argument.substring(option.length()));
				command.remove(index);
			}
			else if (argument.equals("--remote_exec_header") && index + 1 < command.size() &&
				command.get(index + 1).startsWith(header))
			{
				values.add(command.remove(index + 1).substring(header.length()));
				command.remove(index);
			}
			else
				index += 1;
		}
		if (required || !values.isEmpty())
		{
			values.add(value);
			command.add(option + String.join(",", values));
		}
	}

	/**
	 * Preserves explicit cache choices and disables the default combined disk cache on hosted Windows.
	 *
	 * @param command assembled direct argv
	 * @param configured caller and remote arguments
	 * @param separator the program-argument boundary
	 * @param environment explicit settings
	 * @param remoteCache whether the invocation selects a BuildBuddy cache
	 */
	private static void addDiskCache(List<String> command, List<String> configured, int separator,
		Map<String, String> environment, boolean remoteCache)
	{
		String option = "--disk_cache=";
		boolean explicitDiskCache = configured.subList(0, separator).stream().anyMatch(argument ->
			argument.equals("--disk_cache") || argument.startsWith(option));
		boolean hostedWindows = environment.getOrDefault("GITHUB_ACTIONS", "").equals("true") &&
			environment.getOrDefault("RUNNER_OS", "").equals("Windows");
		if (hostedWindows && remoteCache && !explicitDiskCache)
		{
			// Bazel 9.0 races when replacing disk-cache blobs on Windows; upstream CI disables this cache.
			// Remove this workaround when the upstream pin includes the fix for
			// https://github.com/bazelbuild/bazel/issues/28408.
			command.add(option);
			return;
		}

		if (!explicitDiskCache)
			addCache(command, configured, separator, environment, "BAZEL_DISK_CACHE", option);
		if (present(environment.get("BAZEL_DISK_CACHE")))
		{
			command.add("--experimental_disk_cache_gc_max_size=" +
				environment.getOrDefault("BAZEL_DISK_CACHE_MAX_SIZE", "768M"));
			command.add("--experimental_disk_cache_gc_max_age=" +
				environment.getOrDefault("BAZEL_DISK_CACHE_MAX_AGE", "14d"));
		}
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
