package com.holdmyspot.codexunleashed.tooling.release;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.testng.annotations.Test;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.expectThrows;

/**
 * Verifies workspace version and vendor-build composition without a Python version provider.
 */
public final class PackageVersionsTest
{
	/**
	 * Creates package version tests.
	 */
	public PackageVersionsTest()
	{
	}

	/**
	 * Retains the upstream version and distinguishes an absent vendor number from an explicitly empty number.
	 *
	 * @throws IOException if fixture access or version resolution fails
	 */
	@Test
	public void composesExecutableVersion() throws IOException
	{
		Path workspace = Files.createTempDirectory("package-versions-");
		try
		{
			Files.createDirectories(workspace.resolve("codex-rs"));
			Files.writeString(workspace.resolve("codex-rs/Cargo.toml"),
				"[workspace.package]\nversion = \"0.160.0\"\n[package]\nversion = \"9.9.9\"\n");
			assertEquals(PackageVersions.resolve(workspace, Map.of()), "0.160.0+dev");
			assertEquals(PackageVersions.resolve(workspace, Map.of("CODEX_UNLEASHED_BUILD_NUMBER", "41")), "0.160.0+41");
			assertEquals(PackageVersions.resolve(workspace, Map.of("CODEX_UNLEASHED_BUILD_NUMBER", "")), "0.160.0+");
		}
		finally
		{
			delete(workspace);
		}
	}

	/**
	 * Rejects unreadable, malformed, missing, and non-string workspace versions without an unbranded fallback.
	 *
	 * @throws IOException if fixture access or cleanup fails
	 */
	@Test
	public void rejectsInvalidWorkspaceVersions() throws IOException
	{
		Path workspace = Files.createTempDirectory("invalid-package-versions-");
		try
		{
			expectThrows(IOException.class, () -> PackageVersions.resolve(workspace, Map.of()));
			Files.createDirectories(workspace.resolve("codex-rs"));
			Path manifest = workspace.resolve("codex-rs/Cargo.toml");
			for (String text : List.of("[package]\nversion = \"0.160.0\"\n", "[workspace.package]\nlicense = \"MIT\"\n",
				"[workspace.package]\nversion = 160\n", "[workspace.package]\nversion = \"\"\n",
				"[workspace.package]\nversion = \"unfinished\n"))
			{
				Files.writeString(manifest, text);
				expectThrows(IOException.class, () -> PackageVersions.resolve(workspace, Map.of()));
			}
			Files.write(manifest, new byte[] {(byte) 255});
			expectThrows(IOException.class, () -> PackageVersions.resolve(workspace, Map.of()));
		}
		finally
		{
			delete(workspace);
		}
	}

	/**
	 * Removes owned fixture files without following directory symlinks.
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
