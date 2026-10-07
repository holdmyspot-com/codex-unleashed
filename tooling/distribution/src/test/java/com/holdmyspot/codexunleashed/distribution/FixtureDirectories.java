package com.holdmyspot.codexunleashed.distribution;

import java.io.IOException;
import java.nio.file.Path;
import org.apache.commons.io.file.PathUtils;
import org.apache.commons.io.file.StandardDeleteOption;

/**
 * Removes caller-owned fixture trees, including read-only Git objects on Windows.
 */
final class FixtureDirectories
{
	/**
	 * Prevents construction.
	 */
	private FixtureDirectories()
	{
	}

	/**
	 * Deletes a fixture tree after releasing its processes and file handles.
	 *
	 * @param root the caller-owned fixture directory
	 * @throws IOException if the tree cannot be removed
	 */
	static void deleteTree(Path root) throws IOException
	{
		PathUtils.deleteDirectory(root, StandardDeleteOption.OVERRIDE_READ_ONLY);
	}
}
