package com.holdmyspot.codexunleashed.tooling;

import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.math.BigInteger;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Plans which GitHub Actions caches to keep or delete, without access to the cache service.
 */
public final class ActionsCacheRetentionPlan
{
	/**
	 * The retention planning command.
	 */
	public static final String NAME = "plan-actions-cache-retention";
	/**
	 * Selects the upstream reference that names an upstream-scoped cache key.
	 */
	private static final Pattern UPSTREAM_KEY = Pattern.compile("^upstream-([0-9a-f]{40}|main|rust-v\\d+\\.\\d+\\.\\d+)-.*$");
	/**
	 * Selects the cache line of a key that ends with a workflow run ID and attempt number.
	 */
	private static final Pattern RUN_KEY = Pattern.compile("^(.*)-\\d+-\\d+$");
	/**
	 * Matches a full upstream commit ID.
	 */
	private static final Pattern COMMIT = Pattern.compile("[0-9a-f]{40}");

	/**
	 * Prevents construction.
	 */
	private ActionsCacheRetentionPlan()
	{
	}

	/**
	 * Classifies a complete cache inventory from caller-owned input and writes one JSON plan.
	 * <p>
	 * The object must contain retained_upstream_refs, a non-empty array of stable upstream tags, {@code main} or full
	 * commit IDs, and caches with unique positive integer IDs, string key and ref fields and ISO-8601 created_at
	 * instants. A cache whose key starts with {@code upstream-} followed by a commit, {@code main} or stable tag and a
	 * hyphen is retained when that reference is retained and is otherwise obsolete. A cache whose key ends with a
	 * hyphen, a run ID, a hyphen and an attempt number is retained when it is the newest cache with the same key prefix
	 * on the same branch, and is otherwise obsolete; GitHub restores the newest cache that matches a prefix, so older
	 * ones are never read. Every other cache is unmanaged and left to GitHub's eviction of caches unread for seven days.
	 * Newer means a later created_at, then a larger ID. IDs keep inventory order.
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
		Pattern stableTag = scope.getStableReleaseTagPattern();
		Set<String> retained = new HashSet<>();
		for (String reference : RetentionInventory.strings(RetentionInventory.array(document, "retained_upstream_refs")))
		{
			boolean supported = reference.equals("main") || stableTag.matcher(reference).matches() ||
				COMMIT.matcher(reference).matches();
			if (!supported)
				throw new IOException("Retained reference must be a stable upstream tag, main or a full commit: " + reference);
			retained.add(reference);
		}
		if (retained.isEmpty())
			throw new IOException("At least one retained upstream reference is required; retention plan refused");

		List<Cache> caches = new ArrayList<>();
		Set<BigInteger> seenIds = new HashSet<>();
		for (JsonNode node : RetentionInventory.array(document, "caches"))
		{
			JsonNode id = node.path("id");
			if (!id.isIntegralNumber() || id.bigIntegerValue().signum() <= 0)
				throw new IOException("Cache id must be a positive integer");
			BigInteger value = id.bigIntegerValue();
			if (!seenIds.add(value))
				throw new IOException("Duplicate cache id: " + value);
			JsonNode key = node.path("key");
			JsonNode ref = node.path("ref");
			if (!key.isString() || !ref.isString())
				throw new IOException("Cache " + value + " requires string key and ref fields");
			Instant created = RetentionInventory.instant(node, "Cache " + value);
			caches.add(new Cache(value, key.stringValue(), ref.stringValue(), created));
		}

		Map<String, Cache> newestOfLine = new HashMap<>();
		for (Cache cache : caches)
		{
			if (UPSTREAM_KEY.matcher(cache.key()).matches())
				continue;
			Matcher run = RUN_KEY.matcher(cache.key());
			if (!run.matches())
				continue;
			newestOfLine.merge(cache.ref() + "\n" + run.group(1), cache, (left, right) -> left.isNewerThan(right) ? left : right);
		}

		List<BigInteger> retainedIds = new ArrayList<>();
		List<BigInteger> obsoleteIds = new ArrayList<>();
		List<BigInteger> unmanagedIds = new ArrayList<>();
		for (Cache cache : caches)
		{
			Matcher upstream = UPSTREAM_KEY.matcher(cache.key());
			Matcher run = RUN_KEY.matcher(cache.key());
			if (upstream.matches())
			{
				if (retained.contains(upstream.group(1)))
					retainedIds.add(cache.id());
				else
					obsoleteIds.add(cache.id());
			}
			else if (run.matches())
			{
				if (newestOfLine.get(cache.ref() + "\n" + run.group(1)) == cache)
					retainedIds.add(cache.id());
				else
					obsoleteIds.add(cache.id());
			}
			else
				unmanagedIds.add(cache.id());
		}

		out.println(json.writeValueAsString(Map.of("retained_cache_ids", retainedIds,
			"obsolete_cache_ids", obsoleteIds, "unmanaged_cache_ids", unmanagedIds)));
		return 0;
	}

	/**
	 * A cache's identity, key, owning branch and age.
	 *
	 * @param id the cache ID
	 * @param key the cache key
	 * @param ref the branch that owns the cache
	 * @param created when the cache was created
	 */
	private record Cache(BigInteger id, String key, String ref, Instant created)
	{
		/**
		 * Returns whether this cache is newer than another.
		 *
		 * @param other the other cache
		 * @return true if it was created later, or at the same instant with a larger ID
		 */
		boolean isNewerThan(Cache other)
		{
			int comparison = created.compareTo(other.created);
			if (comparison != 0)
				return comparison > 0;
			return id.compareTo(other.id) > 0;
		}
	}
}
