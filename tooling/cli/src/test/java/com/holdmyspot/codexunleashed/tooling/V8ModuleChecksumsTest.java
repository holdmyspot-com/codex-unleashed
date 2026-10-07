package com.holdmyspot.codexunleashed.tooling;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.testng.annotations.Test;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertTrue;
import static org.testng.Assert.expectThrows;

/** Verifies exact V8 module checksum selection, updates, manifest validation and preserved unrelated text. */
public final class V8ModuleChecksumsTest
{
	private static final String DIGEST = "1".repeat(64);

	/** Creates module checksum tests. */
	public V8ModuleChecksumsTest()
	{
	}

	/** Checks code-point ordering when manifest-only filenames span BMP and supplementary characters. */
	@Test
	public void ordersUnicodeCoverageDiagnostics()
	{
		String bmp = "\uE000.gz";
		String supplementary = "\uD800\uDC00.gz";
		String source = block("146_4_0", "good.gz", DIGEST);
		IOException failure = expectThrows(IOException.class, () -> V8ModuleChecksums.check(source,
			Map.of("good.gz", DIGEST, bmp, DIGEST, supplementary, DIGEST), "146.4.0"));
		assertTrue(failure.getMessage().indexOf(bmp) < failure.getMessage().indexOf(supplementary), failure.getMessage());
	}

	/**
	 * Replaces a matching digest and inserts an absent digest while preserving unrelated versions.
	 *
	 * @throws IOException if updating or checking fails
	 */
	@Test
	public void updatesOnlySelectedVersion() throws IOException
	{
		String archive = block("146_4_0", "archive.gz", "0".repeat(64));
		String binding = block("146_4_0", "binding.rs", null);
		String other = block("145_0_0", "archive.gz", "f".repeat(64));
		Map<String, String> checksums = Map.of("archive.gz", DIGEST, "binding.rs", "2".repeat(64));
		String before = "# retain header\n" + archive + "\n" + binding + "\n" + other + "# retain footer\n";
		String updated = V8ModuleChecksums.update(before, checksums, "146.4.0");
		assertEquals(updated, "# retain header\n" + block("146_4_0", "archive.gz", DIGEST) + "\n" +
			block("146_4_0", "binding.rs", "2".repeat(64)) + "\n" + other + "# retain footer\n");
		V8ModuleChecksums.check(updated, checksums, "146.4.0");
		assertEquals(V8ModuleChecksums.update(updated, checksums, "146.4.0"), updated);
	}

	/**
	 * Reports missing coverage, duplicate filenames, absent digests and mismatches with retained diagnostics.
	 */
	@Test
	public void refusesIncompleteOrDriftingModules()
	{
		String module = block("146_4_0", "archive.gz", null);
		IOException failure = expectThrows(IOException.class, () -> V8ModuleChecksums.check(module,
			Map.of("archive.gz", DIGEST, "orphan.gz", DIGEST), "146.4.0"));
		assertTrue(failure.getMessage().contains("manifest has orphan.gz, but MODULE.bazel has no http_file"));
		assertTrue(failure.getMessage().contains("is missing sha256"));
		failure = expectThrows(IOException.class, () -> V8ModuleChecksums.check(module + module,
			Map.of("archive.gz", DIGEST), "146.4.0"));
		assertTrue(failure.getMessage().contains("duplicate http_file entries for archive.gz"));
		failure = expectThrows(IOException.class, () -> V8ModuleChecksums.check(block("146_4_0", "archive.gz",
			"0".repeat(64)), Map.of("archive.gz", DIGEST), "146.4.0"));
		assertTrue(failure.getMessage().contains("expected " + DIGEST));
		expectThrows(IOException.class, () -> V8ModuleChecksums.update(module, Map.of("other", DIGEST), "146.4.0"));
		expectThrows(IOException.class, () -> V8ModuleChecksums.check(module, Map.of("archive.gz", DIGEST), "147.0.0"));
	}

	/** Discovers deduplicated versions without consuming unrelated fields or module declarations. */
	@Test
	public void discoversRemainingAssetVersions()
	{
		String module = block("146_4_0", "archive.gz", null) + block("147_4_0", "binding.rs", null) +
			block("146_4_0", "other.gz", null) + "http_file(\n    name = \"unrelated\",\n)\n";
		assertEquals(V8ModuleChecksums.versions(module), List.of("146.4.0", "147.4.0"));
		assertEquals(V8ModuleChecksums.versions("  " + block("146_4_0", "archive.gz", null)), List.of());
	}

	/**
	 * Accepts blank lines and Unicode whitespace while preserving exact bare filenames and digest case.
	 *
	 * @throws IOException if fixture access or parsing fails
	 */
	@Test
	public void readsStrictChecksumManifest() throws IOException
	{
		Path manifest = Files.createTempFile("v8-module-manifest-", ".sha256");
		try
		{
			Files.writeString(manifest, "\n\u001c" + DIGEST + "\u2003archive.gz\u2003\n\n" + DIGEST + "  binding.rs\n");
			assertEquals(V8ModuleChecksums.readManifest(manifest), Map.of("archive.gz", DIGEST, "binding.rs", DIGEST));
			for (String invalid : List.of("", DIGEST + "  ../archive.gz\n", DIGEST + "  .\n",
				"A".repeat(64) + "  archive.gz\n", DIGEST + "  archive.gz extra\n",
				DIGEST + "  archive.gz\n" + DIGEST + "  archive.gz\n"))
			{
				Files.writeString(manifest, invalid);
				expectThrows(IOException.class, () -> V8ModuleChecksums.readManifest(manifest));
			}
			Files.delete(manifest);
			IOException failure = expectThrows(IOException.class, () -> V8ModuleChecksums.readManifest(manifest));
			assertTrue(failure.getMessage().contains("missing checksum manifest"));
		}
		finally
		{
			Files.deleteIfExists(manifest);
		}
	}

	/**
	 * Builds an independently expected recognized http_file block.
	 *
	 * @param version the underscore-separated version
	 * @param filename the artifact filename
	 * @param digest the optional digest
	 * @return the block text
	 */
	private static String block(String version, String filename, String digest)
	{
		String checksum = "";
		if (digest != null)
			checksum = "    sha256 = \"" + digest + "\",\n";
		return "http_file(\n    name = \"rusty_v8_" + version + "_asset\",\n" +
			"    downloaded_file_path = \"" + filename + "\",\n" + checksum +
			"    urls = [\"https://example.invalid/asset\"],\n)\n";
	}
}
