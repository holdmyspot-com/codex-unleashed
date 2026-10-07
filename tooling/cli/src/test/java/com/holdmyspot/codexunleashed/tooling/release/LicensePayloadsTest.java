package com.holdmyspot.codexunleashed.tooling.release;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.testng.annotations.Test;
import tools.jackson.databind.json.JsonMapper;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertTrue;
import static org.testng.Assert.expectThrows;

/**
 * Verifies byte-preserving license collection and retained evidence decisions.
 */
public final class LicensePayloadsTest
{
	/**
	 * Creates the license payload tests.
	 */
	public LicensePayloadsTest()
	{
	}

	/**
	 * Copies root license names and an explicit nested file, excludes workspace members, and sorts notices.
	 *
	 * @throws IOException if fixture access or collection fails
	 */
	@Test
	public void copiesPayloadBytes() throws IOException
	{
		Path root = Files.createTempDirectory("license-payloads-");
		try
		{
			Path crate = Files.createDirectory(root.resolve("crate"));
			Path manifest = Files.writeString(crate.resolve("Cargo.toml"), "[package]\n");
			byte[] payload = {0, (byte) 255, 10};
			Files.write(crate.resolve("LICENSE-MIT"), payload);
			Files.writeString(crate.resolve("notice.txt"), "notice\n");
			Files.writeString(Files.createDirectory(crate.resolve("legal")).resolve("custom.txt"), "custom license\n");
			Files.writeString(crate.resolve("readme.txt"), "excluded\n");
			Path output = root.resolve("output");
			String metadata = metadata(List.of(packageData("workspace", "workspace", manifest, "", ""),
				packageData("z", "z-crate", manifest, "MIT", ""),
				packageData("a", "a+é²", manifest, "MIT", "legal/custom.txt")));
			LicensePayloads.collect(metadata, output, true);
			assertEquals(Files.readAllBytes(output.resolve("a_é²-1.2.3/LICENSE-MIT")), payload);
			assertEquals(Files.readString(output.resolve("a_é²-1.2.3/custom.txt")), "custom license\n");
			assertTrue(Files.isRegularFile(output.resolve("a_é²-1.2.3/notice.txt")));
			assertFalse(Files.exists(output.resolve("a_é²-1.2.3/readme.txt")));
			assertFalse(Files.exists(output.resolve("workspace-1.2.3")));
			String notices = Files.readString(output.resolve("THIRD_PARTY_NOTICES.md"));
			assertTrue(notices.indexOf("`a+é² 1.2.3`") < notices.indexOf("`z-crate 1.2.3`"));
			assertTrue(notices.contains("  - `a_é²-1.2.3/custom.txt`\n"));
		}
		finally
		{
			delete(root);
		}
	}

	/**
	 * Accepts a declared expression without a payload and emits the same missing-payload notices.
	 *
	 * @throws IOException if fixture access or collection fails
	 */
	@Test
	public void acceptsDeclaredEvidence() throws IOException
	{
		Path root = Files.createTempDirectory("license-expression-");
		try
		{
			Path manifest = Files.writeString(root.resolve("Cargo.toml"), "[package]\n");
			Path output = root.resolve("output");
			LicensePayloads.collect(metadata(List.of(packageData("dependency", "example-crate", manifest, "MIT", ""))),
				output, true);
			assertEquals(Files.readString(output.resolve("THIRD_PARTY_NOTICES.md")), """
				# Third-party Cargo licenses

				Generated from the locked upstream Cargo dependency graph during release packaging.
				License files below are copied from the resolved crate packages without modification.

				- `example-crate 1.2.3` — `MIT`
				  - No license payload file was present in the crate source.

				## Missing payload files

				- `example-crate 1.2.3`
				""");
		}
		finally
		{
			delete(root);
		}
	}

	/**
	 * Writes notices before strict evidence rejection and accepts the same metadata in permissive mode.
	 *
	 * @throws IOException if fixture access fails
	 */
	@Test
	public void preservesFailureReport() throws IOException
	{
		Path root = Files.createTempDirectory("license-evidence-");
		try
		{
			Path manifest = Files.writeString(root.resolve("Cargo.toml"), "[package]\n");
			Path output = root.resolve("output");
			String metadata = metadata(List.of(packageData("dependency", "example-crate", manifest, "", "")));
			IOException failure = expectThrows(IOException.class, () -> LicensePayloads.collect(metadata, output, true));
			assertEquals(failure.getMessage(), "Missing license evidence for: example-crate 1.2.3");
			String report = Files.readString(output.resolve("THIRD_PARTY_NOTICES.md"));
			assertTrue(report.contains("license expression not declared"));
			LicensePayloads.collect(metadata, output, false);
			assertEquals(Files.readString(output.resolve("THIRD_PARTY_NOTICES.md")), report);
		}
		finally
		{
			delete(root);
		}
	}

	/**
	 * Overwrites a destination symlink's target while preserving the link, matching ordinary file-copy semantics.
	 *
	 * @throws IOException if fixture access or collection fails
	 */
	@Test
	public void preservesDestinationLink() throws IOException
	{
		Path root = Files.createTempDirectory("license-destination-link-");
		try
		{
			Path crate = Files.createDirectory(root.resolve("crate"));
			Path manifest = Files.writeString(crate.resolve("Cargo.toml"), "[package]\n");
			Files.writeString(crate.resolve("LICENSE"), "replacement license\n");
			Path output = root.resolve("output");
			Path target = Files.writeString(root.resolve("existing-license"), "previous license\n");
			Path destination = Files.createDirectories(output.resolve("example-crate-1.2.3")).resolve("LICENSE");
			Files.createSymbolicLink(destination, target);
			LicensePayloads.collect(metadata(List.of(packageData("dependency", "example-crate", manifest, "MIT", ""))),
				output, true);
			assertTrue(Files.isSymbolicLink(destination));
			assertEquals(Files.readString(target), "replacement license\n");
		}
		finally
		{
			delete(root);
		}
	}

	/**
	 * Builds fixture package metadata using explicit optional strings.
	 *
	 * @param id the package identity
	 * @param name the display name
	 * @param manifest the source manifest
	 * @param license the declared expression or empty
	 * @param licenseFile the explicit payload path or empty
	 * @return the package object
	 */
	private static Map<String, String> packageData(String id, String name, Path manifest, String license,
		String licenseFile)
	{
		return Map.of("id", id, "name", name, "version", "1.2.3", "manifest_path", manifest.toString(),
			"license", license, "license_file", licenseFile);
	}

	/**
	 * Serializes a controlled resolved dependency graph.
	 *
	 * @param packages the package objects
	 * @return the metadata JSON
	 */
	private static String metadata(List<Map<String, String>> packages)
	{
		return JsonMapper.builder().build().writeValueAsString(Map.of("workspace_members", List.of("workspace"),
			"packages", packages));
	}

	/**
	 * Removes the owned fixture tree.
	 *
	 * @param root the fixture root
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
