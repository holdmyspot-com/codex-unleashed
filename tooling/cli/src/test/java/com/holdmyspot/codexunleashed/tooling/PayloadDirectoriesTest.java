package com.holdmyspot.codexunleashed.tooling;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Instant;
import java.util.Comparator;
import java.util.stream.Stream;
import org.testng.annotations.Test;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.expectThrows;

/**
 * Verifies raw tree copying, source-link following, attributes, and new-destination ownership.
 */
public final class PayloadDirectoriesTest
{
	/**
	 * Creates payload tree tests.
	 */
	public PayloadDirectoriesTest()
	{
	}

	/**
	 * Copies raw bytes through source links and restores directory attributes after population.
	 *
	 * @throws IOException if fixture access, copying, or cleanup fails
	 */
	@Test
	public void copiesTreesAndSourceLinks() throws IOException
	{
		Path root = Files.createTempDirectory("payload-directory-");
		try
		{
			Path source = Files.createDirectory(root.resolve("source"));
			Path external = Files.createDirectory(root.resolve("external"));
			byte[] payload = {0, (byte) 255, 1};
			Path file = Files.write(external.resolve("payload"), payload);
			Files.createSymbolicLink(source.resolve("file"), file);
			Files.createSymbolicLink(source.resolve("directory"), external);
			FileTime modified = FileTime.from(Instant.parse("2023-11-14T22:13:20Z"));
			Files.setLastModifiedTime(source, modified);
			Files.setLastModifiedTime(external, modified);
			Files.setLastModifiedTime(file, modified);
			if (Files.getFileAttributeView(source, PosixFileAttributeView.class) != null)
				Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rwxr-x---"));
			Path output = root.resolve("output");
			PayloadDirectories.copy(source, output);
			assertEquals(Files.readAllBytes(output.resolve("file")), payload);
			assertEquals(Files.readAllBytes(output.resolve("directory/payload")), payload);
			assertFalse(Files.isSymbolicLink(output.resolve("file")));
			assertFalse(Files.isSymbolicLink(output.resolve("directory")));
			assertEquals(Files.getLastModifiedTime(output), modified);
			assertEquals(Files.getLastModifiedTime(output.resolve("directory")), modified);
			assertEquals(Files.getLastModifiedTime(output.resolve("file")), modified);
			if (Files.getFileAttributeView(output, PosixFileAttributeView.class) != null)
				assertEquals(Files.getPosixFilePermissions(output.resolve("file")),
					PosixFilePermissions.fromString("rwxr-x---"));
			expectThrows(IOException.class, () -> PayloadDirectories.copy(source, output));
			assertEquals(Files.readAllBytes(output.resolve("file")), payload);
		}
		finally
		{
			delete(root);
		}
	}

	/**
	 * Rejects a non-directory source before creating output and exposes source directory cycles as IO failures.
	 *
	 * @throws IOException if fixture access or cleanup fails
	 */
	@Test
	public void rejectsInvalidSourceAndCycles() throws IOException
	{
		Path root = Files.createTempDirectory("payload-directory-invalid-");
		try
		{
			Path file = Files.writeString(root.resolve("file"), "source");
			Path output = root.resolve("output");
			expectThrows(IOException.class, () -> PayloadDirectories.copy(file, output));
			assertFalse(Files.exists(output));
			Path source = Files.createDirectory(root.resolve("source"));
			Files.createSymbolicLink(source.resolve("cycle"), source);
			expectThrows(IOException.class, () -> PayloadDirectories.copy(source, output));
			try (Stream<Path> children = Files.list(output))
			{
				assertEquals(children.count(), 0L);
			}
		}
		finally
		{
			delete(root);
		}
	}

	/**
	 * Deletes owned fixture paths without following source directory links.
	 *
	 * @param root the fixture root
	 * @throws IOException if deletion fails
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
