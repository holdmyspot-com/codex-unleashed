package com.holdmyspot.codexunleashed.tooling.test;

import com.holdmyspot.codexunleashed.tooling.DefaultToolingScope;
import com.holdmyspot.codexunleashed.tooling.Main;
import com.holdmyspot.codexunleashed.tooling.ToolingScope;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import org.testng.annotations.Test;
import tools.jackson.databind.JsonNode;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

/**
 * Exercises the GitHub Actions cache retention plan through the supported command dispatcher.
 */
public final class ActionsCacheRetentionPlanTest
{
	/**
	 * A retained upstream commit.
	 */
	private static final String RETAINED_COMMIT = "a".repeat(40);
	/**
	 * An upstream commit that no retained reference names.
	 */
	private static final String OTHER_COMMIT = "b".repeat(40);

	/**
	 * Creates retention-plan tests.
	 */
	public ActionsCacheRetentionPlanTest()
	{
	}

	/**
	 * Keeps upstream-scoped caches only for retained references, keeps the newest entry of each per-run cache line on
	 * each branch, and leaves other caches to GitHub's own eviction.
	 */
	@Test
	public void keepsRetainedUpstreamCachesAndNewestRunCaches()
	{
		String zig = "setup-zig-cache-v2-build-zig-linux-x86_64-0.14.0-";
		String inventory = """
			{
			  "retained_upstream_refs": ["rust-v0.9.0", "%s"],
			  "caches": [
			    %s
			  ]
			}
			""".formatted(RETAINED_COMMIT, String.join(",\n",
			cache(1, "upstream-" + RETAINED_COMMIT + "-downloads-v1-x86_64-unknown-linux-musl-Linux-x64",
				"refs/heads/main.new", "2026-01-01T00:00:00Z"),
			cache(2, "upstream-" + OTHER_COMMIT + "-downloads-v1-x86_64-unknown-linux-musl-Linux-x64",
				"refs/heads/main.new", "2026-01-03T00:00:00Z"),
			cache(3, "upstream-rust-v0.9.0-bazel-repository-aarch64-apple-darwin-" + "c".repeat(64),
				"refs/heads/main.new", "2026-01-01T00:00:00Z"),
			cache(4, "upstream-rust-v0.7.0-bazel-repository-aarch64-apple-darwin-" + "c".repeat(64),
				"refs/heads/main.new", "2026-01-03T00:00:00Z"),
			cache(5, zig + "-100-1", "refs/heads/main.new", "2026-01-02T01:00:00Z"),
			cache(6, zig + "-101-1", "refs/heads/main.new", "2026-01-02T02:00:00Z"),
			cache(7, zig + "-90-1", "refs/heads/main", "2026-01-01T00:00:00Z"),
			cache(8, "scheduler-memory-history-v1-x86_64-unknown-linux-musl-200-2", "refs/heads/main.new",
				"2026-01-02T00:00:00Z"),
			cache(9, "tooling-runtime-v1-Linux-X64-temurin-27.0.1+0-" + "d".repeat(64), "refs/heads/main.new",
				"2026-01-01T00:00:00Z"),
			cache(10, "codeql-dependencies-1-Linux-java-" + "e".repeat(64), "refs/heads/main",
				"2026-01-01T00:00:00Z")));
		JsonNode result = plan(inventory);
		requireThat(result.path("retained_cache_ids").toString(), "retained_cache_ids").isEqualTo("[1,3,6,7,8]");
		requireThat(result.path("obsolete_cache_ids").toString(), "obsolete_cache_ids").isEqualTo("[2,4,5]");
		requireThat(result.path("unmanaged_cache_ids").toString(), "unmanaged_cache_ids").isEqualTo("[9,10]");
	}

	/**
	 * Refuses malformed inventory without emitting a partial deletion candidate list.
	 */
	@Test
	public void refusesInvalidInventoriesWithoutOutput()
	{
		assertRefused("{\"caches\":[]}", "must be an array: retained_upstream_refs");
		assertRefused("{\"retained_upstream_refs\":[],\"caches\":[]}", "At least one retained upstream reference");
		assertRefused("{\"retained_upstream_refs\":[\"feature\"],\"caches\":[]}",
			"Retained reference must be a stable upstream tag, main or a full commit: feature");
		String prefix = "{\"retained_upstream_refs\":[\"main\"],\"caches\":";
		assertRefused(prefix + "[" + cache(1, "k", "refs/heads/main", "2026-01-01T00:00:00Z") + "," +
			cache(1, "k", "refs/heads/main", "2026-01-01T00:00:00Z") + "]}", "Duplicate cache id: 1");
		assertRefused(prefix + "[{\"id\":1,\"key\":\"k\",\"ref\":\"refs/heads/main\"}]}",
			"Cache 1 requires an ISO-8601 created_at instant");
		assertRefused(prefix + "[{\"id\":1,\"ref\":\"refs/heads/main\",\"created_at\":\"2026-01-01T00:00:00Z\"}]}",
			"Cache 1 requires string key and ref fields");
	}

	/**
	 * Returns one cache record.
	 *
	 * @param id the cache ID
	 * @param key the cache key
	 * @param ref the branch that owns the cache
	 * @param created the creation instant
	 * @return the JSON record
	 */
	private static String cache(int id, String key, String ref, String created)
	{
		return "{\"id\":" + id + ",\"key\":\"" + key + "\",\"ref\":\"" + ref + "\",\"created_at\":\"" + created + "\"}";
	}

	/**
	 * Runs the command on a valid inventory.
	 *
	 * @param inventory caller-supplied JSON
	 * @return the parsed plan
	 */
	private static JsonNode plan(String inventory)
	{
		ByteArrayOutputStream output = new ByteArrayOutputStream();
		ByteArrayOutputStream diagnostics = new ByteArrayOutputStream();
		try (PrintStream out = new PrintStream(output, true, StandardCharsets.UTF_8);
			PrintStream err = new PrintStream(diagnostics, true, StandardCharsets.UTF_8))
		{
			int status = Main.run(new String[]{"plan-actions-cache-retention"},
				new ByteArrayInputStream(inventory.getBytes(StandardCharsets.UTF_8)), out, err);
			requireThat(status, "status")
				.withContext(diagnostics.toString(StandardCharsets.UTF_8), "diagnostic")
				.isEqualTo(0);
		}
		try (ToolingScope scope = new DefaultToolingScope())
		{
			return scope.getJsonMapper().readTree(output.toString(StandardCharsets.UTF_8));
		}
	}

	/**
	 * Runs an invalid inventory through the command and requires a diagnostic with no plan.
	 *
	 * @param inventory caller-supplied JSON
	 * @param expectedDiagnostic diagnostic fragment identifying the rejected constraint
	 */
	private static void assertRefused(String inventory, String expectedDiagnostic)
	{
		ByteArrayOutputStream output = new ByteArrayOutputStream();
		ByteArrayOutputStream diagnostics = new ByteArrayOutputStream();
		try (PrintStream out = new PrintStream(output, true, StandardCharsets.UTF_8);
			PrintStream err = new PrintStream(diagnostics, true, StandardCharsets.UTF_8))
		{
			int status = Main.run(new String[]{"plan-actions-cache-retention"},
				new ByteArrayInputStream(inventory.getBytes(StandardCharsets.UTF_8)), out, err);
			requireThat(status, "status").isEqualTo(1);
		}
		requireThat(output.size(), "output.size()").isEqualTo(0);
		requireThat(diagnostics.toString(StandardCharsets.UTF_8).contains(expectedDiagnostic), "diagnostics")
			.withContext(diagnostics.toString(StandardCharsets.UTF_8), "diagnostic")
			.isTrue();
	}
}
