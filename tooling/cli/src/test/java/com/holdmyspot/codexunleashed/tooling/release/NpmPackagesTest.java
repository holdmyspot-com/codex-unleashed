package com.holdmyspot.codexunleashed.tooling.release;

import com.holdmyspot.codexunleashed.tooling.PayloadDirectories;
import com.holdmyspot.codexunleashed.tooling.SystemCommands;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import org.testng.annotations.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertTrue;
import static org.testng.Assert.expectThrows;

/**
 * Verifies both npm package families, all native payloads, legal materials, selector metadata, and launcher output.
 */
public final class NpmPackagesTest
{
	private static final List<Map.Entry<String, String>> TARGETS = List.of(
		Map.entry("x86_64-unknown-linux-musl", "linux-x64"), Map.entry("aarch64-unknown-linux-musl", "linux-arm64"),
		Map.entry("x86_64-apple-darwin", "darwin-x64"), Map.entry("aarch64-apple-darwin", "darwin-arm64"),
		Map.entry("x86_64-pc-windows-msvc", "win32-x64"), Map.entry("aarch64-pc-windows-msvc", "win32-arm64"));

	/**
	 * Creates npm assembly tests.
	 */
	public NpmPackagesTest()
	{
	}

	/**
	 * Assembles fourteen packages with unchanged vendor trees, exact platform metadata, and family-specific access.
	 *
	 * @throws IOException if fixture access, assembly, or cleanup fails
	 */
	@Test
	public void assemblesBothPackageFamilies() throws IOException
	{
		Path root = Files.createTempDirectory("npm-packages-");
		try
		{
			Map<String, Path> sources = sources(root);
			Path packages = root.resolve("packages");
			List<NpmPackages.Directory> directories = NpmPackages.assemble(new NpmPackages.Request(sources, packages,
				"@holdmyspot", "0.160.0-34", "http://127.0.0.1:4873"));
			assertEquals(directories.size(), 14);
			for (String family : List.of("public", "ea"))
			{
				String base = "codex-unleashed";
				String access = "public";
				if (family.equals("ea"))
				{
					base = "codex-unleashed-ea";
					access = "restricted";
				}
				for (Map.Entry<String, String> target : TARGETS)
				{
					Path directory = packages.resolve(family).resolve(target.getValue());
					assertTrue(directories.contains(new NpmPackages.Directory(directory, access)));
					JsonNode metadata = JsonMapper.builder().build().readTree(
						Files.readString(directory.resolve("package.json")));
					assertEquals(metadata.get("name").stringValue(), "@holdmyspot/" + base + "-" + target.getValue());
					assertEquals(metadata.get("version").stringValue(), "0.160.0-34");
					assertEquals(metadata.get("license").stringValue(), "See LICENSE.md");
					String[] platform = target.getValue().split("-");
					assertEquals(metadata.get("os").get(0).stringValue(), platform[0]);
					assertEquals(metadata.get("cpu").get(0).stringValue(), platform[1]);
					assertEquals(metadata.get("files").get(0).stringValue(), "vendor");
					assertEquals(Files.readAllBytes(directory.resolve("licenses/example/LICENSE")), new byte[]{0, (byte) 255});
					assertEquals(Files.readString(directory.resolve("docs/privacy.html")), target.getKey());
					Path vendor = directory.resolve("vendor").resolve(target.getKey());
					assertEquals(Files.readAllBytes(vendor.resolve("codex-path/rg")),
						new byte[]{0, (byte) 255, 1});
					assertEquals(Files.readString(vendor.resolve("codex-package.json")),
						"{\"version\":\"0.160.0+34\"}\n");
				}
				Path main = packages.resolve(family).resolve("main");
				assertTrue(directories.contains(new NpmPackages.Directory(main, access)));
				JsonNode metadata = JsonMapper.builder().build().readTree(Files.readString(main.resolve("package.json")));
				assertEquals(metadata.get("name").stringValue(), "@holdmyspot/" + base);
				assertEquals(metadata.get("type").stringValue(), "module");
				assertEquals(metadata.get("bin").get("codex").stringValue(), "bin/codex.js");
				assertEquals(metadata.get("publishConfig").get("registry").stringValue(), "http://127.0.0.1:4873");
				for (Map.Entry<String, String> target : TARGETS)
					assertEquals(metadata.get("optionalDependencies").get("@holdmyspot/" + base + "-" + target.getValue()).
						stringValue(), "0.160.0-34");
				assertEquals(Files.readString(main.resolve("docs/privacy.html")), TARGETS.getFirst().getKey());
				assertEquals(Files.readAllBytes(main.resolve("licenses/example/LICENSE")), new byte[]{0, (byte) 255});
				String launcher = Files.readString(main.resolve("bin/codex.js"));
				assertTrue(launcher.startsWith("#!/usr/bin/env node\n"));
				assertTrue(launcher.contains("`@holdmyspot/" + base + "-${platformName}`"));
				assertFalse(launcher.contains("__PLATFORM_PACKAGE_PREFIX__"));
				if (Files.getFileAttributeView(main, PosixFileAttributeView.class) != null)
					assertEquals(Files.getPosixFilePermissions(main.resolve("bin/codex.js")),
						PosixFilePermissions.fromString("rwxr-xr-x"));
				assertTrue(Files.readString(main.resolve("README.md")).startsWith("# @holdmyspot/" + base + "\n\n"));
			}
			assertEquals(directories.getFirst().path(), packages.resolve("public/linux-x64"));
			assertEquals(directories.get(6).path(), packages.resolve("public/main"));
			assertEquals(directories.get(7).path(), packages.resolve("ea/linux-x64"));
			assertEquals(directories.getLast().path(), packages.resolve("ea/main"));
		}
		finally
		{
			delete(root);
		}
	}

	/**
	 * Requires all six source platforms and an at-prefixed scope before allocating package output.
	 *
	 * @throws IOException if fixture access or cleanup fails
	 */
	@Test
	public void validatesAssemblyInputs() throws IOException
	{
		Path root = Files.createTempDirectory("npm-packages-invalid-");
		try
		{
			Map<String, Path> sources = sources(root);
			Path output = root.resolve("packages");
			expectThrows(IllegalArgumentException.class, () -> NpmPackages.assemble(new NpmPackages.Request(sources, output,
				"holdmyspot", "0.160.0-34", "local-registry")));
			assertFalse(Files.exists(output));
			sources.remove(TARGETS.getLast().getKey());
			expectThrows(IllegalArgumentException.class, () -> NpmPackages.assemble(new NpmPackages.Request(sources, output,
				"@holdmyspot", "0.160.0-34", "local-registry")));
			assertFalse(Files.exists(output));
		}
		finally
		{
			delete(root);
		}
	}

	/**
	 * Refuses a platform archive without generated third-party license materials.
	 *
	 * @throws IOException if fixture access or cleanup fails
	 */
	@Test
	public void requiresLicenseDirectory() throws IOException
	{
		Path root = Files.createTempDirectory("npm-packages-licenses-");
		try
		{
			Map<String, Path> sources = sources(root);
			Files.move(sources.get(TARGETS.getFirst().getKey()).resolve("licenses"), root.resolve("saved-licenses"));
			IOException failure = expectThrows(IOException.class, () -> NpmPackages.assemble(
				new NpmPackages.Request(sources, root.resolve("packages"), "@holdmyspot", "0.160.0-34", "local-registry")));
			assertTrue(failure.getMessage().contains("Missing generated third-party licenses"));
		}
		finally
		{
			delete(root);
		}
	}

	/**
	 * Runs the bundled selector through Node with an installed optional package and its vendor fallback.
	 * A copied Node executable stands in for Codex and reports literal arguments without model calls.
	 *
	 * @throws IOException if native commands, fixture access, or cleanup fails
	 */
	@Test
	public void launchesOptionalAndFallbackPayloads() throws IOException
	{
		Path root = Files.createTempDirectory("npm-launcher-");
		try
		{
			Map<String, Path> inputs = sources(root);
			SystemCommands.Result host = capture(List.of("node", "-p",
				"JSON.stringify({os:process.platform,cpu:process.arch,executable:process.execPath})"), root, root, Map.of());
			assertEquals(host.status(), 0, host.stderr());
			JsonNode information = JsonMapper.builder().build().readTree(host.stdout());
			String suffix = information.get("os").stringValue() + "-" + information.get("cpu").stringValue();
			String target = TARGETS.stream().filter(entry -> entry.getValue().equals(suffix)).findFirst().
				orElseThrow().getKey();
			String executable = "codex";
			if (information.get("os").stringValue().equals("win32"))
				executable = "codex.exe";
			Path bin = Files.createDirectory(inputs.get(target).resolve("bin"));
			Files.copy(Path.of(information.get("executable").stringValue()), bin.resolve(executable));
			Path packages = root.resolve("packages");
			NpmPackages.assemble(new NpmPackages.Request(inputs, packages, "@holdmyspot", "0.160.0-34", "local"));
			for (String family : List.of("public", "ea"))
			{
				String base = "codex-unleashed";
				if (family.equals("ea"))
					base = "codex-unleashed-ea";
				Path main = packages.resolve(family).resolve("main");
				Path scope = Files.createDirectories(main.resolve("node_modules/@holdmyspot"));
				PayloadDirectories.copy(packages.resolve(family).resolve(suffix), scope.resolve(base + "-" + suffix));
				assertLauncher(main, root);
				delete(main.resolve("node_modules"));
				Path vendor = Files.createDirectory(main.resolve("vendor"));
				PayloadDirectories.copy(inputs.get(target), vendor.resolve(target));
				assertLauncher(main, root);
			}
		}
		finally
		{
			delete(root);
		}
	}

	/**
	 * Packs all fourteen assembled packages through npm into a directory independent of each package's cwd.
	 *
	 * @throws IOException if npm, archive reading, fixture access, or cleanup fails
	 */
	@Test
	public void packsBothFamiliesThroughNpm() throws IOException
	{
		Path root = Files.createTempDirectory("npm-pack-consumer-");
		try
		{
			Path output = Files.createDirectory(root.resolve("archives"));
			Path npmCache = Files.createDirectory(root.resolve("npm-cache"));
			List<NpmPackages.Directory> packages = NpmPackages.assemble(new NpmPackages.Request(sources(root),
				root.resolve("packages"), "@holdmyspot", "0.160.0-34", "http://127.0.0.1:4873"));
			Set<Path> packed = new HashSet<>();
			for (NpmPackages.Directory directory : packages)
			{
				SystemCommands.Result result = capture(List.of("npm", "pack", "--json", "--pack-destination",
					output.toString(), "--registry", "http://127.0.0.1:4873"), directory.path(), root,
					Map.of("NPM_CONFIG_CACHE", npmCache.toString(), "XDG_CACHE_HOME", npmCache.toString()));
				assertEquals(result.status(), 0, result.stderr());
				Path archive;
				try (Stream<Path> files = Files.list(output))
				{
					List<Path> added = files.filter(path -> !packed.contains(path)).toList();
					assertEquals(added.size(), 1, result.stdout());
					archive = added.getFirst();
					packed.add(archive);
				}
				Path unpacked = Files.createTempDirectory(root, "unpacked-");
				NpmArchiveExtractor.extract(archive, unpacked, root);
				Path payload = unpacked.resolve("package");
				assertEquals(Files.readAllBytes(payload.resolve("licenses/example/LICENSE")), new byte[]{0, (byte) 255});
				assertEquals(Files.readString(payload.resolve("package.json")),
					Files.readString(directory.path().resolve("package.json")));
				assertTrue(Files.isRegularFile(payload.resolve("docs/privacy.html")));
				delete(unpacked);
			}
			try (Stream<Path> archives = Files.list(output))
			{
				assertEquals(archives.count(), 14L);
			}
		}
		finally
		{
			delete(root);
		}
	}

	/**
	 * Checks the selector's literal argument and exit-status handoff to the inert executable fixture.
	 *
	 * @param main the selector package
	 * @param root the capture and cwd fixture
	 * @throws IOException if Node execution fails
	 */
	private static void assertLauncher(Path main, Path root) throws IOException
	{
		SystemCommands.Result result = capture(List.of("node", main.resolve("bin/codex.js").toString(),
			"-e", "console.log(JSON.stringify(process.argv.slice(1)));process.exit(7)", "--",
			"space value", "雪", "--literal"),
			root, root, Map.of());
		assertEquals(result.status(), 7, result.stderr());
		assertEquals(result.stdout(), "[\"space value\",\"雪\",\"--literal\"]\n");
	}

	/**
	 * Isolates native Node and npm descriptors from the test runner's control streams.
	 *
	 * @param command the literal process arguments
	 * @param working the process cwd
	 * @param root the caller-owned capture directory
	 * @param environment the explicit environment overrides
	 * @return the status and both captured streams
	 * @throws IOException if execution, decoding, or cleanup fails
	 */
	private static SystemCommands.Result capture(List<String> command, Path working, Path root,
		Map<String, String> environment) throws IOException
	{
		Path capture = Files.createTempDirectory(root, "native-capture-");
		try
		{
			Path stdout = capture.resolve("stdout");
			Path stderr = capture.resolve("stderr");
			ProcessBuilder builder = new ProcessBuilder(command).directory(working.toFile()).redirectOutput(stdout.toFile()).
				redirectError(stderr.toFile());
			builder.environment().putAll(environment);
			try (Process process = builder.start())
			{
				process.getOutputStream().close();
				int status = process.waitFor();
				return new SystemCommands.Result(status, Files.readString(stdout), Files.readString(stderr));
			}
			catch (InterruptedException failure)
			{
				Thread.currentThread().interrupt();
				throw new IOException("Native npm consumer interrupted", failure);
			}
		}
		finally
		{
			delete(capture);
		}
	}

	/**
	 * Creates six complete raw package trees with independent document contents and no required Rust notices file.
	 *
	 * @param root the fixture root
	 * @return the target-to-source mapping
	 * @throws IOException if writing fails
	 */
	private static Map<String, Path> sources(Path root) throws IOException
	{
		Map<String, Path> result = new LinkedHashMap<>();
		for (Map.Entry<String, String> target : TARGETS)
		{
			Path source = Files.createDirectory(root.resolve(target.getKey()));
			for (String document : List.of("LICENSE.md", "docs/LICENSE.html", "docs/terms.html", "docs/privacy.html"))
			{
				Path file = source.resolve(document);
				Files.createDirectories(file.getParent());
				Files.writeString(file, target.getKey());
			}
			Files.createDirectories(source.resolve("licenses/example"));
			Files.write(source.resolve("licenses/example/LICENSE"), new byte[]{0, (byte) 255});
			Files.createDirectories(source.resolve("codex-path"));
			Files.write(source.resolve("codex-path/rg"), new byte[]{0, (byte) 255, 1});
			Files.writeString(source.resolve("codex-package.json"), "{\"version\":\"0.160.0+34\"}\n");
			result.put(target.getKey(), source);
		}
		return result;
	}

	/**
	 * Deletes owned fixture paths without following directory links.
	 *
	 * @param root the fixture root
	 * @throws IOException if deletion fails
	 */
	private static void delete(Path root) throws IOException
	{
		try (Stream<Path> paths = Files.walk(root))
		{
			for (Path path : paths.sorted(Comparator.reverseOrder()).toList())
				Files.delete(path);
		}
	}
}
