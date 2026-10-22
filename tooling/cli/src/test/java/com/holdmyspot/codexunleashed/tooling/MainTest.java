package com.holdmyspot.codexunleashed.tooling;

import java.io.ByteArrayOutputStream;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import org.testng.annotations.Test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

/**
 * Verifies the public tooling command boundary.
 */
public final class MainTest
{
	/**
	 * Creates the command-boundary tests.
	 */
	public MainTest()
	{
	}

	/**
	 * Exposes installer staging through the production command boundary.
	 *
	 * @throws IOException if fixture access fails
	 */
	@Test
	public void stagesInstallers() throws IOException
	{
		Path root = Files.createTempDirectory("staging-cli-");
		try
		{
			Path source = Files.createDirectories(root.resolve("scripts/install"));
			Path release = Files.createDirectory(root.resolve("release"));
			String installer = "holdmyspot-com/codex-unleashed\r\n";
			Files.writeString(source.resolve("install.sh"), installer);
			Files.writeString(source.resolve("install.ps1"), installer);
			var output = new ByteArrayOutputStream();
			try (var stream = new PrintStream(output, true, StandardCharsets.UTF_8))
			{
				requireThat(Main.run(new String[]{"stage-release-installers", root.toString(), release.toString()},
					InputStream.nullInputStream(), stream, stream), "status").isEqualTo(0);
			}
			requireThat(Files.readString(release.resolve("install.sh")), "installer").isEqualTo(installer);
			requireThat(Files.readString(release.resolve("install.ps1")), "installer").isEqualTo(installer);
			requireThat(output.size(), "outputSize").isEqualTo(0);
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
	 * Reports operational failures with a diagnostic and status one.
	 */
	@Test
	public void reportsMissingInstaller()
	{
		var output = new ByteArrayOutputStream();
		try (var stream = new PrintStream(output, true, StandardCharsets.UTF_8))
		{
			requireThat(Main.run(new String[]{"stage-release-installers", "absent-checkout", "absent-release"},
				InputStream.nullInputStream(), stream, stream), "status").isEqualTo(1);
		}
		requireThat(output.toString(StandardCharsets.UTF_8), "output").
			startsWith("ERROR: required installer file is missing:");
	}

	/**
	 * Rejects unknown commands and malformed argument lists with status two.
	 */
	@Test
	public void rejectsInvalidArguments()
	{
		var output = new ByteArrayOutputStream();
		try (var stream = new PrintStream(output, true, StandardCharsets.UTF_8))
		{
			requireThat(Main.run(new String[]{"unknown"}, InputStream.nullInputStream(), stream, stream),
				"status").isEqualTo(2);
			requireThat(Main.run(new String[]{"stage-release-installers"}, InputStream.nullInputStream(),
				stream, stream), "status").isEqualTo(2);
			requireThat(Main.run(new String[0], InputStream.nullInputStream(), stream, stream), "status").isEqualTo(2);
		}
		requireThat(output.toString(StandardCharsets.UTF_8), "output").contains("Usage:");
	}

	/**
	 * Reads the tag inventory from standard input and prints the selected build number.
	 */
	@Test
	public void selectsReleaseBuildNumber()
	{
		var output = new ByteArrayOutputStream();
		var input = new ByteArrayInputStream("rust-v0.160.0+29\nrust-v0.159.1+23\n".
			getBytes(StandardCharsets.UTF_8));
		try (var stream = new PrintStream(output, true, StandardCharsets.UTF_8))
		{
			requireThat(Main.run(new String[]{"next-release-build-number", "rust-v0.160.0"}, input, stream, stream),
				"status").isEqualTo(0);
		}
		requireThat(output.toString(StandardCharsets.UTF_8), "output").isEqualTo("30" + System.lineSeparator());
	}

	/**
	 * Reports invalid upstream tags through the public command boundary.
	 */
	@Test
	public void rejectsInvalidReleaseTag()
	{
		var output = new ByteArrayOutputStream();
		try (var stream = new PrintStream(output, true, StandardCharsets.UTF_8))
		{
			requireThat(Main.run(new String[]{"next-release-build-number", "v0.160.0"}, InputStream.nullInputStream(),
				stream, stream), "status").isEqualTo(1);
		}
		requireThat(output.toString(StandardCharsets.UTF_8), "output").
			startsWith("ERROR: upstream tag must be rust-vX.Y.Z");
	}

	/**
	 * Rejects malformed UTF-8 input instead of treating damaged tags as an empty inventory.
	 */
	@Test
	public void rejectsMalformedTagInput()
	{
		var output = new ByteArrayOutputStream();
		var input = new ByteArrayInputStream(new byte[]{(byte) 0xff});
		try (var stream = new PrintStream(output, true, StandardCharsets.UTF_8))
		{
			requireThat(Main.run(new String[]{"next-release-build-number", "rust-v0.160.0"}, input, stream, stream),
				"status").isEqualTo(1);
		}
		requireThat(output.toString(StandardCharsets.UTF_8), "output").startsWith("ERROR:");
	}

	/**
	 * Prints stable and development cache prefixes through the command boundary.
	 */
	@Test
	public void printsCachePrefix()
	{
		var output = new ByteArrayOutputStream();
		try (var stream = new PrintStream(output, true, StandardCharsets.UTF_8))
		{
			requireThat(Main.run(new String[]{"cache-prefix", "--upstream-ref", "rust-v0.160.0"},
				InputStream.nullInputStream(), stream, stream), "status").isEqualTo(0);
			requireThat(output.toString(StandardCharsets.UTF_8), "output").
				isEqualTo("upstream-rust-v0.160.0-" + System.lineSeparator());
			output.reset();
			requireThat(Main.run(new String[]{"cache-prefix", "--upstream-ref", "main"},
				InputStream.nullInputStream(), stream, stream), "status").isEqualTo(0);
			requireThat(output.toString(StandardCharsets.UTF_8), "output").
				isEqualTo("upstream-unreleased-" + System.lineSeparator());
		}
	}

	/**
	 * Requires the cache prefix's upstream-ref option and its value.
	 */
	@Test
	public void rejectsInvalidCacheArguments()
	{
		var output = new ByteArrayOutputStream();
		try (var stream = new PrintStream(output, true, StandardCharsets.UTF_8))
		{
			requireThat(Main.run(new String[]{"cache-prefix", "main"}, InputStream.nullInputStream(), stream, stream),
				"status").isEqualTo(2);
			requireThat(Main.run(new String[]{"cache-prefix", "--invalid", "main"}, InputStream.nullInputStream(),
				stream, stream), "status").isEqualTo(2);
		}
		requireThat(output.toString(StandardCharsets.UTF_8), "output").contains("Usage:");
	}

	/**
	 * Prints retained stable releases through the command boundary.
	 */
	@Test
	public void printsRetainedStableReleases()
	{
		var output = new ByteArrayOutputStream();
		try (var stream = new PrintStream(output, true, StandardCharsets.UTF_8))
		{
			requireThat(Main.run(new String[]{"stable-releases"}, InputStream.nullInputStream(), stream, stream,
				_ -> "rust-v0.159.2\nrust-v0.160.0\nrust-v0.159.3\n"), "status").isEqualTo(0);
		}
		requireThat(output.toString(StandardCharsets.UTF_8), "output").isEqualTo("rust-v0.160.0" + System.lineSeparator() +
			"rust-v0.159.3" + System.lineSeparator());
	}

	/**
	 * Includes pinned and building releases beyond the newest two stable upstream releases.
	 */
	@Test
	public void retainsPinnedAndBuildingReleases()
	{
		var output = new ByteArrayOutputStream();
		try (var stream = new PrintStream(output, true, StandardCharsets.UTF_8))
		{
			requireThat(Main.run(new String[]{"stable-releases", "--retain-upstream-ref", "rust-v0.160.0",
				"--retain-upstream-ref", "rust-v0.159.0"}, InputStream.nullInputStream(), stream, stream,
				_ -> "rust-v0.161.0\nrust-v0.160.1\nrust-v0.160.0\n"), "status").isEqualTo(0);
		}
		requireThat(output.toString(StandardCharsets.UTF_8), "output").isEqualTo(String.join(System.lineSeparator(),
			"rust-v0.161.0", "rust-v0.160.1", "rust-v0.160.0", "rust-v0.159.0", ""));
	}

	/**
	 * Prints each retained release once when the pinned and building upstream references are identical.
	 */
	@Test
	public void retainsRepeatedProtectedRelease()
	{
		var output = new ByteArrayOutputStream();
		try (var stream = new PrintStream(output, true, StandardCharsets.UTF_8))
		{
			int status = Main.run(new String[]{"stable-releases", "--retain-upstream-ref", "rust-v0.163.0",
				"--retain-upstream-ref", "rust-v0.163.0"}, InputStream.nullInputStream(), stream, stream,
				_ -> "rust-v0.163.0\nrust-v0.162.1\n");
			requireThat(status, "status").isEqualTo(0);
		}
		requireThat(output.toString(StandardCharsets.UTF_8), "output").isEqualTo(
			String.join(System.lineSeparator(), "rust-v0.163.0", "rust-v0.162.1", ""));
	}

	/**
	 * Deletes older caches while preserving the configured upstream release outside the newest two.
	 */
	@Test
	public void preservesPinnedCacheDuringPruning()
	{
		var output = new ByteArrayOutputStream();
		AtomicInteger index = new AtomicInteger();
		try (var stream = new PrintStream(output, true, StandardCharsets.UTF_8))
		{
			requireThat(Main.run(new String[]{"prune-actions-caches", "--repository", "owner/repo",
				"--retain-upstream-ref", "rust-v0.160.0"}, InputStream.nullInputStream(), stream, stream,
				command -> switch (index.getAndIncrement())
				{
					case 0 -> "rust-v0.161.0\nrust-v0.160.1\nrust-v0.160.0\n";
					case 1 -> "{\"id\":1,\"key\":\"upstream-rust-v0.160.0-bazel-cache-test\"}\n" +
						"{\"id\":2,\"key\":\"upstream-rust-v0.159.0-pnpm-linux\"}\n";
					case 2 ->
					{
						requireThat(command, "command").isEqualTo(List.of("gh", "api", "--method", "DELETE",
							"repos/owner/repo/actions/caches/2"));
						yield "";
					}
					default -> throw new AssertionError("Unexpected cache deletion");
				}), "status").isEqualTo(0);
		}
		requireThat(index.get(), "commandCount").isEqualTo(3);
		JsonNode json = JsonMapper.builder().build().readTree(output.toString(StandardCharsets.UTF_8));
		requireThat(json.get("obsolete_cache_ids").size(), "obsoleteCacheIdCount").isEqualTo(1);
		requireThat(json.get("obsolete_cache_ids").get(0).asInt(), "obsoleteCacheId").isEqualTo(2);
	}

	/**
	 * Rejects invalid retained references before discovery or deletion can contact GitHub.
	 */
	@Test
	public void rejectsInvalidRetainedReferences()
	{
		for (String ref : List.of("main", "rust-v0.160.0\n", "rust-v0.160.0-alpha.1"))
		{
			var output = new ByteArrayOutputStream();
			var errors = new ByteArrayOutputStream();
			try (var out = new PrintStream(output, true, StandardCharsets.UTF_8);
				var err = new PrintStream(errors, true, StandardCharsets.UTF_8))
			{
				requireThat(Main.run(new String[]{"prune-actions-caches", "--repository", "owner/repo",
					"--retain-upstream-ref", ref}, InputStream.nullInputStream(), out, err, _ ->
					{
						throw new AssertionError("Invalid retention must not contact GitHub");
					}), "status").isEqualTo(1);
			}
			requireThat(output.size(), "outputSize").isEqualTo(0);
			requireThat(errors.toString(StandardCharsets.UTF_8), "errors").startsWith("ERROR:");
		}
	}

	/**
	 * Preserves the cache cleanup JSON fields and integral identifiers in dry-run mode.
	 */
	@Test
	public void printsCacheCleanupResult()
	{
		var output = new ByteArrayOutputStream();
		AtomicInteger index = new AtomicInteger();
		try (var stream = new PrintStream(output, true, StandardCharsets.UTF_8))
		{
			requireThat(Main.run(new String[]{"prune-actions-caches", "--repository", "owner/repo", "--dry-run"},
				InputStream.nullInputStream(), stream, stream, _ -> switch (index.getAndIncrement())
				{
					case 0 -> "rust-v0.160.0\n";
					case 1 -> "{\"id\":9007199254740993,\"key\":\"pnpm-legacy\"}\n";
					default -> throw new AssertionError("Unexpected deletion");
				}), "status").isEqualTo(0);
		}
		JsonNode json = JsonMapper.builder().build().readTree(output.toString(StandardCharsets.UTF_8));
		requireThat(json.get("retained_releases").get(0).asString(), "retainedRelease").isEqualTo("rust-v0.160.0");
		requireThat(json.get("obsolete_cache_ids").get(0).asString(), "obsoleteCacheId").isEqualTo("9007199254740993");
		requireThat(json.get("dry_run").asBoolean(), "dryRun").isTrue();
		requireThat(index.get(), "commandCount").isEqualTo(2);
	}

	/**
	 * Rejects malformed cleanup options before issuing GitHub requests.
	 */
	@Test
	public void rejectsInvalidCleanupArguments()
	{
		var output = new ByteArrayOutputStream();
		try (var stream = new PrintStream(output, true, StandardCharsets.UTF_8))
		{
			for (String[] args : List.of(new String[]{"prune-actions-caches"},
				new String[]{"prune-actions-caches", "--invalid", "owner/repo"},
				new String[]{"prune-actions-caches", "--repository", "owner/repo", "--invalid"}))
				requireThat(Main.run(args, InputStream.nullInputStream(), stream, stream, _ ->
				{
					throw new AssertionError("Unexpected GitHub request");
				}), "status").isEqualTo(2);
		}
	}

	/**
	 * Returns CI dependency status through the production command boundary.
	 */
	@Test
	public void checksCiResults()
	{
		var output = new ByteArrayOutputStream();
		try (var stream = new PrintStream(output, true, StandardCharsets.UTF_8))
		{
			requireThat(Main.run(new String[]{"check-ci-results", "{\"build\":{\"result\":\"success\"}}"},
				InputStream.nullInputStream(), stream, stream), "status").isEqualTo(0);
			requireThat(Main.run(new String[]{"check-ci-results", "{\"build\":{\"result\":\"skipped\"}}"},
				InputStream.nullInputStream(), stream, stream), "status").isEqualTo(1);
			requireThat(Main.run(new String[]{"check-ci-results"}, InputStream.nullInputStream(), stream, stream),
				"status").isEqualTo(2);
		}
	}

	/**
	 * Preserves cache key option ordering, equals syntax, omitted mode, and repeated scalar values.
	 */
	@Test
	public void derivesReleaseCacheKeys()
	{
		var output = new ByteArrayOutputStream();
		try (var stream = new PrintStream(output, true, StandardCharsets.UTF_8))
		{
			requireThat(Main.run(new String[]{"release-cache-keys", "--v8-version=1.2.3", "--target", "old-tag",
				"--compiler-fingerprint", "a".repeat(64), "--target", "x86_64-unknown-linux-gnu"},
				InputStream.nullInputStream(), stream, stream), "status").isEqualTo(0);
		}
		requireThat(output.toString(StandardCharsets.UTF_8), "output").isEqualTo(String.join(System.lineSeparator(),
			"cargo_download_key=codex-release-downloads-v5-x86_64-unknown-linux-gnu",
			"cargo_target_tag=cargo-v2-x86_64-unknown-linux-gnu-off-" + "a".repeat(64),
			"rusty_v8_key=rusty-v8-v2-x86_64-unknown-linux-gnu-1.2.3", "rusty_v8_version=1.2.3", ""));
	}

	/**
	 * Reports cache key usage errors with status two and no standard output.
	 */
	@Test
	public void rejectsInvalidReleaseCacheKeys()
	{
		for (String[] args : new String[][]{{"release-cache-keys"},
			{"release-cache-keys", "--target", "x-y", "--compiler-fingerprint", "a".repeat(64),
				"--v8-version", "1.2.3", "--mode", "fast"},
			{"release-cache-keys", "--target", "single", "--compiler-fingerprint", "a".repeat(64),
				"--v8-version", "1.2.3"}})
		{
			var output = new ByteArrayOutputStream();
			var error = new ByteArrayOutputStream();
			try (var out = new PrintStream(output, true, StandardCharsets.UTF_8);
				var err = new PrintStream(error, true, StandardCharsets.UTF_8))
			{
				requireThat(Main.run(args, InputStream.nullInputStream(), out, err), "status").isEqualTo(2);
			}
			requireThat(output.size(), "outputSize").isEqualTo(0);
			requireThat(error.size(), "errorSize").isGreaterThan(0);
		}
	}

	/**
	 * Prints cache key help successfully without requiring build arguments.
	 */
	@Test
	public void printsReleaseCacheKeyHelp()
	{
		var output = new ByteArrayOutputStream();
		try (var stream = new PrintStream(output, true, StandardCharsets.UTF_8))
		{
			requireThat(Main.run(new String[]{"release-cache-keys", "--help"}, InputStream.nullInputStream(),
				stream, stream), "status").isEqualTo(0);
		}
		requireThat(output.toString(StandardCharsets.UTF_8), "output").contains("--compiler-fingerprint");
	}
}
