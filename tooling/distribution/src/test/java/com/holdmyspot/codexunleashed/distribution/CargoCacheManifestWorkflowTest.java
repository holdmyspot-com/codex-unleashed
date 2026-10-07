package com.holdmyspot.codexunleashed.distribution;

import java.io.File;
import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.testng.annotations.Test;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertTrue;

/** Executes both maintained Cargo cache manifest steps with a fresh bundled runtime and independent TSV checks. */
public final class CargoCacheManifestWorkflowTest
{
	/** Creates workflow tests. */
	public CargoCacheManifestWorkflowTest()
	{
	}

	/**
	 * Runs actual release step bodies with Python and system Java rejected and validates every byte-size record.
	 *
	 * @throws IOException if fixture, runtime or process access fails
	 * @throws InterruptedException if process waiting is interrupted
	 * @throws URISyntaxException if native fixture class locations are invalid
	 */
	@Test
	public void executesBothManifestCallers() throws IOException, InterruptedException, URISyntaxException
	{
		Path root = Files.createTempDirectory("cargo-manifest-workflow-");
		try
		{
			Path adapters = Files.createDirectory(root.resolve("adapters"));
			for (String name : List.of("python", "python3", "java"))
				JavaCommandFixtures.writeLauncher(adapters.resolve(name), RejectedCommandFixture.class);
			Path image = root.resolve("runtime with spaces");
			DistributionMain.main(new String[]{System.getProperty("tooling.runtime.modules"), image.toString()});
			Path target = Files.createDirectory(root.resolve("target"));
			Path binary = Files.write(target.resolve("cached binary"), new byte[]{0, (byte) 255, 7});
			Path home = root.resolve("cargo-home");
			Path registry = Files.createDirectories(home.resolve("registry/cache"));
			Path crate = Files.writeString(registry.resolve("crate"), "crate");
			Path git = Files.createDirectory(home.resolve("git"));
			Path source = Files.writeString(git.resolve("source"), "source");
			Path captures = Files.createDirectory(root.resolve("captures"));
			Map<String, String> environment = new HashMap<>(System.getenv());
			environment.put("PATH", adapters + File.pathSeparator + environment.getOrDefault("PATH", ""));
			environment.put("CODEX_UNLEASHED_TOOLING", image.resolve("bin/codex-tooling").toString());
			environment.put("CARGO_TARGET_DIR", target.toString());
			environment.put("CARGO_HOME", home.toString());
			environment.put("TMPDIR", captures.toString());
			environment.put("XDG_CACHE_HOME", Files.createDirectory(root.resolve("xdg")).toString());
			for (String job : List.of("build-unix", "build-windows-binaries"))
			{
				Path manifest = root.resolve(job + ".tsv");
				environment.put("MANIFEST_PATH", manifest.toString());
				String command = WorkflowCommands.readStepCommand(job, "Write complete Cargo cache manifest");
				run(root, environment, command);
				assertEquals(Files.readString(manifest), ("3\t" + binary + "\n5\t" + crate + "\n6\t" + source + "\n").
					replace("\n", System.lineSeparator()));
			}
			try (Stream<Path> entries = Files.list(captures))
			{
				assertEquals(entries.count(), 0L);
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
	 * Executes the maintained shell command with complete environment, EOF input and bounded owned capture.
	 *
	 * @param root process directory
	 * @param environment complete child environment
	 * @param command maintained workflow body
	 * @throws IOException if process or capture access fails
	 * @throws InterruptedException if waiting is interrupted
	 */
	private static void run(Path root, Map<String, String> environment, String command)
		throws IOException, InterruptedException
	{
		Path output = root.resolve("console");
		Path input = Files.writeString(root.resolve("stdin"), "");
		ProcessBuilder builder = new ProcessBuilder("bash", "-eu", "-c", command).directory(root.toFile()).
			redirectInput(input.toFile()).redirectErrorStream(true).redirectOutput(output.toFile());
		builder.environment().clear();
		builder.environment().putAll(environment);
		try (Process process = builder.start())
		{
			try
			{
				assertTrue(process.waitFor(30, TimeUnit.SECONDS), "Manifest workflow timed out");
				assertEquals(process.exitValue(), 0, Files.readString(output));
			}
			finally
			{
				if (process.isAlive())
					process.destroyForcibly().waitFor();
			}
		}
	}
}
