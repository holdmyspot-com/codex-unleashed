package com.holdmyspot.codexunleashed.distribution;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Selects native fixture utilities whose platform implementations have different path handling.
 */
final class NativeUtilities
{
	/**
	 * Prevents construction.
	 */
	private NativeUtilities()
	{
	}

	/**
	 * Selects Windows' native tar or the Unix tar on the executor's search path.
	 *
	 * @return the native tar executable
	 * @throws IOException if the required Windows utility is unavailable
	 */
	static String tarExecutable() throws IOException
	{
		if (File.separatorChar != '\\')
			return "tar";
		String systemRoot = System.getenv("SystemRoot");
		if (systemRoot == null)
			throw new IOException("Windows archive fixtures require SystemRoot to locate native tar");
		Path tar = Path.of(systemRoot, "System32", "tar.exe");
		if (!Files.isRegularFile(tar))
			throw new IOException("Required native Windows tar is unavailable: " + tar);
		return tar.toString();
	}
}
