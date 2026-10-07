package com.holdmyspot.codexunleashed.tooling.release;

import com.holdmyspot.codexunleashed.tooling.TextLines;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads the exact lowercase digest and filename coverage declared by a V8 release checksum manifest.
 */
public final class V8ArtifactChecksums
{
	private static final String SPACE = "[\\s\\u001c-\\u001f]";
	private static final Pattern PARTS = Pattern.compile(SPACE + "*([^\\s\\u001c-\\u001f]+)" + SPACE + "+(.*)",
		Pattern.UNICODE_CHARACTER_CLASS | Pattern.DOTALL);
	private static final Pattern EDGE_SPACE = Pattern.compile("^" + SPACE + "+|" + SPACE + "+$",
		Pattern.UNICODE_CHARACTER_CLASS);
	private static final Pattern DIGEST = Pattern.compile("[0-9a-f]{64}");

	/**
	 * Prevents construction.
	 */
	private V8ArtifactChecksums()
	{
	}

	/**
	 * Requires exactly one checksum line for every expected filename without changing digest or filename case.
	 *
	 * @param manifest the downloaded UTF-8 checksum file
	 * @param artifactNames the exact expected filenames
	 * @return the declared digests by filename
	 * @throws IOException if reading, digest syntax, line count, or filename coverage fails
	 * @throws NullPointerException if an argument or filename is null
	 */
	public static Map<String, String> read(Path manifest, Set<String> artifactNames) throws IOException
	{
		Objects.requireNonNull(manifest, "manifest");
		Set<String> names = Set.copyOf(artifactNames);
		List<String> lines = TextLines.split(Files.readString(manifest));
		if (lines.size() != names.size())
			throw new IOException("Expected " + names.size() + " V8 checksums in " + manifest + ", found " + lines.size());

		Map<String, String> checksums = new HashMap<>();
		for (String line : lines)
		{
			Matcher parts = PARTS.matcher(line);
			if (!parts.matches())
				throw new IOException("Invalid V8 checksum line in " + manifest + ": " + line);
			String digest = parts.group(1);
			String name = EDGE_SPACE.matcher(parts.group(2)).replaceAll("");
			if (!DIGEST.matcher(digest).matches())
				throw new IOException("Invalid V8 checksum digest in " + manifest + ": " + digest);
			if (!names.contains(name))
				throw new IOException("Unexpected V8 checksum artifact in " + manifest + ": " + name);
			checksums.put(name, digest);
		}
		if (!checksums.keySet().equals(names))
			throw new IOException("V8 checksum manifest " + manifest + " does not cover " + names);
		return Map.copyOf(checksums);
	}
}
