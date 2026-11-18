package com.holdmyspot.codexunleashed.tooling;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.StringJoiner;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;

/**
 * Verifies Cargo manifests beneath a repository's codex-rs directory.
 */
public final class VerifyCargoWorkspaceManifests
{
	/**
	 * Command name registered by {@link Main}.
	 */
	public static final String NAME = "verify-cargo-workspace-manifests";
	/** Workspace package fields which crates must inherit. */
	private static final List<String> PACKAGE_FIELDS = List.of("version", "edition", "license");
	/** Root manifest's sole feature-table exception. */
	private static final String FEATURE_EXCEPTION = "codex-rs/v8-poc/Cargo.toml";
	/** Exact feature mapping permitted by the sole exception. */
	private static final Map<String, List<String>> EXPECTED_FEATURES = Map.of(
		"sandbox", List.of("v8/v8_enable_sandbox"));
	/** Fixed dependency sections checked at the root and target levels. */
	private static final List<String> DEPENDENCY_SECTIONS =
		List.of("dependencies", "dev-dependencies", "build-dependencies");
	/** Policy explanation printed before all findings. */
	private static final List<String> POLICY_HEADER = List.of(
		"Cargo manifests under codex-rs must inherit workspace package metadata, " +
			"opt into workspace lints, and avoid introducing new workspace crate " +
			"features.",
		"Workspace crate features are disallowed because our Bazel build setup " +
			"does not honor them today, which can let issues hidden behind feature " +
			"gates go unnoticed, and because they add extra crate build " +
			"permutations we want to avoid.",
		"Cargo only applies `codex-rs/Cargo.toml` `[workspace.lints.clippy]` " +
			"entries to a crate when that crate declares:",
		"",
		"[lints]",
		"workspace = true",
		"",
		"Without that opt-in, `cargo clippy` can miss violations that Bazel clippy " +
			"catches.",
		"",
		"Package-name checks apply to `codex-rs/<crate>/Cargo.toml` and " +
			"`codex-rs/utils/<crate>/Cargo.toml`.",
		"Workspace crate features are forbidden; add a targeted exception here " +
			"only if there is a deliberate temporary migration in flight.",
		"");
	/** Python Unicode code-point ordering for manifest keys. */
	private static final Comparator<String> CODE_POINT_ORDER = VerifyCargoWorkspaceManifests::compareCodePoints;

	/** Prevents construction. */
	private VerifyCargoWorkspaceManifests()
	{
	}

	/**
	 * Checks all Cargo manifests beneath one explicit repository root.
	 *
	 * @param scope borrowed TOML mapper
	 *
	 * @param arguments command arguments after the command name
	 *
	 * @param out completed policy report
	 *
	 * @param err usage and input diagnostics
	 *
	 * @return zero for success, one for findings or input failures, and two for command syntax errors
	 *
	 * @throws NullPointerException if an argument is null
	 */
	public static int run(ToolingScope scope, String[] arguments, PrintStream out, PrintStream err)
	{
		Objects.requireNonNull(scope, "scope");
		Objects.requireNonNull(arguments, "arguments");
		Objects.requireNonNull(out, "out");
		Objects.requireNonNull(err, "err");
		for (int index = 0; index < arguments.length; index += 1)
			Objects.requireNonNull(arguments[index], "arguments[" + index + "]");
		if (arguments.length != 1)
		{
			usage(err);
			return 2;
		}

		try
		{
			Path repository = SourceInputPaths.resolve(Path.of(arguments[0]));
			Path cargoRoot = repository.resolve("codex-rs");
			Path rootManifest = cargoRoot.resolve("Cargo.toml");
			Path canonicalCargoRoot = SourceInputPaths.resolve(cargoRoot);
			List<Path> manifests = enumerateManifests(cargoRoot, rootManifest);
			Map<Path, JsonNode> documents = new LinkedHashMap<>();
			for (Path manifest : manifests)
				documents.put(manifest, readManifest(scope, manifest));

			Set<String> packageNames = collectPackageNames(manifests, documents, rootManifest);
			Map<String, List<String>> failures = new LinkedHashMap<>();
			Set<String> usedFeatureExceptions = new HashSet<>();
			for (Path manifest : manifests)
			{
				List<String> errors = manifestErrors(manifest, cargoRoot, canonicalCargoRoot,
					documents.get(manifest), packageNames, usedFeatureExceptions);
				if (!errors.isEmpty())
					failures.put(manifestKey(repository, manifest), errors);
			}
			if (!usedFeatureExceptions.contains(FEATURE_EXCEPTION))
				failures.computeIfAbsent(FEATURE_EXCEPTION, _ -> new ArrayList<>()).add(
					"remove the stale `[features]` exception from `MANIFEST_FEATURE_EXCEPTIONS`");
			if (failures.isEmpty())
				return 0;

			for (String line : POLICY_HEADER)
				out.print(line + "\n");
			List<String> keys = new ArrayList<>(failures.keySet());
			keys.sort(CODE_POINT_ORDER);
			for (String key : keys)
			{
				out.print(key + ":\n");
				for (String error : failures.get(key))
					out.print("  - " + error + "\n");
			}
			return 1;
		}
		catch (IOException | JacksonException | IllegalArgumentException failure)
		{
			err.println("ERROR: " + failure.getMessage());
			return 1;
		}
	}

	/**
	 * Enumerates the required workspace root first and sorted descendant manifests.
	 *
	 * @param cargoRoot input used by this helper
	 *
	 * @param rootManifest input used by this helper
	 *
	 * @return result of the helper operation
	 *
	 * @throws IOException if the operation fails
	 */
	private static List<Path> enumerateManifests(Path cargoRoot, Path rootManifest) throws IOException
	{
		if (!Files.isRegularFile(rootManifest))
			throw new IOException(rootManifest + ": required workspace manifest is missing or is not a file");
		List<Path> descendants = new ArrayList<>();
		Files.walkFileTree(cargoRoot, new SimpleFileVisitor<>()
		{
			@Override
			public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes)
			{
				if (!directory.equals(cargoRoot) && directory.getFileName().toString().equals("Cargo.toml"))
					descendants.add(directory);
				return FileVisitResult.CONTINUE;
			}

			@Override
			public FileVisitResult visitFile(Path file, BasicFileAttributes attributes)
			{
				if (!file.equals(rootManifest) && file.getFileName().toString().equals("Cargo.toml"))
					descendants.add(file);
				return FileVisitResult.CONTINUE;
			}

			@Override
			public FileVisitResult visitFileFailed(Path file, IOException failure) throws IOException
			{
				throw new IOException(file + ": " + failure.getMessage(), failure);
			}
		});
		descendants.sort((left, right) -> CODE_POINT_ORDER.compare(left.toString(), right.toString()));
		List<Path> result = new ArrayList<>();
		result.add(rootManifest);
		result.addAll(descendants);
		return result;
	}

	/**
	 * Reads and parses a complete TOML document before policy evaluation begins.
	 *
	 * @param scope input used by this helper
	 *
	 * @param path input used by this helper
	 *
	 * @return result of the helper operation
	 *
	 * @throws IOException if the operation fails
	 */
	private static JsonNode readManifest(ToolingScope scope, Path path) throws IOException
	{
		String document;
		try
		{
			document = Files.readString(path, StandardCharsets.UTF_8);
		}
		catch (IOException failure)
		{
			throw new IOException(path + ": " + failure.getMessage(), failure);
		}
		try
		{
			JsonNode parsed = scope.getTomlMapper().readTree(document);
			if (parsed == null || !parsed.isObject())
				throw new IOException(path + ": TOML document root must be a table");
			return parsed;
		}
		catch (JacksonException failure)
		{
			throw new IOException(path + ": invalid TOML: " + failure.getOriginalMessage(), failure);
		}
	}

	/**
	 * Collects textual package names from every descendant manifest before checking policy.
	 *
	 * @param manifests input used by this helper
	 *
	 * @param documents input used by this helper
	 *
	 * @param rootManifest input used by this helper
	 *
	 * @return result of the helper operation
	 */
	private static Set<String> collectPackageNames(List<Path> manifests, Map<Path, JsonNode> documents,
		Path rootManifest)
	{
		Set<String> result = new HashSet<>();
		for (Path path : manifests)
		{
			if (path.equals(rootManifest))
				continue;
			JsonNode packageNode = documents.get(path).get("package");
			JsonNode name = null;
			if (packageNode != null && packageNode.isObject())
				name = packageNode.get("name");
			if (name != null && name.isString())
				result.add(name.asString());
		}
		return result;
	}

	/**
	 * Applies q104 policy to one already-parsed manifest.
	 *
	 * @param path input used by this helper
	 *
	 * @param cargoRoot input used by this helper
	 *
	 * @param canonicalCargoRoot input used by this helper
	 *
	 * @param manifest input used by this helper
	 *
	 * @param packageNames input used by this helper
	 *
	 * @param usedFeatureExceptions input used by this helper
	 *
	 * @return result of the helper operation
	 *
	 * @throws IOException if the operation fails
	 */
	private static List<String> manifestErrors(Path path, Path cargoRoot, Path canonicalCargoRoot,
		JsonNode manifest, Set<String> packageNames, Set<String> usedFeatureExceptions) throws IOException
	{
		JsonNode packageNode = manifest.get("package");
		boolean root = path.equals(cargoRoot.resolve("Cargo.toml"));
		if ((packageNode == null || !packageNode.isObject()) && !root)
			return List.of();
		List<String> errors = new ArrayList<>();
		if (packageNode != null && packageNode.isObject())
		{
			for (String field : PACKAGE_FIELDS)
				if (!isWorkspaceReference(packageNode.get(field)))
					errors.add("set `" + field + ".workspace = true` in `[package]`");

			JsonNode lints = manifest.get("lints");
			if (lints == null || !lints.isObject() || !isLiteralTrue(lints.get("workspace")))
				errors.add("add `[lints]` with `workspace = true`");

			String expectedName = expectedPackageName(path, cargoRoot);
			if (expectedName != null)
			{
				JsonNode actualName = packageNode.get("name");
				if (actualName == null || !actualName.isString() || !actualName.asString().equals(expectedName))
					errors.add("set `[package].name` to `" + expectedName + "` (found `" +
						pythonValueString(actualName) + "`)");
			}
		}

		JsonNode features = manifest.get("features");
		if (features != null && !features.isNull())
		{
			String key = manifestKey(cargoRoot.getParent(), path);
			Optional<Map<String, List<String>>> normalized = normalizeFeatures(features);
			if (!key.equals(FEATURE_EXCEPTION))
				errors.add("remove `[features]`; new workspace crate features are not allowed");
			else
			{
				usedFeatureExceptions.add(key);
				if (normalized.isEmpty() || !EXPECTED_FEATURES.equals(normalized.get()))
					errors.add("limit `[features]` to the existing exception list while workspace crate features are being " +
						"removed (expected sandbox = [\"v8/v8_enable_sandbox\"])");
			}
		}

		for (DependencyTable section : dependencySections(manifest))
			for (Map.Entry<String, JsonNode> entry : section.dependencies().properties())
			{
				JsonNode dependency = entry.getValue();
				if (!dependency.isObject())
					continue;
				String label = "[" + section.name() + "]." + entry.getKey();
				if (isLiteralTrue(dependency.get("optional")))
					errors.add("remove `optional = true` from `" + label + "`; new optional dependencies are not allowed " +
						"because they create crate features");
				if (!isInternalDependency(path, entry.getKey(), dependency, packageNames, canonicalCargoRoot))
					continue;
				JsonNode dependencyFeatures = dependency.get("features");
				if (dependencyFeatures != null && !dependencyFeatures.isNull())
					errors.add("remove `features = [...]` from workspace dependency `" + label +
						"`; new workspace crate feature activations are not allowed");
				JsonNode defaultFeatures = dependency.get("default-features");
				if (defaultFeatures != null && defaultFeatures.isBoolean() && !defaultFeatures.booleanValue())
					errors.add("remove `default-features = false` from workspace dependency `" + label +
						"`; new workspace crate feature toggles are not allowed");
			}
		return errors;
	}

	/**
	 * Returns whether a value is a table containing literal boolean {@code workspace = true}.
	 *
	 * @param value input used by this helper
	 *
	 * @return result of the helper operation
	 */
	private static boolean isWorkspaceReference(JsonNode value)
	{
		return value != null && value.isObject() && isLiteralTrue(value.get("workspace"));
	}

	/**
	 * Returns whether a value is the literal TOML boolean true.
	 *
	 * @param value input used by this helper
	 *
	 * @return result of the helper operation
	 */
	private static boolean isLiteralTrue(JsonNode value)
	{
		return value != null && value.isBoolean() && value.booleanValue();
	}

	/**
	 * Computes the policy package name for recognized directory depths.
	 *
	 * @param manifest input used by this helper
	 *
	 * @param cargoRoot input used by this helper
	 *
	 * @return result of the helper operation
	 */
	private static String expectedPackageName(Path manifest, Path cargoRoot)
	{
		Path relative = cargoRoot.relativize(manifest);
		int depth = relative.getNameCount();
		if (depth == 2 && relative.getName(1).toString().equals("Cargo.toml"))
		{
			String directory = relative.getName(0).toString();
			if (directory.equals("windows-sandbox-rs"))
				return "codex-windows-sandbox";
			if (directory.startsWith("codex-"))
				return directory;
			return "codex-" + directory;
		}
		if (depth == 3 && relative.getName(0).toString().equals("utils") &&
			relative.getName(2).toString().equals("Cargo.toml"))
		{
			String directory = relative.getName(1).toString();
			if (directory.equals("path-utils"))
				return "codex-utils-path";
			return "codex-utils-" + directory;
		}
		return null;
	}

	/**
	 * Normalizes a feature table to exact string-keyed, ordered string arrays.
	 *
	 * @param value mapper value for the feature table
	 *
	 * @return normalized table, or empty when the input is not a table of string arrays
	 */
	private static Optional<Map<String, List<String>>> normalizeFeatures(JsonNode value)
	{
		if (!value.isObject())
			return Optional.empty();
		Map<String, List<String>> result = new LinkedHashMap<>();
		for (Map.Entry<String, JsonNode> entry : value.properties())
		{
			Optional<List<String>> items = normalizeStringList(entry.getValue());
			if (items.isEmpty())
				return Optional.empty();
			result.put(entry.getKey(), items.get());
		}
		return Optional.of(result);
	}

	/**
	 * Normalizes an array only when every member is a string.
	 *
	 * @param value mapper value for one feature array
	 *
	 * @return string array, or empty when the input is not a string array
	 */
	private static Optional<List<String>> normalizeStringList(JsonNode value)
	{
		if (!value.isArray())
			return Optional.empty();
		List<String> result = new ArrayList<>();
		for (JsonNode item : value)
		{
			if (!item.isString())
				return Optional.empty();
			result.add(item.asString());
		}
		return Optional.of(result);
	}

	/**
	 * Lists supported dependency sections in policy order.
	 *
	 * @param manifest input used by this helper
	 *
	 * @return supported dependency sections in policy order
	 */
	private static List<DependencyTable> dependencySections(JsonNode manifest)
	{
		List<DependencyTable> result = new ArrayList<>();
		addDependencySections(manifest, "", result);
		JsonNode workspace = manifest.get("workspace");
		if (workspace != null && workspace.isObject())
		{
			JsonNode dependencies = workspace.get("dependencies");
			if (dependencies != null && dependencies.isObject())
				result.add(new DependencyTable("workspace.dependencies", dependencies));
		}
		JsonNode targets = manifest.get("target");
		if (targets != null && targets.isObject())
			for (Map.Entry<String, JsonNode> target : targets.properties())
				if (target.getValue().isObject())
					addDependencySections(target.getValue(), "target." + target.getKey() + ".", result);
		return result;
	}

	/**
	 * Adds the three dependency sections of one table in fixed order.
	 *
	 * @param table input used by this helper
	 *
	 * @param prefix input used by this helper
	 *
	 * @param result input used by this helper
	 *
	 */
	private static void addDependencySections(JsonNode table, String prefix, List<DependencyTable> result)
	{
		for (String name : DEPENDENCY_SECTIONS)
		{
			JsonNode dependencies = table.get(name);
			if (dependencies != null && dependencies.isObject())
				result.add(new DependencyTable(prefix + name, dependencies));
		}
	}

	/**
	 * Determines dependency identity using package alias first and canonical path containment second.
	 *
	 * @param manifest input used by this helper
	 *
	 * @param dependencyName input used by this helper
	 *
	 * @param dependency input used by this helper
	 *
	 * @param packageNames input used by this helper
	 *
	 * @param canonicalCargoRoot input used by this helper
	 *
	 * @return result of the helper operation
	 *
	 * @throws IOException if the operation fails
	 */
	private static boolean isInternalDependency(Path manifest, String dependencyName, JsonNode dependency,
		Set<String> packageNames, Path canonicalCargoRoot) throws IOException
	{
		JsonNode packageName = dependency.get("package");
		if (packageName == null && packageNames.contains(dependencyName))
			return true;
		if (packageName != null && packageName.isString() && packageNames.contains(packageName.asString()))
			return true;
		JsonNode path = dependency.get("path");
		if (path == null || !path.isString())
			return false;
		Path resolved = SourceInputPaths.resolve(manifest.getParent().resolve(path.asString()));
		return resolved.startsWith(canonicalCargoRoot);
	}

	/**
	 * Creates a repository-relative policy key using forward slashes.
	 *
	 * @param repository input used by this helper
	 *
	 * @param path input used by this helper
	 *
	 * @return result of the helper operation
	 */
	private static String manifestKey(Path repository, Path path)
	{
		return repository.relativize(path).toString().replace(path.getFileSystem().getSeparator(), "/");
	}

	/**
	 * Renders package names as Python f-string values, retaining non-string TOML distinctions.
	 *
	 * @param value input used by this helper
	 *
	 * @return result of the helper operation
	 */
	private static String pythonValueString(JsonNode value)
	{
		if (value == null)
			return "None";
		if (value.isString())
			return value.asString();
		return pythonRepr(value);
	}

	/**
	 * Renders a TOML value like the frozen Python helper's diagnostic representation.
	 *
	 * @param value input used by this helper
	 *
	 * @return result of the helper operation
	 */
	private static String pythonRepr(JsonNode value)
	{
		if (value.isString())
			return pythonString(value.asString());
		if (value.isBoolean())
		{
			if (value.booleanValue())
				return "True";
			return "False";
		}
		if (value.isNull())
			return "None";
		if (value.isNumber())
			return value.asString();
		if (value.isArray())
		{
			StringJoiner items = new StringJoiner(", ", "[", "]");
			for (JsonNode item : value)
				items.add(pythonRepr(item));
			return items.toString();
		}
		if (value.isObject())
		{
			StringJoiner entries = new StringJoiner(", ", "{", "}");
			for (Map.Entry<String, JsonNode> entry : value.properties())
				entries.add(pythonString(entry.getKey()) + ": " + pythonRepr(entry.getValue()));
			return entries.toString();
		}
		String text = value.toString();
		if (text.length() >= 2 && text.startsWith("\"") && text.endsWith("\""))
			text = text.substring(1, text.length() - 1);
		if (text.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}"))
		{
			LocalDate date = LocalDate.parse(text);
			return "datetime.date(" + date.getYear() + ", " + date.getMonthValue() + ", " + date.getDayOfMonth() + ")";
		}
		return text;
	}

	/**
	 * Quotes a Python string with its preferred delimiter.
	 *
	 * @param value input used by this helper
	 *
	 * @return Python-compatible quoted string
	 */
	private static String pythonString(String value)
	{
		char quote = '\'';
		if (value.indexOf('\'') >= 0 && value.indexOf('"') < 0)
			quote = '"';
		StringBuilder result = new StringBuilder().append(quote);
		for (int index = 0; index < value.length();)
		{
			int codePoint = value.codePointAt(index);
			index += Character.charCount(codePoint);
			if (codePoint == '\\' || codePoint == quote)
				result.append('\\').appendCodePoint(codePoint);
			else if (codePoint == '\n')
				result.append("\\n");
			else if (codePoint == '\r')
				result.append("\\r");
			else if (codePoint == '\t')
				result.append("\\t");
			else if (Character.isISOControl(codePoint))
				result.append(String.format("\\x%02x", codePoint));
			else
				result.appendCodePoint(codePoint);
		}
		return result.append(quote).toString();
	}

	/**
	 * Compares strings by Unicode code point, matching Python's string ordering.
	 *
	 * @param left input used by this helper
	 *
	 * @param right input used by this helper
	 *
	 * @return result of the helper operation
	 */
	private static int compareCodePoints(String left, String right)
	{
		int leftIndex = 0;
		int rightIndex = 0;
		while (leftIndex < left.length() && rightIndex < right.length())
		{
			int comparison = Integer.compare(left.codePointAt(leftIndex), right.codePointAt(rightIndex));
			if (comparison != 0)
				return comparison;
			leftIndex += Character.charCount(left.codePointAt(leftIndex));
			rightIndex += Character.charCount(right.codePointAt(rightIndex));
		}
		return Integer.compare(left.length() - leftIndex, right.length() - rightIndex);
	}

	/**
	 * Writes the minimal command usage.
	 *
	 * @param err input used by this helper
	 */
	private static void usage(PrintStream err)
	{
		err.println("Usage: codex-tooling " + NAME + " <repository-root>");
	}

	/**
	 * One ordered dependency table and its section label.
	 *
	 * @param name input used by this helper
	 *
	 * @param dependencies input used by this helper
	 */
	private record DependencyTable(String name, JsonNode dependencies)
	{
	}
}
