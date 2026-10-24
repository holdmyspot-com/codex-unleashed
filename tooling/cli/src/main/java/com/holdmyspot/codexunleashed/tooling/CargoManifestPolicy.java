package com.holdmyspot.codexunleashed.tooling;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Stream;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;

/** Checks workspace metadata, lint inheritance, crate naming, and forbidden dependency feature toggles. */
public final class CargoManifestPolicy
{
	private static final List<String> PACKAGE_FIELDS = List.of("version", "edition", "license");
	private static final String FEATURE_PATH = "codex-rs/v8-poc/Cargo.toml";
	private static final String CODE_MODE_PATH = "codex-rs/code-mode/Cargo.toml";
	private static final Map<String, List<String>> FEATURE_EXCEPTION = Map.of("sandbox", List.of("v8/v8_enable_sandbox"));
	private static final Comparator<String> TEXT_ORDER = (first, second) ->
		Arrays.compare(first.codePoints().toArray(), second.codePoints().toArray());

	/** Prevents construction. */
	private CargoManifestPolicy()
	{
	}

	/**
	 * Reads all workspace manifests before reporting policy violations, without changing the supplied repository.
	 *
	 * @param repository the repository containing codex-rs
	 * @param out the policy report destination
	 * @param environment the explicit caller environment
	 * @return zero for accepted manifests or one for policy violations
	 * @throws IOException if a manifest cannot be read or parsed
	 * @throws NullPointerException if an argument or environment entry is null
	 */
	public static int check(Path repository, PrintStream out, Map<String, String> environment) throws IOException
	{
		return check(repository, out, environment, Set.of(FEATURE_PATH));
	}

	/**
	 * Checks the pinned upstream policy, retaining its additional code-mode feature exception.
	 *
	 * @param repository the patched upstream repository
	 * @param out the policy report destination
	 * @param environment the explicit caller environment
	 * @return zero for accepted manifests or one for policy violations
	 * @throws IOException if a manifest cannot be read or parsed
	 * @throws NullPointerException if an argument or environment entry is null
	 */
	public static int checkUpstream(Path repository, PrintStream out, Map<String, String> environment) throws IOException
	{
		return check(repository, out, environment, Set.of(FEATURE_PATH, CODE_MODE_PATH));
	}

	/**
	 * Applies the selected feature-exception policy to all manifests.
	 *
	 * @param repository the repository containing codex-rs
	 * @param out the policy report destination
	 * @param environment the explicit caller environment
	 * @param featurePaths the recognized feature-exception paths
	 * @return zero for accepted manifests or one for policy violations
	 * @throws IOException if a manifest cannot be read or parsed
	 */
	private static int check(Path repository, PrintStream out, Map<String, String> environment, Set<String> featurePaths)
		throws IOException
	{
		Objects.requireNonNull(repository, "repository");
		Objects.requireNonNull(out, "out");
		Map<String, String> variables = Map.copyOf(environment);
		Path root = repository.toRealPath();
		Path cargo = root.resolve("codex-rs");
		Path workspace = cargo.resolve("Cargo.toml");
		JsonNode workspaceManifest = read(workspace);
		Set<Path> excludedRoots = excludedVendorRoots(cargo, workspaceManifest);
		Map<Path, JsonNode> manifests = loadManifests(cargo, workspace, workspaceManifest, excludedRoots);
		Set<String> internalNames = new HashSet<>();
		for (Map.Entry<Path, JsonNode> manifest : manifests.entrySet())
		{
			JsonNode name = manifest.getValue().path("package").path("name");
			if (!manifest.getKey().equals(workspace) && name.isString())
				internalNames.add(name.stringValue());
		}

		Map<String, List<String>> failures = new TreeMap<>(TEXT_ORDER);
		Set<String> usedFeatures = new HashSet<>();
		for (Map.Entry<Path, JsonNode> manifest : manifests.entrySet())
		{
			List<String> errors = errors(cargo, workspace, manifest.getKey(), manifest.getValue(), internalNames,
				usedFeatures, featurePaths, excludedRoots);
			if (!errors.isEmpty())
				failures.put(ArtifactPaths.relativeName(root, manifest.getKey()), errors);
		}
		if ("1".equals(variables.get("ALLOW_STALE_CODE_MODE_FEATURE_EXCEPTION")))
			usedFeatures.add(CODE_MODE_PATH);
		for (String featurePath : featurePaths.stream().sorted(TEXT_ORDER).toList())
			if (!usedFeatures.contains(featurePath))
				failures.computeIfAbsent(featurePath, _ -> new ArrayList<>()).add(
					"remove the stale `[features]` exception from `MANIFEST_FEATURE_EXCEPTIONS`");
		if (failures.isEmpty())
			return 0;
		printGuidance(out);
		for (Map.Entry<String, List<String>> failure : failures.entrySet())
		{
			out.println(failure.getKey() + ":");
			for (String error : failure.getValue())
				out.println("  - " + error);
		}
		return 1;
	}

	/**
	 * Loads first-party crate manifests in retained filesystem order with the required root manifest first.
	 *
	 * @param cargo the workspace directory
	 * @param workspace the root manifest
	 * @param workspaceManifest the parsed root manifest
	 * @param excludedRoots the validated third-party roots
	 * @return the root-first ordered manifests
	 * @throws IOException if enumeration, decoding, or parsing fails
	 */
	private static Map<Path, JsonNode> loadManifests(Path cargo, Path workspace, JsonNode workspaceManifest,
		Set<Path> excludedRoots) throws IOException
	{
		Map<Path, JsonNode> crates = new LinkedHashMap<>();
		try (Stream<Path> paths = Files.walk(cargo))
		{
			for (Path path : paths.filter(path -> path.getFileName().toString().equals("Cargo.toml") &&
				!path.equals(workspace) && !isExcludedVendorPath(path, excludedRoots)).
				sorted(ArtifactPaths.comparator(cargo)).toList())
				crates.put(path, read(path));
		}
		Map<Path, JsonNode> result = new LinkedHashMap<>();
		result.put(workspace, workspaceManifest);
		result.putAll(crates);
		return result;
	}

	/**
	 * Finds literal excluded third-party roots without extending exclusions to first-party source.
	 *
	 * @param cargo the workspace directory
	 * @param manifest the workspace manifest
	 * @return the resolved strict descendants of the workspace vendor directory
	 */
	private static Set<Path> excludedVendorRoots(Path cargo, JsonNode manifest)
	{
		Set<Path> roots = new HashSet<>();
		Path vendor = cargo.resolve("vendor");
		JsonNode exclusions = manifest.path("workspace").path("exclude");
		if (!exclusions.isArray())
			return roots;
		for (JsonNode exclusion : exclusions)
		{
			if (!exclusion.isString() || exclusion.stringValue().matches(".*[\\*?\\[\\]{}].*"))
				continue;
			try
			{
				Path relative = Path.of(exclusion.stringValue());
				if (relative.isAbsolute())
					continue;
				Path lexical = cargo.resolve(relative).normalize();
				Path resolved = resolvePath(cargo.resolve(relative), new HashSet<>());
				if (lexical.startsWith(vendor) && !lexical.equals(vendor) && resolved.startsWith(vendor) &&
					!resolved.equals(vendor))
					roots.add(resolved);
			}
			catch (InvalidPathException _)
			{
				// An invalid literal cannot establish a third-party ownership boundary.
			}
		}
		return roots;
	}

	/**
	 * Checks resolved containment using path components rather than text prefixes.
	 *
	 * @param path the candidate manifest or dependency
	 * @param excludedRoots the validated third-party roots
	 * @return whether the path belongs to an excluded third-party root
	 */
	private static boolean isExcludedVendorPath(Path path, Set<Path> excludedRoots)
	{
		Path resolved = resolvePath(path, new HashSet<>());
		return excludedRoots.stream().anyMatch(resolved::startsWith);
	}

	/**
	 * Reads strict UTF-8 TOML with operational parse diagnostics.
	 *
	 * @param path the manifest
	 * @return the TOML object
	 * @throws IOException if reading or parsing fails
	 */
	private static JsonNode read(Path path) throws IOException
	{
		String text = Files.readString(path);
		try
		{
			return TomlDocuments.parse(text);
		}
		catch (JacksonException failure)
		{
			throw new IOException("Cannot parse Cargo manifest " + path, failure);
		}
	}

	/**
	 * Collects one manifest's errors in the retained policy order.
	 *
	 * @param cargo the workspace directory
	 * @param workspace the root manifest
	 * @param path the inspected manifest
	 * @param manifest the parsed object
	 * @param internalNames the workspace package names
	 * @param usedFeatures the consumed exception paths
	 * @param featurePaths the selected feature-exception paths
	 * @param excludedRoots the validated third-party roots
	 * @return the policy errors
	 */
	private static List<String> errors(Path cargo, Path workspace, Path path, JsonNode manifest,
		Set<String> internalNames, Set<String> usedFeatures, Set<String> featurePaths, Set<Path> excludedRoots)
	{
		JsonNode definition = manifest.path("package");
		if (!definition.isObject() && !path.equals(workspace))
			return List.of();
		List<String> errors = new ArrayList<>();
		if (definition.isObject())
		{
			for (String field : PACKAGE_FIELDS)
				if (!isTrue(definition.path(field).path("workspace")))
					errors.add("set `" + field + ".workspace = true` in `[package]`");
			if (!isTrue(manifest.path("lints").path("workspace")))
				errors.add("add `[lints]` with `workspace = true`");
			String expected = expectedName(cargo, path);
			JsonNode name = definition.path("name");
			if (expected != null && (!name.isString() || !expected.equals(name.stringValue())))
				errors.add("set `[package].name` to `" + expected + "` (found `" + display(name) + "`)");
		}

		String key = ArtifactPaths.relativeName(cargo.getParent(), path);
		JsonNode features = manifest.get("features");
		if (features != null && !features.isNull())
		{
			if (!featurePaths.contains(key))
				errors.add("remove `[features]`; new workspace crate features are not allowed");
			else
			{
				usedFeatures.add(key);
				if (!matchesFeatureException(features))
					errors.add("limit `[features]` to the existing exception list while workspace crate features are being " +
						"removed (expected sandbox = [\"v8/v8_enable_sandbox\"])");
			}
		}
		for (Map.Entry<String, JsonNode> section : dependencySections(manifest).entrySet())
			for (Map.Entry<String, JsonNode> dependency : section.getValue().properties())
				dependencyErrors(errors, cargo, path, section.getKey(), dependency.getKey(), dependency.getValue(),
					internalNames, excludedRoots);
		return errors;
	}

	/**
	 * Collects root, workspace, and target dependency tables in their maintained order.
	 *
	 * @param manifest the manifest object
	 * @return the ordered table names and objects
	 */
	private static Map<String, JsonNode> dependencySections(JsonNode manifest)
	{
		Map<String, JsonNode> sections = new LinkedHashMap<>();
		for (String name : CargoDependencySections.NAMES)
			if (manifest.path(name).isObject())
				sections.put(name, manifest.path(name));
		JsonNode workspace = manifest.path("workspace").path("dependencies");
		if (workspace.isObject())
			sections.put("workspace.dependencies", workspace);
		JsonNode targets = manifest.path("target");
		if (targets.isObject())
			for (Map.Entry<String, JsonNode> target : targets.properties())
				for (String name : CargoDependencySections.NAMES)
					if (target.getValue().path(name).isObject())
						sections.put("target." + target.getKey() + "." + name, target.getValue().path(name));
		return sections;
	}

	/**
	 * Checks exact boolean feature toggles and internal dependency identity.
	 *
	 * @param errors the accumulated report
	 * @param cargo the workspace directory
	 * @param path the source manifest
	 * @param section the dependency table label
	 * @param name the dependency key
	 * @param dependency the dependency definition
	 * @param internalNames the workspace package names
	 * @param excludedRoots the validated third-party roots
	 */
	private static void dependencyErrors(List<String> errors, Path cargo, Path path, String section, String name,
		JsonNode dependency, Set<String> internalNames, Set<Path> excludedRoots)
	{
		if (!dependency.isObject())
			return;
		String label = "[" + section + "]." + name;
		if (isTrue(dependency.path("optional")))
			errors.add("remove `optional = true` from `" + label + "`; new optional dependencies are not allowed because " +
				"they create crate features");
		if (!isInternal(cargo, path, name, dependency, internalNames, excludedRoots))
			return;
		JsonNode features = dependency.get("features");
		if (features != null && !features.isNull())
			errors.add("remove `features = [...]` from workspace dependency `" + label +
				"`; new workspace crate feature activations are not allowed");
		JsonNode defaults = dependency.path("default-features");
		if (defaults.isBoolean() && !defaults.booleanValue())
			errors.add("remove `default-features = false` from workspace dependency `" + label +
				"`; new workspace crate feature toggles are not allowed");
	}

	/**
	 * Recognizes renamed workspace packages and resolved in-tree dependency paths, including nonexistent descendants.
	 *
	 * @param cargo the workspace directory
	 * @param manifest the source manifest
	 * @param name the dependency key
	 * @param dependency the dependency definition
	 * @param internalNames the workspace package names
	 * @param excludedRoots the validated third-party roots
	 * @return whether the dependency is internal
	 */
	private static boolean isInternal(Path cargo, Path manifest, String name, JsonNode dependency,
		Set<String> internalNames, Set<Path> excludedRoots)
	{
		JsonNode path = dependency.path("path");
		if (path.isString() && isExcludedVendorPath(manifest.getParent().resolve(path.stringValue()), excludedRoots))
			return false;
		JsonNode packageName = dependency.get("package");
		if (packageName == null && internalNames.contains(name) || packageName != null && packageName.isString() &&
			internalNames.contains(packageName.stringValue()))
			return true;
		return path.isString() &&
			resolvePath(manifest.getParent().resolve(path.stringValue()), new HashSet<>()).startsWith(cargo);
	}

	/**
	 * Resolves links component by component while retaining missing or inaccessible components.
	 * This preserves non-strict path resolution, including dangling targets and parent traversal after links.
	 *
	 * @param requested the dependency path
	 * @param resolving the links in the current resolution chain
	 * @return the absolute resolved path
	 */
	private static Path resolvePath(Path requested, Set<Path> resolving)
	{
		Path absolute = requested.toAbsolutePath();
		Path resolved = absolute.getRoot();
		for (Path component : absolute)
		{
			String name = component.toString();
			if (name.equals("."))
				continue;
			if (name.equals(".."))
			{
				if (resolved.getParent() != null)
					resolved = resolved.getParent();
				continue;
			}
			Path candidate = resolved.resolve(component);
			resolved = resolveComponent(candidate, resolving);
		}
		return resolved;
	}

	/**
	 * Resolves a single existing component or dangling link, retaining loops and filesystem errors as path text.
	 *
	 * @param candidate the absolute component
	 * @param resolving the active link chain
	 * @return the resolved component or the unchanged candidate
	 */
	private static Path resolveComponent(Path candidate, Set<Path> resolving)
	{
		if (Files.isSymbolicLink(candidate))
		{
			if (!resolving.add(candidate))
				return candidate;
			try
			{
				return resolvePath(candidate.getParent().resolve(Files.readSymbolicLink(candidate)), resolving);
			}
			catch (IOException _)
			{
				return candidate;
			}
			finally
			{
				resolving.remove(candidate);
			}
		}
		if (Files.exists(candidate))
		{
			try
			{
				return candidate.toRealPath();
			}
			catch (IOException _)
			{
				return candidate;
			}
		}
		return candidate;
	}

	/**
	 * Computes exact directory-based naming with the retained platform and utility exceptions.
	 *
	 * @param cargo the workspace directory
	 * @param path the source manifest
	 * @return the required name, or null when naming policy does not apply
	 */
	private static String expectedName(Path cargo, Path path)
	{
		Path relative = cargo.relativize(path);
		if (relative.getNameCount() == 2)
		{
			String directory = relative.getName(0).toString();
			if (directory.equals("windows-sandbox-rs"))
				return "codex-windows-sandbox";
			if (directory.startsWith("codex-"))
				return directory;
			return "codex-" + directory;
		}
		if (relative.getNameCount() == 3 && relative.getName(0).toString().equals("utils"))
		{
			String directory = relative.getName(1).toString();
			if (directory.equals("path-utils"))
				return "codex-utils-path";
			return "codex-utils-" + directory;
		}
		return null;
	}

	/**
	 * Requires a feature table containing only arrays of string values, preserving array order and spelling.
	 *
	 * @param features the supplied feature value
	 * @return whether the exact exception table is supplied
	 */
	private static boolean matchesFeatureException(JsonNode features)
	{
		if (!features.isObject() || features.size() != FEATURE_EXCEPTION.size())
			return false;
		for (Map.Entry<String, List<String>> expected : FEATURE_EXCEPTION.entrySet())
		{
			JsonNode supplied = features.path(expected.getKey());
			if (!supplied.isArray() || supplied.size() != expected.getValue().size())
				return false;
			for (int index = 0; index < supplied.size(); ++index)
			{
				JsonNode value = supplied.get(index);
				if (!value.isString() || !value.stringValue().equals(expected.getValue().get(index)))
					return false;
			}
		}
		return true;
	}

	/**
	 * Requires the explicit TOML boolean true rather than a truthy or string representation.
	 *
	 * @param value the candidate value
	 * @return whether it is the boolean true
	 */
	private static boolean isTrue(JsonNode value)
	{
		return value.isBoolean() && value.booleanValue();
	}

	/**
	 * Formats actual package names for diagnostics while retaining missing and boolean spelling.
	 *
	 * @param value the actual package name
	 * @return its diagnostic text
	 */
	private static String display(JsonNode value)
	{
		if (value.isMissingNode() || value.isNull())
			return "None";
		if (value.isString())
			return value.stringValue();
		if (value.isBoolean())
		{
			if (value.booleanValue())
				return "True";
			return "False";
		}
		return value.toString();
	}

	/**
	 * Writes retained policy guidance only after complete manifest validation.
	 *
	 * @param out the report destination
	 */
	private static void printGuidance(PrintStream out)
	{
		out.println("""
			Cargo manifests under codex-rs must inherit workspace package metadata, opt into workspace lints, \
			and avoid introducing new workspace crate features.
			Workspace crate features are disallowed because our Bazel build setup does not honor them today, \
			which can let issues hidden behind feature gates go unnoticed, and because they add extra crate \
			build permutations we want to avoid.
			Cargo only applies `codex-rs/Cargo.toml` `[workspace.lints.clippy]` entries to a crate when that crate declares:

			[lints]
			workspace = true

			Without that opt-in, `cargo clippy` can miss violations that Bazel clippy catches.

			Package-name checks apply to `codex-rs/<crate>/Cargo.toml` and `codex-rs/utils/<crate>/Cargo.toml`.
			Workspace crate features are forbidden; add a targeted exception here only if there is a deliberate \
			temporary migration in flight.
			""");
	}
}
