package com.holdmyspot.codexunleashed.tooling.release;

import com.holdmyspot.codexunleashed.tooling.Sha256;
import com.holdmyspot.codexunleashed.tooling.Main;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.apache.commons.compress.archivers.zip.ZipFile;
import org.testng.annotations.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertTrue;
import static org.testng.Assert.expectThrows;

/**
 * Verifies package command composition through actual resource extraction, directory assembly, and archive output.
 */
public final class PackageCommandTest
{
	private static final Clock CLOCK = Clock.fixed(Instant.ofEpochSecond(1_700_000_000), ZoneOffset.UTC);

	/**
	 * Creates package command tests.
	 */
	public PackageCommandTest()
	{
	}

	/**
	 * Builds branded app-server packages and repeated archive outputs, retaining raw bytes and explicit replacement.
	 *
	 * @throws IOException if fixture access, command composition, or archive reading fails
	 */
	@Test
	public void buildsPackageAndRepeatedArchives() throws IOException
	{
		try (Fixture fixture = Fixture.create(); PrintStream output =
			new PrintStream(fixture.output, true, StandardCharsets.UTF_8); PrintStream errors =
			new PrintStream(fixture.errors, true, StandardCharsets.UTF_8))
		{
			List<String> arguments = new ArrayList<>(fixture.arguments());
			Path directory = fixture.root.resolve("package");
			Path zip = fixture.root.resolve("package.zip");
			Path tar = fixture.root.resolve("package.tar.gz");
			arguments.addAll(List.of("--package-dir", directory.toString(), "--archive-output", zip.toString(),
				"--archive-output", tar.toString()));
			assertEquals(PackageCommand.run(arguments.toArray(String[]::new), output, errors,
				fixture.environment(), CLOCK), 0);
			String entrypoint = "bin/codex-app-server" + fixture.target.executableSuffix();
			assertEquals(Files.readAllBytes(directory.resolve(entrypoint)), fixture.payload);
			assertEquals(Files.readAllBytes(directory.resolve("codex-path/" + fixture.target.ripgrepName())),
				fixture.payload);
			String resource = "codex-resources/bwrap";
			if (fixture.target.isWindows())
				resource = "codex-resources/codex-command-runner.exe";
			assertEquals(Files.readAllBytes(directory.resolve(resource)), fixture.payload);
			assertFalse(Files.exists(directory.resolve("codex-resources/zsh")));
			assertTrue(Files.isRegularFile(directory.resolve("licenses/rust/THIRD_PARTY_NOTICES.md")));
			JsonNode metadata = JsonMapper.builder().build().readTree(
				Files.readString(directory.resolve("codex-package.json")));
			assertEquals(metadata.get("version").stringValue(), "0.160.0+41");
			assertEquals(metadata.get("variant").stringValue(), "codex-app-server");
			assertEquals(metadata.get("target").stringValue(), fixture.target.triple());
			try (ZipFile archive = ZipFile.builder().setPath(zip).get(); InputStream input =
				archive.getInputStream(archive.getEntry(entrypoint)))
			{
				assertEquals(input.readAllBytes(), fixture.payload);
			}
			assertTrue(Files.size(tar) > 0);
			assertEquals(fixture.output.toString(StandardCharsets.UTF_8), String.join(System.lineSeparator(),
				"Built Codex package archive at " + zip, "Built Codex package archive at " + tar,
				"Built Codex package directory at " + directory, ""));
			assertEquals(fixture.errors.size(), 0);
			Files.writeString(directory.resolve("obsolete"), "previous package");
			expectThrows(IOException.class, () -> PackageCommand.run(arguments.toArray(String[]::new), output, errors,
				fixture.environment(), CLOCK));
			assertTrue(Files.exists(directory.resolve("obsolete")));
			arguments.add("--force");
			assertEquals(PackageCommand.run(arguments.toArray(String[]::new), output, errors,
				fixture.environment(), CLOCK), 0);
			assertFalse(Files.exists(directory.resolve("obsolete")));
		}
	}

	/**
	 * Reports help and invalid target values before accessing input paths or starting builds.
	 *
	 * @throws IOException if command parsing unexpectedly performs file access
	 */
	@Test
	public void validatesArgumentsBeforeExecution() throws IOException
	{
		try (ByteArrayOutputStream bytes = new ByteArrayOutputStream(); PrintStream output =
			new PrintStream(bytes, true, StandardCharsets.UTF_8))
		{
			assertEquals(PackageCommand.run(new String[]{"--help"}, output, output, Map.of(), CLOCK), 0);
			assertTrue(bytes.toString(StandardCharsets.UTF_8).contains("build-codex-package"));
			assertEquals(PackageCommand.run(new String[]{"--repo", "missing", "--target", "unsupported"}, output, output,
				Map.of(), CLOCK), 2);
			assertEquals(PackageCommand.run(new String[]{"--repo", "missing", "--force=false"}, output, output,
				Map.of(), CLOCK), 2);
		}
	}

	/**
	 * Exposes the package command through the maintained CLI dispatcher.
	 *
	 * @throws IOException if closing caller-owned input fails
	 */
	@Test
	public void exposesPackageCommand() throws IOException
	{
		try (InputStream input = InputStream.nullInputStream(); ByteArrayOutputStream bytes = new ByteArrayOutputStream();
			PrintStream output = new PrintStream(bytes, true, StandardCharsets.UTF_8))
		{
			assertEquals(Main.run(new String[]{"build-codex-package", "--help"}, input, output, output), 0);
			assertTrue(bytes.toString(StandardCharsets.UTF_8).contains("build-codex-package"));
		}
	}

	/**
	 * Removes an implicitly owned partial package after legal-material failure while retaining explicit output semantics.
	 *
	 * @throws IOException if fixture access or cleanup fails
	 */
	@Test
	public void removesFailedImplicitPackage() throws IOException
	{
		try (Fixture fixture = Fixture.create(); PrintStream output =
			new PrintStream(fixture.output, true, StandardCharsets.UTF_8); PrintStream errors =
			new PrintStream(fixture.errors, true, StandardCharsets.UTF_8))
		{
			Files.delete(fixture.repository.resolve("docs/privacy.html"));
			expectThrows(IOException.class, () -> PackageCommand.run(fixture.arguments().toArray(String[]::new), output,
				errors, fixture.environment(), CLOCK));
			try (Stream<Path> children = Files.list(fixture.cache))
			{
				assertFalse(children.anyMatch(path -> path.getFileName().toString().startsWith("codex-package-")));
			}
			assertEquals(fixture.output.size(), 0);
		}
	}

	/**
	 * Owns independent prebuilt bytes, a local resource provider, legal inputs, and package output storage.
	 */
	private static final class Fixture implements AutoCloseable
	{
		private final Path root;
		private final Path repository;
		private final Path workspace;
		private final Path cache;
		private final Path binary;
		private final Path rgManifest;
		private final Path zshManifest;
		private final Path licenses;
		private final PackageTarget target;
		private final byte[] payload = {0, (byte) 255, 1};
		private final ByteArrayOutputStream output = new ByteArrayOutputStream();
		private final ByteArrayOutputStream errors = new ByteArrayOutputStream();

		/**
		 * Writes complete isolated packaging inputs with no executable model client or Python provider.
		 *
		 * @param root the owned fixture directory
		 * @throws IOException if fixture writing fails
		 */
		private Fixture(Path root) throws IOException
		{
			this.root = root;
			PackageTarget selected = PackageTarget.LINUX_X86_GNU;
			if (root.getFileSystem().getSeparator().equals("\\"))
				selected = PackageTarget.WINDOWS_X86;
			target = selected;
			repository = Files.createDirectory(root.resolve("repository"));
			workspace = Files.createDirectory(root.resolve("workspace"));
			cache = root.resolve("cache");
			for (String name : List.of("LICENSE.md", "licenses/openai-codex/LICENSE-APACHE-2.0", "docs/LICENSE.html",
				"docs/terms.html", "docs/privacy.html"))
			{
				Path document = repository.resolve(name);
				Files.createDirectories(document.getParent());
				Files.writeString(document, "offline document " + name + "\n");
			}
			Files.createDirectory(workspace.resolve("codex-rs"));
			Files.writeString(workspace.resolve("codex-rs/Cargo.toml"), "[workspace.package]\nversion = \"0.160.0\"\n");
			binary = Files.write(root.resolve("prebuilt.exe"), payload);
			if (Files.getFileAttributeView(binary, PosixFileAttributeView.class) != null)
				Files.setPosixFilePermissions(binary, PosixFilePermissions.fromString("rw---x---"));
			Path rgSource = Files.createDirectory(root.resolve("ripgrep-source"));
			Files.write(rgSource.resolve(target.ripgrepName()), payload);
			Path archive = root.resolve("ripgrep.zip");
			PackageArchives.write(new PackageArchives.Request(rgSource, archive, false, root.resolve("archive-temp"),
				ArchiveOptions.defaults(), List.of()), CLOCK, command -> "");
			rgManifest = Files.writeString(root.resolve("rg-manifest"), "{\"platforms\":{\"" +
				target.dotslashPlatform() + "\":{\"size\":" +
				Files.size(archive) + ",\"hash\":\"sha256\",\"digest\":\"" + Sha256.digest(archive) +
				"\",\"format\":\"zip\",\"path\":\"" + target.ripgrepName() +
				"\",\"providers\":[{\"url\":\"" + archive.toUri() + "\"}]}}}");
			zshManifest = Files.writeString(root.resolve("zsh-manifest"), "{\"platforms\":{}}");
			licenses = Files.createDirectory(root.resolve("licenses"));
			Files.writeString(licenses.resolve("THIRD_PARTY_NOTICES.md"), "prepared notices\n");
		}

		/**
		 * Allocates fixture storage and removes partial initialization on failure.
		 *
		 * @return the initialized fixture
		 * @throws IOException if fixture creation fails
		 */
		private static Fixture create() throws IOException
		{
			Path root = Files.createTempDirectory("package-command-");
			try
			{
				return new Fixture(root);
			}
			catch (IOException | RuntimeException failure)
			{
				try
				{
					delete(root);
				}
				catch (IOException cleanupFailure)
				{
					failure.addSuppressed(cleanupFailure);
				}
				throw failure;
			}
		}

		/**
		 * Supplies retained package inputs and explicit Java context paths.
		 *
		 * @return the command arguments
		 */
		private List<String> arguments()
		{
			List<String> arguments = new ArrayList<>(List.of("--repo", repository.toString(), "--workspace",
				workspace.toString(), "--cache-root", cache.toString(), "--target", target.triple(),
				"--variant", "codex-app-server",
				"--entrypoint-bin", binary.toString(), "--code-mode-host-bin", binary.toString(), "--bwrap-bin",
				binary.toString(), "--rg-manifest", rgManifest.toString(), "--zsh-manifest", zshManifest.toString()));
			if (target.isWindows())
				arguments.addAll(List.of("--codex-command-runner-bin", binary.toString(),
					"--codex-windows-sandbox-setup-bin", binary.toString()));
			return arguments;
		}

		/**
		 * Selects exact build identity and prepared legal materials.
		 *
		 * @return the explicit caller environment
		 */
		private Map<String, String> environment()
		{
			return Map.of("CODEX_UNLEASHED_BUILD_NUMBER", "41", "CODEX_PACKAGE_RUST_LICENSES_DIR", licenses.toString());
		}

		/**
		 * Removes all owned package data.
		 *
		 * @throws IOException if cleanup fails
		 */
		@Override
		public void close() throws IOException
		{
			delete(root);
		}

		/**
		 * Deletes owned paths without following directory symlinks.
		 *
		 * @param root the fixture directory
		 * @throws IOException if cleanup fails
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
}
