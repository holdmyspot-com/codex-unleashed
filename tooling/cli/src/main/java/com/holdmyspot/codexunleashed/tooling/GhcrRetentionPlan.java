package com.holdmyspot.codexunleashed.tooling;

import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.math.BigInteger;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Plans which GHCR compiled-cache package versions to keep or delete, without registry access.
 */
public final class GhcrRetentionPlan
{
	/**
	 * The retention planning command.
	 */
	public static final String NAME = "plan-ghcr-retention";
	/**
	 * The number of newest stable upstream releases whose caches are kept.
	 */
	private static final int RETAINED_RELEASES = 3;
	/**
	 * The number of seed lines kept per target and release. A seed key changes with the runner image, so two lines let
	 * both images of a runner rollout keep a cache.
	 */
	private static final int RETAINED_SEED_LINES = 2;
	/**
	 * Selects the target triple of a compiled-cache tag: the text before the 64-digit key hash, after any format prefix.
	 */
	private static final Pattern CACHE_TARGET = Pattern.compile(
		"^cargo-(?:(?:seed-)?v\\d+-)?(.+?)-(?:off-|profile-seed-)?[0-9a-f]{64}(?:-.*)?$");

	/**
	 * Prevents construction.
	 */
	private GhcrRetentionPlan()
	{
	}

	/**
	 * Classifies a complete inventory from caller-owned input and writes one JSON plan.
	 * <p>
	 * The object must contain release records with tag_name/draft/prerelease fields, protected_upstream_refs strings,
	 * and package_versions with unique positive integer IDs, ISO-8601 created_at instants and metadata.container.tags
	 * string arrays. The retained references are up to three distinct published, non-draft, non-prerelease stable tags
	 * with the highest numeric major, minor, and patch components, followed by any additional explicit stable or
	 * {@code main} references.
	 * <p>
	 * A version belongs to a retained reference when any tag starts with {@code cargo-release-} and ends with that
	 * reference preceded by a hyphen. Among each retained reference's versions for one target, the newest complete
	 * version of each of the two newest seed lines is retained; so is, among the partial versions (those with a tag
	 * ending in {@code -partial}) created after the target's newest complete version, the newest of each of the two
	 * newest seed lines. A version's seed line is its
	 * {@code cargo-seed-} tag without {@code -partial}, or, without one, its other cache tags. A version whose target
	 * cannot be read from its tags is retained. Untagged versions and all other Cargo versions are obsolete; tagged
	 * versions without a {@code cargo-} tag are unmanaged. Newer means a later created_at, then a larger ID. IDs keep
	 * inventory order. The plan does not account for writers that change the registry after collection.
	 *
	 * @param scope open caller-owned shared values; remains open
	 * @param arguments command arguments, which must be empty
	 * @param in complete inventory JSON; remains open
	 * @param out complete plan, written only after inventory validation
	 * @param err invalid-argument diagnostics
	 * @return zero on success or two for unexpected command arguments
	 * @throws NullPointerException if an argument is null
	 * @throws IOException if input cannot be read or does not satisfy the inventory contract
	 */
	public static int run(ToolingScope scope, String[] arguments, InputStream in, PrintStream out, PrintStream err) throws IOException
	{
		Objects.requireNonNull(scope, "scope may not be null");
		Objects.requireNonNull(arguments, "arguments");
		Objects.requireNonNull(in, "in");
		Objects.requireNonNull(out, "out");
		Objects.requireNonNull(err, "err");
		if (arguments.length != 0)
		{
			err.println("Usage: " + NAME + " < inventory.json");
			return 2;
		}

		JsonMapper json = scope.getJsonMapper();
		JsonNode document = RetentionInventory.readObject(json, in);
		List<String> retained = selectRetainedReferences(document, scope.getStableReleaseTagPattern());
		List<Version> versions = new ArrayList<>();
		Set<BigInteger> seenIds = new HashSet<>();
		for (JsonNode node : RetentionInventory.array(document, "package_versions"))
		{
			JsonNode id = node.path("id");
			if (!id.isIntegralNumber())
				throw new IOException("Package version id must be a positive integer");
			BigInteger value = id.bigIntegerValue();
			if (value.signum() <= 0)
				throw new IOException("Package version id must be positive: " + value);
			if (!seenIds.add(value))
				throw new IOException("Duplicate package version id: " + value);
			Instant created = RetentionInventory.instant(node, "Package version " + value);
			List<String> tags = RetentionInventory.strings(RetentionInventory.array(node.path("metadata").path("container"),
				"tags"));
			versions.add(new Version(value, created, tags));
		}

		Set<BigInteger> kept = selectRetainedVersions(versions, retained);
		List<BigInteger> retainedIds = new ArrayList<>();
		List<BigInteger> obsoleteIds = new ArrayList<>();
		List<BigInteger> unmanagedIds = new ArrayList<>();
		for (Version version : versions)
		{
			if (kept.contains(version.id()))
				retainedIds.add(version.id());
			else if (version.tags().isEmpty() || version.tags().stream().anyMatch(tag -> tag.startsWith("cargo-")))
				obsoleteIds.add(version.id());
			else
				unmanagedIds.add(version.id());
		}

		out.println(json.writeValueAsString(Map.of("retained_releases", retained,
			"retained_version_ids", retainedIds, "obsolete_version_ids", obsoleteIds,
			"unmanaged_version_ids", unmanagedIds)));
		return 0;
	}

	/**
	 * Selects the versions to keep among those of retained references.
	 *
	 * @param versions every inventoried version
	 * @param retained the retained references
	 * @return the IDs of the versions to keep
	 */
	private static Set<BigInteger> selectRetainedVersions(List<Version> versions, List<String> retained)
	{
		Map<String, List<Version>> byTarget = new HashMap<>();
		Set<BigInteger> kept = new HashSet<>();
		for (Version version : versions)
		{
			String reference = retainedReference(version, retained);
			if (reference == null)
				continue;
			String target = version.target();
			if (target == null)
			{
				kept.add(version.id());
				continue;
			}
			byTarget.computeIfAbsent(reference + "\n" + target, _ -> new ArrayList<>()).add(version);
		}

		for (List<Version> line : byTarget.values())
		{
			line.sort(Comparator.comparing(Version::created).thenComparing(Version::id).reversed());
			List<Version> complete = line.stream().filter(version -> !version.partial()).toList();
			keepNewestOfSeedLines(complete, kept);
			Instant newestComplete;
			if (complete.isEmpty())
				newestComplete = Instant.MIN;
			else
				newestComplete = complete.getFirst().created();
			keepNewestOfSeedLines(line.stream().
				filter(version -> version.partial() && version.created().isAfter(newestComplete)).
				toList(), kept);
		}
		return kept;
	}

	/**
	 * Keeps the newest version of each of the newest seed lines.
	 *
	 * @param newestFirst versions of one reference and target, newest first
	 * @param kept the IDs of the versions to keep; receives the selected IDs
	 */
	private static void keepNewestOfSeedLines(List<Version> newestFirst, Set<BigInteger> kept)
	{
		Set<String> seedLines = new HashSet<>();
		for (Version version : newestFirst)
		{
			if (seedLines.size() < RETAINED_SEED_LINES && seedLines.add(version.seedLine()))
				kept.add(version.id());
		}
	}

	/**
	 * Returns the retained reference a version belongs to.
	 *
	 * @param version an inventoried version
	 * @param retained the retained references
	 * @return the first retained reference its release tag names, or null if none does
	 */
	private static String retainedReference(Version version, List<String> retained)
	{
		for (String tag : version.tags())
		{
			if (!tag.startsWith("cargo-release-"))
				continue;
			for (String reference : retained)
			{
				if (tag.endsWith("-" + reference))
					return reference;
			}
		}
		return null;
	}

	/**
	 * Selects numeric stable releases and adds explicitly protected baseline references.
	 *
	 * @param document complete inventory
	 * @param stableTag shared immutable stable-release selector
	 * @return up to three distinct stable tags in descending numeric version order, followed by additional protected references in inventory order
	 * @throws IOException if fields are malformed or no stable published release exists
	 */
	private static List<String> selectRetainedReferences(JsonNode document, Pattern stableTag) throws IOException
	{
		Set<String> stable = new LinkedHashSet<>();
		for (JsonNode release : RetentionInventory.array(document, "releases"))
		{
			JsonNode tag = release.path("tag_name");
			JsonNode draft = release.path("draft");
			JsonNode prerelease = release.path("prerelease");
			boolean validRecord = tag.isString() && draft.isBoolean() && prerelease.isBoolean();
			if (!validRecord)
				throw new IOException("Release records require string tag_name and boolean draft/prerelease fields");
			boolean stableRelease = !draft.booleanValue() && !prerelease.booleanValue() &&
				stableTag.matcher(tag.stringValue()).matches();
			if (stableRelease)
				stable.add(tag.stringValue());
		}
		if (stable.isEmpty())
			throw new IOException("No published stable upstream release found; retention plan refused");
		List<String> ordered = new ArrayList<>(stable);
		ordered.sort(GhcrRetentionPlan::compareVersionsDescending);
		Set<String> retained = new LinkedHashSet<>(ordered.subList(0, Math.min(RETAINED_RELEASES, ordered.size())));
		for (String reference : RetentionInventory.strings(RetentionInventory.array(document, "protected_upstream_refs")))
		{
			boolean supportedReference = reference.equals("main") || stableTag.matcher(reference).matches();
			if (!supportedReference)
				throw new IOException("Protected reference must be a stable upstream tag or main: " + reference);
			retained.add(reference);
		}
		return List.copyOf(retained);
	}

	/**
	 * Compares validated stable tags by descending numeric major, minor, and patch components.
	 *
	 * @param left first tag
	 * @param right second tag
	 * @return descending version comparison
	 */
	private static int compareVersionsDescending(String left, String right)
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
	 * A package version's identity, age and tags.
	 *
	 * @param id the version ID
	 * @param created when the version was created
	 * @param tags the version's tags
	 */
	private record Version(BigInteger id, Instant created, List<String> tags)
	{
		/**
		 * Returns whether this version holds a partial cache.
		 *
		 * @return true if any tag ends with {@code -partial}
		 */
		boolean partial()
		{
			return tags.stream().anyMatch(tag -> tag.endsWith("-partial"));
		}

		/**
		 * Returns the target triple read from the first cache tag that names one.
		 *
		 * @return the target, or null if no tag names one
		 */
		String target()
		{
			for (String tag : tags)
			{
				if (tag.startsWith("cargo-release-"))
					continue;
				Matcher matcher = CACHE_TARGET.matcher(tag);
				if (matcher.matches())
					return matcher.group(1);
			}
			return null;
		}

		/**
		 * Returns the seed line that this version's cache continues.
		 *
		 * @return the seed tag without {@code -partial}, or the other cache tags if there is no seed tag
		 */
		String seedLine()
		{
			List<String> cacheTags = new ArrayList<>();
			for (String tag : tags)
			{
				if (tag.startsWith("cargo-release-"))
					continue;
				String line = tag.endsWith("-partial") ? tag.substring(0, tag.length() - "-partial".length()) : tag;
				if (line.startsWith("cargo-seed-"))
					return line;
				cacheTags.add(line);
			}
			cacheTags.sort(null);
			return String.join("\n", cacheTags);
		}
	}
}
