package com.holdmyspot.codexunleashed.tooling.release;

import com.holdmyspot.codexunleashed.tooling.Main;
import com.holdmyspot.codexunleashed.tooling.SystemCommands;
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
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.Comparator;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.testng.annotations.Test;
import org.testng.annotations.DataProvider;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertTrue;

/**
 * Verifies publisher dispatch, semantic argument failures, and actual npm packing into requested output paths.
 */
public final class NpmPublishCommandTest
{
	/** Creates publisher command tests. */
	public NpmPublishCommandTest()
	{
	}

	/**
	 * Supplies useful command help without requiring a tag, download, or npm execution.
	 *
	 * @throws IOException if fixture stream cleanup fails
	 */
	@Test
	public void dispatchesPublisherHelp() throws IOException
	{
		try (ByteArrayOutputStream output = new ByteArrayOutputStream();
			PrintStream out = new PrintStream(output, true, StandardCharsets.UTF_8))
		{
			assertEquals(Main.run(new String[]{"publish-npm-from-release", "--help"},
				InputStream.nullInputStream(), out, out),
				0, output.toString(StandardCharsets.UTF_8));
			assertTrue(output.toString(StandardCharsets.UTF_8).contains("--archive-dir"));
			assertTrue(output.toString(StandardCharsets.UTF_8).contains("--publish"));
		}
	}

	/**
	 * Rejects an absent release identity before creating caller output.
	 *
	 * @throws IOException if fixture access or cleanup fails
	 */
	@Test
	public void requiresTagOrVersion() throws IOException
	{
		Path root = Files.createTempDirectory("npm-publish-invalid-");
		try (ByteArrayOutputStream output = new ByteArrayOutputStream();
			PrintStream out = new PrintStream(output, true, StandardCharsets.UTF_8))
		{
			Path destination = root.resolve("output");
			assertEquals(Main.run(new String[]{"publish-npm-from-release", "--output-dir", destination.toString()},
				InputStream.nullInputStream(), out, out), 1, output.toString(StandardCharsets.UTF_8));
			assertTrue(output.toString(StandardCharsets.UTF_8).contains("provide --tag or --version"));
			assertFalse(Files.exists(destination));
		}
		finally
		{
			delete(root);
		}
	}

	/**
	 * Packs all fourteen packages using relative and absolute destinations while native npm changes package cwd.
	 *
	 * @throws IOException if fixture production, packing, or cleanup fails
	 */
	@Test
	public void packsRelativeAndAbsoluteOutputs() throws IOException
	{
		Path root = Files.createTempDirectory("npm-publish-command-");
		try
		{
			Path archives = createArchives(root);
			for (Path requested : List.of(Path.of("relative-output"), root.resolve("absolute-output")))
			{
				List<String> command = command(root, List.of("--version", "0.160.0+34", "--archive-dir",
					archives.toString(), "--output-dir", requested.toString()));
				SystemCommands.Result result = executePublisher(command, root);
				assertEquals(result.status(), 0, result.stderr());
				Path destination = requested;
				if (!requested.isAbsolute())
					destination = root.resolve(requested);
				try (Stream<Path> files = Files.list(destination))
				{
					assertEquals(files.filter(path -> path.getFileName().toString().endsWith(".tgz")).count(), 14L);
				}
				assertTrue(result.stdout().contains("Created 14 npm packages in " + destination));
				assertTrue(result.stdout().contains("Packages were not published"));
			}
		}
		finally
		{
			delete(root);
		}
	}

	/**
	 * Publishes real npm tarballs to a local registry with exact access, version, tag, auth, and family order.
	 *
	 * @param latest the registry's existing latest version, or empty when the package is absent
	 * @param expectedTag the tag that publication may update
	 * @param missingVersion whether the registry advertises latest without supplying its version metadata
	 * @throws IOException if fixture, HTTP, process, archive, or cleanup access fails
	 * @throws URISyntaxException if the local registry address cannot be represented
	 */
	@Test(dataProvider = "latestVersions")
	public void publishesBothFamiliesLocally(String latest, String expectedTag, boolean missingVersion)
		throws IOException, URISyntaxException
	{
		Path root = Files.createTempDirectory("npm-publish-registry-");
		HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
		URI registry = new URI("http", null, server.getAddress().getAddress().getHostAddress(),
			server.getAddress().getPort(), "/", null, null);
		List<JsonNode> published = Collections.synchronizedList(new ArrayList<>());
		List<String> credentials = Collections.synchronizedList(new ArrayList<>());
		server.createContext("/", exchange ->
		{
			try (exchange)
			{
				int status = 404;
				if (exchange.getRequestMethod().equals("PUT"))
				{
					published.add(JsonMapper.builder().build().readTree(exchange.getRequestBody().readAllBytes()));
					credentials.add(exchange.getRequestHeaders().getFirst("Authorization"));
					status = 201;
				}
				byte[] bytes = "{}".getBytes(StandardCharsets.UTF_8);
				if (exchange.getRequestMethod().equals("GET") && !latest.isEmpty())
				{
					status = 200;
					String name = exchange.getRequestURI().getPath().substring(1);
					Map<String, Object> versions = Map.of();
					if (!missingVersion)
						versions = Map.of(latest, Map.of("name", name, "version", latest));
					bytes = JsonMapper.builder().build().writeValueAsBytes(Map.of("name", name,
						"dist-tags", Map.of("latest", latest), "versions", versions));
				}
				exchange.getResponseHeaders().set("Content-Type", "application/json");
				exchange.sendResponseHeaders(status, bytes.length);
				exchange.getResponseBody().write(bytes);
			}
		});
		server.start();
		try
		{
			Path archives = createArchives(root);
			Path npmrc = root.resolve("fixture.npmrc");
			Files.writeString(npmrc, "//" + registry.getRawAuthority() + "/:_authToken=fixture\n");
			Path output = root.resolve("release");
			SystemCommands.Result result = executePublisher(command(root, List.of("--tag", "rust-v0.160.0+34",
				"--archive-dir", archives.toString(), "--output-dir", output.toString(), "--registry", registry.toString(),
				"--npmrc", npmrc.toString(), "--publish")), root);
			if (missingVersion)
			{
				assertEquals(result.status(), 1, result.stderr());
				assertTrue(result.stderr().contains("Cannot read npm dist-tags"), result.stderr());
				assertEquals(published.size(), 0);
				return;
			}
			assertEquals(result.status(), 0, result.stderr());
			assertEquals(published.size(), 14);
			assertFalse(result.stdout().contains("Packages were not published"));
			for (int index = 0; index < published.size(); index += 1)
			{
				String base = "@holdmyspot/codex-unleashed";
				String access = "public";
				if (index >= 7)
				{
					base = "@holdmyspot/codex-unleashed-ea";
					access = "restricted";
				}
				int platform = index % 7;
				String expected = base;
				if (platform < 6)
					expected = base + "-" + NpmPlatform.values()[platform].packageSuffix();
				JsonNode metadata = published.get(index);
				assertEquals(metadata.get("name").stringValue(), expected);
				assertEquals(metadata.get("access").stringValue(), access);
				assertTrue(metadata.get("dist-tags").has(expectedTag), metadata.toString());
				assertEquals(metadata.get("dist-tags").get(expectedTag).stringValue(), "0.160.0-34");
				if (!expectedTag.equals("latest"))
					assertFalse(metadata.get("dist-tags").has("latest"));
				assertEquals(metadata.get("versions").get("0.160.0-34").get("name").stringValue(), expected);
				assertEquals(credentials.get(index), "Bearer fixture");
				JsonNode attachments = metadata.get("_attachments");
				assertEquals(attachments.size(), 1);
				JsonNode attachment = attachments.iterator().next();
				byte[] bytes = Base64.getDecoder().decode(attachment.get("data").stringValue());
				assertEquals(bytes.length, attachment.get("length").intValue());
				Path archive = root.resolve("published-" + index + ".tgz");
				Files.write(archive, bytes);
				Path unpacked = Files.createDirectory(root.resolve("published-" + index));
				NpmArchiveExtractor.extract(archive, unpacked, root);
				assertEquals(Files.readString(unpacked.resolve("package/licenses/example/LICENSE")), "inert fixture\n");
			}
		}
		finally
		{
			server.stop(0);
			delete(root);
		}
	}

	/**
	 * Supplies registry states that distinguish a new package, a newer vendor build, and a newer upstream release.
	 *
	 * @return the registry latest version and expected publication tag
	 */
	@DataProvider
	public Object[][] latestVersions()
	{
		return new Object[][] {{"", "latest", false}, {"0.160.0-9", "latest", false},
			{"0.160.0-40", "release-0.160.0-34", false}, {"0.161.0-1", "release-0.160.0-34", false},
			{"0.160.0-40", "", true}};
	}

	/**
	 * Produces all six complete platform archives with inert executables and legal materials.
	 *
	 * @param root the owned fixture root
	 * @return the archive directory
	 * @throws IOException if fixture production fails
	 */
	private static Path createArchives(Path root) throws IOException
	{
		Path archives = Files.createDirectory(root.resolve("archives"));
		for (NpmPlatform platform : NpmPlatform.values())
		{
			Path source = Files.createDirectory(root.resolve(platform.target()));
			for (String name : List.of("bin/codex", "LICENSE.md", "docs/LICENSE.html", "docs/terms.html",
				"docs/privacy.html", "licenses/example/LICENSE"))
			{
				Path file = source.resolve(name);
				Files.createDirectories(file.getParent());
				Files.writeString(file, "inert fixture\n");
			}
			Path archive = archives.resolve("codex-package-" + platform.target() + ".tar.gz");
			PackageArchives.write(new PackageArchives.Request(source, archive, false, root,
				new ArchiveEnvironment(Map.of()), List.of()), Clock.systemUTC(), _ ->
			{
				throw new AssertionError("gzip fixture must not run a compressor command");
			});
		}
		return archives;
	}

	/**
	 * Downloads release archives through the actual CLI, packs them, and cleans implicit output after lookup failure.
	 *
	 * @throws IOException if HTTP, fixtures, process execution, or cleanup fail
	 * @throws URISyntaxException if the fixture endpoint cannot be represented
	 */
	@Test
	public void downloadsAndPacksReleaseArchives() throws IOException, URISyntaxException
	{
		Path root = Files.createTempDirectory("npm-publish-download-");
		HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
		URI endpoint = new URI("http", null, server.getAddress().getAddress().getHostAddress(),
			server.getAddress().getPort(), "/", null, null);
		try
		{
			Path archives = createArchives(root);
			List<Map<String, String>> assets = new ArrayList<>();
			Map<String, Path> downloads = new HashMap<>();
			for (NpmPlatform platform : NpmPlatform.values())
			{
				String name = "codex-package-" + platform.target() + ".tar.gz";
				assets.add(Map.of("name", name, "browser_download_url", endpoint.resolve("assets/" + name).toString()));
				downloads.put("/assets/" + name, archives.resolve(name));
			}
			byte[] metadata = JsonMapper.builder().build().writeValueAsBytes(Map.of("assets", assets));
			List<String> lookups = Collections.synchronizedList(new ArrayList<>());
			server.createContext("/", exchange ->
			{
				try (exchange)
				{
					String path = exchange.getRequestURI().getPath();
					int status = 200;
					byte[] bytes = metadata;
					if (path.startsWith("/repos/"))
					{
						lookups.add(path);
						if (!path.startsWith("/repos/owner/repo/"))
							status = 403;
					}
					else if (downloads.containsKey(path))
						bytes = Files.readAllBytes(downloads.get(path));
					else
						status = 404;
					exchange.sendResponseHeaders(status, bytes.length);
					exchange.getResponseBody().write(bytes);
				}
			});
			server.start();
			Path output = root.resolve("downloaded-release");
			SystemCommands.Result result = executePublisher(command(root, List.of("--repository", "owner/repo",
				"--tag", "rust-v0.160.0+34", "--api-base", endpoint.toString(), "--output-dir", output.toString())), root);
			assertEquals(result.status(), 0, result.stderr());
			assertEquals(lookups, List.of("/repos/owner/repo/releases/tags/rust-v0.160.0+34"));
			for (NpmPlatform platform : NpmPlatform.values())
			{
				String name = "codex-package-" + platform.target() + ".tar.gz";
				assertEquals(Files.readAllBytes(output.resolve("archives").resolve(name)),
					Files.readAllBytes(archives.resolve(name)));
			}
			try (Stream<Path> files = Files.list(output))
			{
				assertEquals(files.filter(path -> path.toString().endsWith(".tgz")).count(), 14L);
			}
			SystemCommands.Result denied = executePublisher(command(root, List.of("--repository", "denied/repo",
				"--tag", "rust-v0.160.0+34", "--api-base", endpoint.toString())), root);
			assertEquals(denied.status(), 1, denied.stderr());
			assertTrue(denied.stderr().contains("GitHub release download failed: HTTP 403"));
			try (Stream<Path> files = Files.list(root))
			{
				assertEquals(files.filter(path -> path.getFileName().toString().startsWith("codex-npm-")).count(), 0L);
			}
		}
		finally
		{
			server.stop(0);
			delete(root);
		}
	}

	/**
	 * Constructs the actual CLI process with the current module path and explicit temporary storage.
	 *
	 * @param root the fixture temporary directory
	 * @param arguments the literal publication arguments
	 * @return the complete process command
	 */
	private static List<String> command(Path root, List<String> arguments)
	{
		Path java = Path.of(System.getProperty("java.home"), "bin", "java");
		if (!Files.isRegularFile(java))
			java = java.resolveSibling("java.exe");
		List<String> command = new ArrayList<>(List.of(java.toString(), "-Djava.io.tmpdir=" + root,
			"--module-path", System.getProperty("jdk.module.path"), "--module",
			Main.class.getModule().getName() + "/" + Main.class.getName(), "publish-npm-from-release"));
		command.addAll(arguments);
		return List.copyOf(command);
	}

	/**
	 * Isolates all child descriptors from Surefire, supplies EOF input, and captures native npm output on disk.
	 *
	 * @param command the actual CLI process and literal arguments
	 * @param root the caller-owned cwd and capture directory
	 * @return the ordinary status and separate UTF-8 streams
	 * @throws IOException if process startup, waiting, stream access, or cleanup fails
	 */
	private static SystemCommands.Result executePublisher(List<String> command, Path root) throws IOException
	{
		Path stdout = root.resolve("publisher.stdout");
		Path stderr = root.resolve("publisher.stderr");
		ProcessBuilder builder = SystemCommands.createBuilder(command).directory(root.toFile()).
			redirectOutput(stdout.toFile()).redirectError(stderr.toFile());
		try (Process process = builder.start())
		{
			process.getOutputStream().close();
			try
			{
				int status = process.waitFor();
				return new SystemCommands.Result(status, Files.readString(stdout), Files.readString(stderr));
			}
			catch (InterruptedException failure)
			{
				Thread.currentThread().interrupt();
				throw new IOException("Interrupted while waiting for the npm publisher fixture", failure);
			}
		}
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
