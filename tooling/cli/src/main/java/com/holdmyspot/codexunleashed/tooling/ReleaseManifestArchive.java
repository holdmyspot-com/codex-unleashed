package com.holdmyspot.codexunleashed.tooling;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.zip.GZIPInputStream;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.archivers.tar.TarConstants;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Reads executable identities from the package archives recorded by {@link ReleaseManifest}.
 */
public final class ReleaseManifestArchive
{
	/**
	 * Executable basenames whose bytes the release receipt binds to their containing archive.
	 */
	private static final Set<String> EXECUTABLE_NAMES = Set.of("codex", "codex.exe", "codex-app-server",
		"codex-app-server.exe");
	/**
	 * Receipt schema limit on executable records retained while scanning one archive.
	 */
	private static final int MAX_BINARIES = 16;

	/**
	 * Returns executable identities in archive order without extracting or executing any archive member.
	 * Every member must have a relative POSIX path without parent traversal or backslashes. Empty and dot components
	 * are removed from recorded paths. Recognized executables must be regular files and have distinct normalized paths.
	 *
	 * @param scope   borrowed shared JSON mapper; the caller retains ownership
	 * @param archive gzip-compressed tar archive whose basename is recorded in each executable identity
	 * @return executable records containing archive basename, archive-relative path, byte size and lowercase SHA256;
	 *         an empty array when the archive contains no recognized executables
	 * @throws IllegalArgumentException if a member path is invalid, an executable is not regular, paths repeat, or the
	 *                                  archive contains more than sixteen recognized executables
	 * @throws IOException              if the archive cannot be read or decoded
	 */
	public static JsonNode binaries(ToolingScope scope, Path archive) throws IOException
	{
		ArrayNode binaries = scope.getJsonMapper().createArrayNode();
		Set<String> executablePaths = new HashSet<>();
		try (InputStream input = Files.newInputStream(archive);
			GZIPInputStream gzip = new GZIPInputStream(input);
			TarArchiveInputStream tar = new TarArchiveInputStream(gzip))
		{
			for (TarArchiveEntry entry = tar.getNextEntry(); entry != null; entry = tar.getNextEntry())
			{
				String path = normalizeMemberPath(entry.getName());
				String basename = path.substring(path.lastIndexOf('/') + 1);
				if (!EXECUTABLE_NAMES.contains(basename))
					continue;
				byte type = entry.getLinkFlag();
				boolean regular = type == TarConstants.LF_NORMAL || type == TarConstants.LF_OLDNORM ||
					type == TarConstants.LF_GNUTYPE_SPARSE;
				if (!regular)
					throw new IllegalArgumentException("Executable archive member is not a regular file: " + path);
				if (!executablePaths.add(path))
					throw new IllegalArgumentException("Duplicate executable archive member: " + path);
				if (binaries.size() >= MAX_BINARIES)
					throw new IllegalArgumentException("Archive contains more than sixteen executables: " + archive);

				ObjectNode binary = scope.getJsonMapper().createObjectNode();
				binary.put("archive", archive.getFileName().toString());
				binary.put("path", path);
				binary.put("size", entry.getRealSize());
				binary.put("sha256", digestMember(tar));
				binaries.add(binary);
			}
		}
		return binaries;
	}

	/**
	 * Prevents construction.
	 */
	private ReleaseManifestArchive()
	{
	}

	/**
	 * Normalizes a parsed tar member's POSIX path while rejecting absolute paths and parent traversal.
	 *
	 * @param path archive-namespace path returned by the tar parser
	 * @return normalized POSIX path, or a single dot when the member names the archive root
	 * @throws IllegalArgumentException if {@code path} is absolute, contains backslashes or traverses a parent
	 */
	private static String normalizeMemberPath(String path)
	{
		if (path.startsWith("/") || path.contains("\\"))
			throw new IllegalArgumentException("Unsafe archive member path: " + path);
		List<String> components = new ArrayList<>();
		for (String component : path.split("/"))
		{
			if (component.equals(".."))
				throw new IllegalArgumentException("Unsafe archive member path: " + path);
			if (component.isEmpty() || component.equals("."))
				continue;
			components.add(component);
		}
		if (components.isEmpty())
			return ".";
		return String.join("/", components);
	}

	/**
	 * Hashes the current regular member without retaining its content.
	 *
	 * @param tar positioned member content, consumed through the member's end
	 * @return lowercase SHA256 digest
	 * @throws IOException if the member cannot be read or SHA256 is unavailable
	 */
	private static String digestMember(TarArchiveInputStream tar) throws IOException
	{
		MessageDigest digest;
		try
		{
			digest = MessageDigest.getInstance("SHA-256");
		}
		catch (NoSuchAlgorithmException failure)
		{
			throw new IOException("SHA-256 is unavailable; use a Java runtime with SHA-256 support", failure);
		}
		byte[] buffer = new byte[8192];
		for (int count = tar.read(buffer); count != -1; count = tar.read(buffer))
			digest.update(buffer, 0, count);
		return HexFormat.of().formatHex(digest.digest());
	}
}
