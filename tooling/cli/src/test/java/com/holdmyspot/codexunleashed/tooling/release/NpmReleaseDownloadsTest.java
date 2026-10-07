package com.holdmyspot.codexunleashed.tooling.release;

import com.holdmyspot.codexunleashed.tooling.github.GitHubHttpClient;
import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import org.testng.annotations.Test;
import tools.jackson.databind.json.JsonMapper;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertNull;
import static org.testng.Assert.assertTrue;
import static org.testng.Assert.expectThrows;

/**
 * Verifies anonymous release lookup and six raw artifact downloads against a local HTTP endpoint.
 */
public final class NpmReleaseDownloadsTest
{
	/** Creates the download tests. */
	public NpmReleaseDownloadsTest()
	{
	}

	/**
	 * Downloads all required targets in order, selects the last duplicate asset, and preserves raw bytes.
	 *
	 * @throws IOException if the server, download, fixture, or cleanup fails
	 * @throws URISyntaxException if the local endpoint cannot be represented
	 */
	@Test
	public void downloadsSixReleaseAssets() throws IOException, URISyntaxException
	{
		Path root = Files.createTempDirectory("npm-release-downloads-");
		HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
		URI endpoint = endpoint(server);
		List<String> calls = Collections.synchronizedList(new ArrayList<>());
		List<Map<String, String>> assets = new ArrayList<>();
		String first = "codex-package-x86_64-unknown-linux-musl.tar.gz";
		assets.add(Map.of("name", first, "browser_download_url", endpoint.resolve("obsolete").toString()));
		for (NpmPlatform platform : NpmPlatform.values())
		{
			String name = "codex-package-" + platform.target() + ".tar.gz";
			assets.add(Map.of("name", name, "browser_download_url", endpoint.resolve("assets/" + name).toString()));
		}
		byte[] metadata = JsonMapper.builder().build().writeValueAsBytes(Map.of("assets", assets));
		byte[] payload = {0, (byte) 255, 1, 2};
		server.createContext("/", exchange ->
		{
			try (exchange)
			{
				assertNull(exchange.getRequestHeaders().getFirst("Authorization"));
				String path = exchange.getRequestURI().getPath();
				calls.add(path);
				byte[] response = payload;
				if (path.startsWith("/repos/"))
				{
					assertEquals(exchange.getRequestHeaders().getFirst("Accept"), "application/vnd.github+json");
					response = metadata;
				}
				exchange.sendResponseHeaders(200, response.length);
				exchange.getResponseBody().write(response);
			}
		});
		server.start();
		try (GitHubHttpClient api = GitHubHttpClient.anonymous(endpoint);
			ByteArrayOutputStream bytes = new ByteArrayOutputStream();
			PrintStream progress = new PrintStream(bytes, true, StandardCharsets.UTF_8))
		{
			NpmReleaseDownloads.download("owner/repo", "rust-v٠.١.٢+34", root, api, progress);
			assertEquals(calls.getFirst(), "/repos/owner/repo/releases/tags/rust-v٠.١.٢+34");
			assertEquals(calls.size(), 7);
			int index = 1;
			for (NpmPlatform platform : NpmPlatform.values())
			{
				String name = "codex-package-" + platform.target() + ".tar.gz";
				assertEquals(calls.get(index), "/assets/" + name);
				index += 1;
				assertEquals(Files.readAllBytes(root.resolve(name)), payload);
				assertTrue(bytes.toString(StandardCharsets.UTF_8).contains("Downloading " + name + System.lineSeparator()));
			}
			try (Stream<Path> files = Files.list(root))
			{
				assertEquals(files.count(), 6L);
			}
		}
		finally
		{
			server.stop(0);
			delete(root);
		}
	}

	/**
	 * Preserves ordinary HTTP failure and missing-asset diagnostics without leaving partial download resources.
	 *
	 * @throws IOException if the server, fixture, or cleanup fails
	 * @throws URISyntaxException if the endpoint cannot be represented
	 */
	@Test
	public void reportsLookupAndAssetFailures() throws IOException, URISyntaxException
	{
		Path root = Files.createTempDirectory("npm-release-failures-");
		HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
		URI endpoint = endpoint(server);
		AtomicReference<String> mode = new AtomicReference<>("denied");
		server.createContext("/", exchange ->
		{
			try (exchange)
			{
				int status = 200;
				String body = "{\"assets\":[]}";
				if (mode.get().equals("denied") || exchange.getRequestURI().getPath().equals("/failed-asset"))
					status = 403;
				else if (mode.get().equals("failed-asset"))
					body = JsonMapper.builder().build().writeValueAsString(Map.of("assets", List.of(Map.of(
						"name", "codex-package-x86_64-unknown-linux-musl.tar.gz",
						"browser_download_url", endpoint.resolve("failed-asset").toString()))));
				byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
				exchange.sendResponseHeaders(status, bytes.length);
				exchange.getResponseBody().write(bytes);
			}
		});
		server.start();
		try (GitHubHttpClient api = GitHubHttpClient.anonymous(endpoint);
			PrintStream progress = new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8))
		{
			IOException denied = expectThrows(IOException.class,
				() -> NpmReleaseDownloads.download("owner/repo", "rust-v0.160.0+34", root, api, progress));
			assertTrue(denied.getMessage().contains("HTTP 403"));
			mode.set("missing");
			IOException missing = expectThrows(IOException.class,
				() -> NpmReleaseDownloads.download("owner/repo", "rust-v0.160.0+34", root, api, progress));
			assertEquals(missing.getMessage(),
				"Release owner/repo@rust-v0.160.0+34 has no codex-package-x86_64-unknown-linux-musl.tar.gz");
			mode.set("failed-asset");
			Path existing = root.resolve("codex-package-x86_64-unknown-linux-musl.tar.gz");
			Files.writeString(existing, "existing");
			expectThrows(IOException.class,
				() -> NpmReleaseDownloads.download("owner/repo", "rust-v0.160.0+34", root, api, progress));
			assertEquals(Files.readString(existing), "existing");
			try (Stream<Path> files = Files.list(root))
			{
				assertEquals(files.toList(), List.of(existing));
			}
		}
		finally
		{
			server.stop(0);
			delete(root);
		}
	}

	/**
	 * Represents the local server endpoint with the correct IPv4 or IPv6 host encoding.
	 *
	 * @param server the bound fixture server
	 * @return the absolute endpoint
	 * @throws URISyntaxException if the address cannot be represented
	 */
	private static URI endpoint(HttpServer server) throws URISyntaxException
	{
		return new URI("http", null, server.getAddress().getAddress().getHostAddress(),
			server.getAddress().getPort(), "/", null, null);
	}

	/**
	 * Removes the owned fixture tree without following symbolic links.
	 *
	 * @param root the owned root
	 * @throws IOException if cleanup fails
	 */
	private static void delete(Path root) throws IOException
	{
		try (Stream<Path> files = Files.walk(root))
		{
			for (Path file : files.sorted(Comparator.reverseOrder()).toList())
				Files.delete(file);
		}
	}
}
