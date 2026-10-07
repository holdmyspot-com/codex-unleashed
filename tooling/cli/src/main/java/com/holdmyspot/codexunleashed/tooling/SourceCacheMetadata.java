package com.holdmyspot.codexunleashed.tooling;

import java.io.IOException;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Interprets Cargo metadata only when the requested cache operation consumes its fields. */
public final class SourceCacheMetadata
{
	private static final Set<String> LIBRARY_KINDS = Set.of("lib", "rlib", "dylib", "cdylib", "staticlib", "proc-macro");
	private static final JsonMapper JSON = JsonMapper.builder().enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).
		build();
	private final JsonNode document;

	/**
	 * Retains the parsed Cargo document.
	 *
	 * @param document the validated object
	 */
	private SourceCacheMetadata(JsonNode document)
	{
		this.document = document;
	}

	/**
	 * Reads Cargo's metadata JSON without imposing fields unused by record-only operations.
	 *
	 * @param text complete Cargo output
	 * @return the metadata view
	 * @throws NullPointerException if text is null
	 * @throws IOException if the output is not exactly one JSON object
	 */
	public static SourceCacheMetadata parse(String text) throws IOException
	{
		Objects.requireNonNull(text, "text");
		try
		{
			JsonNode document = JSON.readTree(text);
			if (document == null || !document.isObject())
				throw new IOException("Cargo metadata must be a JSON object");
			return new SourceCacheMetadata(document);
		}
		catch (JacksonException failure)
		{
			throw new IOException("Cargo metadata is not valid JSON", failure);
		}
	}

	/**
	 * Retains the metadata target path or uses the environment/workspace fallback only when the field is absent.
	 *
	 * @param workspace the manifest workspace
	 * @param environment the Cargo invocation environment
	 * @return the target path without changing relative or empty spellings
	 * @throws NullPointerException if either argument is null
	 * @throws IOException if an explicit field is not a string or its path is invalid
	 */
	public Path targetDirectory(Path workspace, Map<String, String> environment) throws IOException
	{
		Objects.requireNonNull(workspace, "workspace");
		Objects.requireNonNull(environment, "environment");
		String value = environment.getOrDefault("CARGO_TARGET_DIR", workspace.resolve("target").toString());
		if (document.has("target_directory"))
			value = text(document, "target_directory");
		try
		{
			return Path.of(value);
		}
		catch (InvalidPathException failure)
		{
			throw new IOException("Invalid Cargo target directory path: " + value, failure);
		}
	}

	/**
	 * Selects owners of the requested executable targets, leaving absent names for the caller's diagnostic.
	 *
	 * @param binaries requested binary names
	 * @return binary names to package names
	 * @throws NullPointerException if binaries is null
	 * @throws IOException if consumed Cargo package or target fields are malformed
	 */
	public Map<String, String> binaryOwners(Set<String> binaries) throws IOException
	{
		Objects.requireNonNull(binaries, "binaries");
		Map<String, String> owners = new LinkedHashMap<>();
		for (JsonNode packageNode : array(document, "packages"))
		{
			for (JsonNode target : array(packageNode, "targets"))
			{
				if (kinds(target).contains("bin"))
				{
					String name = text(target, "name");
					if (binaries.contains(name))
						owners.put(name, text(packageNode, "name"));
				}
			}
		}
		return Map.copyOf(owners);
	}

	/**
	 * Selects packages that own any library target kind recognized by Cargo.
	 *
	 * @return library package names
	 * @throws IOException if consumed package or target fields are malformed
	 */
	public Set<String> libraryOwners() throws IOException
	{
		Set<String> owners = new LinkedHashSet<>();
		for (JsonNode packageNode : array(document, "packages"))
		{
			for (JsonNode target : array(packageNode, "targets"))
			{
				if (kinds(target).stream().anyMatch(LIBRARY_KINDS::contains))
				{
					owners.add(text(packageNode, "name"));
					break;
				}
			}
		}
		return Set.copyOf(owners);
	}

	/**
	 * Limits cold cleanup to sorted workspace packages, retaining external dependencies.
	 *
	 * @return workspace package names in cleanup order
	 * @throws IOException if workspace membership or package fields are malformed
	 */
	public List<String> workspacePackageNames() throws IOException
	{
		Set<String> members = strings(array(document, "workspace_members"));
		List<String> names = new ArrayList<>();
		for (JsonNode packageNode : array(document, "packages"))
		{
			if (members.contains(text(packageNode, "id")))
				names.add(text(packageNode, "name"));
		}
		names.sort(String::compareTo);
		return List.copyOf(names);
	}

	/**
	 * Reads native target kinds without converting JSON scalars into strings.
	 *
	 * @param target the Cargo target
	 * @return its kind names
	 * @throws IOException if the kind field is not a string array
	 */
	private static Set<String> kinds(JsonNode target) throws IOException
	{
		return strings(array(target, "kind"));
	}

	/**
	 * Reads the consumed array field.
	 *
	 * @param object the containing Cargo object
	 * @param field its field name
	 * @return the array
	 * @throws IOException if the field is absent or not an array
	 */
	private static JsonNode array(JsonNode object, String field) throws IOException
	{
		JsonNode value = object.path(field);
		if (!value.isArray())
			throw new IOException("Cargo metadata field must be an array: " + field);
		return value;
	}

	/**
	 * Reads a native JSON string field without discarding an explicit null value.
	 *
	 * @param object the containing object
	 * @param field the field name
	 * @return its exact string
	 * @throws IOException if the field is absent or not a string
	 */
	private static String text(JsonNode object, String field) throws IOException
	{
		JsonNode value = object.path(field);
		if (!value.isString())
			throw new IOException("Cargo metadata field must be a string: " + field);
		return value.stringValue();
	}

	/**
	 * Collects native string values from an array.
	 *
	 * @param array the string array
	 * @return its distinct exact values
	 * @throws IOException if any element is not a string
	 */
	private static Set<String> strings(JsonNode array) throws IOException
	{
		Set<String> result = new LinkedHashSet<>();
		for (JsonNode value : array)
		{
			if (!value.isString())
				throw new IOException("Cargo metadata array must contain strings");
			result.add(value.stringValue());
		}
		return result;
	}
}
