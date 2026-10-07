package com.holdmyspot.codexunleashed.tooling.cache;

import com.holdmyspot.codexunleashed.tooling.CommandRunner;
import com.holdmyspot.codexunleashed.tooling.GitHubRepositories;
import java.io.IOException;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Prunes GitHub dependency caches outside the two newest stable upstream releases.
 */
public final class CachePruner
{
	private static final JsonMapper JSON = JsonMapper.builder().build();
	private static final String STABLE_RELEASE_QUERY =
		".[] | select(.draft == false and .prerelease == false) | .tag_name " +
			"| select(test(\"^rust-v[0-9]+\\\\.[0-9]+\\\\.[0-9]+$\"))";

	/**
	 * Prevents construction.
	 */
	private CachePruner()
	{
	}

	/**
	 * Discovers stable upstream releases through the GitHub CLI.
	 *
	 * @param runner the external-command boundary
	 * @return the retained stable release tags
	 * @throws NullPointerException if {@code runner} is null
	 * @throws IllegalArgumentException if no stable release is found
	 * @throws IOException if release discovery fails
	 */
	public static List<String> discoverRetainedReleases(CommandRunner runner) throws IOException
	{
		Objects.requireNonNull(runner, "runner");
		String tags = runner.run(List.of("gh", "api", "--paginate", "repos/openai/codex/releases?per_page=100",
			"--jq", STABLE_RELEASE_QUERY));
		return CacheRetention.retainedStableTags(tags.lines().toList());
	}

	/**
	 * Lists obsolete caches and deletes them sequentially unless dry-run mode is enabled.
	 * The complete inventory is validated before deletion begins. A failed deletion stops subsequent requests.
	 *
	 * @param repository the repository in owner/name form
	 * @param dryRun whether to report without deleting
	 * @param runner the external-command boundary
	 * @return the retained releases and obsolete identifiers
	 * @throws NullPointerException if {@code repository} or {@code runner} are null
	 * @throws IllegalArgumentException if the repository is invalid or no stable release is found
	 * @throws IOException if discovery, inventory validation, or deletion fails
	 */
	public static Result prune(String repository, boolean dryRun, CommandRunner runner) throws IOException
	{
		Objects.requireNonNull(repository, "repository");
		Objects.requireNonNull(runner, "runner");
		GitHubRepositories.validate(repository);

		List<String> retained = discoverRetainedReleases(runner);
		String inventory = runner.run(List.of("gh", "api", "--paginate",
			"repos/" + repository + "/actions/caches?per_page=100", "--jq", ".actions_caches[] | tojson"));
		List<BigInteger> obsolete = obsoleteIdentifiers(inventory, retained);
		if (!dryRun)
		{
			for (BigInteger identifier : obsolete)
				runner.run(List.of("gh", "api", "--method", "DELETE",
					"repos/" + repository + "/actions/caches/" + identifier));
		}
		return new Result(retained, obsolete, dryRun);
	}

	/**
	 * Validates the entire cache inventory and selects obsolete identifiers without external effects.
	 *
	 * @param inventory the newline-separated JSON cache objects
	 * @param retained the retained stable releases
	 * @return the obsolete identifiers in inventory order
	 * @throws IOException if a JSON object or required cache field is invalid
	 */
	private static List<BigInteger> obsoleteIdentifiers(String inventory, List<String> retained) throws IOException
	{
		List<BigInteger> identifiers = new ArrayList<>();
		try
		{
			for (String line : inventory.lines().toList())
			{
				if (line.isBlank())
					continue;
				JsonNode cache = JSON.readTree(line);
				JsonNode key = cache.get("key");
				if (key == null || !key.isString())
					throw new IOException("Cache inventory requires a string key: " + line);
				if (!CacheRetention.isObsoleteCache(key.asString(), retained))
					continue;
				JsonNode identifier = cache.get("id");
				if (identifier == null || !identifier.isIntegralNumber())
					throw new IOException("Obsolete cache requires an integer id: " + line);
				identifiers.add(new BigInteger(identifier.asString()));
			}
		}
		catch (JacksonException failure)
		{
			throw new IOException("Invalid JSON in GitHub cache inventory: " + failure.getMessage(), failure);
		}
		return List.copyOf(identifiers);
	}

	/**
	 * Describes the cache cleanup result.
	 *
	 * @param retainedReleases the retained stable upstream releases
	 * @param obsoleteCacheIds the obsolete cache identifiers in inventory order
	 * @param dryRun whether deletion is disabled
	 */
	public record Result(List<String> retainedReleases, List<BigInteger> obsoleteCacheIds, boolean dryRun)
	{
		/**
		 * Copies the result collections to preserve their immutable value semantics.
		 *
		 * @param retainedReleases the retained stable upstream releases
		 * @param obsoleteCacheIds the obsolete cache identifiers
		 * @param dryRun whether deletion is disabled
		 * @throws NullPointerException if a collection or any element is null
		 */
		public Result
		{
			retainedReleases = List.copyOf(retainedReleases);
			obsoleteCacheIds = List.copyOf(obsoleteCacheIds);
		}
	}
}
