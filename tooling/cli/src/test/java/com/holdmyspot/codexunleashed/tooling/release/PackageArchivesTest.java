package com.holdmyspot.codexunleashed.tooling.release;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import java.util.stream.Stream;
import java.util.zip.GZIPInputStream;
import java.util.zip.ZipInputStream;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.archivers.zip.ZipFile;
import org.testng.annotations.Test;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertNotEquals;
import static org.testng.Assert.assertTrue;
import static org.testng.Assert.expectThrows;

/**
 * Verifies retained archive formats, member ordering, timestamps, replacement, and temporary cleanup.
 */
public final class PackageArchivesTest
{
	private static final Clock CLOCK = Clock.fixed(Instant.ofEpochSecond(1_234_567_899), ZoneOffset.UTC);

	/**
	 * Creates the package archive tests.
	 */
	public PackageArchivesTest()
	{
	}

	/**
	 * Preserves executable and restricted source permissions in tar members.
	 *
	 * @throws IOException if fixture access or archive processing fails
	 */
	@Test
	public void preservesTarPermissions() throws IOException
	{
		try (Fixture fixture = Fixture.create())
		{
			if (!fixture.directory.getFileSystem().supportedFileAttributeViews().contains("posix"))
				return;
			Files.setPosixFilePermissions(fixture.directory.resolve("nested.bin"),
				PosixFilePermissions.fromString("rwxr-x---"));
			Files.setPosixFilePermissions(fixture.directory.resolve("nested/entry"),
				PosixFilePermissions.fromString("rw-------"));
			Path output = fixture.root.resolve("permissions.tar.gz");
			PackageArchives.write(fixture.request(output, false, ArchiveOptions.defaults()), CLOCK, command -> "");

			try (TarArchiveInputStream archive = new TarArchiveInputStream(new GZIPInputStream(
				Files.newInputStream(output))))
			{
				Map<String, Integer> expected = Map.of("nested.bin", 0750, "nested/entry", 0600);
				for (TarArchiveEntry entry = archive.getNextEntry(); entry != null; entry = archive.getNextEntry())
					if (entry.isFile())
						assertEquals(entry.getMode() & 07777, expected.get(entry.getName()).intValue(), entry.getName());
			}
		}
	}

	/**
	 * Preserves source Unix permissions in the ZIP central directory.
	 *
	 * @throws IOException if fixture access or archive processing fails
	 */
	@Test
	public void preservesZipPermissions() throws IOException
	{
		try (Fixture fixture = Fixture.create())
		{
			if (!fixture.directory.getFileSystem().supportedFileAttributeViews().contains("unix"))
				return;
			Path input = fixture.directory.resolve("nested.bin");
			Files.setPosixFilePermissions(input, PosixFilePermissions.fromString("rwxr-x---"));
			Path output = fixture.root.resolve("permissions.zip");
			PackageArchives.write(fixture.request(output, false, ArchiveOptions.defaults()), CLOCK, command -> "");

			try (ZipFile archive = ZipFile.builder().setPath(output).get())
			{
				assertEquals(archive.getEntry("nested.bin").getUnixMode(),
					((Integer) Files.getAttribute(input, "unix:mode")).intValue());
			}
		}
	}

	/**
	 * Preserves tar member bytes and slash-name ordering and independently controls the gzip header.
	 *
	 * @throws IOException if fixture access or archive processing fails
	 */
	@Test
	public void writesTarGzipMetadata() throws IOException
	{
		try (Fixture fixture = Fixture.create())
		{
			Path output = fixture.root.resolve("package.tar.gz");
			ArchiveOptions options = new ArchiveOptions(OptionalLong.of(1_234_567_890), OptionalLong.of(1_234_567_891),
				Map.of());
			PackageArchives.write(fixture.request(output, false, options), CLOCK, command ->
			{
				throw new AssertionError("A gzip archive does not invoke zstd");
			});
			byte[] gzip = Files.readAllBytes(output);
			assertEquals(ByteBuffer.wrap(gzip, 4, 4).order(ByteOrder.LITTLE_ENDIAN).getInt(), 1_234_567_891);
			assertNotEquals(gzip[3] & 8, 0);
			assertEquals(new String(gzip, 10, "package.tar".length(), StandardCharsets.ISO_8859_1), "package.tar");
			try (GZIPInputStream archive = new GZIPInputStream(new ByteArrayInputStream(gzip)))
			{
				String tar = new String(archive.readAllBytes(), StandardCharsets.ISO_8859_1);
				assertFalse(tar.contains("atime="));
				assertFalse(tar.contains("ctime="));
				assertFalse(tar.contains("LIBARCHIVE.creationtime="));
			}
			List<String> names = new ArrayList<>();
			try (TarArchiveInputStream archive = new TarArchiveInputStream(new GZIPInputStream(
				new ByteArrayInputStream(gzip))))
			{
				while (true)
				{
					TarArchiveEntry entry = archive.getNextEntry();
					if (entry == null)
						break;
					names.add(entry.getName());
					assertEquals(entry.getLastModifiedTime().toInstant().getEpochSecond(), 1_234_567_890L);
					if (entry.isFile())
						assertEquals(archive.readAllBytes(), fixture.payload);
				}
			}
			assertEquals(names, List.of("nested/", "nested.bin", "nested/entry"));
		}
	}

	/**
	 * Retains the exact supplied PAX timestamp text instead of converting it through a floating-point value.
	 *
	 * @throws IOException if fixture access or archive processing fails
	 */
	@Test
	public void preservesPaxTimestampText() throws IOException
	{
		try (Fixture fixture = Fixture.create())
		{
			String timestamp = "1234567890.12345678901234567890";
			Path output = fixture.root.resolve("package.tgz");
			ArchiveOptions options = new ArchiveOptions(OptionalLong.of(1), OptionalLong.empty(),
				Map.of("nested/entry", timestamp));
			PackageArchives.write(fixture.request(output, false, options), CLOCK, command ->
			{
				throw new AssertionError("A gzip archive does not invoke zstd");
			});
			byte[] gzip = Files.readAllBytes(output);
			assertEquals(ByteBuffer.wrap(gzip, 4, 4).order(ByteOrder.LITTLE_ENDIAN).getInt(), 1_234_567_899);
			try (GZIPInputStream archive = new GZIPInputStream(new ByteArrayInputStream(gzip)))
			{
				String tar = new String(archive.readAllBytes(), StandardCharsets.ISO_8859_1);
				assertTrue(tar.contains("mtime=" + timestamp + "\n"));
			}
		}
	}

	/**
	 * Preserves ZIP payloads, member order, and source timestamps independently of tar-specific overrides.
	 *
	 * @throws IOException if fixture access or archive processing fails
	 */
	@Test
	public void writesZipContents() throws IOException
	{
		try (Fixture fixture = Fixture.create())
		{
			Path output = fixture.root.resolve("package.zip");
			PackageArchives.write(fixture.request(output, false, new ArchiveOptions(OptionalLong.of(1),
				OptionalLong.empty(), Map.of())), CLOCK, command ->
			{
				throw new AssertionError("A ZIP archive does not invoke zstd");
			});
			List<String> names = new ArrayList<>();
			try (ZipInputStream archive = new ZipInputStream(Files.newInputStream(output)))
			{
				while (true)
				{
					java.util.zip.ZipEntry entry = archive.getNextEntry();
					if (entry == null)
						break;
					names.add(entry.getName());
					if (!entry.isDirectory())
					{
						assertEquals(archive.readAllBytes(), fixture.payload);
						assertEquals(entry.getLastModifiedTime(), fixture.modified);
					}
				}
			}
			assertEquals(names, List.of("nested/", "nested.bin", "nested/entry"));
		}
	}

	/**
	 * Keeps compression input alive for the command and removes it on success and failure.
	 *
	 * @throws IOException if fixture access or archive processing fails
	 */
	@Test
	public void cleansZstdTemporaryInput() throws IOException
	{
		try (Fixture fixture = Fixture.create())
		{
			Path output = fixture.root.resolve("package.tar.zst");
			PackageArchives.Request request = fixture.request(output, false, ArchiveOptions.defaults());
			PackageArchives.write(request, CLOCK, command ->
			{
				assertEquals(command.subList(0, 5), List.of("dotslash", "zstd-manifest", "-T0", "-19", "-f"));
				assertEquals(command.subList(6, 8), List.of("-o", output.toString()));
				Path input = Path.of(command.get(5));
				assertTrue(input.startsWith(fixture.temporary));
				assertTrue(Files.isRegularFile(input));
				Files.copy(input, output);
				return "";
			});
			fixture.assertTemporaryEmpty();
			PackageArchives.Request failing = fixture.request(output, true, ArchiveOptions.defaults());
			expectThrows(IOException.class, () -> PackageArchives.write(failing, CLOCK, command ->
			{
				assertTrue(Files.isRegularFile(Path.of(command.get(5))));
				throw new IOException("Compression failed");
			}));
			fixture.assertTemporaryEmpty();
			assertFalse(Files.exists(output));
		}
	}

	/**
	 * Rejects output within the package and replaces an existing archive only when explicitly requested.
	 *
	 * @throws IOException if fixture access or archive processing fails
	 */
	@Test
	public void protectsOutputPaths() throws IOException
	{
		try (Fixture fixture = Fixture.create())
		{
			Path inside = fixture.directory.resolve("archive.zip");
			expectThrows(IOException.class, () -> PackageArchives.write(fixture.request(inside, true,
				ArchiveOptions.defaults()), CLOCK, command -> ""));
			assertFalse(Files.exists(inside));
			Path output = Files.writeString(fixture.root.resolve("archive.zip"), "existing");
			expectThrows(IOException.class, () -> PackageArchives.write(fixture.request(output, false,
				ArchiveOptions.defaults()), CLOCK, command -> ""));
			assertEquals(Files.readString(output), "existing");
			PackageArchives.write(fixture.request(output, true, ArchiveOptions.defaults()), CLOCK, command -> "");
			assertTrue(Files.size(output) > 0);
			Path unsupported = Files.writeString(fixture.root.resolve("archive.TAR.GZ"), "existing");
			expectThrows(IOException.class, () -> PackageArchives.write(fixture.request(unsupported, true,
				ArchiveOptions.defaults()), CLOCK, command -> ""));
			assertFalse(Files.exists(unsupported));
			Path existingDirectory = Files.createDirectory(fixture.root.resolve("existing-directory.zip"));
			expectThrows(IOException.class, () -> PackageArchives.write(fixture.request(existingDirectory, true,
				ArchiveOptions.defaults()), CLOCK, command -> ""));
			assertTrue(Files.isDirectory(existingDirectory));
		}
	}

	/**
	 * Owns archive input files, outputs, and compressor temporary storage.
	 */
	private static final class Fixture implements AutoCloseable
	{
		private final Path root;
		private final Path directory;
		private final Path temporary;
		private final byte[] payload = {0, (byte) 255, 1};
		private final FileTime modified = FileTime.from(Instant.parse("2001-01-02T03:04:06Z"));

		/**
		 * Initializes package inputs under an owned directory.
		 *
		 * @param root the owned fixture directory
		 * @throws IOException if initialization fails
		 */
		private Fixture(Path root) throws IOException
		{
			this.root = root;
			directory = Files.createDirectory(root.resolve("package"));
			temporary = Files.createDirectory(root.resolve("temporary"));
			Files.createDirectory(directory.resolve("nested"));
			for (String name : List.of("nested.bin", "nested/entry"))
			{
				Path file = Files.write(directory.resolve(name), payload);
				Files.setLastModifiedTime(file, modified);
			}
		}

		/**
		 * Allocates a fixture and removes partial initialization on failure.
		 *
		 * @return the initialized fixture
		 * @throws IOException if initialization fails
		 */
		private static Fixture create() throws IOException
		{
			Path root = Files.createTempDirectory("package-archives-");
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
		 * Creates an archive request with explicit compression boundaries.
		 *
		 * @param output the archive output path
		 * @param force whether replacement is authorized
		 * @param options the archive timestamp options
		 * @return the archive request
		 */
		private PackageArchives.Request request(Path output, boolean force, ArchiveOptions options)
		{
			return new PackageArchives.Request(directory, output, force, temporary, options,
				List.of("dotslash", "zstd-manifest"));
		}

		/**
		 * Verifies compressor input cleanup.
		 *
		 * @throws IOException if temporary storage cannot be listed
		 */
		private void assertTemporaryEmpty() throws IOException
		{
			try (Stream<Path> paths = Files.list(temporary))
			{
				assertEquals(paths.count(), 0L);
			}
		}

		/**
		 * Removes all owned fixture files.
		 *
		 * @throws IOException if cleanup fails
		 */
		@Override
		public void close() throws IOException
		{
			delete(root);
		}

		/**
		 * Deletes a fixture tree without following directory symlinks.
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
