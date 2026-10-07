package com.holdmyspot.codexunleashed.tooling.github;

import com.holdmyspot.codexunleashed.tooling.release.ReleaseTagReservation;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import org.testng.annotations.Test;
import tools.jackson.databind.json.JsonMapper;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertNull;
import static org.testng.Assert.expectThrows;

/**
 * Verifies real HTTP reservation effects, request headers, and raw tag spelling against a local server.
 */
public final class GitHubHttpClientTest
{
	/**
	 * Creates the HTTP tests.
	 */
	public GitHubHttpClientTest()
	{
	}

	/**
	 * Creates an absent Unicode release tag once and verifies the same tag on retry.
	 *
	 * @throws IOException if the local server or HTTP request fails
	 * @throws URISyntaxException if the local endpoint cannot be represented as a URI
	 */
	@Test
	public void reservesThroughHttp() throws IOException, URISyntaxException
	{
		String tag = "rust-v٠.١٥٩.١+٢٣";
		String sha = "a".repeat(40);
		String reference = "{\"object\":{\"type\":\"commit\",\"sha\":\"" + sha + "\"}}";
		AtomicBoolean stored = new AtomicBoolean();
		List<List<String>> calls = Collections.synchronizedList(new ArrayList<>());
		HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
		server.createContext("/", exchange ->
		{
			try (exchange)
			{
				String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
				calls.add(List.of(exchange.getRequestMethod(), exchange.getRequestURI().getPath(), body,
					exchange.getRequestHeaders().getFirst("Authorization"),
					exchange.getRequestHeaders().getFirst("Accept"),
					exchange.getRequestHeaders().getFirst("Content-Type"),
					exchange.getRequestHeaders().getFirst("X-GitHub-Api-Version"),
					exchange.getRequestHeaders().getFirst("User-Agent")));
				int status = 200;
				String response = reference;
				if ("POST".equals(exchange.getRequestMethod()))
				{
					stored.set(true);
					status = 201;
				}
				else if (!stored.get())
				{
					status = 404;
					response = "{\"message\":\"Not Found\"}";
				}
				byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
				exchange.sendResponseHeaders(status, bytes.length);
				exchange.getResponseBody().write(bytes);
			}
		});
		server.start();
		try (GitHubHttpClient api = new GitHubHttpClient(new URI("http", null,
			server.getAddress().getAddress().getHostAddress(), server.getAddress().getPort(), "/", null, null),
			"fixture-token"))
		{
			ReleaseTagReservation.ensure("owner/repo", tag, sha, api);
			ReleaseTagReservation.ensure("owner/repo", tag, sha, api);
			assertEquals(calls.size(), 3);
			assertEquals(calls.getFirst().get(0), "GET");
			assertEquals(calls.getFirst().get(1), "/repos/owner/repo/git/ref/tags/" + tag);
			assertEquals(calls.get(1).get(0), "POST");
			assertEquals(calls.get(1).get(1), "/repos/owner/repo/git/refs");
			assertEquals(JsonMapper.builder().build().readTree(calls.get(1).get(2)),
				JsonMapper.builder().build().valueToTree(Map.of("ref", "refs/tags/" + tag, "sha", sha)));
			assertEquals(calls.getLast().get(0), "GET");
			for (List<String> call : calls)
			{
				assertEquals(call.subList(3, 7), List.of("Bearer fixture-token", "application/vnd.github+json",
					"application/json", "2022-11-28"));
				assertFalse(call.get(7).isBlank());
			}
		}
		finally
		{
			server.stop(0);
		}
	}

	/**
	 * Reads public release metadata without authorization while retaining raw tags, error status, and strict UTF-8.
	 *
	 * @throws IOException if the local HTTP server or client fails
	 * @throws URISyntaxException if the fixture endpoint cannot be represented
	 */
	@Test
	public void readsAnonymousReleaseMetadata() throws IOException, URISyntaxException
	{
		List<String> paths = Collections.synchronizedList(new ArrayList<>());
		HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
		server.createContext("/", exchange ->
		{
			try (exchange)
			{
				assertNull(exchange.getRequestHeaders().getFirst("Authorization"));
				assertEquals(exchange.getRequestHeaders().getFirst("Accept"), "application/vnd.github+json");
				assertEquals(exchange.getRequestMethod(), "GET");
				paths.add(exchange.getRequestURI().getPath());
				byte[] bytes = "{\"message\":\"雪\"}".getBytes(StandardCharsets.UTF_8);
				if (exchange.getRequestURI().getPath().endsWith("malformed"))
					bytes = new byte[]{(byte) 255};
				exchange.sendResponseHeaders(404, bytes.length);
				exchange.getResponseBody().write(bytes);
			}
		});
		server.start();
		URI endpoint = new URI("http", null, server.getAddress().getAddress().getHostAddress(),
			server.getAddress().getPort(), "/", null, null);
		try (GitHubHttpClient api = GitHubHttpClient.anonymous(endpoint))
		{
			GitHubApi.Response response = api.request(GitHubApi.Method.GET,
				"repos/owner/repo/releases/tags/rust-v٠.١.٢+34", Map.of());
			assertEquals(response.statusCode(), 404);
			assertEquals(response.body(), "{\"message\":\"雪\"}");
			IOException failure = expectThrows(IOException.class,
				() -> api.request(GitHubApi.Method.GET, "malformed", Map.of()));
			assertEquals(failure.getMessage(), "GitHub response is not valid UTF-8");
			assertEquals(paths, List.of("/repos/owner/repo/releases/tags/rust-v٠.١.٢+34", "/malformed"));
			expectThrows(IllegalArgumentException.class, () -> new GitHubHttpClient(endpoint, " "));
		}
		finally
		{
			server.stop(0);
		}
	}
}
