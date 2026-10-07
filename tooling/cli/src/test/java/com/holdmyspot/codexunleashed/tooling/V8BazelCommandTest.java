package com.holdmyspot.codexunleashed.tooling;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import java.util.zip.GZIPInputStream;
import org.testng.annotations.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertTrue;

/** Verifies Bazel V8 staging through the real CLI and an inert native child. */
public final class V8BazelCommandTest
{
	/** Creates command tests. */
	public V8BazelCommandTest()
	{
	}

	/**
	 * Executes children in the checkout, stages rebuilt bytes and propagates build refusal.
	 *
	 * @throws IOException if fixtures, child processes or cleanup fail
	 */
	@Test
	public void stagesThroughNativeChild() throws IOException
	{
		Path root = Files.createTempDirectory("v8-bazel-command-");
		try
		{
			Path checkout = Files.createDirectories(root.resolve("checkout with spaces"));
			Files.writeString(checkout.resolve("library.a"), "stale");
			Files.writeString(checkout.resolve("binding.rs"), "binding");
			Map<String, String> environment = new HashMap<>(System.getenv());
			for (String key : List.of("BAZEL_OUTPUT_USER_ROOT", "BUILDBUDDY_API_KEY", "GITHUB_ACTIONS",
				"BAZEL_REPO_CONTENTS_CACHE", "BAZEL_REPOSITORY_CACHE", "BAZEL_DISK_CACHE", "NODE_OPTIONS"))
				environment.put(key, "");
			String node = SystemCommands.capture(List.of("node", "-p", "process.execPath"), root, root, environment).
				stdout().strip();
			Path preload = Files.writeString(root.resolve("bazel fixture.cjs"), """
				const fs = require('node:fs');
				const args = process.argv.slice(1);
				args[0] = require('node:path').basename(args[0]);
				fs.appendFileSync(process.env.FIXTURE_LOG, JSON.stringify({cwd:process.cwd(),args}) + '\\n');
				if (args.includes('build')) {
				  if (process.env.FIXTURE_FAIL_BUILD) { console.error('fixture build refused'); process.exit(37); }
				  fs.writeFileSync('library.a', 'rebuilt');
				} else if (args.includes('cquery')) {
				  console.log('library.a\\nbinding.rs');
				} else if (args.includes('info')) {
				  console.log(process.cwd());
				} else process.exit(91);
				process.exit(0);
				""");
			JsonMapper mapper = JsonMapper.builder().build();
			Path log = root.resolve("calls.jsonl");
			environment.put("FIXTURE_LOG", log.toString());
			environment.put("CODEX_BAZEL_BIN", node);
			environment.put("NODE_OPTIONS", "--require=" + mapper.writeValueAsString(preload.toString()));
			Path output = root.resolve("staged");
			SystemCommands.Result staged = cli(root, environment, checkout, output);
			assertEquals(staged.status(), 0, staged.stdout() + staged.stderr());
			assertEquals(TextLines.split(staged.stdout()).size(), 3);
			try (InputStream input = new GZIPInputStream(Files.newInputStream(output.resolve(
				"librusty_v8_release_x86_64-unknown-linux-gnu.a.gz"))))
			{
				assertEquals(input.readAllBytes(), "rebuilt".getBytes(java.nio.charset.StandardCharsets.UTF_8));
			}
			String manifest = Files.readString(output.resolve("rusty_v8_release_x86_64-unknown-linux-gnu.sha256"));
			assertTrue(manifest.endsWith("\r\n"), manifest);
			assertFalse(manifest.replace("\r\n", "").contains("\n"), manifest);
			assertEquals(manifest.split("\r\n").length, 2);
			List<String> calls = TextLines.split(Files.readString(log));
			assertEquals(calls.size(), 4);
			for (String call : calls)
				assertEquals(mapper.readTree(call).path("cwd").asString(), checkout.toString());
			JsonNode build = mapper.readTree(calls.getFirst()).path("args");
			assertEquals(build.get(0).asString(), "build");
			assertTrue(build.toString().contains("--remote_download_toplevel"));
			assertTrue(build.toString().contains("--config=rusty-v8-upstream-libcxx"));
			assertTrue(build.toString().contains("--config=fixture-config"));
			assertTrue(build.toString().contains("--config=fixture-second"));
			Files.delete(log);
			environment.put("FIXTURE_FAIL_BUILD", "1");
			Path refused = root.resolve("refused");
			SystemCommands.Result failed = cli(root, environment, checkout, refused);
			assertEquals(failed.status(), 1, failed.stdout() + failed.stderr());
			assertTrue(failed.stderr().contains("fixture build refused"), failed.stderr());
			assertEquals(TextLines.split(Files.readString(log)).size(), 1);
			assertFalse(Files.exists(refused));
		}
		finally
		{
			try (Stream<Path> files = Files.walk(root))
			{
				for (Path file : files.sorted(Comparator.reverseOrder()).toList())
					Files.delete(file);
			}
		}
	}

	/**
	 * Runs the named-module CLI from a directory different from its explicit checkout.
	 *
	 * @param root owned process and temporary directory
	 * @param environment controlled child environment
	 * @param checkout explicit repository working directory
	 * @param output staging directory
	 * @return completed command outcome
	 * @throws IOException if child handling fails
	 */
	private static SystemCommands.Result cli(Path root, Map<String, String> environment, Path checkout, Path output)
		throws IOException
	{
		List<String> command = new ArrayList<>(List.of(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
			"-Djava.io.tmpdir=" + root, "-Dline.separator=\r\n", "--module-path", System.getProperty("jdk.module.path"),
			"--module",
			"com.holdmyspot.codexunleashed.tooling/com.holdmyspot.codexunleashed.tooling.Main", "rusty-v8-bazel",
			checkout.toString(), "stage-release-pair", "--platform", "linux-x86_64", "--target",
			"x86_64-unknown-linux-gnu", "--output-dir", output.toString(), "--skip-build", "--bazel-config",
			"fixture-config", "--bazel-config", "fixture-second", "--bazel-config", "fixture-config",
			"--compilation-mode", "opt"));
		return SystemCommands.capture(command, root, root, environment);
	}
}
