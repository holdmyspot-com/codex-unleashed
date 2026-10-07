package com.holdmyspot.codexunleashed.tooling.release;

import com.holdmyspot.codexunleashed.tooling.PayloadFiles;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URLConnection;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.Objects;

/**
 * Downloads raw resource bytes through an owned sibling temporary file before replacing the destination.
 */
public final class ArtifactDownloads
{
	/**
	 * Prevents construction.
	 */
	private ArtifactDownloads()
	{
	}

	/**
	 * Downloads a URL with explicit connection and read timeouts and removes partial input on every outcome.
	 *
	 * @param source the resource URL
	 * @param destination the destination file
	 * @param timeout the positive connection and read timeout
	 * @throws IOException if URL opening, copying, destination replacement, or temporary cleanup fails
	 * @throws IllegalArgumentException if the timeout is outside the positive integer-millisecond range
	 * @throws NullPointerException if any argument is null
	 */
	public static void download(URI source, Path destination, Duration timeout) throws IOException
	{
		Objects.requireNonNull(source, "source");
		Objects.requireNonNull(destination, "destination");
		Objects.requireNonNull(timeout, "timeout");
		long milliseconds = timeout.toMillis();
		if (milliseconds <= 0 || milliseconds > Integer.MAX_VALUE)
			throw new IllegalArgumentException("Download timeout must be a positive integer number of milliseconds");
		Path output = destination.toAbsolutePath();
		Files.createDirectories(output.getParent());
		Path temporary = output.resolveSibling(output.getFileName() + ".tmp");
		PayloadFiles.delete(temporary);
		try (ResourceTemporaryFile owned = new ResourceTemporaryFile(temporary))
		{
			copyUrl(source, owned.path(), (int) milliseconds);
			if (Files.isDirectory(output, LinkOption.NOFOLLOW_LINKS))
				throw new IOException("Download destination is a directory: " + output);
			Files.move(owned.path(), output, StandardCopyOption.REPLACE_EXISTING);
		}
	}

	/**
	 * Copies URL bytes while closing both streams and disconnecting HTTP transport.
	 *
	 * @param source the resource URL
	 * @param destination the owned temporary output
	 * @param timeout the connection and read timeout in milliseconds
	 * @throws IOException if URL construction, connection, stream copying, or closing fails
	 */
	private static void copyUrl(URI source, Path destination, int timeout) throws IOException
	{
		URLConnection connection;
		try
		{
			connection = source.toURL().openConnection();
		}
		catch (IllegalArgumentException failure)
		{
			throw new IOException("Unable to open resource URL: " + source, failure);
		}
		connection.setConnectTimeout(timeout);
		connection.setReadTimeout(timeout);
		try (InputStream input = connection.getInputStream(); OutputStream output = Files.newOutputStream(destination))
		{
			input.transferTo(output);
		}
		finally
		{
			if (connection instanceof HttpURLConnection http)
				http.disconnect();
		}
	}
}
