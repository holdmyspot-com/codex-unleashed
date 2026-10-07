package com.holdmyspot.codexunleashed.tooling.release;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.stream.Stream;

import org.testng.annotations.Test;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertThrows;

public final class InstallersTest
{
	/**
	 * Creates the installer staging tests.
	 */
	public InstallersTest()
	{
	}

	/**
	 * Preserves arbitrary installer bytes and Windows line endings.
	 *
	 * @throws IOException if fixture access fails
	 */
	@Test
	public void copiesExactBytes() throws IOException
	{
		try (Fixture fixture = new Fixture())
		{
			byte[] shell = "holdmyspot-com/codex-unleashed\n\0ÿ".getBytes(StandardCharsets.ISO_8859_1);
			byte[] powershell = "holdmyspot-com/codex-unleashed\r\n".getBytes(StandardCharsets.US_ASCII);
			Files.write(fixture.sources.resolve("install.sh"), shell);
			Files.write(fixture.sources.resolve("install.ps1"), powershell);
			Installers.stage(fixture.root, fixture.release);
			assertEquals(Files.readAllBytes(fixture.release.resolve("install.sh")), shell);
			assertEquals(Files.readAllBytes(fixture.release.resolve("install.ps1")), powershell);
		}
	}

	/**
	 * Rejects missing inputs before changing existing release contents.
	 *
	 * @throws IOException if fixture access fails
	 */
	@Test
	public void rejectsMissingInstaller() throws IOException
	{
		try (Fixture fixture = new Fixture())
		{
			Files.delete(fixture.sources.resolve("install.ps1"));
			Path marker = fixture.release.resolve("marker.txt");
			Files.writeString(marker, "retained");
			assertThrows(IllegalArgumentException.class, () -> Installers.stage(fixture.root, fixture.release));
			assertEquals(Files.readString(marker), "retained");
			assertFalse(Files.exists(fixture.release.resolve("install.sh")));
		}
	}

	/**
	 * Rejects OpenAI installers without copying the other installer.
	 *
	 * @throws IOException if fixture access fails
	 */
	@Test
	public void rejectsUnpatchedInstaller() throws IOException
	{
		try (Fixture fixture = new Fixture())
		{
			Files.writeString(fixture.sources.resolve("install.ps1"), "https://github.com/openai/codex/releases");
			assertThrows(IllegalArgumentException.class, () -> Installers.stage(fixture.root, fixture.release));
			assertFalse(Files.exists(fixture.release.resolve("install.sh")));
			assertFalse(Files.exists(fixture.release.resolve("install.ps1")));
		}
	}

	/**
	 * Requires the caller's release directory to exist.
	 *
	 * @throws IOException if fixture access fails
	 */
	@Test
	public void rejectsMissingReleaseDirectory() throws IOException
	{
		try (Fixture fixture = new Fixture())
		{
			Files.delete(fixture.release);
			assertThrows(IllegalArgumentException.class, () -> Installers.stage(fixture.root, fixture.release));
			assertFalse(Files.exists(fixture.release));
		}
	}

	/**
	 * Owns an isolated filesystem fixture for one test.
	 */
	private static final class Fixture implements AutoCloseable
	{
		private final Path root;
		private final Path sources;
		private final Path release;

		/**
		 * Creates patched installers and an empty release directory.
		 *
		 * @throws IOException if fixture creation fails
		 */
		private Fixture() throws IOException
		{
			root = Files.createTempDirectory("installers-");
			sources = Files.createDirectories(root.resolve("scripts/install"));
			release = Files.createDirectory(root.resolve("release"));
			Files.writeString(sources.resolve("install.sh"), "holdmyspot-com/codex-unleashed");
			Files.writeString(sources.resolve("install.ps1"), "holdmyspot-com/codex-unleashed");
		}

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
