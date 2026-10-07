package com.holdmyspot.codexunleashed.tooling.release;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;
import java.util.zip.GZIPOutputStream;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.apache.commons.compress.archivers.tar.TarConstants;
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream;
import org.testng.annotations.Test;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertTrue;
import static org.testng.Assert.expectThrows;

/**
 * Verifies exact archive member selection and link resolution without filesystem extraction.
 */
public final class ArchiveMembersTest
{
	/**
	 * Creates archive member tests.
	 */
	public ArchiveMembersTest()
	{
	}

	/**
	 * Selects the last duplicate and resolves symbolic and preceding hard links while rejecting cycles and directories.
	 *
	 * @throws IOException if fixture archive writing, reading, or cleanup fails
	 */
	@Test
	public void selectsTarMembersAndLinks() throws IOException
	{
		Path root = Files.createTempDirectory("tar-resource-members-");
		try
		{
			byte[] original = {1};
			byte[] replacement = {0, (byte) 255, 2};
			byte[] literalBackslash = {3};
			List<Member> members = List.of(regular("dir/tool", original), link("hard", "dir/tool", TarConstants.LF_LINK),
				regular("dir/tool", replacement), link("dir/symlink", "./tool", TarConstants.LF_SYMLINK),
				regular("dir\\tool", literalBackslash), link("literal", "./dir\\tool", TarConstants.LF_SYMLINK),
				link("cycle-a", "cycle-b", TarConstants.LF_SYMLINK), link("cycle-b", "cycle-a", TarConstants.LF_SYMLINK),
				link("forward", "future", TarConstants.LF_LINK), regular("future", replacement),
				new Member(new TarArchiveEntry("dir/"), new byte[0]));
			Path archive = root.resolve("members.tar.gz");
			try (TarArchiveOutputStream output = new TarArchiveOutputStream(
				new GZIPOutputStream(Files.newOutputStream(archive))))
			{
				for (Member member : members)
				{
					output.putArchiveEntry(member.entry());
					output.write(member.payload());
					output.closeArchiveEntry();
				}
			}
			Path executable = root.resolve("executable");
			ArchiveMembers.copy(archive, "tar.gz", "dir/tool", executable, "fixture");
			assertEquals(Files.readAllBytes(executable), replacement);
			ArchiveMembers.copy(archive, "tar.gz", "hard", executable, "fixture");
			assertEquals(Files.readAllBytes(executable), original);
			ArchiveMembers.copy(archive, "tar.gz", "dir/symlink", executable, "fixture");
			assertEquals(Files.readAllBytes(executable), replacement);
			ArchiveMembers.copy(archive, "tar.gz", "literal", executable, "fixture");
			assertEquals(Files.readAllBytes(executable), literalBackslash);
			for (String member : List.of("cycle-a", "forward", "dir/"))
			{
				expectThrows(IOException.class, () -> ArchiveMembers.copy(archive, "tar.gz", member, executable, "fixture"));
				assertFalse(Files.exists(executable));
				try (Stream<Path> paths = Files.list(root))
				{
					assertFalse(paths.anyMatch(path -> path.getFileName().toString().startsWith(".resource-member-")));
				}
			}
		}
		finally
		{
			delete(root);
		}
	}

	/**
	 * Selects the final duplicate ZIP entry, permits empty directory members, and preserves destination directories.
	 *
	 * @throws IOException if fixture archive writing, reading, or cleanup fails
	 */
	@Test
	public void selectsZipMembersAndPreservesDirectories() throws IOException
	{
		Path root = Files.createTempDirectory("zip-resource-members-");
		try
		{
			byte[] replacement = {0, (byte) 255, 2};
			Path archive = root.resolve("members.zip");
			try (ZipArchiveOutputStream output = new ZipArchiveOutputStream(archive))
			{
				output.putArchiveEntry(new ZipArchiveEntry("tool"));
				output.write(new byte[] {1});
				output.closeArchiveEntry();
				output.putArchiveEntry(new ZipArchiveEntry("tool"));
				output.write(replacement);
				output.closeArchiveEntry();
				output.putArchiveEntry(new ZipArchiveEntry("directory/"));
				output.closeArchiveEntry();
			}
			Path executable = root.resolve("executable");
			ArchiveMembers.copy(archive, "zip", "tool", executable, "fixture");
			assertEquals(Files.readAllBytes(executable), replacement);
			ArchiveMembers.copy(archive, "zip", "directory/", executable, "fixture");
			assertEquals(Files.size(executable), 0L);
			Files.delete(executable);
			Files.createDirectory(executable);
			expectThrows(IOException.class, () -> ArchiveMembers.copy(archive, "zip", "tool", executable, "fixture"));
			assertTrue(Files.isDirectory(executable));
		}
		finally
		{
			delete(root);
		}
	}

	/**
	 * Creates a regular member with explicit raw bytes.
	 *
	 * @param name the archive member name
	 * @param payload the member's raw bytes
	 * @return the fixture member
	 */
	private static Member regular(String name, byte[] payload)
	{
		TarArchiveEntry entry = new TarArchiveEntry(name);
		entry.setSize(payload.length);
		return new Member(entry, payload);
	}

	/**
	 * Creates a link member with no payload bytes.
	 *
	 * @param name the archive member name
	 * @param target the logical target member name
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
	 * Removes fixture paths without following directory symlinks.
	 *
	 * @param root the owned fixture directory
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
	 * Pairs archive metadata with its fixture payload.
	 *
	 * @param entry the archive metadata
	 * @param payload the raw member bytes
	 */
	private record Member(TarArchiveEntry entry, byte[] payload)
	{
	}
}
