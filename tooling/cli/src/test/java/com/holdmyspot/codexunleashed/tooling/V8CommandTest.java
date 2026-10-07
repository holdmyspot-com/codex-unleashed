package com.holdmyspot.codexunleashed.tooling;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;
import org.testng.annotations.Test;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertTrue;

/** Verifies V8 operations through the maintained command dispatcher. */
public final class V8CommandTest
{
	/** Creates command tests. */
	public V8CommandTest()
	{
	}

	/**
	 * Checks, updates and rechecks a selected module version while preserving unrelated text.
	 *
	 * @throws IOException if fixture access or cleanup fails
	 */
	@Test
	public void reconcilesModuleFromCommand() throws IOException
	{
		Path root = Files.createTempDirectory("v8-command-module-");
		try
		{
			String digest = "a".repeat(64);
			Path module = root.resolve("MODULE.bazel");
			String original = "# untouched\r\nhttp_file(\r\n    name = \"rusty_v8_146_4_0_asset\",\r\n" +
				"    downloaded_file_path = \"library.gz\",\r\n)\r\n";
			Files.writeString(module, original);
			Path directory = Files.createDirectories(root.resolve("third_party/v8"));
			Files.writeString(directory.resolve("rusty_v8_146_4_0.sha256"), digest + "  library.gz\n");
			Result drift = invoke(root, "check-module-bazel");
			assertEquals(drift.status(), 1);
			assertTrue(drift.error().contains("is missing sha256"), drift.error());
			Result update = invoke(root, "update-module-bazel");
			assertEquals(update.status(), 0, update.error());
			assertTrue(update.output().contains("updated " + module), update.output());
			String expected = original.replace("\r\n", "\n").replace("\n)\n",
				"\n    sha256 = \"" + digest + "\",\n)\n").replace("\n", System.lineSeparator());
			assertEquals(Files.readString(module), expected);
			assertEquals(invoke(root, "check-module-bazel").status(), 0);
			Result unchanged = invoke(root, "update-module-bazel");
			assertEquals(unchanged.status(), 0, unchanged.error());
			assertTrue(unchanged.output().contains("checksums are already current"), unchanged.output());
			assertEquals(Files.readString(module), expected);
			Path alternate = root.resolve("alternate.bazel");
			Files.writeString(alternate, expected + "http_file(\n    name = \"rusty_v8_147_0_0_other\",\n" +
				"    downloaded_file_path = \"other.gz\",\n)\n");
			Result overridden = invoke(root, "check-module-bazel", "--module-bazel", alternate.toString());
			assertEquals(overridden.status(), 0, overridden.error());
		}
		finally
		{
			delete(root);
		}
	}

	/**
	 * Stages upstream Cargo outputs with the same artifact names and printed paths.
	 *
	 * @throws IOException if fixture access or cleanup fails
	 */
	@Test
	public void stagesUpstreamFromCommand() throws IOException
	{
		Path root = Files.createTempDirectory("v8-command-stage-");
		try
		{
			String target = "x86_64-pc-windows-msvc";
			Path targetDirectory = root.resolve("target directory");
			Path gn = Files.createDirectories(targetDirectory.resolve(target + "/release/gn_out/obj"));
			Files.write(gn.resolve("rusty_v8.lib"), new byte[]{0, (byte) 255});
			Files.writeString(gn.getParent().resolve("src_binding.rs"), "bindings");
			Path output = root.resolve("output directory");
			Result staged = invoke(root, "stage-upstream-release-pair", "--target-dir", targetDirectory.toString(),
				"--target", target, "--output-dir", output.toString(), "--sandbox");
			assertEquals(staged.status(), 0, staged.error());
			assertEquals(TextLines.split(staged.output()), List.of(
				output.resolve("rusty_v8_ptrcomp_sandbox_release_" + target + ".lib.gz").toString(),
				output.resolve("src_binding_ptrcomp_sandbox_release_" + target + ".rs").toString(),
				output.resolve("rusty_v8_ptrcomp_sandbox_release_" + target + ".sha256").toString()));
			assertEquals(Files.readString(output.resolve("src_binding_ptrcomp_sandbox_release_" + target + ".rs")),
				"bindings");
		}
		finally
		{
			delete(root);
		}
	}

	/**
	 * Rejects missing required options and unsupported compilation modes before running a child.
	 *
	 * @throws IOException if an owned stream fails to close
	 */
	@Test
	public void rejectsInvalidStageArguments() throws IOException
	{
		Path root = Path.of(".");
		assertEquals(invoke(root, "stage-release-pair", "--target", "fixture").status(), 2);
		Result invalid = invoke(root, "stage-release-pair", "--platform", "fixture", "--target", "fixture",
			"--output-dir", "unused", "--compilation-mode", "release");
		assertEquals(invalid.status(), 2);
		assertTrue(invalid.error().contains("--compilation-mode"), invalid.error());
	}

	/**
	 * Checks the selected checkout's consumer source using its authoritative Cargo lockfile version.
	 *
	 * @throws IOException if fixture access or cleanup fails
	 */
	@Test
	public void checksConsumersFromCommand() throws IOException
	{
		Path root = Files.createTempDirectory("v8-command-consumers-");
		try
		{
			Path workspace = Files.createDirectory(root.resolve("codex-rs"));
			Files.writeString(workspace.resolve("Cargo.lock"), "[[package]]\nname = \"v8\"\nversion = \"146.4.0\"\n");
			Path directory = Files.createDirectories(root.resolve("third_party/v8"));
			Files.writeString(directory.resolve("BUILD.bazel"), "# selectors are absent\n");
			Result missing = invoke(root, "check-consumer-selectors");
			assertEquals(missing.status(), 1, missing.error());
			assertTrue(missing.error().contains(":v8_146_4_0_aarch64_apple_darwin_bazel"), missing.error());
		}
		finally
		{
			delete(root);
		}
	}

	/**
	 * Invokes the real command dispatcher with owned streams and no live standard input.
	 *
	 * @param root explicit repository root
	 * @param arguments operation and options
	 * @return command status and output
	 * @throws IOException if an owned stream fails to close
	 */
	private static Result invoke(Path root, String... arguments) throws IOException
	{
		String[] command = new String[arguments.length + 2];
		command[0] = "rusty-v8-bazel";
		command[1] = root.toString();
		System.arraycopy(arguments, 0, command, 2, arguments.length);
		var output = new ByteArrayOutputStream();
		var error = new ByteArrayOutputStream();
		try (var out = new PrintStream(output, true, StandardCharsets.UTF_8);
			var err = new PrintStream(error, true, StandardCharsets.UTF_8);
			var input = new ByteArrayInputStream(new byte[0]))
		{
			int status = Main.run(command, input, out, err);
			return new Result(status, output.toString(StandardCharsets.UTF_8), error.toString(StandardCharsets.UTF_8));
		}
	}

	/**
	 * Removes the owned fixture tree.
	 *
	 * @param root fixture root
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

	/**
	 * Captures one command outcome.
	 *
	 * @param status command exit status
	 * @param output standard output
	 * @param error diagnostic output
	 */
	private record Result(int status, String output, String error)
	{
	}
}
