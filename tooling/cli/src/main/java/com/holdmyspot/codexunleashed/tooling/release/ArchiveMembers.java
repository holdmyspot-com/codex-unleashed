package com.holdmyspot.codexunleashed.tooling.release;

import com.holdmyspot.codexunleashed.tooling.PayloadFiles;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.zip.GZIPInputStream;
import java.util.zip.CRC32;
import java.util.zip.CheckedInputStream;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarFile;
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipFile;

/**
 * Copies one declared resource member without extracting archive paths onto the filesystem.
 */
public final class ArchiveMembers
{
	/**
	 * Prevents construction.
	 */
	private ArchiveMembers()
	{
	}

	/**
	 * Copies a raw gzip executable or the last named archive member, resolving tar links within archive metadata.
	 *
	 * @param archive the verified archive file
	 * @param format the declared gz, tar.gz, or ZIP format
	 * @param member the exact requested member name, unused for raw gzip
	 * @param destination the executable destination
	 * @param label the resource name used in failures
	 * @throws IOException if member selection, decoding, copying, or temporary cleanup fails
	 * @throws NullPointerException if any argument is null
	 */
	public static void copy(Path archive, String format, String member, Path destination, String label) throws IOException
	{
		Objects.requireNonNull(archive, "archive");
		Objects.requireNonNull(format, "format");
		Objects.requireNonNull(member, "member");
		Objects.requireNonNull(destination, "destination");
		Objects.requireNonNull(label, "label");
		Path output = destination.toAbsolutePath();
		Files.createDirectories(output.getParent());
		PayloadFiles.delete(output);
		switch (format)
		{
			case "gz" ->
			{
				try (InputStream input = new GZIPInputStream(Files.newInputStream(archive));
					OutputStream destinationStream = Files.newOutputStream(output))
				{
					input.transferTo(destinationStream);
				}
			}
			case "tar.gz" -> copyTar(archive, member, output, label);
			case "zip" -> copyZip(archive, member, output, label);
			default -> throw new IOException("Unsupported " + label + " archive format: " + format);
		}
	}

	/**
	 * Copies the last central-directory ZIP entry matching the requested name.
	 *
	 * @param archive the verified archive
	 * @param member the exact member name
	 * @param destination the executable destination
	 * @param label the resource name
	 * @throws IOException if selection, reading, or writing fails
	 */
	private static void copyZip(Path archive, String member, Path destination, String label) throws IOException
	{
		try (ZipFile zip = ZipFile.builder().setPath(archive).get())
		{
			ZipArchiveEntry selected = null;
			for (ZipArchiveEntry entry : zip.getEntries(member))
				selected = entry;
			if (selected == null)
				throw missingMember(archive, member, label);
			CRC32 checksum = new CRC32();
			try (InputStream input = new CheckedInputStream(zip.getInputStream(selected), checksum);
				OutputStream output = Files.newOutputStream(destination))
			{
				input.transferTo(output);
			}
			if (checksum.getValue() != selected.getCrc())
				throw new IOException("Bad CRC-32 for " + label + " archive member '" + member + "'");
		}
	}

	/**
	 * Spools gzip to an owned tar file and copies only the selected member's raw bytes.
	 *
	 * @param archive the verified archive
	 * @param member the exact member name
	 * @param destination the executable destination
	 * @param label the resource name
	 * @throws IOException if decompression, selection, copying, or cleanup fails
	 */
	private static void copyTar(Path archive, String member, Path destination, String label) throws IOException
	{
		try (ResourceTemporaryFile temporary = new ResourceTemporaryFile(Files.createTempFile(destination.getParent(),
			".resource-member-", ".tar")))
		{
			try (InputStream input = new GZIPInputStream(Files.newInputStream(archive));
				OutputStream output = Files.newOutputStream(temporary.path()))
			{
				input.transferTo(output);
			}
			try (TarFile tar = new TarFile(temporary.path()))
			{
				List<TarArchiveEntry> entries = tar.getEntries();
				int index = find(entries, member, entries.size(), false);
				if (index < 0)
					throw missingMember(archive, member, label);
				TarArchiveEntry selected = resolveLinks(entries, index, archive, label);
				if (!selected.isFile())
					throw new IOException(label + " archive member '" + member + "' is not a file");
				try (InputStream input = tar.getInputStream(selected); OutputStream output = Files.newOutputStream(destination))
				{
					input.transferTo(output);
				}
			}
		}
	}

	/**
	 * Resolves symlinks against all members and hardlinks against members preceding the link.
	 *
	 * @param entries the archive's ordered metadata
	 * @param index the requested member index
	 * @param archive the archive path for failures
	 * @param label the resource name
	 * @return the resolved payload member
	 * @throws IOException if a link is cyclic or its target is absent
	 */
	private static TarArchiveEntry resolveLinks(List<TarArchiveEntry> entries, int index, Path archive, String label)
		throws IOException
	{
		Set<Integer> visited = new HashSet<>();
		while (true)
		{
			if (!visited.add(index))
				throw new IOException("Cyclic " + label + " archive member link: " + entries.get(index).getName());
			TarArchiveEntry selected = entries.get(index);
			if (!selected.isLink() && !selected.isSymbolicLink())
				return selected;
			String target = linkTarget(selected);
			int limit = index;
			if (selected.isSymbolicLink())
				limit = entries.size();
			index = find(entries, target, limit, true);
			if (index < 0)
				throw missingMember(archive, target, label);
		}
	}

	/**
	 * Resolves a relative symlink target against its containing archive directory.
	 *
	 * @param entry the link metadata
	 * @return the archive-relative or absolute target name
	 */
	private static String linkTarget(TarArchiveEntry entry)
	{
		String target = entry.getLinkName();
		if (entry.isSymbolicLink() && !target.startsWith("/"))
		{
			int separator = entry.getName().lastIndexOf('/');
			if (separator >= 0)
				return entry.getName().substring(0, separator + 1) + target;
		}
		return target;
	}

	/**
	 * Looks backwards for a named entry, normalizing POSIX components only for link resolution.
	 *
	 * @param entries the ordered archive metadata
	 * @param member the requested member name
	 * @param limit the exclusive upper index
	 * @param normalize whether link lookup normalizes POSIX components
	 * @return the selected index, or minus one when absent
	 */
	private static int find(List<TarArchiveEntry> entries, String member, int limit, boolean normalize)
	{
		String expected = trimSlashes(member);
		if (normalize)
			expected = normalizeName(expected);
		for (int index = limit - 1; index >= 0; --index)
		{
			String actual = trimSlashes(entries.get(index).getName());
			if (normalize)
				actual = normalizeName(actual);
			if (actual.equals(expected))
				return index;
		}
		return -1;
	}

	/**
	 * Trims trailing directory markers without changing exact member-name components.
	 *
	 * @param name the archive member name
	 * @return the name without trailing slashes
	 */
	private static String trimSlashes(String name)
	{
		int end = name.length();
		while (end > 0 && name.charAt(end - 1) == '/')
			--end;
		return name.substring(0, end);
	}

	/**
	 * Normalizes logical POSIX member names while retaining literal backslashes on every host platform.
	 *
	 * @param name the archive member name
	 * @return the normalized logical member name
	 */
	private static String normalizeName(String name)
	{
		String prefix = "";
		if (name.startsWith("//") && !name.startsWith("///"))
			prefix = "//";
		else if (name.startsWith("/"))
			prefix = "/";
		List<String> components = new ArrayList<>();
		for (String component : name.split("/"))
		{
			if (component.isEmpty() || component.equals("."))
				continue;
			if (component.equals(".."))
			{
				if (!components.isEmpty() && !components.getLast().equals(".."))
					components.removeLast();
				else if (prefix.isEmpty())
					components.add(component);
			}
			else
				components.add(component);
		}
		String normalized = prefix + String.join("/", components);
		if (normalized.isEmpty())
			return ".";
		return normalized;
	}

	/**
	 * Describes a missing resource member.
	 *
	 * @param archive the verified archive
	 * @param member the requested member name
	 * @param label the resource name
	 * @return the member-selection failure
	 */
	private static IOException missingMember(Path archive, String member, String label)
	{
		return new IOException(label + " archive " + archive + " is missing '" + member + "'");
	}
}
