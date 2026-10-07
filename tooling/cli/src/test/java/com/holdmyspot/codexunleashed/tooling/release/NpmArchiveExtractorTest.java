package com.holdmyspot.codexunleashed.tooling.release;

import com.holdmyspot.codexunleashed.tooling.SystemCommands;
import com.holdmyspot.codexunleashed.tooling.Sha256;
import java.io.IOException;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Instant;
import java.util.Comparator;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import java.util.zip.GZIPOutputStream;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.apache.commons.compress.archivers.tar.TarConstants;
import org.testng.annotations.Test;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertTrue;
import static org.testng.Assert.expectThrows;

/**
 * Verifies complete package archive extraction, retained payload attributes, and destination confinement.
 */
public final class NpmArchiveExtractorTest
{
	/**
	 * Creates extraction tests.
	 */
	public NpmArchiveExtractorTest()
	{
	}

	/**
	 * Extracts actual GNU tar sparse archives in old GNU and all retained PAX sparse versions.
	 *
	 * @throws IOException if fixture access, tar execution, extraction, or cleanup fails
	 */
	@Test
	public void extractsSparseTarPayloads() throws IOException
	{
		Path root = Files.createTempDirectory("npm-extractor-sparse-");
		try
		{
			Path source = Files.createDirectory(root.resolve("source"));
			String name = "bin/" + "é".repeat(90) + "-codex";
			Path binary = source.resolve(name);
			Files.createDirectories(binary.getParent());
			try (FileChannel channel = FileChannel.open(binary, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE))
			{
				for (int index = 0; index < 7; ++index)
				{
					channel.position(index * 8192L);
					channel.write(ByteBuffer.wrap(new byte[]{(byte) 255}));
				}
				channel.force(true);
			}
			byte[] expected = Files.readAllBytes(binary);
			for (List<String> format : List.of(List.of("--format=gnu"),
				List.of("--format=posix", "--sparse-version=0.0"), List.of("--format=posix", "--sparse-version=0.1"),
				List.of("--format=posix", "--sparse-version=1.0")))
			{
				Path archive = root.resolve("sparse.tar.gz");
				List<String> command = new ArrayList<>(List.of("tar", "--sparse"));
				command.addAll(format);
				command.addAll(List.of("-czf", archive.toString(), "-C", source.toString(), name));
				SystemCommands.Result generated = SystemCommands.capture(command, root, root);
				assertEquals(generated.status(), 0, generated.stderr());
				SystemCommands.DigestResult nativeResult = SystemCommands.digest(
					List.of("tar", "-xOf", archive.toString(), name), root, root, Map.of());
				assertEquals(nativeResult.status(), 0, nativeResult.stderr());
				assertEquals(nativeResult.stdoutSha256(), Sha256.digest(binary), format.toString());
				Path destination = Files.createTempDirectory(root, "package-");
				NpmArchiveExtractor.extract(archive, destination, root.resolve("spool"));
				assertEquals(Files.readAllBytes(destination.resolve(name)), expected, format.toString());
				assertEmpty(root.resolve("spool"));
			}
		}
		finally
		{
			delete(root);
		}
	}

	/**
	 * Uses the real sparse name when a producer's placeholder ends inside a UTF-8 character.
	 *
	 * @throws IOException if fixture creation, extraction or cleanup fails
	 */
	@Test
	public void validatesSelectedSparseName() throws IOException
	{
		Path root = Files.createTempDirectory("npm-extractor-sparse-name-");
		try
		{
			byte[] malformed = {(byte) 195};
			Path archive = sparseNameFixture(root, malformed, "safe".getBytes(StandardCharsets.UTF_8));
			Path output = root.resolve("valid");
			NpmArchiveExtractor.extract(archive, output, root.resolve("spool"));
			assertEquals(Files.readAllBytes(output.resolve("safe")), new byte[]{(byte) 255});
			for (byte[] invalid : List.of(malformed, "../outside".getBytes(StandardCharsets.UTF_8)))
			{
				Path rejected = root.resolve("rejected");
				Path unsafe = sparseNameFixture(root, "placeholder".getBytes(StandardCharsets.UTF_8), invalid);
				expectThrows(IOException.class, () -> NpmArchiveExtractor.extract(unsafe, rejected, root.resolve("spool")));
				assertFalse(Files.exists(root.resolve("outside")));
				assertEmpty(root.resolve("spool"));
			}
			byte[] naming = paxRecord("path", malformed);
			TarArchiveEntry header = new TarArchiveEntry("metadata", TarConstants.LF_PAX_EXTENDED_HEADER_LC);
			header.setSize(naming.length);
			Path ordinary = write(root, List.of(new Member(header, naming), regular("placeholder", new byte[]{1})));
			Path rejected = root.resolve("ordinary-rejected");
			expectThrows(IOException.class, () -> NpmArchiveExtractor.extract(ordinary, rejected, root.resolve("spool")));
			assertFalse(Files.exists(rejected.resolve("placeholder")));
			assertEmpty(root.resolve("spool"));
		}
		finally
		{
			delete(root);
		}
	}

	/**
	 * Writes a PAX sparse-one fixture with independently controlled raw naming bytes.
	 *
	 * @param root the owned fixture directory
	 * @param placeholder the PAX placeholder name
	 * @param realName the authoritative sparse name
	 * @return the fixture archive
	 * @throws IOException if archive writing fails
	 */
	private static Path sparseNameFixture(Path root, byte[] placeholder, byte[] realName) throws IOException
	{
		ByteArrayOutputStream metadata = new ByteArrayOutputStream();
		metadata.writeBytes(paxRecord("GNU.sparse.major", new byte[]{'1'}));
		metadata.writeBytes(paxRecord("GNU.sparse.minor", new byte[]{'0'}));
		metadata.writeBytes(paxRecord("GNU.sparse.realsize", new byte[]{'1'}));
		metadata.writeBytes(paxRecord("GNU.sparse.name", realName));
		metadata.writeBytes(paxRecord("path", placeholder));
		TarArchiveEntry header = new TarArchiveEntry("metadata", TarConstants.LF_PAX_EXTENDED_HEADER_LC);
		header.setSize(metadata.size());
		byte[] payload = new byte[TarConstants.DEFAULT_RCDSIZE + 1];
		byte[] map = "1\n0\n1\n".getBytes(StandardCharsets.US_ASCII);
		System.arraycopy(map, 0, payload, 0, map.length);
		payload[TarConstants.DEFAULT_RCDSIZE] = (byte) 255;
		return write(root, List.of(new Member(header, metadata.toByteArray()), regular("placeholder", payload)));
	}

	/**
	 * Frames one PAX key/value record by its complete byte length.
	 *
	 * @param key the ASCII field name
	 * @param value independently controlled raw bytes
	 * @return the complete length-prefixed record
	 */
	private static byte[] paxRecord(String key, byte[] value)
	{
		ByteArrayOutputStream body = new ByteArrayOutputStream();
		body.writeBytes((key + "=").getBytes(StandardCharsets.US_ASCII));
		body.writeBytes(value);
		body.write('\n');
		int length = body.size() + 3;
		int calculated = body.size() + Integer.toString(length).length() + 1;
		while (calculated != length)
		{
			length = calculated;
			calculated = body.size() + Integer.toString(length).length() + 1;
		}
		ByteArrayOutputStream result = new ByteArrayOutputStream();
		result.writeBytes((length + " ").getBytes(StandardCharsets.US_ASCII));
		result.writeBytes(body.toByteArray());
		return result.toByteArray();
	}

	/**
	 * Copies duplicate payloads, long Unicode paths, and directory attributes without retaining spool files.
	 *
	 * @throws IOException if fixture access or extraction fails
	 */
	@Test
	public void extractsPayloadsAndAttributes() throws IOException
	{
		Path root = Files.createTempDirectory("npm-extractor-");
		try
		{
			byte[] payload = {0, (byte) 255, 2};
			FileTime modified = FileTime.from(Instant.parse("2023-11-14T22:13:20Z"));
			TarArchiveEntry directory = new TarArchiveEntry("bin/");
			directory.setLastModifiedTime(modified);
			directory.setMode(0750);
			Member replacement = regular("bin/codex", payload);
			replacement.entry().setLastModifiedTime(modified);
			replacement.entry().setMode(0755);
			String longName = "licenses/" + "é".repeat(90) + "/LICENSE";
			Path archive = write(root, List.of(new Member(directory, new byte[0]), regular("bin/codex", new byte[]{1}),
				replacement, regular(longName, payload)));
			Path output = root.resolve("package");
			Path temporary = root.resolve("spool");
			NpmArchiveExtractor.extract(archive, output, temporary);
			assertEquals(Files.readAllBytes(output.resolve("bin/codex")), payload);
			assertEquals(Files.readAllBytes(output.resolve(longName)), payload);
			assertEquals(Files.getLastModifiedTime(output.resolve("bin/codex")), modified);
			assertEquals(Files.getLastModifiedTime(output.resolve("bin")), modified);
			if (Files.getFileAttributeView(output, PosixFileAttributeView.class) != null)
			{
				assertEquals(Files.getPosixFilePermissions(output.resolve("bin/codex")),
					PosixFilePermissions.fromString("rwxr-xr-x"));
				assertEquals(Files.getPosixFilePermissions(output.resolve("bin")),
					PosixFilePermissions.fromString("rwxr-x---"));
			}
			assertEmpty(temporary);
		}
		finally
		{
			delete(root);
		}
	}

	/**
	 * Rejects unsafe names and link targets before copying an earlier ordinary payload.
	 *
	 * @throws IOException if fixture access or cleanup fails
	 */
	@Test
	public void validatesAllPathsBeforeWriting() throws IOException
	{
		Path root = Files.createTempDirectory("npm-extractor-unsafe-");
		try
		{
			Path outside = Files.writeString(root.resolve("outside"), "original");
			for (Member invalid : List.of(regular("../outside", new byte[]{1}), regular(outside.toString(), new byte[]{1}),
				link("escape", "../outside", TarConstants.LF_SYMLINK),
				link("escape", "../outside", TarConstants.LF_LINK)))
			{
				Path archive = write(root, List.of(regular("safe", new byte[]{2}), invalid));
				Path output = root.resolve("package");
				Path temporary = root.resolve("spool");
				expectThrows("Unsafe member must reject: " + invalid.entry().getName(), IOException.class,
					() -> NpmArchiveExtractor.extract(archive, output, temporary));
				assertFalse(Files.exists(output.resolve("safe")));
				assertEquals(Files.readString(outside), "original");
				assertEmpty(temporary);
			}
			Path archive = Files.writeString(root.resolve("invalid.tar.gz"), "not a gzip stream");
			expectThrows(IOException.class, () -> NpmArchiveExtractor.extract(archive, root.resolve("invalid"),
				root.resolve("spool")));
			assertClosedSource(archive);
			assertEmpty(root.resolve("spool"));
		}
		finally
		{
			delete(root);
		}
	}

	/**
	 * Preserves safe symbolic and hard links, and refuses writes through existing escaping directory links.
	 *
	 * @throws IOException if fixture access or extraction fails
	 */
	@Test
	public void confinesLinksToDestination() throws IOException
	{
		Path root = Files.createTempDirectory("npm-extractor-links-");
		try
		{
			byte[] payload = {0, (byte) 255, 3};
			Path archive = write(root, List.of(regular("bin/codex", payload),
				link("hard", "bin/codex", TarConstants.LF_LINK), link("bin/alias", "codex", TarConstants.LF_SYMLINK)));
			Path output = root.resolve("package");
			NpmArchiveExtractor.extract(archive, output, root.resolve("spool"));
			assertEquals(Files.readAllBytes(output.resolve("hard")), payload);
			assertEquals(Files.readAllBytes(output.resolve("bin/alias")), payload);
			assertTrue(Files.isSymbolicLink(output.resolve("bin/alias")));
			assertTrue(Files.isSameFile(output.resolve("hard"), output.resolve("bin/codex")));
			Path outside = Files.createDirectory(root.resolve("outside"));
			Files.createSymbolicLink(output.resolve("escape"), outside);
			Path escaping = write(root, List.of(regular("escape/payload", new byte[]{1})));
			expectThrows(IOException.class, () -> NpmArchiveExtractor.extract(escaping, output, root.resolve("spool")));
			assertFalse(Files.exists(outside.resolve("payload")));
			assertEmpty(root.resolve("spool"));
		}
		finally
		{
			delete(root);
		}
	}

	/**
	 * Writes a gzip tar fixture using independent archive construction.
	 *
	 * @param root the fixture root
	 * @param members the ordered members
	 * @return the archive path
	 * @throws IOException if archive writing fails
	 */
	private static Path write(Path root, List<Member> members) throws IOException
	{
		return write(root, members, TarArchiveOutputStream.LONGFILE_POSIX);
	}

	/**
	 * Writes independently constructed entries with the selected extended-name format.
	 *
	 * @param root the fixture root
	 * @param members the ordered members
	 * @param longFileMode the Commons Compress extended-name mode
	 * @return the archive path
	 * @throws IOException if archive writing fails
	 */
	private static Path write(Path root, List<Member> members, int longFileMode) throws IOException
	{
		Path archive = root.resolve("source.tar.gz");
		try (TarArchiveOutputStream output = new TarArchiveOutputStream(
			new GZIPOutputStream(Files.newOutputStream(archive))))
		{
			output.setLongFileMode(longFileMode);
			for (Member member : members)
			{
				output.putArchiveEntry(member.entry());
				output.write(member.payload());
				output.closeArchiveEntry();
			}
		}
		return archive;
	}

	/**
	 * Preserves GNU extended names and link targets while rejecting an absolute name before extraction.
	 *
	 * @throws IOException if fixture access, extraction, or cleanup fails
	 */
	@Test
	public void preservesGnuExtendedNames() throws IOException
	{
		Path root = Files.createTempDirectory("npm-extractor-gnu-");
		try
		{
			byte[] payload = {0, (byte) 255, 4};
			String name = "licenses/" + "x".repeat(130) + "/LICENSE";
			Path archive = write(root, List.of(regular(name, payload),
				link("alias", name, TarConstants.LF_SYMLINK)), TarArchiveOutputStream.LONGFILE_GNU);
			Path output = root.resolve("package");
			NpmArchiveExtractor.extract(archive, output, root.resolve("spool"));
			assertEquals(Files.readAllBytes(output.resolve(name)), payload);
			assertEquals(Files.readAllBytes(output.resolve("alias")), payload);
			assertEquals(Files.readSymbolicLink(output.resolve("alias")).toString(), name);
			Path invalid = write(root, List.of(regular("safe", payload),
				regular(root.resolve("outside").toString(), payload)), TarArchiveOutputStream.LONGFILE_GNU);
			Path rejected = root.resolve("rejected");
			expectThrows(IOException.class, () -> NpmArchiveExtractor.extract(invalid, rejected, root.resolve("spool")));
			assertFalse(Files.exists(rejected.resolve("safe")));
			assertFalse(Files.exists(root.resolve("outside")));
			assertEmpty(root.resolve("spool"));
		}
		finally
		{
			delete(root);
		}
	}

	/**
	 * Creates a regular member with exact name and raw payload bytes.
	 *
	 * @param name the archive name, retaining an absolute spelling for rejection tests
	 * @param payload the member bytes
	 * @return the fixture member
	 */
	private static Member regular(String name, byte[] payload)
	{
		TarArchiveEntry entry = new TarArchiveEntry(name, true);
		entry.setSize(payload.length);
		return new Member(entry, payload);
	}

	/**
	 * Creates a payload-free link member.
	 *
	 * @param name the link name
	 * @param target the link target
	 * @param type the tar link type
	 * @return the fixture member
	 */
	private static Member link(String name, String target, byte type)
	{
		TarArchiveEntry entry = new TarArchiveEntry(name, type);
		entry.setLinkName(target);
		return new Member(entry, new byte[0]);
	}

	/**
	 * Verifies spool cleanup at the caller-owned temporary root.
	 *
	 * @param root the temporary root
	 * @throws IOException if listing fails
	 */
	private static void assertEmpty(Path root) throws IOException
	{
		try (Stream<Path> paths = Files.list(root))
		{
			assertEquals(paths.count(), 0L);
		}
	}

	/**
	 * Checks Linux's descriptor boundary for an unclosed source after gzip construction fails.
	 * Fixture deletion also exercises the source-close requirement on platforms that disallow deleting open files.
	 *
	 * @param source the malformed archive file
	 * @throws IOException if available descriptor metadata cannot be inspected
	 */
	private static void assertClosedSource(Path source) throws IOException
	{
		Path descriptors = Path.of("/proc/self/fd");
		if (!Files.isDirectory(descriptors))
			return;
		Path canonical = source.toRealPath();
		List<Path> paths;
		try (Stream<Path> stream = Files.list(descriptors))
		{
			paths = stream.toList();
		}
		for (Path descriptor : paths)
		{
			Path target;
			try
			{
				target = Files.readSymbolicLink(descriptor);
			}
			catch (NoSuchFileException _)
			{
				continue;
			}
			assertFalse(target.equals(canonical), "Malformed archive source remains open");
		}
	}

	/**
	 * Deletes owned fixture paths without following directory links.
	 *
	 * @param root the fixture root
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

	/**
	 * Associates archive metadata with independent fixture payload bytes.
	 *
	 * @param entry the tar metadata
	 * @param payload the raw bytes
	 */
	private record Member(TarArchiveEntry entry, byte[] payload)
	{
	}
}
