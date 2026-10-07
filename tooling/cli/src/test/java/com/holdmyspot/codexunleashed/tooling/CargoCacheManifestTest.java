package com.holdmyspot.codexunleashed.tooling;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Map;
import java.util.stream.Stream;
import org.testng.annotations.Test;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.expectThrows;

/** Checks the complete Cargo cache size manifest's root selection, ordering, raw paths and file sizes. */
public final class CargoCacheManifestTest
{
	/** Creates manifest tests. */
	public CargoCacheManifestTest()
	{
	}

	/**
	 * Keeps target, registry cache and Git roots in order, excludes registry source, and follows regular-file links.
	 *
	 * @throws IOException if fixture, manifest or cleanup operations fail
	 */
	@Test
	public void writesCompleteOrderedInventory() throws IOException
	{
		Path root = Files.createTempDirectory("cargo-cache-manifest-");
		try
		{
			Path target = Files.createDirectories(root.resolve("target/nested")).getParent();
			Path first = Files.write(target.resolve("a"), new byte[]{0, (byte) 255});
			Path nested = Files.writeString(target.resolve("nested/last"), "three");
			Path link = Files.createSymbolicLink(target.resolve("z-link"), first);
			Path home = root.resolve("cargo-home");
			Path registry = Files.createDirectories(home.resolve("registry/cache"));
			Path archive = Files.writeString(registry.resolve("v8.crate"), "crate");
			Path git = Files.createDirectories(home.resolve("git/checkouts"));
			Path checkout = Files.writeString(git.resolve("source"), "git");
			Path sources = Files.createDirectories(home.resolve("registry/src"));
			Files.writeString(sources.resolve("excluded"), "excluded");
			Files.createSymbolicLink(target.resolve("directory-link"), git);
			Files.createSymbolicLink(target.resolve("broken"), root.resolve("absent"));
			Path output = Files.writeString(root.resolve("manifest.tsv"), "stale");
			Map<String, String> environment = Map.of("CARGO_TARGET_DIR", target + "/./", "CARGO_HOME", home.toString());
			CargoCacheManifest.write(output, environment);
			String expected = "2\t" + first + "\n5\t" + nested + "\n2\t" + link + "\n5\t" + archive +
				"\n3\t" + checkout + "\n";
			assertEquals(Files.readString(output), expected.replace("\n", System.lineSeparator()));
			CargoCacheManifest.write(output, environment);
			assertEquals(Files.readString(output), expected.replace("\n", System.lineSeparator()));
			Path targetLink = Files.createSymbolicLink(root.resolve("target-link"), target);
			CargoCacheManifest.write(output, Map.of("CARGO_TARGET_DIR", targetLink.toString(), "CARGO_HOME", ""));
			String linked = "2\t" + targetLink.resolve("a") + "\n5\t" + targetLink.resolve("nested/last") +
				"\n2\t" + targetLink.resolve("z-link") + "\n";
			assertEquals(Files.readString(output), linked.replace("\n", System.lineSeparator()));
			CargoCacheManifest.write(output, Map.of("CARGO_TARGET_DIR", root.resolve("missing").toString()));
			assertEquals(Files.readString(output), "");
			Files.writeString(output, "preserve");
			expectThrows(IOException.class, () -> CargoCacheManifest.write(output, Map.of()));
			assertEquals(Files.readString(output), "preserve");
		}
		finally
		{
			try (Stream<Path> paths = Files.walk(root))
			{
				for (Path path : paths.sorted(Comparator.reverseOrder()).toList())
					Files.delete(path);
			}
		}
	}
}
