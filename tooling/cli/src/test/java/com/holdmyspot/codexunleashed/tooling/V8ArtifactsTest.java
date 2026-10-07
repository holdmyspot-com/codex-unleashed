package com.holdmyspot.codexunleashed.tooling;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;
import java.util.zip.GZIPInputStream;
import org.testng.annotations.Test;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.expectThrows;

/** Verifies V8 staging payloads, reproducible gzip headers, artifact names and Cargo output discovery. */
public final class V8ArtifactsTest
{
	/** Creates artifact tests. */
	public V8ArtifactsTest()
	{
	}

	/**
	 * Stages both artifact families with raw binding bytes, deterministic gzip and matching checksums.
	 *
	 * @throws IOException if staging, fixture access or cleanup fails
	 */
	@Test
	public void stagesCompatibleArtifactPairs() throws IOException
	{
		Path root = Files.createTempDirectory("v8-artifacts-");
		try
		{
			byte[] libraryBytes = {0, (byte) 255, 7};
			byte[] bindingBytes = {1, (byte) 254, 9};
			Path library = Files.write(root.resolve("library"), libraryBytes);
			Path binding = Files.write(root.resolve("binding"), bindingBytes);
			for (String target : List.of("aarch64-apple-darwin", "x86_64-pc-windows-msvc"))
			{
				for (boolean sandbox : new boolean[]{false, true})
				{
					String profile = "release";
					if (sandbox)
						profile = "ptrcomp_sandbox_release";
					String name = "librusty_v8_" + profile + "_" + target + ".a.gz";
					if (target.endsWith("-pc-windows-msvc"))
						name = "rusty_v8_" + profile + "_" + target + ".lib.gz";
					Path output = root.resolve(target + "-" + profile);
					List<Path> staged = V8Artifacts.stage(target, library, binding, output, sandbox);
					assertEquals(staged, List.of(output.resolve(name), output.resolve("src_binding_" + profile + "_" + target +
						".rs"), output.resolve("rusty_v8_" + profile + "_" + target + ".sha256")));
					try (InputStream input = new GZIPInputStream(Files.newInputStream(staged.getFirst())))
					{
						assertEquals(input.readAllBytes(), libraryBytes);
					}
					assertEquals(Files.readAllBytes(staged.get(1)), bindingBytes);
					byte[] gzip = Files.readAllBytes(staged.getFirst());
					assertEquals(gzip[3], 0);
					assertEquals(java.util.Arrays.copyOfRange(gzip, 4, 8), new byte[4]);
					assertEquals(Files.readString(staged.getLast()), Sha256.digest(staged.getFirst()) + "  " + name +
						System.lineSeparator() + Sha256.digest(staged.get(1)) + "  " + staged.get(1).getFileName() +
						System.lineSeparator());
					V8Artifacts.stage(target, library, binding, output, sandbox);
					assertEquals(Files.readAllBytes(staged.getFirst()), gzip);
				}
			}
		}
		finally
		{
			delete(root);
		}
	}

	/**
	 * Resolves direct Cargo output first, then independently selects sorted recursive library and binding paths.
	 *
	 * @throws IOException if fixture access or discovery fails
	 */
	@Test
	public void discoversCargoReleasePairPaths() throws IOException
	{
		Path root = Files.createTempDirectory("v8-output-paths-");
		try
		{
			String target = "x86_64-pc-windows-msvc";
			Path release = root.resolve(target + "/release");
			Path library = release.resolve("gn_out/obj/rusty_v8.lib");
			Path binding = release.resolve("gn_out/src_binding.rs");
			assertEquals(V8Artifacts.upstreamPaths(target, root), new V8Artifacts.Pair(library, binding));
			Path alternative = Files.createDirectories(release.resolve("build/a/out"));
			Path fallbackLibrary = Files.writeString(alternative.resolve("rusty_v8.lib"), "archive");
			Path fallbackBinding = Files.writeString(alternative.resolve("src_binding.rs"), "binding");
			assertEquals(V8Artifacts.upstreamPaths(target, root), new V8Artifacts.Pair(fallbackLibrary, fallbackBinding));
			Files.createDirectories(library.getParent());
			Files.writeString(library, "direct archive");
			Files.writeString(binding, "direct binding");
			assertEquals(V8Artifacts.upstreamPaths(target, root), new V8Artifacts.Pair(library, binding));
		}
		finally
		{
			delete(root);
		}
	}

	/**
	 * Reports absent inputs before creating the output directory.
	 *
	 * @throws IOException if fixture operations fail
	 */
	@Test
	public void missingPairPreventsStaging() throws IOException
	{
		Path root = Files.createTempDirectory("v8-missing-pair-");
		try
		{
			Path output = root.resolve("output");
			expectThrows(IOException.class, () -> V8Artifacts.stage("x86_64-unknown-linux-musl", root.resolve("missing"),
				root.resolve("binding"), output, false));
			assertFalse(Files.exists(output));
		}
		finally
		{
			delete(root);
		}
	}

	/**
	 * Removes the owned fixture after success or failure without following directory links.
	 *
	 * @param root the allocated fixture root
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
