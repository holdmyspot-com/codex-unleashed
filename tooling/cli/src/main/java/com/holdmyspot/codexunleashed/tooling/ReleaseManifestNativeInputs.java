package com.holdmyspot.codexunleashed.tooling;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import tools.jackson.databind.node.ObjectNode;

/**
 * Produces validated native-build diagnostic inputs from completed package operation records and fresh observations.
 */
public final class ReleaseManifestNativeInputs
{
	/** Required command options. */
	private static final Set<String> REQUIRED = Set.of("build-result", "archive-result", "target", "package-root",
		"output");
	/** Optional explicit current-boundary observations. */
	private static final Set<String> OPTIONAL = Set.of("runner-arch", "image-os", "image-version",
		"configured-v8-archive");

	/**
	 * Immutable current process settings supplied independently of historical observations.
	 *
	 * @param temporaryDirectory existing owned process-capture parent
	 * @param workingDirectory current process working directory
	 * @param runnerOs normalized Linux, macOS or Windows producer host
	 * @param bashExecutable explicitly selected Bash executable
	 * @param variables current process environment overrides
	 */
	public record Environment(Path temporaryDirectory, Path workingDirectory, String runnerOs,
		String bashExecutable, Map<String, String> variables)
	{
		/**
		 * Validates and copies current process settings.
		 *
		 * @param temporaryDirectory owned process-capture parent
		 * @param workingDirectory current process directory
		 * @param runnerOs normalized producer host
		 * @param bashExecutable explicit Bash executable
		 * @param variables current overrides
		 */
		public Environment
		{
			Objects.requireNonNull(temporaryDirectory, "temporaryDirectory");
			Objects.requireNonNull(workingDirectory, "workingDirectory");
			ReleaseManifestSchema.require(Set.of("Linux", "macOS", "Windows").contains(runnerOs),
				"Unsupported producer runner OS");
			ReleaseManifestSchema.require(bashExecutable != null && !bashExecutable.isBlank(),
				"Explicit Bash selection must be nonempty");
			variables = Map.copyOf(variables);
		}
	}

	/**
	 * Prevents construction.
	 */
	private ReleaseManifestNativeInputs()
	{
	}

	/**
	 * Resolves ambient host values once at the production command boundary.
	 *
	 * @param scope borrowed JSON services
	 * @param arguments named command options
	 * @param environment current process environment supplied by the dispatcher
	 * @param out borrowed completion stream
	 * @param err borrowed diagnostics stream
	 * @return zero on success, two for invalid arguments, one for rejected observations or infrastructure failure
	 */
	public static int run(ToolingScope scope, String[] arguments, Map<String, String> environment,
		PrintStream out, PrintStream err)
	{
		if (help(arguments, out))
			return 0;
		try
		{
			String bash = "bash";
			if (environment.containsKey("CODEX_TOOLING_BASH"))
				bash = environment.get("CODEX_TOOLING_BASH");
			else if (environment.containsKey("BASH"))
				bash = environment.get("BASH");
			Environment settings = new Environment(Path.of(System.getProperty("java.io.tmpdir")),
				Path.of("").toAbsolutePath(), runnerOs(System.getProperty("os.name")), bash, environment);
			return run(scope, arguments, out, err, SystemCommands::capture, settings);
		}
		catch (IllegalArgumentException failure)
		{
			err.println("Native inputs failed: " + failure.getMessage());
			return 1;
		}
	}

	/**
	 * Produces a document through explicit filesystem and injected process boundaries.
	 *
	 * @param scope borrowed JSON services
	 * @param arguments named command options
	 * @param out borrowed completion stream
	 * @param err borrowed diagnostics stream
	 * @param capture fixed tool-probe process boundary
	 * @param environment immutable current process settings
	 * @return zero on success, two for syntax errors, one for rejected inputs or observation failures
	 */
	public static int run(ToolingScope scope, String[] arguments, PrintStream out, PrintStream err,
		SystemCommands.Capture capture, Environment environment)
	{
		Objects.requireNonNull(scope, "scope");
		Objects.requireNonNull(capture, "capture");
		Objects.requireNonNull(environment, "environment");
		Objects.requireNonNull(err, "err");
		if (help(arguments, out))
			return 0;
		Map<String, String> options;
		try
		{
			options = parse(arguments);
		}
		catch (IllegalArgumentException failure)
		{
			err.println("release-native-inputs: error: " + failure.getMessage());
			return 2;
		}
		try
		{
			String target = options.get("target");
			ObjectNode document = scope.getJsonMapper().createObjectNode().put("format_version", 1).put("target", target);
			ObjectNode runner = document.putObject("runner").put("os", environment.runnerOs());
			for (String name : List.of("runner-arch", "image-os", "image-version"))
			{
				String field = name.replace('-', '_');
				if (name.equals("runner-arch"))
					field = "arch";
				runner.put(field, options.get(name));
			}
			document.set("operations", NativeOperationRecords.operations(scope, Path.of(options.get("build-result")),
				Path.of(options.get("archive-result")), target, environment.runnerOs()));
			Path v8 = null;
			if (options.containsKey("configured-v8-archive"))
				v8 = Path.of(options.get("configured-v8-archive"));
			document.set("resources", NativeResourceObservations.collect(scope, Path.of(options.get("package-root")),
				target, v8));
			document.set("tools", NativeToolObservations.collect(scope, capture, environment));
			ReleaseManifestNative.validate(document, target);
			String encoded = ArtifactJson.format(scope, scope.getJsonMapper().convertValue(document, Object.class));
			ReleaseManifestSchema.require(encoded.getBytes(StandardCharsets.US_ASCII).length <=
				ReleaseManifestSchema.MAX_DOCUMENT_BYTES, "Native inputs JSON exceeds 4 MiB");
			Path output = Path.of(options.get("output"));
			ReleaseManifestSchema.write(scope, output, document);
			out.println("Recorded native inputs for " + target);
			return 0;
		}
		catch (IOException | IllegalArgumentException failure)
		{
			err.println("Native inputs failed: " + failure.getMessage());
			return 1;
		}
	}

	/**
	 * Parses the fixed option surface without resolving input paths or probing tools.
	 *
	 * @param arguments option tokens
	 * @return accepted options
	 */
	private static Map<String, String> parse(String[] arguments)
	{
		Map<String, String> options = new LinkedHashMap<>();
		for (int index = 0; index < arguments.length; index += 2)
		{
			String token = arguments[index];
			ReleaseManifestSchema.require(token.startsWith("--"), "Expected a named option");
			String name = token.substring(2);
			ReleaseManifestSchema.require(REQUIRED.contains(name) || OPTIONAL.contains(name), "Unknown option: " + token);
			ReleaseManifestSchema.require(!options.containsKey(name), "Duplicate option: " + token);
			ReleaseManifestSchema.require(index + 1 < arguments.length && !arguments[index + 1].startsWith("--"),
				"Missing value for " + token);
			String value = arguments[index + 1];
			if (REQUIRED.contains(name) || name.equals("configured-v8-archive"))
				ReleaseManifestSchema.require(!value.isEmpty(), "Empty value for " + token);
			if (!Set.of("target", "runner-arch", "image-os", "image-version").contains(name))
				Path.of(value);
			options.put(name, value);
		}
		ReleaseManifestSchema.require(options.keySet().containsAll(REQUIRED),
			"Required options: build-result, archive-result, target, package-root, output");
		ReleaseManifestSchema.require(ReleaseManifestSchema.TARGETS.contains(options.get("target")),
			"Unsupported release target");
		return options;
	}

	/**
	 * Prints help before any filesystem or process observation.
	 *
	 * @param arguments option tokens
	 * @param out borrowed output stream
	 * @return whether help was requested
	 */
	private static boolean help(String[] arguments, PrintStream out)
	{
		if (!List.of(arguments).contains("--help") && !List.of(arguments).contains("-h"))
			return false;
		out.println("Usage: release-native-inputs --build-result PATH --archive-result PATH --target TARGET " +
			"--package-root PATH --output PATH [--runner-arch TEXT] [--image-os TEXT] [--image-version TEXT] " +
			"[--configured-v8-archive PATH]");
		return true;
	}

	/**
	 * Normalizes the producer JVM's host identity without guessing image observations.
	 *
	 * @param host operating system name at the production boundary
	 * @return normalized supported runner OS
	 */
	private static String runnerOs(String host)
	{
		String normalized = host.toLowerCase(Locale.ROOT);
		if (normalized.contains("windows"))
			return "Windows";
		if (normalized.contains("mac"))
			return "macOS";
		ReleaseManifestSchema.require(normalized.contains("linux"), "Unsupported producer runner OS");
		return "Linux";
	}
}
