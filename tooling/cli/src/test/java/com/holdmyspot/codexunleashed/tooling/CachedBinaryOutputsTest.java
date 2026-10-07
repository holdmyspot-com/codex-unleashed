package com.holdmyspot.codexunleashed.tooling;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;
import org.testng.annotations.Test;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertTrue;
import static org.testng.Assert.expectThrows;

/** Verifies binary-unit invalidation without discarding library units or unrelated outputs. */
public final class CachedBinaryOutputsTest
{
	private static final String CONTENT = "retain or invalidate only by unit identity";

	/** Creates cache-output tests. */
	public CachedBinaryOutputsTest()
	{
	}

	/**
	 * Deletes requested markers, binary files, and debug bundles while retaining library and sibling state.
	 *
	 * @throws IOException if fixture access, invalidation, or cleanup fails
	 */
	@Test
	public void preservesLibraryAndSiblingUnits() throws IOException
	{
		try (Fixture fixture = new Fixture())
		{
			for (String marker : List.of("bin-codex", "bin-codex.json", "dep-bin-codex", "output-bin-codex"))
				fixture.file(".fingerprint/package-first/" + marker);
			Path libraryMarker = fixture.file(".fingerprint/package-first/lib-package");
			Path siblingMarker = fixture.file(".fingerprint/package-first/bin-other");
			fixture.file(".fingerprint/package-second/bin-codex.json");
			Path unrelated = fixture.file(".fingerprint/other-unit/bin-other");
			Path library = fixture.file("deps/libpackage-hash.rlib");
			Path sibling = fixture.file("other");
			fixture.file("codex");
			fixture.file("codex.exe");
			fixture.file("codex.dSYM/Contents/Resources/DWARF/codex");
			Path outside = Files.createDirectory(fixture.base.resolve("outside-debug"));
			Path retained = Files.writeString(outside.resolve("keep"), CONTENT);
			Files.createSymbolicLink(fixture.release.resolve("codex.dSYM/Contents/link"), outside);
			assertEquals(CachedBinaryOutputs.invalidate(fixture.release, "codex"), 2);
			for (String marker : List.of("bin-codex", "bin-codex.json", "dep-bin-codex", "output-bin-codex"))
				assertFalse(Files.exists(fixture.release.resolve(".fingerprint/package-first/" + marker)));
			assertFalse(Files.exists(fixture.release.resolve(".fingerprint/package-second/bin-codex.json")));
			for (String name : List.of("codex", "codex.exe", "codex.dSYM"))
				assertFalse(Files.exists(fixture.release.resolve(name)));
			for (Path path : List.of(libraryMarker, siblingMarker, unrelated, library, sibling, retained))
				assertEquals(Files.readString(path), CONTENT);
		}
	}

	/**
	 * Deletes binary outputs even when the fingerprint directory is absent without creating cache state.
	 *
	 * @throws IOException if fixture access, invalidation, or cleanup fails
	 */
	@Test
	public void handlesMissingFingerprints() throws IOException
	{
		try (Fixture fixture = new Fixture())
		{
			Path binary = fixture.file("codex");
			assertEquals(CachedBinaryOutputs.invalidate(fixture.release, "codex"), 0);
			assertFalse(Files.exists(binary));
			assertFalse(Files.exists(fixture.release.resolve(".fingerprint")));
		}
	}

	/**
	 * Unlinks debug-bundle links while preserving their unrelated directory targets.
	 *
	 * @throws IOException if fixture access, invalidation, or cleanup fails
	 */
	@Test
	public void preservesDebugLinkTargets() throws IOException
	{
		try (Fixture fixture = new Fixture())
		{
			Path outside = Files.createDirectory(fixture.base.resolve("outside"));
			Path retained = Files.writeString(outside.resolve("keep"), "unrelated debug files");
			Path link = fixture.release.resolve("codex.dSYM");
			Files.createSymbolicLink(link, outside);
			assertEquals(CachedBinaryOutputs.invalidate(fixture.release, "codex"), 0);
			assertFalse(Files.isSymbolicLink(link));
			assertEquals(Files.readString(retained), "unrelated debug files");
			Files.createSymbolicLink(link, fixture.base.resolve("missing"));
			assertEquals(CachedBinaryOutputs.invalidate(fixture.release, "codex"), 0);
			assertFalse(Files.isSymbolicLink(link));
		}
	}

	/**
	 * Rejects binary path traversal before deleting any existing output.
	 *
	 * @throws IOException if fixture access or cleanup fails
	 */
	@Test
	public void rejectsBinaryPathTraversal() throws IOException
	{
		try (Fixture fixture = new Fixture())
		{
			Path binary = fixture.file("codex");
			for (String name : List.of("", ".", "..", "../codex", "codex/", fixture.base.toString()))
				expectThrows(IllegalArgumentException.class, () -> CachedBinaryOutputs.invalidate(fixture.release, name));
			assertTrue(Files.isRegularFile(binary));
		}
	}

	/**
	 * Retains directories that occupy file-marker paths and reports their invalid filesystem shape.
	 *
	 * @throws IOException if fixture access or cleanup fails
	 */
	@Test
	public void refusesDirectoryMarkers() throws IOException
	{
		try (Fixture fixture = new Fixture())
		{
			fixture.file(".fingerprint/package/bin-codex.json");
			Path marker = Files.createDirectory(fixture.release.resolve(".fingerprint/package/bin-codex"));
			expectThrows(IOException.class, () -> CachedBinaryOutputs.invalidate(fixture.release, "codex"));
			assertTrue(Files.isDirectory(marker));
		}
	}

	/** Owns cache markers and unrelated controls beneath Maven's managed temporary directory. */
	private static final class Fixture implements AutoCloseable
	{
		private final Path base = Files.createTempDirectory("cached-binary-outputs-");
		private final Path release = Files.createDirectory(base.resolve("release"));

		/**
		 * Allocates fixture ownership.
		 *
		 * @throws IOException if allocation fails
		 */
		private Fixture() throws IOException
		{
		}

		/**
		 * Writes a cache or output marker.
		 *
		 * @param name relative output name
		 * @return the written marker
		 * @throws IOException if writing fails
		 */
		private Path file(String name) throws IOException
		{
			Path file = release.resolve(name);
			Files.createDirectories(file.getParent());
			return Files.writeString(file, CONTENT);
		}

		@Override
		public void close() throws IOException
		{
			try (Stream<Path> paths = Files.walk(base))
			{
				for (Path path : paths.sorted(Comparator.reverseOrder()).toList())
					Files.delete(path);
			}
		}
	}
}
