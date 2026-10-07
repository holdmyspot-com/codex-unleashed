package com.holdmyspot.codexunleashed.tooling;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import java.util.zip.GZIPInputStream;
import org.testng.annotations.Test;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertTrue;
import static org.testng.Assert.expectThrows;

/** Verifies Bazel materialization, command policy and output selection before artifact staging. */
public final class V8BazelTest
{
	/** Creates staging tests. */
	public V8BazelTest()
	{
	}

	/**
	 * Rebuilds existing outputs before querying, including the retained skip-build option.
	 *
	 * @throws IOException if fixture access, staging or cleanup fails
	 */
	@Test
	public void materializesBeforeStaging() throws IOException
	{
		Path root = Files.createTempDirectory("v8-bazel-staging-");
		try
		{
			Path outputBase = root.resolve("output base");
			Path executionRoot = root.resolve("execution root");
			Path library = Files.createDirectories(outputBase.resolve("external/v8")).resolve("library.a");
			Path binding = Files.createDirectories(executionRoot.resolve("bazel-out")).resolve("src_binding.rs");
			Files.writeString(binding, "generated binding");
			for (boolean skipBuild : new boolean[]{false, true})
			{
				Files.writeString(library, "stale library");
				List<List<String>> commands = new ArrayList<>();
				CommandRunner runner = command ->
				{
					commands.add(command);
					if (command.contains("build"))
					{
						Files.writeString(library, "rebuilt library");
						return "";
					}
					if (command.contains("cquery"))
						return "external/v8/library.a\n\u2003bazel-out/src_binding.rs\u2003\n";
					if (command.contains("output_base"))
						return outputBase + "\n";
					if (command.contains("execution_root"))
						return executionRoot + "\n";
					throw new IOException("unexpected command: " + command);
				};
				var request = new V8Bazel.Request("linux-x86_64", "x86_64-unknown-linux-gnu",
					root.resolve("staged-" + skipBuild), true, skipBuild,
					List.of("ci-v8", "rusty-v8-upstream-libcxx", "ci-v8"), "opt");
				List<Path> staged = V8Bazel.stage(request, root, Map.of("BUILDBUDDY_API_KEY", "fixture-key"), runner);
				String label = "//third_party/v8:rusty_v8_sandbox_release_pair_x86_64_unknown_linux_gnu";
				assertEquals(commands.size(), 4);
				assertEquals(commands.getFirst(), List.of("bazel", "build", "--config=buildbuddy-generic-rbe",
					"--remote_header=x-buildbuddy-api-key=fixture-key", "--remote_local_fallback", "-c", "opt",
					"--platforms=@llvm//platforms:linux-x86_64", "--config=rusty-v8-upstream-libcxx", "--config=ci-v8",
					"--remote_download_toplevel", label));
				assertEquals(commands.get(1).get(1), "cquery");
				assertEquals(commands.get(1).getLast(), "set(" + label + ")");
				assertTrue(commands.get(1).contains("--output=files"));
				assertEquals(staged.getFirst().getFileName().toString(),
					"librusty_v8_ptrcomp_sandbox_release_x86_64-unknown-linux-gnu.a.gz");
				try (InputStream input = new GZIPInputStream(Files.newInputStream(staged.getFirst())))
				{
					assertEquals(new String(input.readAllBytes(), StandardCharsets.UTF_8), "rebuilt library");
				}
				assertEquals(Files.readString(staged.get(1)), "generated binding");
			}
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
	 * Refuses missing queried files, incomplete artifact pairs and failed builds before creating staged output.
	 *
	 * @throws IOException if fixture access or cleanup fails
	 */
	@Test
	public void refusesIncompleteBuiltOutputs() throws IOException
	{
		Path root = Files.createTempDirectory("v8-bazel-missing-");
		try
		{
			Path output = root.resolve("staged");
			Files.writeString(root.resolve("library.a"), "library");
			Files.writeString(root.resolve("binding.rs"), "binding");
			Files.writeString(root.resolve(".a"), "hidden library name");
			Files.writeString(root.resolve(".rs"), "hidden binding name");
			var request = new V8Bazel.Request("linux-x86_64", "x86_64-unknown-linux-gnu", output, false, false,
				List.of(), "fastbuild");
			for (String query : List.of("missing.a\n", "binding.rs\n", "library.a\n", ".a\nbinding.rs\n",
				"library.a\n.rs\n"))
			{
				CommandRunner runner = command ->
				{
					if (command.contains("cquery"))
						return query;
					if (command.contains("info"))
						return root.toString();
					return "";
				};
				expectThrows(IOException.class, () -> V8Bazel.stage(request, root, Map.of(), runner));
				assertFalse(Files.exists(output));
			}
			List<List<String>> commands = new ArrayList<>();
			CommandRunner failedBuild = command ->
			{
				commands.add(command);
				throw new IOException("build failed");
			};
			IOException failure = expectThrows(IOException.class,
				() -> V8Bazel.stage(request, root, Map.of(), failedBuild));
			assertEquals(failure.getMessage(), "build failed");
			assertEquals(commands.size(), 1);
			assertEquals(commands.getFirst().get(1), "build");
			assertFalse(Files.exists(output));
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
}
