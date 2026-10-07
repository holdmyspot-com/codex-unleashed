package com.holdmyspot.codexunleashed.distribution;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.Comparator;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import org.testng.annotations.Test;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertThrows;
import static org.testng.Assert.assertTrue;

/**
 * Verifies complete runtime assembly and standalone launcher behavior.
 */
public final class DistributionMainTest
{
	/**
	 * Creates the runtime assembly tests.
	 */
	public DistributionMainTest()
	{
	}

	/**
	 * Executes the maintained installer workflow step through a bundled launcher in a whitespace path.
	 * The search path contains neither Java nor Python.
	 *
	 * @throws IOException if image assembly or fixture access fails
	 * @throws InterruptedException if the launcher wait is interrupted
	 */
	@Test
	public void buildsStandaloneRuntime() throws IOException, InterruptedException
	{
		try (Fixture fixture = new Fixture())
		{
			Path modules = Files.createDirectory(fixture.root.resolve("modules"));
			Path stagedModules = Path.of(System.getProperty("tooling.runtime.modules"));
			try (Stream<Path> jars = Files.list(stagedModules))
			{
				for (Path jar : jars.toList())
					Files.copy(jar, modules.resolve(jar.getFileName()));
			}
			Path image = fixture.root.resolve("runtime with spaces");
			DistributionMain.main(new String[]{modules.toString(), image.toString()});
			Path java = image.resolve("bin/java");
			if (!Files.isRegularFile(java))
				java = java.resolveSibling("java.exe");
			Path moduleLog = fixture.root.resolve("runtime-modules.log");
			ProcessBuilder moduleBuilder = new ProcessBuilder(java.toString(),
				"-Djava.io.tmpdir=" + fixture.root, "--list-modules");
			moduleBuilder.redirectErrorStream(true).redirectOutput(moduleLog.toFile());
			try (Process process = moduleBuilder.start())
			{
				assertTrue(process.waitFor(30, TimeUnit.SECONDS), "Runtime module inspection timed out");
				assertEquals(process.exitValue(), 0, Files.readString(moduleLog));
			}
			assertTrue(Files.readString(moduleLog).contains("java.net.http@"));
			assertFalse(Files.readString(moduleLog).contains("jdk.httpserver@"));
			Path checkout = Files.createDirectory(fixture.root.resolve("upstream-installers"));
			Path source = Files.createDirectories(checkout.resolve("scripts/install"));
			Path release = Files.createDirectory(fixture.root.resolve("release-stage"));
			String installer = "holdmyspot-com/codex-unleashed\r\n";
			Files.writeString(source.resolve("install.sh"), installer);
			Files.writeString(source.resolve("install.ps1"), installer);
			Path log = fixture.root.resolve("launcher.log");
			String command = WorkflowCommands.readStepCommand("publish", "Stage patched release installers");
			var builder = new ProcessBuilder("bash", "-eu", "-c", command);
			builder.directory(fixture.root.toFile()).redirectErrorStream(true).redirectOutput(log.toFile());
			builder.environment().put("CODEX_UNLEASHED_TOOLING", image.resolve("bin/codex-tooling").toString());
			builder.environment().put("PATH", fixture.root.resolve("empty-search-path").toString());
			Path temporaryDirectory = Files.createDirectory(fixture.root.resolve("runtime temp"));
			builder.environment().put("TMPDIR", temporaryDirectory.toString());
			builder.environment().put("JDK_JAVA_OPTIONS", "-XshowSettings:properties");
			try (Process process = builder.start())
			{
				try
				{
					assertTrue(process.waitFor(30, TimeUnit.SECONDS), "Bundled launcher timed out");
					assertEquals(process.exitValue(), 0, Files.readString(log));
					assertTrue(Files.readString(log).contains("java.io.tmpdir = " + temporaryDirectory),
						Files.readString(log));
				}
				finally
				{
					if (process.isAlive())
						process.destroyForcibly().waitFor();
				}
			}
			assertEquals(Files.readString(release.resolve("install.sh")), installer);
			assertEquals(Files.readString(release.resolve("install.ps1")), installer);
		}
	}

	/**
	 * Executes V8 canary metadata selection without Python or Java on the search path.
	 *
	 * @throws IOException if runtime assembly or fixture access fails
	 * @throws InterruptedException if the command wait is interrupted
	 */
	@Test
	public void resolvesCanaryMetadata() throws IOException, InterruptedException
	{
		try (Fixture fixture = new Fixture())
		{
			Path image = fixture.root.resolve("runtime");
			DistributionMain.main(new String[]{System.getProperty("tooling.runtime.modules"), image.toString()});
			Path source = Files.createDirectories(fixture.root.resolve("upstream/codex-rs"));
			Files.writeString(source.resolve("Cargo.lock"), "[[package]]\nname='v8'\nversion='149.2.0'\n");
			Path outputs = fixture.root.resolve("github-output");
			Path log = fixture.root.resolve("canary.log");
			Path workflow = Path.of(System.getProperty("tooling.release.workflow")).resolveSibling("v8-canary.yml");
			String command = WorkflowCommands.readStepCommand(workflow, "metadata", "Resolve exact v8 crate version");
			ProcessBuilder builder = new ProcessBuilder("bash", "-eu", "-c", command);
			builder.directory(fixture.root.toFile()).redirectErrorStream(true).redirectOutput(log.toFile());
			builder.environment().put("CODEX_UNLEASHED_TOOLING", image.resolve("bin/codex-tooling").toString());
			builder.environment().put("GITHUB_OUTPUT", outputs.toString());
			builder.environment().put("PATH", fixture.root.resolve("empty-search-path").toString());
			builder.environment().put("TMPDIR", fixture.root.toString());
			try (Process process = builder.start())
			{
				try
				{
					assertTrue(process.waitFor(30, TimeUnit.SECONDS), "V8 version selection timed out");
					assertEquals(process.exitValue(), 0, Files.readString(log));
				}
				finally
				{
					if (process.isAlive())
						process.destroyForcibly().waitFor();
				}
			}
			assertEquals(Files.readString(outputs), "version=149.2.0\n");
		}
	}

	/**
	 * Executes forced canary decisions with no Python, Java, or Git on the search path.
	 *
	 * @throws IOException if runtime assembly or fixture access fails
	 * @throws InterruptedException if the command wait is interrupted
	 */
	@Test
	public void selectsForcedCanary() throws IOException, InterruptedException
	{
		try (Fixture fixture = new Fixture())
		{
			Path image = fixture.root.resolve("runtime with spaces");
			DistributionMain.main(new String[]{System.getProperty("tooling.runtime.modules"), image.toString()});
			Path log = fixture.root.resolve("canary.log");
			ProcessBuilder builder = new ProcessBuilder("bash", "-eu", "-c",
				"\"$CODEX_UNLEASHED_TOOLING\" v8-canary-changes . --force");
			builder.directory(fixture.root.toFile()).redirectErrorStream(true).redirectOutput(log.toFile());
			builder.environment().put("CODEX_UNLEASHED_TOOLING", image.resolve("bin/codex-tooling").toString());
			builder.environment().put("PATH", fixture.root.resolve("empty-search-path").toString());
			builder.environment().put("TMPDIR", fixture.root.toString());
			try (Process process = builder.start())
			{
				assertTrue(process.waitFor(30, TimeUnit.SECONDS), "Canary selection timed out");
				assertEquals(process.exitValue(), 0, Files.readString(log));
			}
			assertEquals(Files.readString(log), "canary_required=true\ncanary_reason=manual workflow dispatch\n" +
				"windows_source_required=true\nwindows_source_reason=manual workflow dispatch\n");
		}
	}

	/**
	 * Executes both release normalization steps through the bundled runtime with an empty search path.
	 *
	 * @throws IOException if runtime assembly or fixture access fails
	 * @throws InterruptedException if the workflow command wait is interrupted
	 */
	@Test
	public void normalizesReleaseSources() throws IOException, InterruptedException
	{
		try (Fixture fixture = new Fixture())
		{
			Path image = fixture.root.resolve("runtime");
			DistributionMain.main(new String[]{System.getProperty("tooling.runtime.modules"), image.toString()});
			Path checkout = Files.createDirectories(fixture.root.resolve("upstream"));
			Path source = Files.writeString(checkout.resolve("source.txt"), "fixture");
			Path metadata = Files.createDirectories(checkout.resolve(".git")).resolve("marker");
			Files.writeString(metadata, "retained");
			FileTime original = Files.getLastModifiedTime(metadata);
			FileTime expected = FileTime.from(1_600_000_000, TimeUnit.SECONDS);
			for (String job : new String[]{"build-unix", "build-windows-binaries"})
			{
				Files.setLastModifiedTime(source, FileTime.from(100, TimeUnit.SECONDS));
				Path log = fixture.root.resolve(job + ".log");
				String command = WorkflowCommands.readStepCommand(job, "Normalize patched source timestamps");
				ProcessBuilder builder = new ProcessBuilder("bash", "-eu", "-c", command);
				builder.directory(fixture.root.toFile()).redirectErrorStream(true).redirectOutput(log.toFile());
				builder.environment().put("CODEX_UNLEASHED_TOOLING", image.resolve("bin/codex-tooling").toString());
				builder.environment().put("SOURCE_DATE_EPOCH", "1600000000");
				builder.environment().put("PATH", fixture.root.resolve("empty-search-path").toString());
				builder.environment().put("TMPDIR", fixture.root.toString());
				try (Process process = builder.start())
				{
					try
					{
						assertTrue(process.waitFor(30, TimeUnit.SECONDS), "Source normalization timed out");
						assertEquals(process.exitValue(), 0, Files.readString(log));
					}
					finally
					{
						if (process.isAlive())
							process.destroyForcibly().waitFor();
					}
				}
				assertEquals(Files.getLastModifiedTime(source), expected);
				assertEquals(Files.getLastModifiedTime(checkout), expected);
				assertEquals(Files.getLastModifiedTime(metadata), original);
			}
		}
	}

	/**
	 * Rejects existing output without changing its contents.
	 *
	 * @throws IOException if fixture access fails
	 */
	@Test
	public void preservesExistingOutput() throws IOException
	{
		try (Fixture fixture = new Fixture())
		{
			Path marker = fixture.root.resolve("marker.txt");
			Files.writeString(marker, "retained");
			assertThrows(IllegalArgumentException.class, () -> DistributionMain.main(
				new String[]{fixture.root.toString(), fixture.root.toString()}));
			assertEquals(Files.readString(marker), "retained");
		}
	}

	/**
	 * Rejects missing modules and malformed argument lists.
	 *
	 * @throws IOException if fixture access fails
	 */
	@Test
	public void rejectsInvalidInputs() throws IOException
	{
		try (Fixture fixture = new Fixture())
		{
			Path image = fixture.root.resolve("image");
			assertThrows(IOException.class, () -> DistributionMain.main(
				new String[]{fixture.root.resolve("missing-modules").toString(), image.toString()}));
			assertFalse(Files.exists(image));
			assertThrows(IllegalArgumentException.class, () -> DistributionMain.main(new String[0]));
		}
	}

	/**
	 * Owns temporary image and launcher inputs for one test.
	 */
	private static final class Fixture implements AutoCloseable
	{
		private final Path root;

		/**
		 * Allocates the fixture directory.
		 *
		 * @throws IOException if directory creation fails
		 */
		private Fixture() throws IOException
		{
			root = Files.createTempDirectory("runtime-");
		}

		/**
		 * Deletes the owned fixture tree.
		 *
		 * @throws IOException if a fixture path cannot be deleted
		 */
		@Override
		public void close() throws IOException
		{
			try (Stream<Path> paths = Files.walk(root))
			{
				for (Path path : paths.sorted(Comparator.reverseOrder()).toList())
					Files.delete(path);
			}
		}
	}
}
