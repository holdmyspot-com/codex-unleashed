package com.holdmyspot.codexunleashed.tooling;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.NotDirectoryException;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;

/** Resolves source-cache path identity through existing ancestors and symbolic links. */
final class SourceInputPaths
{
	/** Prevents construction. */
	private SourceInputPaths()
	{
	}

	/**
	 * Resolves an existing path or a prospective path beneath its canonical ancestor.
	 *
	 * @param path the source or cache path
	 * @return its canonical filesystem identity
	 * @throws IOException if path inspection fails or links form a cycle
	 */
	static Path resolve(Path path) throws IOException
	{
		return resolve(path.toAbsolutePath(), new HashSet<>());
	}

	/**
	 * Resolves missing suffixes without collapsing parent traversal before symbolic-link resolution.
	 *
	 * @param path the absolute path
	 * @param active paths being resolved for cycle detection
	 * @return a canonical existing prefix and resolved suffix
	 * @throws IOException if inspection or cycle detection fails
	 */
	private static Path resolve(Path path, Set<Path> active) throws IOException
	{
		if (!active.add(path))
			throw new IOException("Source-input path contains a symbolic-link cycle: " + path);
		try
		{
			if (Files.isSymbolicLink(path))
			{
				Path target = Files.readSymbolicLink(path);
				if (!target.isAbsolute())
					target = path.getParent().resolve(target);
				return resolve(target, active);
			}
			try
			{
				return path.toRealPath();
			}
			catch (NoSuchFileException | NotDirectoryException missing)
			{
				Path parent = path.getParent();
				if (parent == null)
					throw missing;
				return resolve(parent, active).resolve(path.getFileName()).normalize();
			}
		}
		finally
		{
			active.remove(path);
		}
	}
}
