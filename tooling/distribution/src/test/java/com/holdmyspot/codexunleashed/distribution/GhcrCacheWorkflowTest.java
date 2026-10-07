package com.holdmyspot.codexunleashed.distribution;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.File;
import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.testng.annotations.Test;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertTrue;

/** Executes GHCR cache operations through a fresh runtime with real Bash, tar and isolated registry fixtures. */
public final class GhcrCacheWorkflowTest
{
	/** Creates cache workflow tests. */
	public GhcrCacheWorkflowTest()
	{
	}

	/**
	 * Rejects invalid tags before transport and preserves dependency payloads across every original restore case.
	 *
	 * @throws IOException if fixture, process or cleanup operations fail
	 * @throws InterruptedException if waiting is interrupted
	 * @throws URISyntaxException if native fixture class locations are invalid
	 */
	@Test
	public void restoresOnlyReusableDependencies() throws IOException, InterruptedException, URISyntaxException
	{
		Path root = Files.createTempDirectory("ghcr-pull-");
		try
		{
			Map<String, String> environment = environment(root);
			cache(root, environment, "pull", "unexpected-tag", root.resolve("restored"), "rust-v0.158.0", 2);
			assertFalse(Files.exists(root.resolve("commands")));
			assertFalse(Files.exists(root.resolve("restored")));
			assertTrue(Files.readString(root.resolve("stderr")).contains("Cargo cache tag"));
			restore(root.resolve("legacy"), environment, "cargo-x86_64-apple-darwin-rust-v0.158.0", "rust-v0.158.0",
				false, false);
			String tag = "cargo-v2-x86_64-apple-darwin-off-" + "a".repeat(64);
			for (String version : List.of("rust-v0.159.0", "rust-v0.159.1"))
				restore(root.resolve(version), environment, tag, version, false, false);
			restore(root.resolve("migration"), environment, tag, "rust-v0.159.1", true, false);
			restore(root.resolve("gnu-tar"), environment, tag, "rust-v0.160.0", false, true);
		}
		finally
		{
			delete(root);
		}
	}

	/**
	 * Keeps the latest two release associations and refuses deletion when discovery is empty or fails.
	 *
	 * @throws IOException if fixture, process or cleanup operations fail
	 * @throws InterruptedException if waiting is interrupted
	 * @throws URISyntaxException if native fixture class locations are invalid
	 */
	@Test
	public void prunesWithVerifiedReleaseInventory() throws IOException, InterruptedException, URISyntaxException
	{
		Path root = Files.createTempDirectory("ghcr-prune-");
		try
		{
			Map<String, String> environment = environment(root);
			String tag = "cargo-v2-x86_64-apple-darwin-off-" + "a".repeat(64);
			cache(root, environment, "prune", tag, root.resolve("target"), "rust-v0.159.1", 0);
			Path deleted = root.resolve("deleted");
			assertEquals(Files.readAllLines(deleted), List.of("4", "5", "6", "7"));
			Files.delete(deleted);
			for (String status : List.of("0", "1"))
			{
				environment.put("CACHE_TEST_LOOKUP", status);
				cache(root, environment, "prune", tag, root.resolve("target"), "rust-v0.160.0", 1);
				assertFalse(Files.exists(deleted));
			}
		}
		finally
		{
			delete(root);
		}
	}

	/**
	 * Streams real tar archives through a colon-bearing temporary path and publishes the release alias first.
	 *
	 * @throws IOException if fixture, process or cleanup operations fail
	 * @throws InterruptedException if waiting is interrupted
	 * @throws URISyntaxException if native fixture class locations are invalid
	 * @throws NoSuchAlgorithmException if SHA-256 is unavailable
	 */
	@Test
	public void roundTripsColonTemporaryPath() throws IOException, InterruptedException, URISyntaxException,
		NoSuchAlgorithmException
	{
		Path root = Files.createTempDirectory("ghcr-roundtrip-");
		try
		{
			Map<String, String> environment = environment(root);
			String temporaryName = "D:";
			if (File.separatorChar == '\\')
				temporaryName = "colon-drive-temporary";
			Path temporary = Files.createDirectory(root.resolve(temporaryName));
			assertTrue(temporary.toString().contains(":"), "The fixture requires a colon-bearing path");
			environment.put("RUNNER_TEMP", temporary.toString());
			Path source = Files.createDirectory(root.resolve("source"));
			Path relative = Path.of("x86_64-pc-windows-msvc/release/deps/example.rlib");
			Files.createDirectories(source.resolve(relative).getParent());
			Files.writeString(source.resolve(relative), "compiled dependency");
			String tag = "cargo-v2-x86_64-pc-windows-msvc-off-" + "a".repeat(64);
			cache(root, environment, "push", tag, source, "rust-v0.160.0", 0);
			Path restored = root.resolve("restored");
			cache(root, environment, "pull", tag, restored, "rust-v0.160.0", 0);
			assertEquals(Files.readString(restored.resolve(relative)), "compiled dependency");
			List<List<String>> calls = commands(root.resolve("commands"));
			String identity = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(
				tag.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
			String reference = "ghcr.io/example/cargo-cache:cargo-release-" + identity + "-rust-v0.160.0";
			assertEquals(calls.getFirst().subList(0, 2), List.of("push", reference));
			assertEquals(calls.get(1), List.of("tag", reference, tag));
			try (Stream<Path> entries = Files.list(temporary))
			{
				assertEquals(entries.count(), 0L);
			}
		}
		finally
		{
			delete(root);
		}
	}

	/**
	 * Restores a real hard-linked archive and checks transport fallback, cleanup and dependency identity.
	 *
	 * @param root owned case directory
	 * @param parent complete shared environment
	 * @param tag cache identity
	 * @param version upstream release association
	 * @param miss whether the stable cache transport refuses its first request
	 * @param gnu whether an available GNU tar must replace a failing native tar
	 * @throws IOException if fixture, process or cleanup operations fail
	 * @throws InterruptedException if waiting is interrupted
	 * @throws URISyntaxException if native fixture class locations are invalid
	 */
	private static void restore(Path root, Map<String, String> parent, String tag, String version, boolean miss,
		boolean gnu) throws IOException, InterruptedException, URISyntaxException
	{
		Files.createDirectory(root);
		Map<String, String> environment = new HashMap<>(parent);
		environment.put("CACHE_TEST_COMMANDS", root.resolve("commands").toString());
		environment.put("CACHE_TEST_REMOTE", root.resolve("archive").toString());
		String stableMiss = "0";
		if (miss)
			stableMiss = "1";
		environment.put("CACHE_TEST_STABLE_MISS", stableMiss);
		Path temporary = Files.createDirectory(root.resolve("temporary"));
		environment.put("RUNNER_TEMP", temporary.toString());
		Path source = root.resolve("source");
		Path release = Files.createDirectories(source.resolve("x86_64-apple-darwin/release"));
		Path dependencies = Files.createDirectory(release.resolve("deps"));
		Files.writeString(dependencies.resolve("libcodex_example.rlib"), "reusable dependency");
		Files.writeString(dependencies.resolve("codex-generated-data"), "reusable codex dependency");
		for (String binary : List.of("codex", "codex-code-mode-host", "codex-responses-api-proxy", "bwrap"))
			Files.writeString(release.resolve(binary), "cached " + binary);
		Files.createLink(dependencies.resolve("codex-hashed-binary"), release.resolve("codex"));
		Files.createLink(dependencies.resolve("bwrap-hashed-binary"), release.resolve("bwrap"));
		Path symbols = release.resolve("codex.dSYM/Contents/Resources/DWARF/codex");
		Files.createDirectories(symbols.getParent());
		Files.writeString(symbols, "cached symbols");
		run(root, environment, List.of("tar", "--zstd", "-cf", environment.get("CACHE_TEST_REMOTE"), "-C",
			source.toString(), "./x86_64-apple-darwin/release/codex", "./x86_64-apple-darwin/release/bwrap",
			"./x86_64-apple-darwin/release/deps", "./x86_64-apple-darwin/release/codex-code-mode-host",
			"./x86_64-apple-darwin/release/codex-responses-api-proxy", "./x86_64-apple-darwin/release/codex.dSYM"), 0);
		if (gnu)
		{
			Path adapters = Files.createDirectory(root.resolve("adapters"));
			String tar = run(root, environment, List.of("bash", "-c", "command -v gtar || command -v tar"), 0).strip();
			Files.createSymbolicLink(adapters.resolve("gtar"), Path.of(tar));
			JavaCommandFixtures.writeLauncher(adapters.resolve("tar"), RejectedCommandFixture.class);
			environment.put("PATH", adapters + File.pathSeparator + environment.get("PATH"));
		}
		Path target = root.resolve("restored");
		Path previous = Files.createDirectories(target.resolve("x86_64-apple-darwin/release"));
		Files.writeString(previous.resolve("codex-responses-api-proxy"), "previous cache");
		Files.createDirectories(previous.resolve("codex.dSYM/Contents"));
		cache(root, environment, "pull", tag, target, version, 0);
		List<String> references = commands(root.resolve("commands")).stream().map(call -> call.get(1)).toList();
		var expected = new ArrayList<>(List.of("ghcr.io/example/cargo-cache:" + tag));
		if (miss)
			expected.add("ghcr.io/example/cargo-cache:cargo-x86_64-apple-darwin-" + version);
		assertEquals(references, expected);
		Path restored = target.resolve("x86_64-apple-darwin/release");
		assertEquals(Files.readString(restored.resolve("deps/libcodex_example.rlib")), "reusable dependency");
		assertEquals(Files.readString(restored.resolve("deps/codex-generated-data")), "reusable codex dependency");
		assertEquals(Files.readString(restored.resolve("deps/codex-hashed-binary")), "cached codex");
		assertEquals(Files.readString(restored.resolve("deps/bwrap-hashed-binary")), "cached bwrap");
		for (String removed : List.of("codex", "codex-code-mode-host", "codex-responses-api-proxy", "bwrap", "codex.dSYM"))
			assertFalse(Files.exists(restored.resolve(removed)), removed);
		try (Stream<Path> entries = Files.list(temporary))
		{
			assertEquals(entries.count(), 0L);
		}
	}

	/**
	 * Creates a fresh runtime and controlled native boundaries while rejecting Python and system Java on PATH.
	 *
	 * @param root owned fixture root
	 * @return complete fixture environment
	 * @throws IOException if fixture operations fail
	 * @throws URISyntaxException if native class locations are invalid
	 */
	private static Map<String, String> environment(Path root) throws IOException, URISyntaxException
	{
		Path adapters = Files.createDirectory(root.resolve("adapters"));
		for (String name : List.of("gh", "oras"))
			JavaCommandFixtures.writeLauncher(adapters.resolve(name), GhcrBoundaryFixture.class);
		for (String name : List.of("python", "python3", "java"))
			JavaCommandFixtures.writeLauncher(adapters.resolve(name), RejectedCommandFixture.class);
		Path runtime = root.resolve("runtime with spaces");
		DistributionMain.main(new String[]{System.getProperty("tooling.runtime.modules"), runtime.toString()});
		Map<String, String> environment = new HashMap<>(System.getenv());
		environment.remove("CACHE_TEST_LOOKUP");
		environment.remove("CACHE_TEST_STABLE_MISS");
		environment.put("PATH", adapters + File.pathSeparator + environment.getOrDefault("PATH", ""));
		environment.put("CODEX_UNLEASHED_TOOLING", runtime.resolve("bin/codex-tooling").toString());
		environment.put("CACHE_TEST_COMMANDS", root.resolve("commands").toString());
		environment.put("CACHE_TEST_DELETIONS", root.resolve("deleted").toString());
		environment.put("CACHE_TEST_REMOTE", root.resolve("registry-archive").toString());
		environment.put("RUNNER_TEMP", root.toString());
		environment.put("TMPDIR", root.toString());
		environment.put("XDG_CACHE_HOME", Files.createDirectory(root.resolve("xdg")).toString());
		return environment;
	}

	/**
	 * Invokes the maintained cache script with the expected outcome.
	 *
	 * @param root process directory
	 * @param environment complete environment
	 * @param operation cache operation
	 * @param tag cache identity
	 * @param target archive source or destination
	 * @param version upstream association
	 * @param status expected process status
	 * @throws IOException if process or capture access fails
	 * @throws InterruptedException if waiting is interrupted
	 */
	private static void cache(Path root, Map<String, String> environment, String operation, String tag,
		Path target, String version, int status) throws IOException, InterruptedException
	{
		Path script = Path.of(System.getProperty("tooling.release.workflow")).getParent().getParent().
			resolve("scripts/ghcr-cargo-target-cache.sh");
		run(root, environment, NativeCommands.scriptBuilder(script, operation, "ghcr.io/example/cargo-cache", tag,
			target.toString(), version), status);
	}

	/**
	 * Reads the independent length-prefixed native invocation receipt.
	 *
	 * @param log fixture command log
	 * @return recorded argument vectors
	 * @throws IOException if the log is unreadable or incomplete
	 */
	private static List<List<String>> commands(Path log) throws IOException
	{
		var commands = new ArrayList<List<String>>();
		try (var input = new DataInputStream(new ByteArrayInputStream(Files.readAllBytes(log))))
		{
			while (input.available() > 0)
			{
				int count = input.readInt();
				var command = new ArrayList<String>();
				for (int index = 0; index < count; index += 1)
					command.add(input.readUTF());
				commands.add(command);
			}
		}
		return commands;
	}

	/**
	 * Executes native commands with EOF input, independent captures and bounded termination.
	 *
	 * @param root process directory
	 * @param environment complete environment
	 * @param command executable and arguments
	 * @param status expected exit status
	 * @return standard output
	 * @throws IOException if process or capture access fails
	 * @throws InterruptedException if waiting is interrupted
	 */
	private static String run(Path root, Map<String, String> environment, List<String> command, int status)
		throws IOException, InterruptedException
	{
		return run(root, environment, NativeCommands.createBuilder(command), status);
	}

	/**
	 * Executes a prepared native command with independent captures and bounded termination.
	 *
	 * @param root process directory
	 * @param environment complete environment
	 * @param command the prepared native command
	 * @param status expected exit status
	 * @return standard output
	 * @throws IOException if process or capture access fails
	 * @throws InterruptedException if waiting is interrupted
	 */
	private static String run(Path root, Map<String, String> environment, ProcessBuilder command, int status)
		throws IOException, InterruptedException
	{
		Path output = root.resolve("stdout");
		Path error = root.resolve("stderr");
		Path input = Files.writeString(root.resolve("stdin"), "");
		ProcessBuilder builder = command.directory(root.toFile()).redirectInput(input.toFile()).
			redirectOutput(output.toFile()).redirectError(error.toFile());
		builder.environment().clear();
		builder.environment().putAll(environment);
		try (Process process = builder.start())
		{
			try
			{
				assertTrue(process.waitFor(30, TimeUnit.SECONDS), "Cache process timed out: " + command.command());
				assertEquals(process.exitValue(), status, command.command() + "\n" + Files.readString(error));
				return Files.readString(output);
			}
			finally
			{
				if (process.isAlive())
					process.destroyForcibly().waitFor();
			}
		}
	}

	/**
	 * Deletes every owned fixture after success or failure without following links.
	 *
	 * @param root fixture root
	 * @throws IOException if cleanup fails
	 */
	private static void delete(Path root) throws IOException
	{
		FixtureDirectories.deleteTree(root);
	}
}
