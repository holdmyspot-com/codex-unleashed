package com.holdmyspot.codexunleashed.tooling;

import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Previews old main's manifest-level GHCR retention policy without registry access or deletion.
 */
public final class RetentionPreview
{
	/**
	 * The read-only private-cache retention command.
	 */
	public static final String NAME = "preview-ghcr-retention";

	/**
	 * Stable upstream release spelling accepted by old main's retention policy.
	 */
	private static final Pattern STABLE_TAG = Pattern.compile("rust-v[0-9]+\\.[0-9]+\\.[0-9]+");

	/**
	 * Parses exactly one inventory document using the existing tooling JSON dependency.
	 */
	private static final JsonMapper JSON = JsonMapper.builder().
		enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();

	/**
	 * Prevents construction.
	 */
	private RetentionPreview()
	{
	}

	/**
	 * Classifies a complete inventory from caller-owned input and writes one JSON preview.
	 * The object must contain release records with tag_name/draft/prerelease fields,
	 * protected_upstream_refs strings and package_versions with unique positive integer
	 * IDs and metadata.container.tags string arrays. The two newest distinct numeric stable
	 * releases and explicit stable references protect every alias of matching Cargo manifests.
	 * Untagged and other Cargo versions are obsolete candidates; unrelated tagged versions
	 * are unmanaged. IDs retain inventory order. This preview neither authorizes deletion
	 * nor accounts for writers that change the registry after collection.
	 *
	 * @param arguments command arguments, which must be empty
	 * @param in complete inventory JSON; remains open
	 * @param out complete preview, written only after inventory validation
	 * @param err invalid-argument diagnostics
	 * @return zero on success or two for unexpected command arguments
	 * @throws NullPointerException if an argument is null
	 * @throws IOException if input cannot be read or does not satisfy the inventory contract
	 */
	public static int run(String[] arguments, InputStream in, PrintStream out, PrintStream err) throws IOException
	{
		Objects.requireNonNull(arguments, "arguments");
		Objects.requireNonNull(in, "in");
		Objects.requireNonNull(out, "out");
		Objects.requireNonNull(err, "err");
		if (arguments.length != 0)
		{
			err.println("Usage: " + NAME + " < inventory.json");
			return 2;
		}

		JsonNode document;
		try
		{
			document = JSON.readTree(new String(in.readAllBytes(), StandardCharsets.UTF_8));
		}
		catch (JacksonException failure)
		{
			throw new IOException("Retention inventory must contain exactly one JSON object", failure);
		}
		if (document == null || !document.isObject())
			throw new IOException("Retention inventory must be a JSON object");

		List<String> retained = retainedReleases(document);
		List<BigInteger> retainedIds = new ArrayList<>();
		List<BigInteger> obsoleteIds = new ArrayList<>();
		List<BigInteger> unmanagedIds = new ArrayList<>();
		Set<BigInteger> seenIds = new HashSet<>();
		for (JsonNode version : array(document, "package_versions"))
		{
			JsonNode id = version.path("id");
			if (!id.isIntegralNumber())
				throw new IOException("Package version id must be a positive integer");
			BigInteger value = id.bigIntegerValue();
			if (value.signum() <= 0)
				throw new IOException("Package version id must be positive: " + value);
			if (!seenIds.add(value))
				throw new IOException("Duplicate package version id: " + value);
			List<String> tags = strings(array(version.path("metadata").path("container"), "tags"));
			boolean protectedVersion = tags.stream().anyMatch(tag -> tag.startsWith("cargo-") &&
				retained.stream().anyMatch(release -> tag.endsWith("-" + release)));
			boolean obsoleteVersion = tags.isEmpty() || tags.stream().anyMatch(tag -> tag.startsWith("cargo-"));
			if (protectedVersion)
				retainedIds.add(value);
			else if (obsoleteVersion)
				obsoleteIds.add(value);
			else
				unmanagedIds.add(value);
		}

		out.println(JSON.writeValueAsString(Map.of("retained_releases", retained,
			"retained_version_ids", retainedIds, "obsolete_version_ids", obsoleteIds,
			"unmanaged_version_ids", unmanagedIds, "dry_run", true)));
		return 0;
	}

	/**
	 * Selects numeric stable releases and adds explicitly protected baseline references.
	 *
	 * @param document complete inventory
	 * @return newest two tags followed by any additional protected references
	 * @throws IOException if fields are malformed or no stable published release exists
	 */
	private static List<String> retainedReleases(JsonNode document) throws IOException
	{
		Set<String> stable = new LinkedHashSet<>();
		for (JsonNode release : array(document, "releases"))
		{
			JsonNode tag = release.path("tag_name");
			JsonNode draft = release.path("draft");
			JsonNode prerelease = release.path("prerelease");
			boolean validRecord = tag.isString() && draft.isBoolean() && prerelease.isBoolean();
			if (!validRecord)
				throw new IOException("Release records require string tag_name and boolean draft/prerelease fields");
			boolean stableRelease = !draft.booleanValue() && !prerelease.booleanValue() &&
				STABLE_TAG.matcher(tag.stringValue()).matches();
			if (stableRelease)
				stable.add(tag.stringValue());
		}
		if (stable.isEmpty())
			throw new IOException("No published stable upstream release found; retention preview refused");
		List<String> ordered = new ArrayList<>(stable);
		ordered.sort(RetentionPreview::compareNewestFirst);
		Set<String> retained = new LinkedHashSet<>(ordered.subList(0, Math.min(2, ordered.size())));
		for (String reference : strings(array(document, "protected_upstream_refs")))
		{
			if (!STABLE_TAG.matcher(reference).matches())
				throw new IOException("Protected reference must be a stable upstream tag: " + reference);
			retained.add(reference);
		}
		return List.copyOf(retained);
	}

	/**
	 * Compares validated stable tags using old main's exact numeric component ordering.
	 *
	 * @param left first tag
	 * @param right second tag
	 * @return descending version comparison
	 */
	private static int compareNewestFirst(String left, String right)
	{
		String[] leftParts = left.substring("rust-v".length()).split("\\.");
		String[] rightParts = right.substring("rust-v".length()).split("\\.");
		for (int index = 0; index < leftParts.length; ++index)
		{
			int comparison = new BigInteger(rightParts[index]).compareTo(new BigInteger(leftParts[index]));
			if (comparison != 0)
				return comparison;
		}
		return 0;
	}

	/**
	 * Requires an inventory array without treating a missing field as an empty inventory.
	 *
	 * @param object containing object
	 * @param field required field name
	 * @return the array
	 * @throws IOException if the field is missing or is not an array
	 */
	private static JsonNode array(JsonNode object, String field) throws IOException
	{
		JsonNode value = object.path(field);
		if (!value.isArray())
			throw new IOException("Retention inventory field must be an array: " + field);
		return value;
	}

	/**
	 * Validates a required inventory string array.
	 *
	 * @param array array selected from the inventory
	 * @return its strings, in input order
	 * @throws IOException if an element is not a string
	 */
	private static List<String> strings(JsonNode array) throws IOException
	{
		List<String> result = new ArrayList<>();
		for (JsonNode value : array)
		{
			if (!value.isString())
				throw new IOException("Retention inventory array must contain strings");
			result.add(value.stringValue());
		}
		return result;
	}
}
