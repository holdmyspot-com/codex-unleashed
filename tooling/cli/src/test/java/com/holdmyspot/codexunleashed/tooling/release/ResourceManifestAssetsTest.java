package com.holdmyspot.codexunleashed.tooling.release;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.testng.annotations.Test;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.expectThrows;

/** Checks release inventory extraction independently from package target archive selection. */
public final class ResourceManifestAssetsTest
{
	/** Creates release manifest tests. */
	public ResourceManifestAssetsTest()
	{
	}

	/**
	 * Retains platform insertion order, first eligible provider policy and raw URL basename spelling.
	 *
	 * @throws IOException if fixture, extraction or cleanup operations fail
	 */
	@Test
	public void selectsFirstEligibleReleaseProvider() throws IOException
	{
		Path file = Files.createTempFile("resource-assets-", ".dotslash");
		try
		{
			Files.writeString(file, """
				#!/usr/bin/env dotslash
				{"platforms": {
				  "z-platform": {"size": 9007199254740993, "digest": "first", "providers": [
				    {"type": "unused", "name": "skip"},
				    {"url": "https://example.invalid/release/raw%2Fname.tar.gz?download=1#fragment"},
				    {"type": "github-release", "name": "later.tar.gz"}]},
				  "a-platform": {"size": "42", "digest": "second", "providers": [
				    {"type": "github-release", "name": "named.tar.gz"},
				    {"url": "https://example.invalid/later"}]}
				}}
				""");
			List<DotSlashManifest.ReleaseAsset> assets = DotSlashManifest.releaseAssets(file);
			assertEquals(assets.size(), 2);
			assertEquals(assets.getFirst().name(), "raw%2Fname.tar.gz");
			assertEquals(assets.getFirst().size(), "9007199254740993");
			assertEquals(assets.getFirst().digest(), "first");
			assertEquals(assets.getLast().name(), "named.tar.gz");
			assertEquals(assets.getLast().size(), "42");
			assertEquals(assets.getLast().digest(), "second");
			Files.writeString(file, "#!dotslash\n{\"platforms\": {\"x\": {\"size\": 1, \"digest\": \"x\", " +
				"\"providers\": [{\"type\": \"unrelated\"}]}}}");
			expectThrows(IOException.class, () -> DotSlashManifest.releaseAssets(file));
			Files.writeString(file, "#!dotslash\n{\"platforms\": []}");
			expectThrows(IOException.class, () -> DotSlashManifest.releaseAssets(file));
		}
		finally
		{
			Files.delete(file);
		}
	}
}
