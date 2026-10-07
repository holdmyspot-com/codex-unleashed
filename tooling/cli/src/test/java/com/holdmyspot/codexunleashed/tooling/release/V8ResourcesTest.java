package com.holdmyspot.codexunleashed.tooling.release;

import com.holdmyspot.codexunleashed.tooling.Sha256;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.testng.annotations.Test;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertTrue;
import static org.testng.Assert.expectThrows;

/**
 * Verifies V8 override policy and real checksum-verified local resource downloads.
 */
public final class V8ResourcesTest
{
	/**
	 * Creates V8 resource tests.
	 */
	public V8ResourcesTest()
	{
	}

	/**
	 * Skips unsupported Windows artifacts, exact source-build values, and complete caller overrides.
	 *
	 * @throws IOException if fixture access or override resolution fails
	 */
	@Test
	public void preservesOverridePolicy() throws IOException
	{
		try (Fixture fixture = Fixture.create())
		{
			assertTrue(V8Resources.resolve(fixture.request(PackageTarget.WINDOWS_X86, Map.of())).isEmpty());
			for (String value : List.of("true", "1", "yes"))
				assertTrue(V8Resources.resolve(fixture.request(PackageTarget.LINUX_X86_MUSL,
					Map.of("V8_FROM_SOURCE", value))).isEmpty());
			assertTrue(V8Resources.resolve(fixture.request(PackageTarget.LINUX_X86_MUSL,
				Map.of("RUSTY_V8_ARCHIVE", "archive", "RUSTY_V8_SRC_BINDING_PATH", "binding"))).isEmpty());
			for (String key : List.of("RUSTY_V8_ARCHIVE", "RUSTY_V8_SRC_BINDING_PATH"))
				expectThrows(IOException.class, () -> V8Resources.resolve(fixture.request(PackageTarget.LINUX_X86_MUSL,
					Map.of(key, "supplied"))));
			expectThrows(IOException.class, () -> V8Resources.resolve(fixture.request(PackageTarget.LINUX_X86_MUSL,
				Map.of("V8_FROM_SOURCE", "TRUE"))));
			assertFalse(Files.exists(fixture.cache));
		}
	}

	/**
	 * Downloads both declared artifacts, rechecks cache contents, and deletes failed artifacts and partial downloads.
	 *
	 * @throws IOException if fixture access or resource resolution fails
	 */
	@Test
	public void verifiesAndReusesArtifactPair() throws IOException
	{
		try (Fixture fixture = Fixture.create())
		{
			fixture.prepare();
			V8Resources.Request request = fixture.request(PackageTarget.LINUX_X86_MUSL, Map.of());
			Map<String, String> resolved = V8Resources.resolve(request);
			Path archive = Path.of(resolved.get("RUSTY_V8_ARCHIVE"));
			Path binding = Path.of(resolved.get("RUSTY_V8_SRC_BINDING_PATH"));
			assertEquals(archive.getParent(), fixture.cache.resolve("rusty-v8-1.2.3-x86_64-unknown-linux-musl"));
			assertEquals(Files.readAllBytes(archive), fixture.archiveBytes);
			assertEquals(Files.readAllBytes(binding), fixture.bindingBytes);
			Files.delete(fixture.provider.resolve(Fixture.ARCHIVE_NAME));
			Files.delete(fixture.provider.resolve(Fixture.BINDING_NAME));
			assertEquals(V8Resources.resolve(request), resolved);
			Files.writeString(archive, "corrupt cached archive");
			expectThrows(IOException.class, () -> V8Resources.resolve(request));
			assertFalse(Files.exists(archive));
			assertFalse(Files.exists(archive.resolveSibling(archive.getFileName() + ".tmp")));
			Files.writeString(fixture.provider.resolve(Fixture.ARCHIVE_NAME), "corrupt replacement");
			expectThrows(IOException.class, () -> V8Resources.resolve(request));
			assertFalse(Files.exists(archive));
			assertEquals(Files.readAllBytes(binding), fixture.bindingBytes);
		}
	}

	/**
	 * Rejects uppercase digests, missing entries, unknown names, and duplicate coverage before artifact downloading.
	 *
	 * @throws IOException if fixture access fails
	 */
	@Test
	public void rejectsInvalidChecksumManifests() throws IOException
	{
		try (Fixture fixture = Fixture.create())
		{
			fixture.prepare();
			String archiveLine = Sha256.digest(fixture.provider.resolve(Fixture.ARCHIVE_NAME)) + "  " + Fixture.ARCHIVE_NAME;
			String bindingLine = Sha256.digest(fixture.provider.resolve(Fixture.BINDING_NAME)) + "  " + Fixture.BINDING_NAME;
			for (String manifest : List.of(archiveLine + "\n", archiveLine + "\n" + archiveLine + "\n",
				Sha256.digest(fixture.provider.resolve(Fixture.ARCHIVE_NAME)).toUpperCase(java.util.Locale.ROOT) +
					"  " + Fixture.ARCHIVE_NAME + "\n" + bindingLine + "\n",
				archiveLine + "\n" + "0".repeat(64) + "  unknown\n"))
			{
				Files.writeString(fixture.checksums, manifest);
				expectThrows(IOException.class, () ->
					V8Resources.resolve(fixture.request(PackageTarget.LINUX_X86_MUSL, Map.of())));
				try (Stream<Path> paths = Files.walk(fixture.cache))
				{
					assertFalse(paths.anyMatch(path -> path.getFileName().toString().equals(Fixture.ARCHIVE_NAME)));
				}
			}
		}
	}

	/**
	 * Owns a Cargo lockfile, local V8 provider, and isolated resource cache.
	 */
	private static final class Fixture implements AutoCloseable
	{
		private final Path root;
		private final Path workspace;
		private final Path provider;
		private final Path checksums;
		private final Path cache;
		private static final String ARCHIVE_NAME =
			"librusty_v8_ptrcomp_sandbox_release_x86_64-unknown-linux-musl.a.gz";
		private static final String BINDING_NAME =
			"src_binding_ptrcomp_sandbox_release_x86_64-unknown-linux-musl.rs";
		private final byte[] archiveBytes = {0, (byte) 255, 1};
		private final byte[] bindingBytes = {2, (byte) 255, 3};

		/**
		 * Defines fixture paths without creating resource inputs or cache data.
		 *
		 * @param root the owned fixture directory
		 */
		private Fixture(Path root)
		{
			this.root = root;
			workspace = root.resolve("workspace");
			provider = root.resolve("releases/rusty-v8-v1.2.3");
			checksums = provider.resolve("rusty_v8_ptrcomp_sandbox_release_x86_64-unknown-linux-musl.sha256");
			cache = root.resolve("cache");
		}

		/**
		 * Allocates owned fixture storage.
		 *
		 * @return the fixture
		 * @throws IOException if allocation fails
		 */
		private static Fixture create() throws IOException
		{
			return new Fixture(Files.createTempDirectory("v8-resources-"));
		}

		/**
		 * Writes a real resolved V8 version and local artifact checksum manifest.
		 *
		 * @throws IOException if fixture writing fails
		 */
		private void prepare() throws IOException
		{
			Files.createDirectories(workspace.resolve("codex-rs"));
			Files.writeString(workspace.resolve("codex-rs/Cargo.lock"), "[[package]]\nname = \"v8\"\nversion = \"1.2.3\"\n");
			Files.createDirectories(provider);
			Files.write(provider.resolve(ARCHIVE_NAME), archiveBytes);
			Files.write(provider.resolve(BINDING_NAME), bindingBytes);
			Files.writeString(checksums, "\u00a0" + Sha256.digest(provider.resolve(ARCHIVE_NAME)) + "\u00a0" + ARCHIVE_NAME +
				"\u001f\r\n" + Sha256.digest(provider.resolve(BINDING_NAME)) + "\t" + BINDING_NAME + "\u2029");
		}

		/**
		 * Supplies explicit environment, workspace, cache, and provider boundaries.
		 *
		 * @param target the package target
		 * @param environment the caller environment
		 * @return the resource request
		 */
		private V8Resources.Request request(PackageTarget target, Map<String, String> environment)
		{
			return new V8Resources.Request(workspace, target, cache, root.resolve("releases").toUri(), environment);
		}

		/**
		 * Removes all owned fixture data.
		 *
		 * @throws IOException if cleanup fails
		 */
		@Override
		public void close() throws IOException
		{
			try (Stream<Path> paths = Files.walk(root))
			{
				for (Path path : paths.sorted(Comparator.reverseOrder()).toList())
					Files.delete(path);
			}
		}
	}
}
