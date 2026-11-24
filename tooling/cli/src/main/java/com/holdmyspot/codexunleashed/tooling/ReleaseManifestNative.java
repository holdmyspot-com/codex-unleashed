package com.holdmyspot.codexunleashed.tooling;

import tools.jackson.databind.JsonNode;

import java.math.BigInteger;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static java.nio.charset.StandardCharsets.UTF_8;

/**
 * Validates native build observations recorded by {@link ReleaseManifest} without executing observed commands.
 * This class is stateless and thread-safe.
 */
public final class ReleaseManifestNative
{
	/**
	 * Maximum encoded size of one diagnostic string, in bytes.
	 */
	private static final int MAX_STRING_BYTES = 64 * 1024;

	/**
	 * Maximum commands, arguments per command, and environment entries in the native observation contract.
	 */
	private static final int MAX_ENTRIES = 256;

	/**
	 * Exact process variable names permitted by the release builder's diagnostic contract.
	 */
	private static final Set<String> ENVIRONMENT_NAMES = Set.of(
		"PATH", "CARGO_HOME", "CARGO_TARGET_DIR", "RUSTUP_TOOLCHAIN", "RUSTC", "RUSTC_WRAPPER",
		"RUSTC_WORKSPACE_WRAPPER", "RUSTFLAGS", "CARGO_ENCODED_RUSTFLAGS", "CARGO_BUILD_JOBS",
		"CARGO_INCREMENTAL", "STABLE_GIT_COMMIT", "CODEX_UNLEASHED_BUILD_NUMBER", "CODEX_BWRAP_SHA256",
		"MACOSX_DEPLOYMENT_TARGET", "DEVELOPER_DIR", "SDKROOT", "LIBSQLITE3_FLAGS", "SOURCE_DATE_EPOCH", "INCLUDE",
		"LIB", "LIBPATH", "VCINSTALLDIR", "VCToolsInstallDir", "WindowsSdkDir", "WindowsSDKVersion", "CC", "CXX",
		"AR", "CFLAGS", "CXXFLAGS", "LDFLAGS", "AWS_LC_SYS_NO_JITTER_ENTROPY");

	/**
	 * Process variable families that require a nonempty suffix.
	 */
	private static final List<String> ENVIRONMENT_PREFIXES = List.of(
		"CARGO_PROFILE_RELEASE_", "CARGO_TARGET_", "RUSTY_V8_", "CC_", "CXX_", "AR_", "CFLAGS_", "CXXFLAGS_",
		"LDFLAGS_");

	/**
	 * Complete diagnostic tool inventory required in every native build observation.
	 */
	private static final Set<String> TOOL_NAMES = Set.of(
		"rustc", "cargo", "git", "zig", "cc", "cxx", "linker", "strip", "objcopy", "dsymutil", "python", "zstd");

	/**
	 * Validates the complete native observation document for one receipt target.
	 * <p>
	 * The top-level observations describe the packaging host. A target whose executables are compiled on a different
	 * host also has {@code compile_host}, which records that host's runner, its {@code build_binaries} operation, its
	 * tools and its configured V8 archive.
	 *
	 * @param value  parsed native_build document
	 * @param target receipt's native target
	 * @throws IllegalArgumentException if the document violates the native observation contract
	 */
	public static void validate(JsonNode value, String target)
	{
		if (value != null && value.isObject() && value.has("compile_host"))
		{
			fields(value, "native_build", "format_version", "target", "runner", "operations", "tools", "resources",
				"compile_host");
			JsonNode compileHost = value.get("compile_host");
			fields(compileHost, "native_build compile_host", "runner", "operations", "tools", "resources");
			validateCompileHost(compileHost, "native_build compile_host");
		}
		else
			fields(value, "native_build", "format_version", "target", "runner", "operations", "tools", "resources");
		validateHeader(value, target, "native_build");
		validateRunner(value.get("runner"));
		validateOperations(value.get("operations"), List.of("build_binaries", "archive_packages"),
			"native_build requires build_binaries then archive_packages");
		validateTools(value.get("tools"));
		validateResources(value.get("resources"), Set.of("packaged_resource", "configured_v8_archive"));
	}

	/**
	 * Validates the standalone observations of a host that only compiled a target's executables.
	 * <p>
	 * {@code release-native-inputs} writes this document on the compile host. The packaging host embeds its
	 * {@code runner}, {@code operations}, {@code tools} and {@code resources} as {@code native_build.compile_host}.
	 *
	 * @param value  parsed compile-host document
	 * @param target expected native target
	 * @throws IllegalArgumentException if the document violates the compile-host observation contract
	 */
	public static void validateCompile(JsonNode value, String target)
	{
		fields(value, "compile-host native inputs", "format_version", "target", "runner", "operations", "tools",
			"resources");
		validateHeader(value, target, "compile-host native inputs");
		validateCompileHost(value, "compile-host native inputs");
	}

	/**
	 * Returns the Python version supplied by an observed tool entry.
	 *
	 * @param value previously validated native_build document
	 * @return observed Python version, or an empty string when no textual version was observed
	 */
	public static String pythonVersion(JsonNode value)
	{
		for (JsonNode tool : value.get("tools"))
		{
			boolean observedPython = textEquals(tool.get("name"), "python") &&
				textEquals(tool.get("status"), "observed");
			if (observedPython && tool.get("version").isString())
				return tool.get("version").stringValue();
		}
		return "";
	}

	/**
	 * Prevents instances of this stateless validator.
	 */
	private ReleaseManifestNative()
	{
	}

	/**
	 * Validates the format version and target of a native observation document.
	 *
	 * @param value   parsed document
	 * @param target  expected native target
	 * @param subject diagnostic document name
	 * @throws IllegalArgumentException if either field is invalid
	 */
	private static void validateHeader(JsonNode value, String target, String subject)
	{
		JsonNode format = value.get("format_version");
		require(format.isIntegralNumber() && format.bigIntegerValue().equals(BigInteger.ONE),
			"Unsupported " + subject + " format_version");
		require(textEquals(value.get("target"), target), subject + " target differs from receipt target");
		boundedString(value.get("target"), subject + " target");
	}

	/**
	 * Validates the observations of a host that only compiled the executables.
	 *
	 * @param value   parsed object whose runner, operations, tools and resources describe the compile host
	 * @param subject diagnostic object name
	 * @throws IllegalArgumentException if an observation is invalid
	 */
	private static void validateCompileHost(JsonNode value, String subject)
	{
		validateRunner(value.get("runner"));
		validateOperations(value.get("operations"), List.of("build_binaries"), subject + " requires only build_binaries");
		validateTools(value.get("tools"));
		validateResources(value.get("resources"), Set.of("configured_v8_archive"));
	}

	/**
	 * Validates nullable runner observations.
	 *
	 * @param runner parsed runner object
	 * @throws IllegalArgumentException if its fields or observations are invalid
	 */
	private static void validateRunner(JsonNode runner)
	{
		fields(runner, "native_build runner", "os", "arch", "image_os", "image_version");
		for (String name : runner.propertyNames())
			nullableString(runner.get(name), "native_build runner " + name);
	}

	/**
	 * Validates ordered operations and their cumulative entry limits.
	 *
	 * @param operations parsed operations array
	 * @param expected   required operation names in order
	 * @param diagnostic message for an operation list of the wrong length
	 * @throws IllegalArgumentException if operation order, arguments, or environment entries are invalid
	 */
	private static void validateOperations(JsonNode operations, List<String> expected, String diagnostic)
	{
		require(operations.isArray() && operations.size() == expected.size(), diagnostic);
		int commandCount = 0;
		int environmentCount = 0;
		for (int index = 0; index < expected.size(); ++index)
		{
			JsonNode operation = operations.get(index);
			fields(operation, "native_build operation", "operation", "commands", "environment");
			require(textEquals(operation.get("operation"), expected.get(index)),
				"native_build operation order is invalid");
			JsonNode commands = operation.get("commands");
			require(commands.isArray() && commands.size() > 0 && commands.size() <= MAX_ENTRIES,
				"native_build operation requires 1 to 256 commands");
			commandCount += commands.size();
			require(commandCount <= MAX_ENTRIES, "native_build requires at most 256 commands");
			validateCommands(commands);
			JsonNode environment = operation.get("environment");
			require(environment.isObject() && environment.size() <= MAX_ENTRIES,
				"native_build environment requires at most 256 entries");
			environmentCount += environment.size();
			require(environmentCount <= MAX_ENTRIES, "native_build requires at most 256 environment entries");
			validateEnvironment(environment);
		}
	}

	/**
	 * Validates argument vectors without interpreting or executing their contents.
	 *
	 * @param commands parsed command arrays
	 * @throws IllegalArgumentException if a vector is empty, oversized, nontextual, or contains NUL
	 */
	private static void validateCommands(JsonNode commands)
	{
		for (JsonNode command : commands)
		{
			require(command.isArray() && command.size() > 0 && command.size() <= MAX_ENTRIES,
				"native_build command requires 1 to 256 arguments");
			for (JsonNode argument : command)
			{
				String text = boundedString(argument, "native_build argument");
				require(text.indexOf('\0') < 0, "native_build argument contains NUL");
			}
		}
	}

	/**
	 * Validates only the process variables approved for diagnostic publication.
	 *
	 * @param environment parsed environment object
	 * @throws IllegalArgumentException if a variable name or value violates the diagnostic contract
	 */
	private static void validateEnvironment(JsonNode environment)
	{
		for (String name : environment.propertyNames())
		{
			require(approvedEnvironmentName(name), "Unapproved native_build environment key: '" + name + "'");
			String subject = "native_build environment value for " + name;
			String observed = boundedString(environment.get(name), subject);
			require(observed.indexOf('\0') < 0, subject + " contains NUL");
		}
	}

	/**
	 * Tests exact variable names and prefix families with required suffixes.
	 *
	 * @param name process variable name
	 * @return whether this variable may be published
	 */
	private static boolean approvedEnvironmentName(String name)
	{
		if (ENVIRONMENT_NAMES.contains(name))
			return true;
		for (String prefix : ENVIRONMENT_PREFIXES)
		{
			if (name.startsWith(prefix) && name.length() > prefix.length())
				return true;
		}
		return false;
	}

	/**
	 * Validates exactly one observation for every required tool.
	 *
	 * @param tools parsed tool array
	 * @throws IllegalArgumentException if a tool is absent, repeated, or malformed
	 */
	private static void validateTools(JsonNode tools)
	{
		require(tools.isArray() && tools.size() == TOOL_NAMES.size(),
			"native_build requires one entry for each diagnostic tool");
		Set<String> seen = new HashSet<>();
		for (JsonNode tool : tools)
		{
			fields(tool, "native_build tool", "name", "status", "path", "version");
			JsonNode nameNode = tool.get("name");
			require(nameNode.isString() && TOOL_NAMES.contains(nameNode.stringValue()), "Unsupported native_build tool");
			String name = nameNode.stringValue();
			require(seen.add(name), "Duplicate native_build tool: " + name);
			JsonNode status = tool.get("status");
			boolean validStatus = textEquals(status, "observed") || textEquals(status, "unavailable") ||
				textEquals(status, "not_applicable");
			require(validStatus, "Invalid native_build tool status for " + name);
			nullableString(tool.get("path"), "native_build tool path for " + name);
			nullableString(tool.get("version"), "native_build tool version for " + name);
		}
		require(seen.equals(TOOL_NAMES), "native_build is missing a diagnostic tool");
	}

	/**
	 * Validates resource identity, canonical archive paths, and deterministic order.
	 *
	 * @param resources parsed resource array
	 * @param roles     resource roles that the document may contain
	 * @throws IllegalArgumentException if a resource or its position is invalid
	 */
	private static void validateResources(JsonNode resources, Set<String> roles)
	{
		require(resources.isArray(), "native_build resources must be an array");
		String previousRole = "";
		String previousPath = "";
		boolean first = true;
		boolean ordered = true;
		boolean unique = true;
		Set<List<String>> identities = new HashSet<>();
		for (JsonNode resource : resources)
		{
			fields(resource, "native_build resource", "role", "path", "size", "sha256");
			JsonNode roleNode = resource.get("role");
			require(roleNode.isString() && roles.contains(roleNode.stringValue()), "Unsupported native_build resource role");
			String role = roleNode.stringValue();
			String path = boundedString(resource.get("path"), "native_build resource path");
			validateResourcePath(path);
			if (role.equals("configured_v8_archive"))
				require(path.indexOf('/') < 0, "configured_v8_archive path must be a basename");
			JsonNode size = resource.get("size");
			require(size.isIntegralNumber() && size.bigIntegerValue().signum() >= 0,
				"native_build resource size must be a nonnegative integer");
			JsonNode digest = resource.get("sha256");
			require(digest.isString() && digest.stringValue().matches("[0-9a-f]{64}"),
				"Invalid native_build resource SHA256");
			if (!identities.add(List.of(role, path)))
				unique = false;
			if (!first)
			{
				int order = compareUnicode(previousRole, role);
				if (order == 0)
					order = compareUnicode(previousPath, path);
				if (order > 0)
					ordered = false;
			}
			previousRole = role;
			previousPath = path;
			first = false;
		}
		require(unique, "Duplicate native_build resource identity");
		require(ordered, "native_build resources must be sorted by role and path");
	}

	/**
	 * Validates a canonical relative POSIX archive path independently of the host filesystem.
	 *
	 * @param path persisted archive path
	 * @throws IllegalArgumentException if the path has empty, dot, parent, absolute, or backslash components
	 */
	private static void validateResourcePath(String path)
	{
		require(!path.isEmpty() && path.indexOf('\\') < 0, "Unsafe native_build resource path: '" + path + "'");
		for (String component : path.split("/", -1))
		{
			boolean canonical = !component.isEmpty() && !component.equals(".") && !component.equals("..");
			require(canonical, "Unsafe native_build resource path: '" + path + "'");
		}
	}

	/**
	 * Compares Unicode scalar values to preserve Python's resource ordering for supplementary characters.
	 *
	 * @param left  preceding identity component
	 * @param right current identity component
	 * @return negative, zero, or positive according to Unicode scalar order
	 */
	private static int compareUnicode(String left, String right)
	{
		int leftIndex = 0;
		int rightIndex = 0;
		while (leftIndex < left.length() && rightIndex < right.length())
		{
			int leftPoint = left.codePointAt(leftIndex);
			int rightPoint = right.codePointAt(rightIndex);
			int difference = Integer.compare(leftPoint, rightPoint);
			if (difference != 0)
				return difference;
			leftIndex += Character.charCount(leftPoint);
			rightIndex += Character.charCount(rightPoint);
		}
		return Integer.compare(left.length() - leftIndex, right.length() - rightIndex);
	}

	/**
	 * Requires exactly the named object fields, rejecting missing and extension fields.
	 *
	 * @param value    parsed object
	 * @param subject  diagnostic object name
	 * @param expected exact required field names
	 * @throws IllegalArgumentException if the object shape differs
	 */
	private static void fields(JsonNode value, String subject, String... expected)
	{
		String diagnostic = "Invalid " + subject + " fields; expected " + String.join(", ", expected);
		require(value != null && value.isObject(), diagnostic);
		require(new HashSet<>(value.propertyNames()).equals(Set.of(expected)), diagnostic);
	}

	/**
	 * Validates a nullable observation without confusing null with an empty observation.
	 *
	 * @param value   parsed observation
	 * @param subject diagnostic observation name
	 * @throws IllegalArgumentException if a nonnull observation is not bounded UTF-8 text
	 */
	private static void nullableString(JsonNode value, String subject)
	{
		if (!value.isNull())
			boundedString(value, subject);
	}

	/**
	 * Validates one string's exact UTF-8 representation and encoded size.
	 *
	 * @param value   parsed observation
	 * @param subject diagnostic observation name
	 * @return validated textual observation
	 * @throws IllegalArgumentException if the observation is not UTF-8 text of at most 64 KiB
	 */
	private static String boundedString(JsonNode value, String subject)
	{
		require(value.isString(), subject + " must be text");
		String text = value.stringValue();
		require(UTF_8.newEncoder().canEncode(text), subject + " must be valid UTF-8 text");
		require(text.getBytes(UTF_8).length <= MAX_STRING_BYTES, subject + " exceeds 64 KiB");
		return text;
	}

	/**
	 * Compares a textual JSON node without coercing other scalar types.
	 *
	 * @param value    parsed node
	 * @param expected exact expected text
	 * @return whether the node contains the expected string
	 */
	private static boolean textEquals(JsonNode value, String expected)
	{
		return value.isString() && value.stringValue().equals(expected);
	}

	/**
	 * Reports a violated native observation contract with a caller-facing input diagnostic.
	 *
	 * @param condition whether the contract holds
	 * @param message   diagnostic for the violated contract
	 * @throws IllegalArgumentException if the condition is false
	 */
	private static void require(boolean condition, String message)
	{
		if (!condition)
			throw new IllegalArgumentException(message);
	}
}
