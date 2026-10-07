package com.holdmyspot.codexunleashed.tooling.release;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Comparator;
import java.util.stream.Stream;
import org.testng.annotations.Test;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertTrue;
import static org.testng.Assert.expectThrows;

/**
 * Verifies real HTTP redirects, failed replacement, and owned temporary download cleanup.
 */
public final class ArtifactDownloadsTest
{
	/**
	 * Creates download tests.
	 */
	public ArtifactDownloadsTest()
	{
	}

	/**
	 * Downloads raw bytes through a redirect and preserves existing files and directories after failures.
	 *
	 * @throws IOException if fixture initialization, download, or cleanup fails
	 * @throws URISyntaxException if the loopback endpoint cannot be represented as a URI
	 */
	@Test
	public void downloadsAndPreservesFailedDestinations() throws IOException, URISyntaxException
	{
		Path root = Files.createTempDirectory("artifact-downloads-");
		HttpServer server = null;
		try
		{
			byte[] payload = {0, (byte) 255, 1};
			server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
			server.createContext("/", exchange ->
			{
				try (exchange)
				{
					switch (exchange.getRequestURI().getPath())
					{
						case "/redirect" ->
						{
							exchange.getResponseHeaders().set("Location", "/payload");
							exchange.sendResponseHeaders(302, -1);
						}
						case "/payload" ->
						{
							exchange.sendResponseHeaders(200, payload.length);
							exchange.getResponseBody().write(payload);
						}
						default -> exchange.sendResponseHeaders(404, -1);
					}
				}
			});
			server.start();
			URI endpoint = new URI("http", null, server.getAddress().getAddress().getHostAddress(),
				server.getAddress().getPort(), "/", null, null);
			Path destination = root.resolve("artifact");
			Path temporary = root.resolve("artifact.tmp");
			Files.writeString(temporary, "stale partial download");
			ArtifactDownloads.download(endpoint.resolve("redirect"), destination, Duration.ofSeconds(5));
			assertEquals(Files.readAllBytes(destination), payload);
			assertFalse(Files.exists(temporary));
			expectThrows(IOException.class, () ->
				ArtifactDownloads.download(endpoint.resolve("missing"), destination, Duration.ofSeconds(5)));
			assertEquals(Files.readAllBytes(destination), payload);
			assertFalse(Files.exists(temporary));
			Files.delete(destination);
			Files.createDirectory(destination);
			expectThrows(IOException.class, () ->
				ArtifactDownloads.download(endpoint.resolve("payload"), destination, Duration.ofSeconds(5)));
			assertTrue(Files.isDirectory(destination));
			assertFalse(Files.exists(temporary));
		}
		finally
		{
			if (server != null)
				server.stop(0);
			try (Stream<Path> paths = Files.walk(root))
			{
				for (Path path : paths.sorted(Comparator.reverseOrder()).toList())
					Files.delete(path);
			}
		}
	}
}
