package com.holdmyspot.codexunleashed.tooling.release;

import com.holdmyspot.codexunleashed.tooling.PayloadFiles;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.zip.GZIPInputStream;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.io.input.BoundedInputStream;

/**
 * Extracts release package tar.gz archives inside a caller-owned directory while retaining executable attributes.
 */
public final class NpmArchiveExtractor
{
	private static final Map<PosixFilePermission, Integer> PERMISSION_BITS = Map.of(
		PosixFilePermission.OWNER_READ, 0400,
		PosixFilePermission.OWNER_WRITE, 0200,
		PosixFilePermission.OWNER_EXECUTE, 0100,
		PosixFilePermission.GROUP_READ, 0040,
		PosixFilePermission.GROUP_WRITE, 0020,
		PosixFilePermission.GROUP_EXECUTE, 0010,
		PosixFilePermission.OTHERS_READ, 0004,
		PosixFilePermission.OTHERS_WRITE, 0002,
		PosixFilePermission.OTHERS_EXECUTE, 0001);

	/**
	 * Prevents construction.
	 */
	private NpmArchiveExtractor()
	{
	}

	/**
	 * Validates every archive member before extracting files, directories, and confined links in archive order.
	 *
	 * @param archive the release package tar.gz file
	 * @param destination the caller-owned extraction directory
	 * @param temporaryDirectory the caller-owned parent for an automatically removed tar spool file
	 * @throws NullPointerException if an argument is null
	 * @throws IOException if decompression, validation, extraction, attribute restoration, or cleanup fails
	 */
	public static void extract(Path archive, Path destination, Path temporaryDirectory) throws IOException
	{
		Objects.requireNonNull(archive, "archive");
		Objects.requireNonNull(destination, "destination");
		Objects.requireNonNull(temporaryDirectory, "temporaryDirectory");
		Path root = Files.createDirectories(destination).toRealPath();
		Files.createDirectories(temporaryDirectory);
		try (ResourceTemporaryFile temporary =
			new ResourceTemporaryFile(Files.createTempFile(temporaryDirectory, ".npm-package-", ".tar")))
		{
			try (InputStream source = Files.newInputStream(archive); InputStream input = new GZIPInputStream(source);
				OutputStream output = Files.newOutputStream(temporary.path()))
			{
				input.transferTo(output);
			}
			List<TarMemberNames.Payload> payloads = readMetadata(temporary.path());
			List<TarArchiveEntry> entries = payloads.stream().map(TarMemberNames.Payload::entry).toList();
			List<Member> members = validate(entries, TarMemberNames.read(temporary.path(), payloads), root);
			try (InputStream source = Files.newInputStream(temporary.path());
				PackageTarInputStream tar = new PackageTarInputStream(source))
			{
				for (Member member : members)
				{
					if (tar.getNextEntry() == null)
						throw new IOException("Package archive ended before its validated member list");
					extractMember(tar, member, root);
				}
				List<Member> directories = members.stream().filter(member -> member.entry().isDirectory()).
					sorted(Comparator.comparingInt((Member member) -> member.destination().getNameCount()).reversed()).toList();
				for (Member member : directories)
					restoreAttributes(member);
			}
		}
	}

	/**
	 * Indexes metadata with the sequential sparse-capable reader and records physical payload boundaries.
	 *
	 * @param archive the owned uncompressed tar file
	 * @return ordered metadata and physical payload spans
	 * @throws IOException if parsing, payload skipping, or input ownership fails
	 */
	private static List<TarMemberNames.Payload> readMetadata(Path archive) throws IOException
	{
		List<TarMemberNames.Payload> result = new ArrayList<>();
		try (InputStream source = Files.newInputStream(archive);
			BoundedInputStream counted = BoundedInputStream.builder().setInputStream(source).get();
			PackageTarInputStream tar = new PackageTarInputStream(counted))
		{
			for (TarArchiveEntry entry = tar.getNextEntry(); entry != null; entry = tar.getNextEntry())
			{
				long offset = counted.getCount();
				tar.skipNBytes(entry.getRealSize());
				result.add(new TarMemberNames.Payload(entry, offset, counted.getCount() - offset));
			}
		}
		return List.copyOf(result);
	}

	/**
	 * Resolves all destination and link paths before making archive-directed filesystem changes.
	 *
	 * @param entries the ordered archive metadata
	 * @param names the original naming fields paired with those entries
	 * @param root the canonical destination root
	 * @return validated ordered members
	 * @throws IOException if a member type or path is unsupported or escapes the destination
	 */
	private static List<Member> validate(List<TarArchiveEntry> entries, List<TarMemberNames.Names> names, Path root)
		throws IOException
	{
		List<Member> result = new ArrayList<>();
		for (int index = 0; index < entries.size(); ++index)
		{
			TarArchiveEntry entry = entries.get(index);
			if (!entry.isFile() && !entry.isSparse() && !entry.isDirectory() && !entry.isSymbolicLink() && !entry.isLink())
				throw new IOException("Unsupported package archive member type: " + entry.getName());
			TarMemberNames.Names original = names.get(index);
			Path destination = root.resolve(original.name()).normalize();
			validatePath(destination, root, original.name());
			Path linkTarget = null;
			if (entry.isSymbolicLink())
				linkTarget = destination.getParent().resolve(original.link()).normalize();
			else if (entry.isLink())
				linkTarget = root.resolve(original.link()).normalize();
			if (linkTarget != null)
				validatePath(linkTarget, root, entry.getName() + " -> " + entry.getLinkName());
			result.add(new Member(entry, destination, linkTarget));
		}
		return List.copyOf(result);
	}

	/**
	 * Rejects lexical escape and existing symbolic-link ancestors that resolve outside the destination.
	 *
	 * @param destination the normalized candidate path
	 * @param root the canonical destination root
	 * @param name the diagnostic member spelling
	 * @throws IOException if the candidate escapes or an existing ancestor cannot be resolved
	 */
	private static void validatePath(Path destination, Path root, String name) throws IOException
	{
		if (!destination.startsWith(root))
			throw new IOException("Unsafe archive member: " + name);
		Path ancestor = destination;
		while (!Files.exists(ancestor, LinkOption.NOFOLLOW_LINKS))
			ancestor = ancestor.getParent();
		if (!ancestor.toRealPath().startsWith(root))
			throw new IOException("Unsafe archive member: " + name);
	}

	/**
	 * Applies an ordered member and repeats confinement checks after earlier archive links become visible.
	 *
	 * @param tar the current member's sequential payload reader
	 * @param member the validated member
	 * @param root the canonical destination root
	 * @throws IOException if filesystem changes, payload copying, or attributes fail
	 */
	private static void extractMember(InputStream tar, Member member, Path root) throws IOException
	{
		TarArchiveEntry entry = member.entry();
		Path destination = member.destination();
		validatePath(destination, root, entry.getName());
		if (entry.isDirectory())
		{
			Files.createDirectories(destination);
			return;
		}
		Files.createDirectories(destination.getParent());
		validatePath(destination, root, entry.getName());
		if (entry.isSymbolicLink())
		{
			validatePath(member.linkTarget(), root, entry.getLinkName());
			PayloadFiles.delete(destination);
			Files.createSymbolicLink(destination, Path.of(entry.getLinkName()));
			return;
		}
		if (entry.isLink())
		{
			validatePath(member.linkTarget(), root, entry.getLinkName());
			PayloadFiles.delete(destination);
			Files.createLink(destination, member.linkTarget());
		}
		else
		{
			try (OutputStream output = Files.newOutputStream(destination))
			{
				tar.transferTo(output);
			}
		}
		restoreAttributes(member);
	}

	/**
	 * Restores modification times and supported ordinary permission bits without importing archive ownership.
	 *
	 * @param member the extracted ordinary file, hard link, or directory
	 * @throws IOException if attribute restoration fails
	 */
	private static void restoreAttributes(Member member) throws IOException
	{
		Files.setLastModifiedTime(member.destination(), member.entry().getLastModifiedTime());
		if (Files.getFileAttributeView(member.destination(), PosixFileAttributeView.class) != null)
		{
			Set<PosixFilePermission> permissions = EnumSet.noneOf(PosixFilePermission.class);
			for (Map.Entry<PosixFilePermission, Integer> bit : PERMISSION_BITS.entrySet())
			{
				if ((member.entry().getMode() & bit.getValue()) != 0)
					permissions.add(bit.getKey());
			}
			Files.setPosixFilePermissions(member.destination(), permissions);
		}
	}

	/**
	 * Associates original tar metadata with destination paths inside the selected extraction root.
	 *
	 * @param entry the original tar entry
	 * @param destination the normalized output path
	 * @param linkTarget the normalized link target, or null for an ordinary member
	 */
	private record Member(TarArchiveEntry entry, Path destination, Path linkTarget)
	{
	}
}
