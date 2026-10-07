package com.holdmyspot.codexunleashed.tooling.release;

import com.holdmyspot.codexunleashed.tooling.Main;
import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.testng.annotations.Test;
import tools.jackson.databind.json.JsonMapper;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertTrue;

/**
 * Verifies latest-release decisions through the registered command and GitHub HTTP boundary.
 */
public final class ReleaseLatestCommandTest
{
	/**
	 * Creates publication policy tests.
	 */
	public ReleaseLatestCommandTest()
	{
	}

	/**
	 * Preserves newer vendor builds, accepts equal versions, and fails closed on malformed latest metadata.
	 *
	 * @throws IOException if the local HTTP fixture fails
	 * @throws URISyntaxException if the local endpoint cannot be represented
	 */
	@Test
	public void protectsLatestVendorBuild() throws IOException, URISyntaxException
	{
		HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
		AtomicReference<String> latest = new AtomicReference<>("rust-v0.160.0+12");
		server.createContext("/repos/owner/repo/releases/latest", exchange ->
		{
			try (exchange)
			{
				byte[] response = JsonMapper.builder().build().writeValueAsBytes(Map.of("tag_name", latest.get(),
					"draft", false, "prerelease", false));
				exchange.sendResponseHeaders(200, response.length);
				exchange.getResponseBody().write(response);
			}
		});
		server.start();
		try
		{
			URI endpoint = new URI("http", null, server.getAddress().getAddress().getHostAddress(),
				server.getAddress().getPort(), "/", null, null);
			for (String candidate : List.of("rust-v0.160.0+2", "rust-v0.160.0+12", "rust-v0.161.0+1"))
			{
				ByteArrayOutputStream output = new ByteArrayOutputStream();
				try (PrintStream out = new PrintStream(output, true, StandardCharsets.UTF_8))
				{
					assertEquals(Main.run(new String[] {"release-is-latest", "--repository", "owner/repo", "--tag",
						candidate, "--api-base", endpoint.toString()}, InputStream.nullInputStream(), out, out), 0,
						output.toString(StandardCharsets.UTF_8));
				}
				String expected = "true";
				if (candidate.endsWith("+2"))
					expected = "false";
				assertEquals(output.toString(StandardCharsets.UTF_8).strip(), expected);
			}
			latest.set("rust-v0.160.0-alpha.1");
			ByteArrayOutputStream output = new ByteArrayOutputStream();
			try (PrintStream out = new PrintStream(output, true, StandardCharsets.UTF_8))
			{
				assertEquals(Main.run(new String[] {"release-is-latest", "--repository", "owner/repo", "--tag",
					"rust-v0.161.0+1", "--api-base", endpoint.toString()}, InputStream.nullInputStream(), out, out), 1);
				assertTrue(output.toString(StandardCharsets.UTF_8).contains("stable"));
			}
		}
		finally
		{
			server.stop(0);
		}
	}
}
