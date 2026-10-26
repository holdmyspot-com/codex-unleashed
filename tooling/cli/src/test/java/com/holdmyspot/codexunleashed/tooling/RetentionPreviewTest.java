package com.holdmyspot.codexunleashed.tooling;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import org.testng.annotations.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertTrue;

/**
 * Exercises the read-only retention preview through the supported command dispatcher.
 */
public final class RetentionPreviewTest
{
	/**
	 * Creates preview tests.
	 */
	public RetentionPreviewTest()
	{
	}

	/**
	 * Protects the newest two numeric stable versions and every alias of a pinned manifest.
	 */
	@Test
	public void previewsSharedAliasesAndPinnedBaseline()
	{
		String inventory = """
			{
			  "releases": [
			    {"tag_name":"rust-v0.9.0","draft":false,"prerelease":false},
			    {"tag_name":"rust-v0.10.0","draft":false,"prerelease":false},
			    {"tag_name":"rust-v0.8.0","draft":false,"prerelease":false},
			    {"tag_name":"rust-v0.12.0","draft":true,"prerelease":false},
			    {"tag_name":"rust-v0.11.0","draft":false,"prerelease":true}
			  ],
			  "protected_upstream_refs": ["rust-v0.1.0"],
			  "package_versions": [
			    {"id":1,"metadata":{"container":{"tags":["cargo-release-a-rust-v0.10.0"]}}},
			    {"id":2,"metadata":{"container":{"tags":["cargo-release-b-rust-v0.8.0","cargo-release-c-rust-v0.1.0"]}}},
			    {"id":3,"metadata":{"container":{"tags":["cargo-v3-target-parts-v2-partial","cargo-release-d-rust-v0.8.0"]}}},
			    {"id":4,"metadata":{"container":{"tags":["unrelated"]}}},
			    {"id":5,"metadata":{"container":{"tags":[]}}},
			    {"id":6,"metadata":{"container":{"tags":["cargo-release-e-rust-v0.9.0"]}}}
			  ]
			}
			""";
		ByteArrayOutputStream output = new ByteArrayOutputStream();
		ByteArrayOutputStream diagnostics = new ByteArrayOutputStream();
		try (PrintStream out = new PrintStream(output, true, StandardCharsets.UTF_8);
			PrintStream err = new PrintStream(diagnostics, true, StandardCharsets.UTF_8))
		{
			int status = Main.run(new String[]{"preview-ghcr-retention"},
				new ByteArrayInputStream(inventory.getBytes(StandardCharsets.UTF_8)), out, err);
			assertEquals(status, 0, diagnostics.toString(StandardCharsets.UTF_8));
		}
		JsonNode result = JsonMapper.builder().build().readTree(output.toString(StandardCharsets.UTF_8));
		assertEquals(result.path("retained_releases").toString(),
			"[\"rust-v0.10.0\",\"rust-v0.9.0\",\"rust-v0.1.0\"]");
		assertEquals(result.path("retained_version_ids").toString(), "[1,2,6]");
		assertEquals(result.path("obsolete_version_ids").toString(), "[3,5]");
		assertEquals(result.path("unmanaged_version_ids").toString(), "[4]");
		assertTrue(result.path("dry_run").asBoolean());
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
		assertRefused(prefix + "[{\"id\":1,\"metadata\":{\"container\":{}}}]}", "must be an array: tags");
		assertRefused(prefix + "[{\"id\":1,\"metadata\":{\"container\":{\"tags\":[]}}}," +
			"{\"id\":1,\"metadata\":{\"container\":{\"tags\":[]}}}]}", "Duplicate package version id: 1");
		assertRefused(prefix + "[{\"id\":1.0,\"metadata\":{\"container\":{\"tags\":[]}}}]}",
			"id must be a positive integer");
	}

	/**
	 * Runs an invalid inventory through the command and requires a diagnostic with no preview.
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
			int status = Main.run(new String[]{"preview-ghcr-retention"},
				new ByteArrayInputStream(inventory.getBytes(StandardCharsets.UTF_8)), out, err);
			assertEquals(status, 1);
		}
		assertEquals(output.size(), 0);
		assertTrue(diagnostics.toString(StandardCharsets.UTF_8).contains(expectedDiagnostic),
			diagnostics.toString(StandardCharsets.UTF_8));
	}
}
