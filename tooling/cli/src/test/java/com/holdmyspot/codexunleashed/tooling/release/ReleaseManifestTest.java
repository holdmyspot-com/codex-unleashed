package com.holdmyspot.codexunleashed.tooling.release;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Comparator;
import java.util.stream.Stream;
import org.testng.annotations.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertTrue;

/**
 * Verifies manifest artifact bytes, patch scope, metadata, and deterministic build dates.
 */
public final class ReleaseManifestTest
{
	/**
	 * Creates the manifest tests.
	 */
	public ReleaseManifestTest()
	{
	}

	/**
	 * Includes nested and binary artifacts while excluding the manifest itself and upstream internal patches.
	 * Preserves Unicode target whitespace handling and uses UTC seconds when the build date is omitted.
	 *
	 * @throws IOException if fixture access or manifest generation fails
	 */
	@Test
	public void recordsArtifactsAndActiveQueue() throws IOException
	{
		Path root = Files.createTempDirectory("release-manifest-");
		try
		{
			Path release = Files.createDirectory(root.resolve("release"));
			Files.write(release.resolve("binary.bin"), new byte[]{0, (byte) 255, 1});
			Files.writeString(Files.createDirectory(release.resolve("binary")).resolve("data.bin"), "nested artifact");
			Files.writeString(Files.createDirectory(release.resolve("nested")).resolve("é.txt"), "artifact\n");
			Path output = Files.writeString(release.resolve("release-manifest.json"), "previous manifest");
			Path patch = Files.createDirectories(root.resolve("patches/owner/repo/issue-1")).resolve("fix.patch");
			Files.writeString(patch, "active queue patch\n");
			Files.writeString(Files.createDirectories(root.resolve("upstream/internal/patches")).resolve("other.patch"),
				"excluded internal patch\n");
			ReleaseManifest.generate(request(release, root, output, "", " target-a,\u00a0target-é\u0085, ,target-a "),
				Clock.fixed(Instant.parse("2026-10-06T12:34:56.987Z"), ZoneOffset.ofHours(3)));
			String serialized = Files.readString(output);
			assertTrue(serialized.chars().allMatch(value -> value < 128));
			assertTrue(serialized.endsWith("\n"));
			JsonNode document = JsonMapper.builder().build().readTree(serialized);
			assertEquals(document.get("schema_version").intValue(), 2);
			assertEquals(document.get("release").get("build_date").stringValue(), "2026-10-06T12:34:56Z");
			JsonNode targets = document.get("release").get("supported_targets");
			assertEquals(targets.size(), 3);
			assertEquals(targets.get(0).stringValue(), "target-a");
			assertEquals(targets.get(1).stringValue(), "target-é");
			assertEquals(targets.get(2).stringValue(), "target-a");
			JsonNode artifacts = document.get("artifacts");
			assertEquals(artifacts.size(), 3);
			assertEquals(artifacts.get(0).get("path").stringValue(), "binary/data.bin");
			assertEquals(artifacts.get(1).get("path").stringValue(), "binary.bin");
			assertEquals(artifacts.get(1).get("size_bytes").longValue(), 3L);
			assertEquals(artifacts.get(1).get("sha256").stringValue(),
				"47ffa3ea45a70b8a41c2c0825df323c00a8b7a01c1ea06083cc41dddcc001123");
			assertEquals(artifacts.get(2).get("path").stringValue(), "nested/é.txt");
			assertEquals(document.get("patches").size(), 1);
			assertEquals(document.get("patches").get(0).get("path").stringValue(),
				"patches/owner/repo/issue-1/fix.patch");
			assertFalse(serialized.contains("excluded internal patch"));
		}
		finally
		{
			delete(root);
		}
	}

	/**
	 * Preserves an explicit build date and emits empty collections for absent artifact and patch directories.
	 *
	 * @throws IOException if fixture access or manifest generation fails
	 */
	@Test
	public void preservesExplicitMetadata() throws IOException
	{
		Path root = Files.createTempDirectory("empty-release-manifest-");
		try
		{
			Path output = root.resolve("reports/manifest.json");
			ReleaseManifest.generate(request(root.resolve("missing-release"), root.resolve("missing-patches"), output,
				"explicit build date", ""), Clock.fixed(Instant.EPOCH, ZoneOffset.UTC));
			JsonNode document = JsonMapper.builder().build().readTree(Files.readString(output));
			assertEquals(document.get("release").get("build_date").stringValue(), "explicit build date");
			assertEquals(document.get("release").get("builder_type").stringValue(), "local-script");
			assertEquals(document.get("upstream").get("commit").stringValue(), "opaque source commit");
			assertEquals(document.get("workflow").get("run_attempt").stringValue(), "2");
			assertEquals(document.get("verification").get("consolidated_checksums").stringValue(), "SHA256SUMS");
			assertEquals(document.get("verification").get("attestation_bundle").stringValue(), "");
			assertEquals(document.get("artifacts").size(), 0);
			assertEquals(document.get("patches").size(), 0);
			assertEquals(document.get("release").get("supported_targets").size(), 0);
		}
		finally
		{
			delete(root);
		}
	}

	/**
	 * Supplies all manifest metadata explicitly without requiring a builder checkout.
	 *
	 * @param release the artifact directory
	 * @param patchRepo the repository containing the active patch queue
	 * @param output the output manifest
	 * @param buildDate the supplied date or an empty value
	 * @param targets the comma-separated targets
	 * @return the manifest request
	 */
	private static ReleaseManifest.Request request(Path release, Path patchRepo, Path output, String buildDate,
		String targets)
	{
		return new ReleaseManifest.Request(release, patchRepo, output,
			new ReleaseManifest.Release("holdmyspot-com/codex-unleashed", "rust-v0.160.0+7", "7", buildDate,
				"local-script", targets), new ReleaseManifest.Upstream("openai/codex", "rust-v0.160.0", "opaque source commit"),
			new ReleaseManifest.Workflow("build-release.yml", "refs/heads/main", "source-sha", "100", "2"),
			new ReleaseManifest.Verification("SHA256SUMS", ""));
	}

	/**
	 * Removes the fixture tree without following symbolic links.
	 *
	 * @param root the owned fixture root
	 * @throws IOException if cleanup fails
	 */
	private static void delete(Path root) throws IOException
	{
		try (Stream<Path> paths = Files.walk(root))
		{
			for (Path path : paths.sorted(Comparator.reverseOrder()).toList())
				Files.delete(path);
		}
	}
}
