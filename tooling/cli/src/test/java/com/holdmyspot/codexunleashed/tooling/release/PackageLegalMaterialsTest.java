package com.holdmyspot.codexunleashed.tooling.release;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;
import org.testng.annotations.Test;
import tools.jackson.databind.json.JsonMapper;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertTrue;
import static org.testng.Assert.expectThrows;

/**
 * Verifies offline legal documents, prepared Cargo payload priority, and workspace metadata fallback.
 */
public final class PackageLegalMaterialsTest
{
	private static final List<String> DOCUMENTS = List.of("LICENSE.md", "licenses/openai-codex/LICENSE-APACHE-2.0",
		"docs/LICENSE.html", "docs/terms.html", "docs/privacy.html");

	/**
	 * Creates the package legal-material tests.
	 */
	public PackageLegalMaterialsTest()
	{
	}

	/**
	 * Copies documents and prepared dependency payloads without invoking Cargo even when a workspace is supplied.
	 *
	 * @throws IOException if fixture access or copying fails
	 */
	@Test
	public void copiesPreparedFiles() throws IOException
	{
		try (Fixture fixture = Fixture.create())
		{
			Path prepared = Files.createDirectory(fixture.root.resolve("prepared"));
			byte[] payload = {0, (byte) 255, 10};
			Files.write(Files.createDirectory(prepared.resolve("crate-1.2.3")).resolve("LICENSE-MIT"), payload);
			Files.writeString(prepared.resolve("THIRD_PARTY_NOTICES.md"), "prepared notices\n");
			PackageLegalMaterials.copy(fixture.repository, fixture.output, Optional.of(prepared),
				Optional.of(fixture.root.resolve("workspace")), command ->
				{
					throw new AssertionError("Prepared payloads must not rerun Cargo: " + command);
				});
			for (String document : DOCUMENTS)
				assertEquals(Files.readAllBytes(fixture.output.resolve(document)),
					Files.readAllBytes(fixture.repository.resolve(document)));
			assertEquals(Files.readAllBytes(fixture.output.resolve("licenses/rust/crate-1.2.3/LICENSE-MIT")), payload);
			assertEquals(Files.readString(fixture.output.resolve("licenses/rust/THIRD_PARTY_NOTICES.md")),
				"prepared notices\n");
		}
	}

	/**
	 * Preserves prepared payload timestamps and permissions as directory-copy metadata.
	 *
	 * @throws IOException if fixture access or copying fails
	 */
	@Test
	public void preservesPreparedAttributes() throws IOException
	{
		try (Fixture fixture = Fixture.create())
		{
			Path prepared = Files.createDirectory(fixture.root.resolve("prepared"));
			Path license = Files.writeString(prepared.resolve("LICENSE"), "dependency license\n");
			Files.writeString(prepared.resolve("THIRD_PARTY_NOTICES.md"), "prepared notices\n");
			FileTime modified = FileTime.fromMillis(1000);
			Files.setLastModifiedTime(license, modified);
			Files.setLastModifiedTime(prepared, modified);
			Set<PosixFilePermission> permissions = Set.copyOf(PosixFilePermissions.fromString("r--r-----"));
			if (Files.getFileAttributeView(license, PosixFileAttributeView.class) != null)
				Files.setPosixFilePermissions(license, permissions);
			PackageLegalMaterials.copy(fixture.repository, fixture.output, Optional.of(prepared), Optional.empty(),
				command ->
				{
					throw new AssertionError("Prepared attributes must not require Cargo");
				});
			Path copied = fixture.output.resolve("licenses/rust/LICENSE");
			assertEquals(Files.getLastModifiedTime(copied), modified);
			assertEquals(Files.getLastModifiedTime(copied.getParent()), modified);
			if (Files.getFileAttributeView(license, PosixFileAttributeView.class) != null)
				assertEquals(Files.getPosixFilePermissions(copied), permissions);
		}
	}

	/**
	 * Rejects missing prepared payloads and notices before attempting the workspace fallback.
	 *
	 * @throws IOException if fixture access fails
	 */
	@Test
	public void rejectsIncompletePreparedFiles() throws IOException
	{
		try (Fixture fixture = Fixture.create())
		{
			Path prepared = fixture.root.resolve("prepared");
			IOException missingDirectory = expectThrows(IOException.class, () -> PackageLegalMaterials.copy(
				fixture.repository, fixture.output, Optional.of(prepared), Optional.empty(), command ->
				{
					throw new AssertionError("Invalid prepared path must not invoke Cargo");
				}));
			assertTrue(missingDirectory.getMessage().contains("Prepared Cargo license directory does not exist"));
			Files.createDirectory(prepared);
			IOException missingNotices = expectThrows(IOException.class, () -> PackageLegalMaterials.copy(
				fixture.repository, fixture.output, Optional.of(prepared), Optional.empty(), command ->
				{
					throw new AssertionError("Missing prepared notices must not invoke Cargo");
				}));
			assertTrue(missingNotices.getMessage().contains("Prepared Cargo license notices do not exist"));
		}
	}

	/**
	 * Runs the locked metadata boundary only for an existing workspace manifest and collects its dependencies.
	 *
	 * @throws IOException if fixture access or copying fails
	 */
	@Test
	public void collectsWorkspaceFallback() throws IOException
	{
		try (Fixture fixture = Fixture.create())
		{
			Path workspace = Files.createDirectory(fixture.root.resolve("workspace"));
			Path manifest = Files.writeString(Files.createDirectory(workspace.resolve("codex-rs")).resolve("Cargo.toml"),
				"[workspace]\n");
			Path crate = Files.createDirectory(fixture.root.resolve("dependency"));
			Path dependencyManifest = Files.writeString(crate.resolve("Cargo.toml"), "[package]\n");
			Files.writeString(crate.resolve("LICENSE"), "dependency license\n");
			String metadata = JsonMapper.builder().build().writeValueAsString(Map.of("packages", List.of(Map.of(
				"id", "dependency", "name", "dependency", "version", "1.2.3", "manifest_path",
				dependencyManifest.toString(), "license", "MIT"))));
			PackageLegalMaterials.copy(fixture.repository, fixture.output, Optional.empty(), Optional.of(workspace),
				command ->
				{
					assertEquals(command, List.of("cargo", "metadata", "--format-version", "1", "--locked",
						"--manifest-path", manifest.toString()));
					return metadata;
				});
			assertEquals(Files.readString(fixture.output.resolve("licenses/rust/dependency-1.2.3/LICENSE")),
				"dependency license\n");
		}
	}

	/**
	 * Keeps documents available when no workspace manifest is supplied and rejects missing product documents.
	 *
	 * @throws IOException if fixture access or copying fails
	 */
	@Test
	public void handlesAbsentWorkspace() throws IOException
	{
		try (Fixture fixture = Fixture.create())
		{
			PackageLegalMaterials.copy(fixture.repository, fixture.output, Optional.empty(),
				Optional.of(fixture.root.resolve("missing-workspace")), command ->
				{
					throw new AssertionError("Absent workspace manifest must not invoke Cargo");
				});
			assertFalse(Files.exists(fixture.output.resolve("licenses/rust")));
			Files.delete(fixture.repository.resolve("docs/privacy.html"));
			IOException failure = expectThrows(IOException.class, () -> PackageLegalMaterials.copy(fixture.repository,
				fixture.output, Optional.empty(), Optional.empty(), command ->
				{
					throw new AssertionError("Missing document must fail before Cargo");
				}));
			assertTrue(failure.getMessage().contains("Missing legal material"));
		}
	}

	/**
	 * Owns all test inputs and output paths.
	 */
	private static final class Fixture implements AutoCloseable
	{
		private final Path root;
		private final Path repository;
		private final Path output;

		/**
		 * Creates the offline source documents under an owned root.
		 *
		 * @param root the owned fixture directory
		 * @throws IOException if setup fails
		 */
		private Fixture(Path root) throws IOException
		{
			this.root = root;
			repository = Files.createDirectory(root.resolve("repository"));
			output = Files.createDirectory(root.resolve("package"));
			for (String document : DOCUMENTS)
			{
				Path source = repository.resolve(document);
				Files.createDirectories(source.getParent());
				Files.write(source, new byte[]{0, (byte) 255, 10});
			}
		}

		/**
		 * Allocates a fixture and retains cleanup ownership if initialization fails.
		 *
		 * @return the initialized fixture
		 * @throws IOException if setup fails
		 */
		private static Fixture create() throws IOException
		{
			Path root = Files.createTempDirectory("package-legal-materials-");
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
		 * Removes the owned fixture tree.
		 *
		 * @throws IOException if cleanup fails
		 */
		@Override
		public void close() throws IOException
		{
			delete(root);
		}

		/**
		 * Deletes all owned paths without following symbolic links.
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
