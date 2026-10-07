package com.holdmyspot.codexunleashed.distribution;

import java.io.File;
import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.testng.annotations.Test;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertTrue;

/** Tests reproduction's current prerequisites without executing Git transport or a release build. */
public final class ReproductionPrerequisitesTest
{
	/** Creates prerequisite tests. */
	public ReproductionPrerequisitesTest()
	{
	}

	/**
	 * Reaches the recorded-source retrieval boundary with no Python command on the controlled PATH.
	 *
	 * @throws IOException if fixture, process or cleanup operations fail
	 * @throws URISyntaxException if native fixture class locations are invalid
	 * @throws InterruptedException if process waiting is interrupted
	 */
	@Test
	public void startsWithoutPythonPrerequisite() throws IOException, URISyntaxException, InterruptedException
	{
		Path root = Files.createTempDirectory("reproduction-prerequisite-");
		try
		{
			Path commands = Files.createDirectory(root.resolve("commands"));
			for (String name : List.of("bash", "dirname", "basename", "mkdir", "mktemp", "rm", "jq"))
				Files.createSymbolicLink(commands.resolve(name), executable(name));
			JavaCommandFixtures.writeLauncher(commands.resolve("git"), ReproductionGitFixture.class);
			assertFalse(Files.exists(commands.resolve("python3")));
			Path manifest = Files.writeString(root.resolve("release-manifest.json"), """
				{"release": {"patch_repository": "fixtures/repository", "build_number": "1",
				  "supported_targets": ["x86_64-unknown-linux-musl"]},
				 "workflow": {"sha": "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"},
				 "upstream": {"repository": "openai/codex", "tag": "rust-v0.160.0",
				  "commit": "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"}, "patches": [], "artifacts": []}
				""");
			Path project = Path.of(System.getProperty("tooling.release.workflow")).getParent().getParent().getParent();
			Path marker = root.resolve("git-marker");
			Path temporary = Files.createDirectory(root.resolve("temporary"));
			Path console = root.resolve("console");
			Path input = Files.writeString(root.resolve("stdin"), "");
			ProcessBuilder builder = new ProcessBuilder(commands.resolve("bash").toString(),
				project.resolve("scripts/reproduce-release.sh").toString(), manifest.toString(), "--output-dir",
				root.resolve("output").toString()).directory(root.toFile()).redirectInput(input.toFile()).
				redirectErrorStream(true).redirectOutput(console.toFile());
			builder.environment().clear();
			builder.environment().putAll(Map.of("PATH", commands.toString(), "TMPDIR", temporary.toString(),
				"REPRODUCTION_GIT_MARKER", marker.toString(), "XDG_CACHE_HOME", root.resolve("xdg").toString()));
			try (Process process = builder.start())
			{
				try
				{
					assertTrue(process.waitFor(30, TimeUnit.SECONDS), "Reproduction prerequisite check timed out");
					assertEquals(process.exitValue(), 37, Files.readString(console));
				}
				finally
				{
					if (process.isAlive())
						process.destroyForcibly().waitFor();
				}
			}
			assertEquals(Files.readString(marker), "clone");
			try (Stream<Path> paths = Files.list(temporary))
			{
				assertEquals(paths.count(), 0L);
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
	 * Resolves the native utility from the current executor PATH before constructing the isolated PATH.
	 *
	 * @param name utility name
	 * @return canonical executable path
	 * @throws IOException if the named utility is unavailable
	 */
	private static Path executable(String name) throws IOException
	{
		for (String directory : System.getenv("PATH").split(Pattern.quote(File.pathSeparator)))
		{
			Path candidate = Path.of(directory).resolve(name);
			if (Files.isExecutable(candidate) && !Files.isDirectory(candidate))
				return candidate.toRealPath();
		}
		throw new IOException("Required native fixture utility is unavailable: " + name);
	}
}
