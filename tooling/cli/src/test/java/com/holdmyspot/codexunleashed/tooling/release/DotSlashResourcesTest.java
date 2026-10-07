package com.holdmyspot.codexunleashed.tooling.release;

import com.holdmyspot.codexunleashed.tooling.Sha256;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;
import java.util.zip.GZIPOutputStream;
import org.testng.annotations.Test;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertTrue;
import static org.testng.Assert.expectThrows;

/**
 * Verifies manifest selection, raw executable extraction, verified cache reuse, and corrupt download cleanup.
 */
public final class DotSlashResourcesTest
{
	/**
	 * Creates resource resolution tests.
	 */
	public DotSlashResourcesTest()
	{
	}

	/**
	 * Creates a verified raw gzip cache and reuses it after the provider disappears.
	 *
	 * @throws IOException if fixture access, extraction, or cleanup fails
	 */
	@Test
	public void extractsAndReusesVerifiedGzip() throws IOException
	{
		try (Fixture fixture = Fixture.create())
		{
			fixture.archive = fixture.root.resolve("archive.gz");
			try (GZIPOutputStream output = new GZIPOutputStream(Files.newOutputStream(fixture.archive)))
			{
				output.write(fixture.payload);
			}
			fixture.writeManifest("sha256", Sha256.digest(fixture.archive), Files.size(fixture.archive), "gz",
				"tool", fixture.target.dotslashPlatform());
			Path executable = DotSlashResources.fetch(fixture.request(false)).orElseThrow();
			assertEquals(Files.readAllBytes(executable), fixture.payload);
			Files.delete(fixture.archive);
			Files.writeString(executable, "damaged extracted executable");
			assertEquals(Files.readAllBytes(DotSlashResources.fetch(fixture.request(false)).orElseThrow()), fixture.payload);
		}
	}

	/**
	 * Downloads local ZIP artifacts, extracts the named executable, and reuses only verified cached archives.
	 *
	 * @throws IOException if fixture access or resource resolution fails
	 */
	@Test
	public void extractsAndReusesVerifiedZip() throws IOException
	{
		try (Fixture fixture = Fixture.create())
		{
			fixture.writeArchive("zip");
			fixture.writeManifest("sha256", Sha256.digest(fixture.archive), Files.size(fixture.archive), "zip",
				"tool", fixture.target.dotslashPlatform());
			Path output = DotSlashResources.fetch(fixture.request(false)).orElseThrow();
			assertEquals(Files.readAllBytes(output), fixture.payload);
			if (Files.getFileAttributeView(output, PosixFileAttributeView.class) != null)
				assertTrue(Files.getPosixFilePermissions(output).
					containsAll(PosixFilePermissions.fromString("--x--x--x")));
			Files.delete(fixture.archive);
			Files.writeString(output, "damaged extracted executable");
			assertEquals(Files.readAllBytes(DotSlashResources.fetch(fixture.request(false)).orElseThrow()), fixture.payload);
			assertFalse(Files.exists(fixture.cache.resolve("target-rg/archive.zip.tmp")));
		}
	}

	/**
	 * Extracts raw tar.gz members from a shebang-prefixed manifest.
	 *
	 * @throws IOException if fixture access or resource resolution fails
	 */
	@Test
	public void extractsTarGzipMember() throws IOException
	{
		try (Fixture fixture = Fixture.create())
		{
			fixture.writeArchive("tar.gz");
			fixture.writeManifest("sha256", Sha256.digest(fixture.archive), Files.size(fixture.archive), "tar.gz",
				"tool", fixture.target.dotslashPlatform());
			assertEquals(Files.readAllBytes(DotSlashResources.fetch(fixture.request(false)).orElseThrow()), fixture.payload);
		}
	}

	/**
	 * Reads shebang manifests using every retained ASCII and Unicode line boundary.
	 *
	 * @throws IOException if fixture access or resource resolution fails
	 */
	@Test
	public void readsShebangLineBoundaries() throws IOException
	{
		try (Fixture fixture = Fixture.create())
		{
			fixture.writeArchive("zip");
			for (String newline : List.of("\r", "\r\n", "\u0085", "\u2028", "\u2029", "\u001c", "\u001d", "\u001e",
				"\u000b", "\f"))
			{
				fixture.writeManifest("sha256", Sha256.digest(fixture.archive), Files.size(fixture.archive), "zip",
					"tool", fixture.target.dotslashPlatform());
				Files.writeString(fixture.manifest, Files.readString(fixture.manifest).replace("\n", newline));
				assertEquals(Files.readAllBytes(DotSlashResources.fetch(fixture.request(false)).orElseThrow()),
					fixture.payload);
			}
		}
	}

	/**
	 * Treats absent target platforms as optional only when requested and rejects unsupported hash policies.
	 *
	 * @throws IOException if fixture access fails
	 */
	@Test
	public void distinguishesOptionalPlatform() throws IOException
	{
		try (Fixture fixture = Fixture.create())
		{
			Files.writeString(fixture.manifest, "{\"platforms\":{}}");
			assertTrue(DotSlashResources.fetch(fixture.request(true)).isEmpty());
			expectThrows(IOException.class, () -> DotSlashResources.fetch(fixture.request(false)));
			assertFalse(Files.exists(fixture.cache));
			fixture.writeArchive("zip");
			fixture.writeManifest("SHA256", Sha256.digest(fixture.archive), Files.size(fixture.archive), "zip",
				"tool", fixture.target.dotslashPlatform());
			expectThrows(IOException.class, () -> DotSlashResources.fetch(fixture.request(true)));
			assertFalse(Files.exists(fixture.cache));
		}
	}

	/**
	 * Removes invalid cached and downloaded archives without accepting an unverified executable.
	 *
	 * @throws IOException if fixture access fails
	 */
	@Test
	public void removesCorruptArchives() throws IOException
	{
		try (Fixture fixture = Fixture.create())
		{
			fixture.writeArchive("zip");
			fixture.writeManifest("sha256", "0".repeat(64), Files.size(fixture.archive), "zip", "tool",
				fixture.target.dotslashPlatform());
			Path cached = fixture.cache.resolve("target-rg/archive.zip");
			Files.createDirectories(cached.getParent());
			Files.writeString(cached, "corrupt old archive");
			expectThrows(IOException.class, () -> DotSlashResources.fetch(fixture.request(false)));
			assertFalse(Files.exists(cached));
			assertFalse(Files.exists(cached.resolveSibling("archive.zip.tmp")));
			assertFalse(Files.exists(fixture.cache.resolve("target-rg/rg")));
			fixture.writeManifest("sha256", Sha256.digest(fixture.archive), Files.size(fixture.archive) + 1, "zip",
				"tool", fixture.target.dotslashPlatform());
			expectThrows(IOException.class, () -> DotSlashResources.fetch(fixture.request(false)));
			assertFalse(Files.exists(cached));
		}
	}

	/**
	 * Removes an old extraction before reporting a missing archive member.
	 *
	 * @throws IOException if fixture access fails
	 */
	@Test
	public void rejectsMissingMember() throws IOException
	{
		try (Fixture fixture = Fixture.create())
		{
			fixture.writeArchive("zip");
			fixture.writeManifest("sha256", Sha256.digest(fixture.archive), Files.size(fixture.archive), "zip",
				"missing-member", fixture.target.dotslashPlatform());
			Path output = fixture.cache.resolve("target-rg/rg");
			Files.createDirectories(output.getParent());
			Files.writeString(output, "old executable");
			expectThrows(IOException.class, () -> DotSlashResources.fetch(fixture.request(false)));
			assertFalse(Files.exists(output));
		}
	}

	/**
	 * Owns a local archive provider, manifest override, and resource cache.
	 */
	private static final class Fixture implements AutoCloseable
	{
		private final Path root;
		private final Path input;
		private final Path manifest;
		private final Path cache;
		private final PackageTarget target;
		private Path archive;
		private final byte[] payload = {0, (byte) 255, 1};

		/**
		 * Initializes executable inputs without creating the resolver's cache.
		 *
		 * @param root the owned fixture directory
		 * @throws IOException if initialization fails
		 */
		private Fixture(Path root) throws IOException
		{
			this.root = root;
			PackageTarget selected = PackageTarget.LINUX_X86_MUSL;
			if (root.getFileSystem().getSeparator().equals("\\"))
				selected = PackageTarget.WINDOWS_X86;
			target = selected;
			input = Files.createDirectory(root.resolve("input"));
			Files.write(input.resolve("tool"), payload);
			manifest = root.resolve("manifest");
			cache = root.resolve("cache");
		}

		/**
		 * Allocates fixture storage and removes partial initialization on failure.
		 *
		 * @return the initialized fixture
		 * @throws IOException if initialization fails
		 */
		private static Fixture create() throws IOException
		{
			Path root = Files.createTempDirectory("dotslash-resources-");
			try
			{
				return new Fixture(root);
			}
			catch (IOException | RuntimeException failure)
			{
				try
				{
					delete(root);
				}
				catch (IOException cleanupFailure)
				{
					failure.addSuppressed(cleanupFailure);
				}
				throw failure;
			}
		}

		/**
		 * Creates an archive with a real package archive writer.
		 *
		 * @param format the archive format suffix
		 * @throws IOException if archive writing fails
		 */
		private void writeArchive(String format) throws IOException
		{
			archive = root.resolve("archive." + format);
			PackageArchives.write(new PackageArchives.Request(input, archive, false, root.resolve("temporary"),
				ArchiveOptions.defaults(), List.of()), java.time.Clock.systemUTC(), command -> "");
		}

		/**
		 * Writes a DotSlash manifest with a local provider and explicit artifact metadata.
		 *
		 * @param hash the declared hash algorithm
		 * @param digest the declared archive digest
		 * @param size the declared archive size
		 * @param format the archive format
		 * @param member the executable member path
		 * @param platform the manifest platform name
		 * @throws IOException if manifest writing fails
		 */
		private void writeManifest(String hash, String digest, long size, String format, String member, String platform)
			throws IOException
		{
			Files.writeString(manifest, "#!/usr/bin/env dotslash\n" + "{\"platforms\":{\"" + platform +
				"\":{\"size\":" + size + ",\"hash\":\"" + hash + "\",\"digest\":\"" + digest +
				"\",\"format\":\"" + format + "\",\"path\":\"" + member + "\",\"providers\":[{\"url\":\"" +
				archive.toUri() + "\"}]}}}");
		}

		/**
		 * Supplies explicit resource paths and platform selection.
		 *
		 * @param missingOk whether an absent platform is optional
		 * @return the resolver request
		 */
		private DotSlashResources.Request request(boolean missingOk)
		{
			return new DotSlashResources.Request(target, manifest, "ripgrep", "target-rg",
				"rg", missingOk, cache);
		}

		/**
		 * Removes all owned fixture data.
		 *
		 * @throws IOException if cleanup fails
		 */
		@Override
		public void close() throws IOException
		{
			delete(root);
		}

		/**
		 * Deletes fixture paths without following directory symlinks.
		 *
		 * @param root the fixture root
		 * @throws IOException if cleanup fails
		 */
		private static void delete(Path root) throws IOException
		{
			try (Stream<Path> paths = Files.walk(root))
			{
				for (Path file : paths.sorted(Comparator.reverseOrder()).toList())
					Files.delete(file);
			}
		}
	}
}
