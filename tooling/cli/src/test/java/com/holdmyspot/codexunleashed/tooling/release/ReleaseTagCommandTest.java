package com.holdmyspot.codexunleashed.tooling.release;

import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.testng.annotations.Test;
import static org.testng.Assert.assertEquals;

/**
 * Verifies release provenance output only after HTTP reservation succeeds.
 */
public final class ReleaseTagCommandTest
{
	/**
	 * Creates the command tests.
	 */
	public ReleaseTagCommandTest()
	{
	}

	/**
	 * Reads the original run and reserves its exact commit before emitting provenance.
	 *
	 * @throws IOException if the local fixture or command fails
	 * @throws URISyntaxException if the local API endpoint cannot be represented
	 */
	@Test
	public void emitsReservedBuildProvenance() throws IOException, URISyntaxException
	{
		String sha = "a".repeat(40);
		List<String> requests = Collections.synchronizedList(new ArrayList<>());
		HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
		server.createContext("/", exchange ->
		{
			try (exchange)
			{
				String path = exchange.getRequestURI().getPath();
				requests.add(exchange.getRequestMethod() + " " + path);
				String body;
				int status = 200;
				if (path.endsWith("/actions/runs/7"))
					body = "{\"repository\":{\"full_name\":\"owner/repo\"}," +
						"\"path\":\".github/workflows/build-release.yml\",\"head_sha\":\"" + sha +
						"\",\"head_branch\":\"release/topic\",\"run_attempt\":3}";
				else if ("POST".equals(exchange.getRequestMethod()))
				{
					body = "{\"object\":{\"type\":\"commit\",\"sha\":\"" + sha + "\"}}";
					status = 201;
				}
				else
				{
					body = "{}";
					status = 404;
				}
				byte[] response = body.getBytes(StandardCharsets.UTF_8);
				exchange.sendResponseHeaders(status, response.length);
				exchange.getResponseBody().write(response);
			}
		});
		server.start();
		try
		{
			URI base = new URI("http", null, server.getAddress().getAddress().getHostAddress(),
				server.getAddress().getPort(), "/", null, null);
			ByteArrayOutputStream output = new ByteArrayOutputStream();
			try (PrintStream stream = new PrintStream(output, false, StandardCharsets.UTF_8))
			{
				assertEquals(ReleaseTagCommand.run(new String[]{"--repository", "owner/repo", "--tag", "rust-v0.159.1+23",
					"--artifact-run-id", "+0007", "--api-base", base.toString()}, stream, stream, "fixture-token"), 0);
			}
			assertEquals(output.toString(StandardCharsets.UTF_8), String.join(System.lineSeparator(),
				"source_sha=" + sha, "source_ref=refs/heads/release/topic", "source_run_attempt=3", ""));
			assertEquals(requests, List.of("GET /repos/owner/repo/actions/runs/7",
				"GET /repos/owner/repo/git/ref/tags/rust-v0.159.1+23", "POST /repos/owner/repo/git/refs"));
		}
		finally
		{
			server.stop(0);
		}
	}

	/**
	 * Handles help and invalid arguments before requiring credentials or accessing the network.
	 *
	 * @throws IOException if parsing unexpectedly reaches the request boundary
	 */
	@Test
	public void parsesBeforeCredentials() throws IOException
	{
		try (PrintStream stream = new PrintStream(new ByteArrayOutputStream(), false, StandardCharsets.UTF_8))
		{
			assertEquals(ReleaseTagCommand.run(new String[]{"--help"}, stream, stream, null), 0);
			assertEquals(ReleaseTagCommand.run(new String[0], stream, stream, null), 2);
		}
	}
}
