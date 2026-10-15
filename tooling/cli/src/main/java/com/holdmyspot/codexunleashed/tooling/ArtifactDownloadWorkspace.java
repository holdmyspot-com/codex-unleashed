package com.holdmyspot.codexunleashed.tooling;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.stream.Stream;

/**
 * Owns downloaded bytes and extraction files until artifact delivery finishes.
 *
 * @param path exclusively owned temporary directory
 */
record ArtifactDownloadWorkspace(Path path) implements AutoCloseable
{
	/**
	 * Allocates scratch storage in the configured temporary directory.
	 *
	 * @return owned storage
	 * @throws IOException if allocation fails
	 */
	static ArtifactDownloadWorkspace create() throws IOException
	{
		return new ArtifactDownloadWorkspace(Files.createTempDirectory("workflow-artifact-"));
	}

	/**
	 * Removes downloaded bytes and extracted files after success or failure.
	 *
	 * @throws IOException if cleanup fails
	 */
	@Override
	public void close() throws IOException
	{
		try (Stream<Path> paths = Files.walk(path))
		{
			for (Path entry : paths.sorted(Comparator.reverseOrder()).toList())
				Files.delete(entry);
		}
	}
}
