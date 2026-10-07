package com.holdmyspot.codexunleashed.tooling.release;

import com.holdmyspot.codexunleashed.tooling.PayloadFiles;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Objects;

/**
 * Owns a single temporary resource file and suppresses cleanup failures behind a primary failure.
 *
 * @param path the owned temporary file path
 */
record ResourceTemporaryFile(Path path) implements AutoCloseable
{
	/**
	 * Retains a required owned temporary path.
	 *
	 * @param path the owned temporary file path
	 * @throws NullPointerException if {@code path} is null
	 */
	ResourceTemporaryFile
	{
		Objects.requireNonNull(path, "path");
	}

	/**
	 * Deletes the owned temporary file or link without removing a directory.
	 *
	 * @throws IOException if cleanup fails
	 */
	@Override
	public void close() throws IOException
	{
		PayloadFiles.delete(path);
	}
}
