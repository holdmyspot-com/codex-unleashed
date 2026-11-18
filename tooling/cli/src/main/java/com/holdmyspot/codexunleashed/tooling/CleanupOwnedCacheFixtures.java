package com.holdmyspot.codexunleashed.tooling;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;

/**
 * Approves the deletion of recorded cache fixture package versions. A recorded fixture is approved only when the
 * package-version listing shows the version with the recorded manifest digest and exactly the recorded aliases, so a
 * version that another cache has since tagged or replaced is never approved.
 */
public final class CleanupOwnedCacheFixtures
{
	/**
	 * The command name.
	 */
	public static final String NAME = "cleanup-owned-cache-fixtures";

	/**
	 * Names the arguments that the command accepts.
	 */
	private static final String USAGE = "Usage: codex-tooling " + NAME + " <request.json> <versions.json>";

	/**
	 * One package version's identity.
	 *
	 * @param id   the package version ID
	 * @param name the manifest digest, such as {@code sha256:...}
	 * @param tags the complete alias list, in any order
	 */
	public record Version(long id, String name, List<String> tags)
	{
		/**
		 * Copies the alias list.
		 *
		 * @param id   the package version ID
		 * @param name the manifest digest
		 * @param tags the complete alias list
		 * @throws NullPointerException if {@code name} or {@code tags} are null
		 */
		public Version
		{
			Objects.requireNonNull(name, "name");
			tags = List.copyOf(tags);
		}
	}

	/**
	 * Prevents construction.
	 */
	private CleanupOwnedCacheFixtures()
	{
	}

	/**
	 * Prints the ID of each recorded fixture that the listing shows, one per line in the recorded order, after verifying
	 * every one of them. Nothing is printed when a present fixture does not match its record.
	 *
	 * @param scope     shared tooling values borrowed until the command finishes
	 * @param arguments the path of the JSON array of recorded {@code id}/{@code name}/{@code tags} records, then the path
	 *                  of the JSON array of pages that {@code gh api --paginate --slurp} returns for the package's
	 *                  versions
	 * @param out       the approved IDs
	 * @param err       diagnostics
	 * @return zero on success, one when an input cannot be read or a present fixture does not match its record, or two
	 *         for invalid command arguments
	 * @throws NullPointerException if any argument is null
	 */
	public static int run(ToolingScope scope, String[] arguments, PrintStream out, PrintStream err)
	{
		Objects.requireNonNull(scope, "scope");
		Objects.requireNonNull(arguments, "arguments");
		Objects.requireNonNull(out, "out");
		Objects.requireNonNull(err, "err");

		if (arguments.length != 2)
		{
			err.println(USAGE);
			return 2;
		}

		try
		{
			// A recorded request and one repository's package listing are small documents that the verification needs
			// whole, because a fixture's ID can occur on any page.
			JsonNode request = scope.getJsonMapper().readTree(Files.readString(Path.of(arguments[0])));
			JsonNode pages = scope.getJsonMapper().readTree(Files.readString(Path.of(arguments[1])));
			List<Version> expected = new ArrayList<>();
			for (JsonNode record : array(request, "request"))
				expected.add(readVersion(record, record.path("tags")));
			List<JsonNode> listing = new ArrayList<>();
			for (JsonNode page : array(pages, "versions"))
			{
				for (JsonNode version : array(page, "page of versions"))
					listing.add(version);
			}

			for (long approved : approvedVersions(expected, listing))
				out.println(approved);
			return 0;
		}
		catch (IOException | IllegalArgumentException | JacksonException failure)
		{
			err.println("ERROR: " + failure.getMessage());
			return 1;
		}
	}

	/**
	 * Verifies an observed fixture version against its recorded identity, ignoring the order of the aliases. The
	 * arguments are not changed.
	 *
	 * @param expected the recorded identity
	 * @param actual   the observed identity
	 * @throws NullPointerException     if an argument is null
	 * @throws IllegalArgumentException if the version ID, manifest digest or complete alias set differs
	 */
	public static void verifyVersion(Version expected, Version actual)
	{
		Objects.requireNonNull(expected, "expected");
		Objects.requireNonNull(actual, "actual");

		if (actual.id() != expected.id())
			throw new IllegalArgumentException("Package version ID differs: " + actual.id());
		if (!actual.name().equals(expected.name()))
			throw new IllegalArgumentException("Package manifest differs: " + actual.name());
		if (!sorted(actual.tags()).equals(sorted(expected.tags())))
			throw new IllegalArgumentException("Package aliases differ: " + actual.tags());
	}

	/**
	 * Returns the IDs of the recorded fixtures that a GitHub package-version listing contains. Recorded fixtures that the
	 * listing lacks and listed versions that no record names are ignored. Neither argument is changed.
	 *
	 * @param expected the recorded fixtures
	 * @param actual   the listing's version records; each has {@code id}, {@code name} and
	 *                 {@code metadata.container.tags}, and a later record with the same ID replaces an earlier one
	 * @return the IDs in the order of {@code expected}, or an empty list when none is present
	 * @throws NullPointerException     if an argument is null
	 * @throws IllegalArgumentException if a listed version lacks an ID, if a present fixture lacks a name or tags, or if
	 *                                  a present fixture's manifest digest or complete alias set differs from its record;
	 *                                  no partial approval is returned
	 */
	public static List<Long> approvedVersions(List<Version> expected, List<JsonNode> actual)
	{
		Objects.requireNonNull(expected, "expected");
		Objects.requireNonNull(actual, "actual");

		Map<Long, JsonNode> versions = new HashMap<>();
		for (JsonNode version : actual)
			versions.put(readId(version), version);

		List<Long> approved = new ArrayList<>();
		for (Version fixture : expected)
		{
			JsonNode version = versions.get(fixture.id());
			if (version == null)
				continue;
			JsonNode tags = version.path("metadata").path("container").path("tags");
			verifyVersion(fixture, readVersion(version, tags));
			approved.add(fixture.id());
		}
		return approved;
	}

	/**
	 * Reads a version's identity from a record.
	 *
	 * @param record the record with {@code id} and {@code name}
	 * @param tags   the record's alias array
	 * @return the identity
	 * @throws IllegalArgumentException if the ID, name or tags are missing or have the wrong type
	 */
	private static Version readVersion(JsonNode record, JsonNode tags)
	{
		long id = readId(record);
		JsonNode name = record.path("name");
		if (!name.isString())
			throw new IllegalArgumentException("Package version " + id + " has no manifest digest (name)");
		List<String> aliases = new ArrayList<>();
		for (JsonNode tag : array(tags, "tags of package version " + id))
		{
			if (!tag.isString())
				throw new IllegalArgumentException("Package version " + id + " has a tag that is not text");
			aliases.add(tag.stringValue());
		}
		return new Version(id, name.stringValue(), aliases);
	}

	/**
	 * Reads a version's ID.
	 *
	 * @param record the record
	 * @return the ID
	 * @throws IllegalArgumentException if the record has no integer ID
	 */
	private static long readId(JsonNode record)
	{
		JsonNode id = record.path("id");
		if (!id.isIntegralNumber() || !id.canConvertToLong())
			throw new IllegalArgumentException("A package version record has no integer id: " + record);
		return id.longValue();
	}

	/**
	 * Requires a JSON array.
	 *
	 * @param node        the node to check
	 * @param description names the node in the failure message
	 * @return the node
	 * @throws IllegalArgumentException if the node is not an array
	 */
	private static JsonNode array(JsonNode node, String description)
	{
		if (!node.isArray())
			throw new IllegalArgumentException("The " + description + " must be a JSON array");
		return node;
	}

	/**
	 * Sorts aliases for an order-independent comparison.
	 *
	 * @param tags the aliases
	 * @return a sorted copy
	 */
	private static List<String> sorted(List<String> tags)
	{
		List<String> copy = new ArrayList<>(tags);
		copy.sort(null);
		return copy;
	}
}
