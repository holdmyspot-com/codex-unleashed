package com.holdmyspot.codexunleashed.tooling;

import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.Objects;

/**
 * Confines Cargo target names to one native filename before the source-cache coordinator performs writes.
 */
public final class SourceCacheTargets
{
	private static final String JSON_SUFFIX = ".json";

	/**
	 * Prevents construction.
	 */
	private SourceCacheTargets()
	{
	}

	/**
	 * Returns the cache directory name for a target confined to one native filename.
	 * Removes a final lowercase {@code .json} only when the filename contains other characters;
	 * preserves case and leading dots, and rejects names whose resulting directory is {@code .} or {@code ..}.
	 *
	 * @param target the native triple or custom target filename
	 * @return the cache directory name
	 * @throws NullPointerException if target is null
	 * @throws IllegalArgumentException if either filename would escape or ambiguously identify a cache directory
	 */
	public static String directoryName(String target)
	{
		Objects.requireNonNull(target, "target");
		String directory = target;
		if (target.endsWith(JSON_SUFFIX) && target.length() > JSON_SUFFIX.length())
			directory = target.substring(0, target.length() - JSON_SUFFIX.length());
		try
		{
			if (!isFilename(target) || !isFilename(directory) || directory.equals(".") || directory.equals(".."))
				throw new IllegalArgumentException("Invalid Cargo target directory name: " + target);
		}
		catch (InvalidPathException failure)
		{
			throw new IllegalArgumentException("Invalid Cargo target directory name: " + target, failure);
		}
		return directory;
	}

	/**
	 * Indicates whether a nonempty path spelling is exactly one native filename.
	 *
	 * @param value the original path spelling
	 * @return whether the spelling is one nonempty filename
	 */
	private static boolean isFilename(String value)
	{
		Path name = Path.of(value).getFileName();
		return !value.isEmpty() && name != null && name.toString().equals(value);
	}
}
