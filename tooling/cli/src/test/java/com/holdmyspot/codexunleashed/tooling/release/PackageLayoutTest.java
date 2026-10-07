package com.holdmyspot.codexunleashed.tooling.release;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;
import org.testng.annotations.Test;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertTrue;
import static org.testng.Assert.expectThrows;

/**
 * Verifies canonical directories, metadata bytes, required resources, and explicit output replacement.
 */
public final class PackageLayoutTest
{
	/**
	 * Creates the canonical package layout tests.
	 */
	public PackageLayoutTest()
	{
	}

	/**
	 * Places the app-server and code-mode host together and preserves package metadata key order.
	 *
	 * @throws IOException if fixture access, building, or validation fails
	 */
	@Test
	public void buildsAppServerLayout() throws IOException
	{
		try (Fixture fixture = Fixture.create())
		{
			PackageLayout.Request request = fixture.request(PackageTarget.LINUX_X86_MUSL, PackageVariant.APP_SERVER,
				fixture.inputs(Optional.of(fixture.binary), Optional.of(fixture.binary)));
			PackageLayout.prepare(fixture.output, false);
			PackageLayout.build(request, command ->
			{
				throw new AssertionError("No workspace metadata is required");
			});
			PackageLayout.validate(fixture.output, request.variant(), request.target(), true);
			for (String name : List.of("bin/codex-app-server", "bin/codex-code-mode-host", "codex-path/rg",
				"codex-resources/zsh/bin/zsh", "codex-resources/bwrap"))
				assertEquals(Files.readAllBytes(fixture.output.resolve(name)), Files.readAllBytes(fixture.binary));
			assertEquals(Files.readString(fixture.output.resolve("codex-package.json")), """
				{
				  "layoutVersion": 1,
				  "version": "1.2.3",
				  "target": "x86_64-unknown-linux-musl",
				  "variant": "codex-app-server",
				  "entrypoint": "bin/codex-app-server",
				  "resourcesDir": "codex-resources",
				  "pathDir": "codex-path"
				}
				""");
			Path entrypoint = fixture.output.resolve("bin/codex-app-server");
			if (Files.getFileAttributeView(entrypoint, PosixFileAttributeView.class) != null)
			{
				Set<PosixFilePermission> permissions = Files.getPosixFilePermissions(entrypoint);
				assertTrue(permissions.containsAll(PosixFilePermissions.fromString("--x--x--x")));
				Files.setPosixFilePermissions(entrypoint, PosixFilePermissions.fromString("-----x---"));
				expectThrows(IOException.class, () -> PackageLayout.validate(fixture.output, request.variant(),
					request.target(), true));
				Files.setPosixFilePermissions(entrypoint, PosixFilePermissions.fromString("rw-r--r--"));
				IOException failure = expectThrows(IOException.class, () -> PackageLayout.validate(fixture.output,
					request.variant(), request.target(), true));
				assertTrue(failure.getMessage().contains("Package file is not executable"));
			}
		}
	}

	/**
	 * Uses Windows filenames and validates Windows resources without requiring executable bits.
	 *
	 * @throws IOException if fixture access, building, or validation fails
	 */
	@Test
	public void buildsWindowsLayout() throws IOException
	{
		try (Fixture fixture = Fixture.create())
		{
			PackageInputs inputs = new PackageInputs(fixture.binary, fixture.binary, fixture.binary,
				Optional.empty(), Optional.empty(), Optional.of(fixture.binary), Optional.of(fixture.binary));
			PackageLayout.Request request = fixture.request(PackageTarget.WINDOWS_X86, PackageVariant.CODEX, inputs);
			PackageLayout.prepare(fixture.output, false);
			PackageLayout.build(request, command ->
			{
				throw new AssertionError("No workspace metadata is required");
			});
			PackageLayout.validate(fixture.output, request.variant(), request.target(), false);
			for (String name : List.of("bin/codex.exe", "bin/codex-code-mode-host.exe", "codex-path/rg.exe",
				"codex-resources/codex-command-runner.exe", "codex-resources/codex-windows-sandbox-setup.exe"))
				assertTrue(Files.isRegularFile(fixture.output.resolve(name)));
			assertFalse(Files.exists(fixture.output.resolve("codex-resources/zsh")));
			Files.delete(fixture.output.resolve("codex-resources/codex-command-runner.exe"));
			expectThrows(IOException.class, () -> PackageLayout.validate(fixture.output, request.variant(),
				request.target(), false));
		}
	}

	/**
	 * Rejects nonempty output unless replacement is explicit and preserves other directories.
	 *
	 * @throws IOException if fixture access or output preparation fails
	 */
	@Test
	public void replacesOutputOnlyWhenForced() throws IOException
	{
		try (Fixture fixture = Fixture.create())
		{
			Files.createDirectory(fixture.output);
			Files.writeString(fixture.output.resolve("old-artifact"), "old artifact");
			expectThrows(IOException.class, () -> PackageLayout.prepare(fixture.output, false));
			assertTrue(Files.exists(fixture.output.resolve("old-artifact")));
			PackageLayout.prepare(fixture.output, true);
			try (Stream<Path> paths = Files.list(fixture.output))
			{
				assertEquals(paths.count(), 0L);
			}
			assertTrue(Files.exists(fixture.binary));
			Files.delete(fixture.output);
			Files.writeString(fixture.output, "not a directory");
			expectThrows(IOException.class, () -> PackageLayout.prepare(fixture.output, true));
			assertEquals(Files.readString(fixture.output), "not a directory");
		}
	}

	/**
	 * Rejects incorrect metadata and missing Linux resources after assembly.
	 *
	 * @throws IOException if fixture access or building fails
	 */
	@Test
	public void rejectsInvalidArtifacts() throws IOException
	{
		try (Fixture fixture = Fixture.create())
		{
			PackageLayout.Request request = fixture.request(PackageTarget.LINUX_ARM_GNU, PackageVariant.CODEX,
				fixture.inputs(Optional.empty(), Optional.empty()));
			PackageLayout.prepare(fixture.output, false);
			PackageLayout.build(request, command ->
			{
				throw new AssertionError("No workspace metadata is required");
			});
			IOException missing = expectThrows(IOException.class, () -> PackageLayout.validate(fixture.output,
				request.variant(), request.target(), false));
			assertTrue(missing.getMessage().contains("codex-resources/bwrap"));
			Files.writeString(fixture.output.resolve("codex-package.json"), "{}");
			IOException metadata = expectThrows(IOException.class, () -> PackageLayout.validate(fixture.output,
				request.variant(), request.target(), false));
			assertTrue(metadata.getMessage().contains("Invalid package metadata field"));
		}
	}

	/**
	 * Owns a minimal offline source repository, binary input, and package output.
	 */
	private static final class Fixture implements AutoCloseable
	{
		private final Path root;
		private final Path repository;
		private final Path binary;
		private final Path output;

		/**
		 * Creates byte fixtures and all required offline documents.
		 *
		 * @param root the owned fixture root
		 * @throws IOException if initialization fails
		 */
		private Fixture(Path root) throws IOException
		{
			this.root = root;
			repository = Files.createDirectory(root.resolve("repository"));
			binary = Files.write(root.resolve("binary"), new byte[]{0, (byte) 255, 1});
			output = root.resolve("package");
			for (String name : List.of("LICENSE.md", "licenses/openai-codex/LICENSE-APACHE-2.0",
				"docs/LICENSE.html", "docs/terms.html", "docs/privacy.html"))
			{
				Path document = repository.resolve(name);
				Files.createDirectories(document.getParent());
				Files.writeString(document, "offline document\n");
			}
		}

		/**
		 * Allocates a fixture and cleans partial initialization on failure.
		 *
		 * @return the initialized fixture
		 * @throws IOException if initialization fails
		 */
		private static Fixture create() throws IOException
		{
			Path root = Files.createTempDirectory("package-layout-");
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
		 * Constructs explicit binary inputs with optional Unix resources.
		 *
		 * @param zsh the optional zsh binary
		 * @param bwrap the optional bwrap binary
		 * @return the package inputs
		 */
		private PackageInputs inputs(Optional<Path> zsh, Optional<Path> bwrap)
		{
			return new PackageInputs(binary, binary, binary, zsh, bwrap, Optional.empty(), Optional.empty());
		}

		/**
		 * Creates an assembly request without requiring a workspace or prepared licenses.
		 *
		 * @param target the selected target
		 * @param variant the selected variant
		 * @param inputs the binary inputs
		 * @return the assembly request
		 */
		private PackageLayout.Request request(PackageTarget target, PackageVariant variant, PackageInputs inputs)
		{
			return new PackageLayout.Request(output, "1.2.3", variant, target, inputs, repository, Optional.empty(),
				Optional.empty());
		}

		/**
		 * Deletes the owned fixture tree.
		 *
		 * @throws IOException if cleanup fails
		 */
		@Override
		public void close() throws IOException
		{
			delete(root);
		}

		/**
		 * Removes fixture paths without following directory symlinks.
		 *
		 * @param root the owned fixture root
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
