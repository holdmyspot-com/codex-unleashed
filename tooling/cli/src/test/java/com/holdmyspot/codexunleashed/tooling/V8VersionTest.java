package com.holdmyspot.codexunleashed.tooling;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.stream.Stream;
import org.testng.annotations.Test;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertTrue;

/**
 * Verifies V8 version selection through the retained command boundary.
 */
public final class V8VersionTest
{
	/**
	 * Creates the command tests.
	 */
	public V8VersionTest()
	{
	}
	/**
	 * Verifies direct and nested lockfiles, duplicate versions, fallback, and rejected ambiguity.
	 *
	 * @throws IOException if a fixture cannot be created or removed
	 */
	@Test
	public void resolvesLockfileAndModuleVersions() throws IOException
	{
		Path root = Files.createTempDirectory("v8-version-");
		try
		{
			Path nested = Files.createDirectories(root.resolve("upstream/codex-rs")).resolve("Cargo.lock");
			Files.writeString(nested, "[[package]]\nname='v8'\nversion='149.2.0'\n".repeat(2));
			assertCommand(root, 0, "149.2.0\n", "");
			Path direct = Files.createDirectories(root.resolve("codex-rs")).resolve("Cargo.lock");
			Files.writeString(direct, "[[package]]\nname='v8'\nversion='150.0.0'\n");
			assertCommand(root, 0, "150.0.0\n", "");
			Files.writeString(direct, "[[package]]\nname='other'\nversion='1.0.0'\n");
			Files.writeString(root.resolve("MODULE.bazel"),
				"https://static.crates.io/crates/v8/v8-151.1.0.crate\n".repeat(2));
			assertCommand(root, 0, "151.1.0\n", "");
			Files.writeString(root.resolve("MODULE.bazel"), "no pinned crate\n");
			assertCommand(root, 1, "", "expected exactly one pinned v8 crate version in MODULE.bazel, found: []");
			Files.writeString(root.resolve("MODULE.bazel"),
				"https://static.crates.io/crates/v8/v8-151.1.0.crate\n" +
				"https://static.crates.io/crates/v8/v8-152.0.0.crate\n");
			assertCommand(root, 1, "", "found: [151.1.0, 152.0.0]");
			Files.writeString(direct, "[[package]]\nname='v8'\nversion='2.0.0'\n" +
				"[[package]]\nname='v8'\nversion='1.0.0'\n");
			assertCommand(root, 1, "", "expected exactly one resolved v8 version, found: [1.0.0, 2.0.0]");
			Files.writeString(direct, "[[package]]\nname='v8'\nversion='1.0.0'\nversion='2.0.0'\n");
			assertCommand(root, 1, "", "Cargo.lock");
			Files.write(direct, new byte[]{(byte) 0xc3, (byte) 0x28});
			assertCommand(root, 1, "", "Cargo.lock is not valid UTF-8");
		}
		finally
		{
			try (Stream<Path> paths = Files.walk(root))
			{
				for (Path path : paths.sorted(Comparator.reverseOrder()).toList())
					Files.delete(path);
			}
		}
	}

	/**
	 * Verifies a command result without invoking external tools.
	 *
	 * @param root the checkout fixture
	 * @param expectedStatus the expected exit status
	 * @param expectedOutput the exact standard output
	 * @param expectedError the diagnostic substring
	 */
	private static void assertCommand(Path root, int expectedStatus, String expectedOutput, String expectedError)
	{
		ByteArrayOutputStream output = new ByteArrayOutputStream();
		ByteArrayOutputStream errors = new ByteArrayOutputStream();
		try (PrintStream out = new PrintStream(output, true, StandardCharsets.UTF_8);
			PrintStream err = new PrintStream(errors, true, StandardCharsets.UTF_8))
		{
			assertEquals(Main.run(new String[]{"resolved-v8-crate-version", root.toString()},
				new ByteArrayInputStream(new byte[0]), out, err), expectedStatus);
		}
		assertEquals(output.toString(StandardCharsets.UTF_8), expectedOutput.replace("\n", System.lineSeparator()));
		assertTrue(errors.toString(StandardCharsets.UTF_8).contains(expectedError),
			errors.toString(StandardCharsets.UTF_8));
	}
}
