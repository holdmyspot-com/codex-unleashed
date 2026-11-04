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
 * Exercises the GHCR cache retention plan through the supported command dispatcher.
 */
public final class GhcrRetentionPlanTest
{
	/**
	 * A 64-digit hexadecimal cache-key hash.
	 */
	private static final String HASH = "0123456789abcdef".repeat(4);

	/**
	 * Creates retention-plan tests.
	 */
	public GhcrRetentionPlanTest()
	{
	}

	/**
	 * Keeps caches only for the newest three stable releases and the protected baselines; within those, keeps the newest
	 * complete cache of each target's two newest seed lines and, among the partial caches newer than the target's newest
	 * complete cache, the newest of each of the two newest seed lines.
	 */
	@Test
	public void keepsNewestCachesOfRetainedReleases()
	{
		String inventory = """
			{
			  "releases": [
			    {"tag_name":"rust-v0.9.0","draft":false,"prerelease":false},
			    {"tag_name":"rust-v0.10.0","draft":false,"prerelease":false},
			    {"tag_name":"rust-v0.8.0","draft":false,"prerelease":false},
			    {"tag_name":"rust-v0.7.0","draft":false,"prerelease":false},
			    {"tag_name":"rust-v0.12.0","draft":true,"prerelease":false},
			    {"tag_name":"rust-v0.11.0","draft":false,"prerelease":true}
			  ],
			  "protected_upstream_refs": ["main", "main"],
			  "package_versions": [
			    %s
			  ]
			}
			""".formatted(String.join(",\n", version(1, "2026-01-01T00:00:00Z", "cargo-release-a-rust-v0.7.0",
				exact("x86_64-unknown-linux-musl", "")),
			version(2, "2026-01-01T00:00:00Z", "cargo-release-b-rust-v0.8.0", exact("x86_64-unknown-linux-musl", ""),
				seed("x86_64-unknown-linux-musl", "1", "")),
			version(10, "2026-01-02T01:00:00Z", "cargo-release-c-main", exact("x86_64-unknown-linux-musl", ""),
				seed("x86_64-unknown-linux-musl", "2", "")),
			version(11, "2026-01-02T02:00:00Z", "cargo-release-d-main", exact("x86_64-unknown-linux-musl", ""),
				seed("x86_64-unknown-linux-musl", "3", "")),
			version(12, "2026-01-02T03:00:00Z", "cargo-release-e-main", exact("x86_64-unknown-linux-musl", ""),
				seed("x86_64-unknown-linux-musl", "2", "")),
			version(13, "2026-01-02T00:30:00Z", "cargo-release-f-main", exact("x86_64-unknown-linux-musl", ""),
				seed("x86_64-unknown-linux-musl", "4", "")),
			version(14, "2026-01-02T04:00:00Z", "cargo-release-g-main", exact("x86_64-unknown-linux-musl", "-partial"),
				seed("x86_64-unknown-linux-musl", "2", "-partial")),
			version(15, "2026-01-02T02:30:00Z", "cargo-release-h-main", exact("x86_64-unknown-linux-musl", "-partial"),
				seed("x86_64-unknown-linux-musl", "2", "-partial")),
			version(16, "2026-01-02T05:00:00Z", "cargo-release-i-main", exact("aarch64-unknown-linux-musl", "")),
			version(21, "2026-01-02T03:30:00Z", "cargo-release-l-main", exact("x86_64-unknown-linux-musl", "-partial"),
				seed("x86_64-unknown-linux-musl", "2", "-partial")),
			version(22, "2026-01-02T03:00:00Z", "cargo-release-m-main", exact("aarch64-apple-darwin", "-partial"),
				seed("aarch64-apple-darwin", "5", "-partial")),
			version(23, "2026-01-02T02:00:00Z", "cargo-release-n-main", exact("aarch64-apple-darwin", "-partial"),
				seed("aarch64-apple-darwin", "6", "-partial")),
			version(24, "2026-01-02T01:00:00Z", "cargo-release-o-main", exact("aarch64-apple-darwin", "-partial"),
				seed("aarch64-apple-darwin", "7", "-partial")),
			version(17, "2026-01-02T05:00:00Z"),
			version(18, "2026-01-02T05:00:00Z", "unrelated"),
			version(19, "2026-01-02T05:00:00Z", "cargo-release-j-main-feature", exact("x86_64-unknown-linux-musl", "")),
			version(20, "2026-01-01T00:00:00Z", "cargo-v2-aarch64-apple-darwin-off-" + HASH,
				"cargo-release-k-rust-v0.9.0")));
		JsonNode result = plan(inventory);
		requireThat(result.path("retained_releases").toString(), "retained_releases")
			.isEqualTo("[\"rust-v0.10.0\",\"rust-v0.9.0\",\"rust-v0.8.0\",\"main\"]");
		requireThat(result.path("retained_version_ids").toString(), "retained_version_ids")
			.isEqualTo("[2,11,12,14,16,22,23,20]");
		requireThat(result.path("obsolete_version_ids").toString(), "obsolete_version_ids")
			.isEqualTo("[1,10,13,15,21,24,17,19]");
		requireThat(result.path("unmanaged_version_ids").toString(), "unmanaged_version_ids").isEqualTo("[18]");
	}

	/**
	 * Refuses malformed or incomplete inventory without emitting a partial deletion candidate list.
	 */
	@Test
	public void refusesInvalidInventoriesWithoutOutput()
	{
		assertRefused("{\"releases\":[]}", "No published stable upstream release");
		assertRefused("{\"releases\":null}", "must be an array: releases");
		assertRefused("{}", "must be an array: releases");
		assertRefused("{\"releases\":", "exactly one JSON object");
		String prefix = """
			{"releases":[{"tag_name":"rust-v0.10.0","draft":false,"prerelease":false}],
			 "protected_upstream_refs":[],"package_versions":
			""";
		assertRefused(prefix + "[{\"id\":1,\"created_at\":\"2026-01-01T00:00:00Z\",\"metadata\":{\"container\":{}}}]}",
			"must be an array: tags");
		assertRefused(prefix + "[" + version(1, "2026-01-01T00:00:00Z") + "," + version(1, "2026-01-01T00:00:00Z") +
			"]}", "Duplicate package version id: 1");
		assertRefused(prefix + "[{\"id\":1.0,\"created_at\":\"2026-01-01T00:00:00Z\",\"metadata\":{\"container\":" +
			"{\"tags\":[]}}}]}", "id must be a positive integer");
		assertRefused(prefix + "[{\"id\":1,\"metadata\":{\"container\":{\"tags\":[]}}}]}",
			"Package version 1 requires an ISO-8601 created_at instant");
		String protectedReferencePrefix = """
			{"releases":[{"tag_name":"rust-v0.10.0","draft":false,"prerelease":false}],
			 "package_versions":[],"protected_upstream_refs":
			""";
		assertRefused(protectedReferencePrefix + "[\"main-feature\"]}",
			"Protected reference must be a stable upstream tag or main: main-feature");
		assertRefused(protectedReferencePrefix + "[\" main\"]}",
			"Protected reference must be a stable upstream tag or main:  main");
	}

	/**
	 * Returns an exact compiled-cache tag in the current layout.
	 *
	 * @param target the Rust target triple
	 * @param suffix empty for a complete cache or {@code -partial}
	 * @return the tag
	 */
	private static String exact(String target, String suffix)
	{
		return "cargo-v3-" + target + "-" + HASH + "-parts-v2" + suffix;
	}

	/**
	 * Returns a seed compiled-cache tag in the current layout.
	 *
	 * @param target the Rust target triple
	 * @param line a character that distinguishes the seed key
	 * @param suffix empty for a complete cache or {@code -partial}
	 * @return the tag
	 */
	private static String seed(String target, String line, String suffix)
	{
		return "cargo-seed-v1-" + target + "-" + line.repeat(64) + "-parts-v2" + suffix;
	}

	/**
	 * Returns one package version record.
	 *
	 * @param id the version ID
	 * @param created the creation instant
	 * @param tags the version's tags
	 * @return the JSON record
	 */
	private static String version(int id, String created, String... tags)
	{
		StringBuilder list = new StringBuilder();
		for (String tag : tags)
		{
			if (!list.isEmpty())
				list.append(',');
			list.append('"').append(tag).append('"');
		}
		return "{\"id\":" + id + ",\"created_at\":\"" + created + "\",\"metadata\":{\"container\":{\"tags\":[" + list +
			"]}}}";
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
			int status = Main.run(new String[]{"plan-ghcr-retention"},
				new ByteArrayInputStream(inventory.getBytes(StandardCharsets.UTF_8)), out, err);
			requireThat(status, "status")
				.withContext(diagnostics.toString(StandardCharsets.UTF_8), "diagnostic")
				.isEqualTo(0);
		}
		try (ToolingScope scope = new DefaultToolingScope())
		{
			return scope.getLeaderJsonMapper().readTree(output.toString(StandardCharsets.UTF_8));
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
			int status = Main.run(new String[]{"plan-ghcr-retention"},
				new ByteArrayInputStream(inventory.getBytes(StandardCharsets.UTF_8)), out, err);
			requireThat(status, "status").isEqualTo(1);
		}
		requireThat(output.size(), "output.size()").isEqualTo(0);
		requireThat(diagnostics.toString(StandardCharsets.UTF_8).contains(expectedDiagnostic), "diagnostics")
			.withContext(diagnostics.toString(StandardCharsets.UTF_8), "diagnostic")
			.isTrue();
	}
}
