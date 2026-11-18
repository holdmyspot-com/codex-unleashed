package com.holdmyspot.codexunleashed.tooling;

import java.io.IOException;
import java.io.PrintStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;

/** Verifies the original source version or the identity reported by a compiled CLI package. */
public final class PackageIdentity
{
	/** Source identity command name. */
	private static final String SOURCE_MODE = "source";
	/** Compiled package identity command name. */
	private static final String BINARY_MODE = "binary";
	/** Source workspace argument name. */
	private static final String SOURCE_ROOT_OPTION = "--source-root";
	/** Compiled package directory argument name. */
	private static final String PACKAGE_DIRECTORY_OPTION = "--package-dir";
	/** Expected upstream version argument name. */
	private static final String VERSION_OPTION = "--version";
	/** Exact upstream build digits argument name. */
	private static final String BUILD_OPTION = "--build";
	/** Atomic identity proof output argument name. */
	private static final String PROOF_OUTPUT_OPTION = "--output";
	/** Layout metadata file selected by the upstream package format. */
	private static final String PACKAGE_METADATA_FILE = "codex-package.json";
	/** Version-1 package entrypoints that the upstream layout permits. */
	private static final Set<String> PACKAGE_ENTRYPOINTS = Set.of("bin/codex", "bin/codex.exe");
	/** JSON field naming the upstream package layout version. */
	private static final String LAYOUT_VERSION_FIELD = "layoutVersion";
	/** JSON field naming the package CLI entrypoint. */
	private static final String ENTRYPOINT_FIELD = "entrypoint";
	/** TOML table naming the workspace-wide package metadata. */
	private static final String WORKSPACE_FIELD = "workspace";
	/** TOML table naming workspace package metadata. */
	private static final String PACKAGE_FIELD = "package";
	/** TOML value naming the original upstream package version. */
	private static final String CARGO_VERSION_FIELD = "version";
	/** Required build-info field for the CLI version and build. */
	private static final String BUILD_INFO_CLI_VERSION_FIELD = "Codex CLI version";
	/** Required build-info field for the exact build number. */
	private static final String BUILD_INFO_BUILD_NUMBER_FIELD = "Build number";
	/** Required build-info field for the upstream version. */
	private static final String BUILD_INFO_UPSTREAM_VERSION_FIELD = "Upstream version";
	/** Required build-info field for the package provider. */
	private static final String BUILD_INFO_PROVIDER_FIELD = "Provided by";
	/** Provider value required by the release package identity contract. */
	private static final String BUILD_INFO_PROVIDER = "Codex Unleashed";
	/** Second line the patched CLI prints after its version line, as asserted by its own build-info test. */
	private static final String SEE_BUILD_INFO_LINE = "(See --build-info for more information)";
	/** Prefix for same-directory temporary files used to atomically replace the proof. */
	private static final String PROOF_TEMPORARY_PREFIX = "package-identity-";

	/** Prevents construction. */
	private PackageIdentity()
	{
	}

	/**
	 * Verifies source or compiled package identity with the system process capture implementation.
	 *
	 * @param scope borrowed shared values; the caller retains ownership
	 * @param arguments command mode followed by its named arguments
	 * @param out borrowed success output; identity operations do not write to it
	 * @param err borrowed diagnostics
	 * @return zero when identity is verified, one when source or package verification fails, or two for invalid arguments
	 * @throws NullPointerException if a supplied reference is null
	 */
	public static int run(ToolingScope scope, String[] arguments, PrintStream out, PrintStream err)
	{
		return run(scope, arguments, out, err, SystemCommands::capture);
	}

	/**
	 * Verifies source or compiled package identity with the supplied captured-process boundary.
	 *
	 * @param scope borrowed shared values; the caller retains ownership
	 * @param arguments command mode followed by its named arguments
	 * @param out borrowed success output; identity operations do not write to it
	 * @param err borrowed diagnostics
	 * @param capture process capture operation used for the actual package entrypoint
	 * @return zero when identity is verified, one when source or package verification fails, or two for invalid arguments
	 * @throws NullPointerException if a supplied reference or argument is null
	 */
	public static int run(ToolingScope scope, String[] arguments, PrintStream out, PrintStream err,
		SystemCommands.Capture capture)
	{
		Objects.requireNonNull(scope, "scope");
		Objects.requireNonNull(arguments, "arguments");
		Objects.requireNonNull(out, "out");
		Objects.requireNonNull(err, "err");
		Objects.requireNonNull(capture, "capture");

		Map<String, String> options;
		try
		{
			options = parseArguments(arguments);
		}
		catch (IllegalArgumentException failure)
		{
			err.println("package-identity: " + failure.getMessage());
			printUsage(err);
			return 2;
		}

		try
		{
			if (arguments[0].equals(SOURCE_MODE))
				verifySource(scope, Path.of(options.get(SOURCE_ROOT_OPTION)), options.get(VERSION_OPTION),
					options.get(BUILD_OPTION));
			else
				verifyBinary(scope, options, capture);
			return 0;
		}
		catch (IOException | IllegalArgumentException failure)
		{
			err.println("package-identity: " + failure.getMessage());
			return 1;
		}
	}

	/**
	 * Parses the selected command's complete option set and rejects duplicate, missing, or unsupported arguments.
	 *
	 * @param arguments command mode followed by option-value pairs
	 * @return immutable options with the values supplied by the caller
	 * @throws IllegalArgumentException if the mode or option set is invalid
	 */
	private static Map<String, String> parseArguments(String[] arguments)
	{
		if (arguments.length == 0)
			throw new IllegalArgumentException("expected source or binary operation");

		Set<String> requiredOptions = switch (arguments[0])
		{
			case SOURCE_MODE -> Set.of(SOURCE_ROOT_OPTION, VERSION_OPTION, BUILD_OPTION);
			case BINARY_MODE -> Set.of(PACKAGE_DIRECTORY_OPTION, VERSION_OPTION, BUILD_OPTION, PROOF_OUTPUT_OPTION);
			default -> throw new IllegalArgumentException("unsupported operation: " + arguments[0]);
		};
		if (arguments.length != requiredOptions.size() * 2 + 1)
			throw new IllegalArgumentException("each required option needs exactly one value");

		Map<String, String> options = new LinkedHashMap<>();
		for (int index = 1; index < arguments.length; index += 2)
		{
			String name = arguments[index];
			String value = arguments[index + 1];
			if (name == null || value == null || !requiredOptions.contains(name) || options.containsKey(name))
				throw new IllegalArgumentException("unsupported, repeated, or valueless option: " + name);
			options.put(name, value);
		}
		if (!options.keySet().equals(requiredOptions))
			throw new IllegalArgumentException("all required options must be supplied");
		return Map.copyOf(options);
	}

	/**
	 * Requires the original workspace package version and leaves Cargo.toml unchanged.
	 *
	 * @param scope borrowed shared TOML parser
	 * @param sourceRoot original upstream checkout
	 * @param expectedVersion exact version selected by release detection
	 * @param build exact upstream build digits
	 * @throws IOException if the manifest cannot be read or does not contain the required version
	 */
	private static void verifySource(ToolingScope scope, Path sourceRoot, String expectedVersion, String build)
		throws IOException
	{
		validateBuild(build);
		Path manifestPath = sourceRoot.resolve("codex-rs/Cargo.toml");
		JsonNode manifest;
		try
		{
			manifest = scope.getTomlMapper().readTree(Files.readString(manifestPath));
		}
		catch (JacksonException failure)
		{
			throw new IOException("Invalid Cargo.toml: " + failure.getOriginalMessage(), failure);
		}
		JsonNode workspace = null;
		if (manifest != null)
			workspace = manifest.get(WORKSPACE_FIELD);
		JsonNode workspacePackage = null;
		if (workspace != null)
			workspacePackage = workspace.get(PACKAGE_FIELD);
		JsonNode actualVersion = null;
		if (workspacePackage != null)
			actualVersion = workspacePackage.get(CARGO_VERSION_FIELD);
		if (actualVersion == null || !actualVersion.isString())
			throw new IOException("Cargo.toml must define workspace.package.version");
		String version = actualVersion.asString();
		if (!version.equals(expectedVersion))
			throw new IOException("Cargo.toml workspace.package.version must equal '" + expectedVersion + "'; got '" +
				version + "'");
	}

	/**
	 * Requires a nonempty sequence of ASCII decimal digits, preserving its original spelling.
	 *
	 * @param build caller-supplied build number
	 * @throws IOException if {@code build} contains a non-ASCII-decimal character or is empty
	 */
	private static void validateBuild(String build) throws IOException
	{
		if (build.isEmpty())
			throw new IOException("build must contain only ASCII decimal digits; got ''");
		boolean hasOnlyAsciiDigits = build.chars().allMatch(digit -> digit >= '0' && digit <= '9');
		if (hasOnlyAsciiDigits)
			return;
		throw new IOException("build must contain only ASCII decimal digits; got '" + build + "'");
	}

	/**
	 * Runs both identity commands and atomically writes the validated version line as proof.
	 *
	 * @param scope borrowed shared JSON parser
	 * @param options parsed binary command arguments
	 * @param capture process capture operation supplied by the production boundary or an in-memory result fixture
	 * @throws IOException if package files, process capture, response validation, or proof replacement fails
	 */
	private static void verifyBinary(ToolingScope scope, Map<String, String> options, SystemCommands.Capture capture)
		throws IOException
	{
		String build = options.get(BUILD_OPTION);
		validateBuild(build);
		Path packageDirectory = Path.of(options.get(PACKAGE_DIRECTORY_OPTION)).toRealPath();
		Path executable = packageEntrypoint(scope, packageDirectory);
		Path workingDirectory = Path.of("").toAbsolutePath();
		Path temporaryDirectory = Path.of(System.getProperty("java.io.tmpdir"));
		SystemCommands.Result versionResult = capture.capture(List.of(executable.toString(), VERSION_OPTION),
			workingDirectory, temporaryDirectory, Map.of());
		SystemCommands.Result buildInfoResult = capture.capture(List.of(executable.toString(), "--build-info"),
			workingDirectory, temporaryDirectory, Map.of());
		String version = options.get(VERSION_OPTION);
		String banner = validateBinaryResponses(version, build, versionResult, buildInfoResult);
		writeProof(Path.of(options.get(PROOF_OUTPUT_OPTION)), banner);
	}

	/**
	 * Reads version-1 package metadata and selects its existing supported executable.
	 *
	 * @param scope borrowed shared JSON parser
	 * @param packageDirectory canonical package directory
	 * @return existing version-1 package CLI executable
	 * @throws IOException if metadata is malformed or the selected entrypoint is unsupported or missing
	 */
	private static Path packageEntrypoint(ToolingScope scope, Path packageDirectory) throws IOException
	{
		JsonNode metadata;
		try
		{
			metadata = scope.getJsonMapper().readTree(Files.readString(packageDirectory.resolve(PACKAGE_METADATA_FILE)));
		}
		catch (JacksonException failure)
		{
			throw new IOException("Invalid " + PACKAGE_METADATA_FILE + ": " + failure.getOriginalMessage(), failure);
		}
		if (metadata == null || !metadata.isObject())
			throw new IOException(PACKAGE_METADATA_FILE + " must be an object");
		JsonNode layoutVersion = metadata.get(LAYOUT_VERSION_FIELD);
		boolean isLayoutVersionOne = layoutVersion != null && layoutVersion.isIntegralNumber() &&
			layoutVersion.decimalValue().compareTo(BigDecimal.ONE) == 0;
		if (!isLayoutVersionOne)
			throw new IOException(PACKAGE_METADATA_FILE + " layoutVersion must be integer 1");

		JsonNode entrypointValue = metadata.get(ENTRYPOINT_FIELD);
		String entrypoint = "";
		if (entrypointValue != null && entrypointValue.isString())
			entrypoint = entrypointValue.asString();
		if (!PACKAGE_ENTRYPOINTS.contains(entrypoint))
			throw new IOException(PACKAGE_METADATA_FILE + " entrypoint must be bin/codex or bin/codex.exe");
		Path executable = packageDirectory.resolve(entrypoint);
		if (!Files.isRegularFile(executable))
			throw new IOException("Package CLI entrypoint does not exist: " + executable);
		return executable;
	}

	/**
	 * Checks both successful process results, the exact two-line banner, and each unique required build-info field.
	 *
	 * @param version expected upstream version
	 * @param build exact upstream build digits
	 * @param versionResult captured {@code --version} result
	 * @param buildInfoResult captured {@code --build-info} result
	 * @return the accepted first banner line with its original line terminator, for proof writing
	 * @throws IOException if either process failed or the captured identity output differs from the expected values
	 */
	private static String validateBinaryResponses(String version, String build, SystemCommands.Result versionResult,
		SystemCommands.Result buildInfoResult) throws IOException
	{
		if (versionResult.status() != 0)
			throw new IOException("Package CLI --version failed with exit code " + versionResult.status());
		if (buildInfoResult.status() != 0)
			throw new IOException("Package CLI --build-info failed with exit code " + buildInfoResult.status());

		String expectedBanner = "codex-cli " + version + "+" + build;
		String banner = versionResult.stdout();
		Pattern bannerPattern = Pattern.compile(Pattern.quote(expectedBanner) + "(\\r?\\n)" +
			Pattern.quote(SEE_BUILD_INFO_LINE) + "(?:\\r?\\n)?");
		Matcher bannerMatch = bannerPattern.matcher(banner);
		if (!bannerMatch.matches())
			throw new IOException("Package CLI --version must report exactly '" + expectedBanner + "' followed by '" +
				SEE_BUILD_INFO_LINE + "' on a second line; got '" + banner + "'");

		Map<String, String> expectedFields = new LinkedHashMap<>();
		expectedFields.put(BUILD_INFO_CLI_VERSION_FIELD, expectedBanner.substring("codex-cli ".length()));
		expectedFields.put(BUILD_INFO_BUILD_NUMBER_FIELD, build);
		expectedFields.put(BUILD_INFO_UPSTREAM_VERSION_FIELD, version);
		expectedFields.put(BUILD_INFO_PROVIDER_FIELD, BUILD_INFO_PROVIDER);
		Map<String, String> fields = readUniqueBuildInfoFields(buildInfoResult.stdout(), expectedFields);
		for (Map.Entry<String, String> field : expectedFields.entrySet())
		{
			if (!field.getValue().equals(fields.get(field.getKey())))
				throw new IOException("Package CLI --build-info field '" + field.getKey() + "' must equal '" +
					field.getValue() + "'; got '" + fields.get(field.getKey()) + "'");
		}
		return expectedBanner + bannerMatch.group(1);
	}

	/**
	 * Reads required fields from colon-separated build-info lines and rejects duplicate names.
	 *
	 * @param output captured build-info stdout
	 * @param expectedFields the exact required field names and values
	 * @return required names and their captured values
	 * @throws IOException if a required field appears more than once
	 */
	private static Map<String, String> readUniqueBuildInfoFields(String output, Map<String, String> expectedFields)
		throws IOException
	{
		Map<String, String> fields = new LinkedHashMap<>();
		for (String line : output.split("\\R"))
		{
			int separator = line.indexOf(':');
			if (separator < 0)
				continue;
			String name = line.substring(0, separator).strip();
			if (!expectedFields.containsKey(name))
				continue;
			if (fields.containsKey(name))
				throw new IOException("Package CLI --build-info repeats required field '" + name + "'");
			fields.put(name, line.substring(separator + 1).strip());
		}
		return fields;
	}

	/**
	 * Replaces the proof with the accepted version line through a temporary file in the destination directory.
	 *
	 * @param output requested proof path
	 * @param banner accepted first banner line with its line terminator
	 * @throws IOException if the destination directory cannot be written or atomic replacement fails
	 */
	private static void writeProof(Path output, String banner) throws IOException
	{
		Path target = output.toAbsolutePath();
		Path temporary = Files.createTempFile(target.getParent(), PROOF_TEMPORARY_PREFIX, ".tmp");
		try
		{
			Files.writeString(temporary, banner, StandardCharsets.UTF_8);
			Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
		}
		finally
		{
			Files.deleteIfExists(temporary);
		}
	}

	/**
	 * Prints the supported package identity operation forms.
	 *
	 * @param err borrowed diagnostic output
	 */
	private static void printUsage(PrintStream err)
	{
		err.println("Usage: package-identity source --source-root ROOT --version X.Y.Z --build DIGITS");
		err.println("       package-identity binary --package-dir DIR --version X.Y.Z --build DIGITS --output PROOF");
	}
}
