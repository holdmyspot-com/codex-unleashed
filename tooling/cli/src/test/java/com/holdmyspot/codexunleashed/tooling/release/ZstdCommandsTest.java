package com.holdmyspot.codexunleashed.tooling.release;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.testng.annotations.Test;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertTrue;
import static org.testng.Assert.expectThrows;

/**
 * Verifies native zstd preference, DotSlash fallback, and missing-tool diagnostics.
 */
public final class ZstdCommandsTest
{
	/**
	 * Creates the zstd command selection tests.
	 */
	public ZstdCommandsTest()
	{
	}

	/**
	 * Selects native zstd without requiring a manifest or querying DotSlash.
	 *
	 * @throws IOException if command selection fails
	 */
	@Test
	public void prefersNativeZstd() throws IOException
	{
		Path nativeExecutable = Path.of("tools", "zstd");
		assertEquals(ZstdCommands.resolve(Path.of("unused-manifest"), name ->
		{
			assertEquals(name, "zstd");
			return Optional.of(nativeExecutable);
		}), List.of(nativeExecutable.toString()));
	}

	/**
	 * Selects DotSlash followed by the supplied regular manifest when native zstd is absent.
	 *
	 * @throws IOException if fixture access or command selection fails
	 */
	@Test
	public void fallsBackToDotslashManifest() throws IOException
	{
		Path manifest = Files.createTempFile("zstd-manifest-", ".json");
		try
		{
			Files.writeString(manifest, "#!/usr/bin/env dotslash\n{}\n");
			Path dotslash = Path.of("tools", "dotslash");
			assertEquals(ZstdCommands.resolve(manifest, name ->
			{
				if (name.equals("dotslash"))
					return Optional.of(dotslash);
				return Optional.empty();
			}), List.of(dotslash.toString(), manifest.toString()));
		}
		finally
		{
			Files.delete(manifest);
		}
	}

	/**
	 * Reports the required tools and manifest when neither compression route is available.
	 */
	@Test
	public void rejectsMissingCompressionTools()
	{
		Path manifest = Path.of("missing-manifest");
		IOException failure = expectThrows(IOException.class, () -> ZstdCommands.resolve(manifest,
			name -> Optional.empty()));
		assertTrue(failure.getMessage().contains("zstd is required"));
		assertTrue(failure.getMessage().contains(manifest.toString()));
		expectThrows(IOException.class, () -> ZstdCommands.resolve(manifest, name ->
		{
			if (name.equals("dotslash"))
				return Optional.of(Path.of("dotslash"));
			return Optional.empty();
		}));
	}
}
