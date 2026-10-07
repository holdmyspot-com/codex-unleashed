package com.holdmyspot.codexunleashed.tooling.release;

import com.holdmyspot.codexunleashed.tooling.ArtifactPaths;
import com.holdmyspot.codexunleashed.tooling.PayloadFiles;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Stream;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Copies third-party Cargo license payloads without changing their bytes or declared expressions.
 */
public final class LicensePayloads
{
	private static final List<String> LICENSE_NAMES = List.of("LICENSE", "LICENCE", "COPYING", "NOTICE", "AUTHORS");
	private static final Comparator<String> TEXT_ORDER = (first, second) ->
		Arrays.compare(first.codePoints().toArray(), second.codePoints().toArray());

	/**
	 * Prevents construction.
	 */
	private LicensePayloads()
	{
	}

	/**
	 * Collects resolved non-workspace packages and writes UTF-8 notices.
	 * Strict missing-evidence rejection occurs after notices and available payloads are written.
	 *
	 * @param metadata the resolved Cargo metadata JSON
	 * @param output the payload and notices directory
	 * @param requireLicenseEvidence indicates whether missing files and declarations cause failure
	 * @throws IOException if metadata is invalid, collection fails, or strict evidence is missing
	 * @throws NullPointerException if {@code metadata} or {@code output} are null
	 */
	public static void collect(String metadata, Path output, boolean requireLicenseEvidence) throws IOException
	{
		Objects.requireNonNull(metadata, "metadata");
		Objects.requireNonNull(output, "output");
		JsonNode document;
		try
		{
			document = JsonMapper.builder().build().readTree(metadata);
		}
		catch (JacksonException failure)
		{
			throw new IOException("Cargo metadata is not valid JSON", failure);
		}
		if (document == null || !document.isObject())
			throw new IOException("Cargo metadata must be an object");
		Files.createDirectories(output);
		Set<String> workspace = workspaceMembers(document);
		List<Dependency> packages = packages(document);
		List<String> lines = new ArrayList<>(List.of("# Third-party Cargo licenses", "",
			"Generated from the locked upstream Cargo dependency graph during release packaging.",
			"License files below are copied from the resolved crate packages without modification.", ""));
		List<String> missingPayloads = new ArrayList<>();
		List<String> missingEvidence = new ArrayList<>();
		for (Dependency dependency : packages)
		{
			if (workspace.contains(dependency.id()))
				continue;
			Path destination = output.resolve(safeName(dependency.name()) + "-" + safeName(dependency.version()));
			List<String> copied = new ArrayList<>();
			for (Path source : licenseFiles(dependency))
			{
				Path target = destination.resolve(source.getFileName());
				Files.createDirectories(target.getParent());
				PayloadFiles.copy(source, target);
				copied.add(ArtifactPaths.relativeName(output, target));
			}
			String label = dependency.name() + " " + dependency.version();
			String expression = dependency.license();
			if (expression.isEmpty())
				expression = "license expression not declared";
			lines.add("- `" + label + "` — `" + expression + "`");
			if (copied.isEmpty())
			{
				lines.add("  - No license payload file was present in the crate source.");
				missingPayloads.add(label);
				if (dependency.license().isEmpty())
					missingEvidence.add(label);
			}
			else
				for (String path : copied)
					lines.add("  - `" + path + "`");
		}
		if (!missingPayloads.isEmpty())
		{
			lines.addAll(List.of("", "## Missing payload files", ""));
			for (String label : missingPayloads)
				lines.add("- `" + label + "`");
		}
		Files.writeString(output.resolve("THIRD_PARTY_NOTICES.md"), String.join("\n", lines) + "\n");
		if (requireLicenseEvidence && !missingEvidence.isEmpty())
			throw new IOException("Missing license evidence for: " + String.join(", ", missingEvidence));
	}

	/**
	 * Reads workspace identities without treating malformed inventories as absent.
	 *
	 * @param document the Cargo metadata object
	 * @return the workspace identities
	 * @throws IOException if the inventory is invalid
	 */
	private static Set<String> workspaceMembers(JsonNode document) throws IOException
	{
		Set<String> result = new HashSet<>();
		JsonNode members = document.get("workspace_members");
		if (members == null)
			return result;
		if (!members.isArray())
			throw new IOException("Cargo workspace_members must be an array");
		for (JsonNode member : members)
		{
			if (!member.isString())
				throw new IOException("Cargo workspace member must be a string");
			result.add(member.stringValue());
		}
		return result;
	}

	/**
	 * Reads packages and sorts raw names and versions by Unicode code point.
	 *
	 * @param document the Cargo metadata object
	 * @return the sorted dependency records
	 * @throws IOException if package metadata is invalid
	 */
	private static List<Dependency> packages(JsonNode document) throws IOException
	{
		JsonNode values = document.get("packages");
		if (values == null || !values.isArray())
			throw new IOException("Cargo packages must be an array");
		List<Dependency> result = new ArrayList<>();
		for (JsonNode value : values)
			result.add(new Dependency(text(value, "id", true), text(value, "name", true), text(value, "version", true),
				Path.of(text(value, "manifest_path", true)).toAbsolutePath().getParent(), text(value, "license", false),
				text(value, "license_file", false)));
		result.sort(Comparator.comparing(Dependency::name, TEXT_ORDER).thenComparing(Dependency::version, TEXT_ORDER));
		return result;
	}

	/**
	 * Reads a string field while allowing absent or null optional license metadata.
	 *
	 * @param object the package metadata object
	 * @param field the field name
	 * @param required indicates whether the field must be supplied
	 * @return the string or an empty optional value
	 * @throws IOException if the field has an invalid type
	 */
	private static String text(JsonNode object, String field, boolean required) throws IOException
	{
		if (!object.isObject())
			throw new IOException("Cargo package must be an object");
		JsonNode value = object.get(field);
		if ((value == null || value.isNull()) && !required)
			return "";
		if (value == null || !value.isString())
			throw new IOException("Cargo package " + field + " must be a string");
		return value.stringValue();
	}

	/**
	 * Selects sorted root payload files and appends an explicit license_file when it is not already present.
	 *
	 * @param dependency the dependency metadata
	 * @return the payload files
	 * @throws IOException if source enumeration fails
	 */
	private static List<Path> licenseFiles(Dependency dependency) throws IOException
	{
		List<Path> files;
		try (Stream<Path> paths = Files.list(dependency.root()))
		{
			files = new ArrayList<>(paths.filter(Files::isRegularFile).
				filter(path -> LICENSE_NAMES.stream().anyMatch(path.getFileName().toString().
					toUpperCase(Locale.ROOT)::startsWith)).sorted(ArtifactPaths.comparator(dependency.root())).toList());
		}
		if (!dependency.licenseFile().isEmpty())
		{
			Path file = dependency.root().resolve(dependency.licenseFile());
			if (Files.isRegularFile(file) && !files.contains(file))
				files.add(file);
		}
		return files;
	}

	/**
	 * Preserves Unicode letters and all numeric categories, replacing other non-punctuation characters.
	 *
	 * @param value the package name or version
	 * @return the payload directory component
	 */
	private static String safeName(String value)
	{
		StringBuilder result = new StringBuilder();
		value.codePoints().forEach(codePoint ->
		{
			boolean numeric = switch (Character.getType(codePoint))
			{
				case Character.DECIMAL_DIGIT_NUMBER, Character.LETTER_NUMBER, Character.OTHER_NUMBER -> true;
				default -> false;
			};
			if (Character.isLetter(codePoint) || numeric || codePoint == '.' || codePoint == '-' || codePoint == '_')
				result.appendCodePoint(codePoint);
			else
				result.append('_');
		});
		return result.toString();
	}

	/**
	 * Holds the resolved fields used by payload collection.
	 *
	 * @param id the Cargo package identity
	 * @param name the package name
	 * @param version the package version
	 * @param root the package source directory
	 * @param license the optional declared expression
	 * @param licenseFile the optional explicit payload path
	 */
	private record Dependency(String id, String name, String version, Path root, String license, String licenseFile)
	{
	}
}
