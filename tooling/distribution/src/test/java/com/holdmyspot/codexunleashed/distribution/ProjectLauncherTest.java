package com.holdmyspot.codexunleashed.distribution;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import org.testng.annotations.Test;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertTrue;

public final class ProjectLauncherTest
{
	/**
	 * Creates the project launcher tests.
	 */
	public ProjectLauncherTest()
	{
	}

	/**
	 * Passes arguments with spaces to the cached runtime from another working directory.
	 *
	 * @throws IOException if fixture access fails
	 * @throws InterruptedException if the launcher wait is interrupted
	 */
	@Test
	public void invokesCachedRuntime() throws IOException, InterruptedException
	{
		try (Fixture fixture = new Fixture())
		{
			Path runtime = fixture.root.resolve(".cat/work/temp/build-caches/maven/target/tooling-distribution/runtime/bin");
			Files.createDirectories(runtime);
			fixture.executable(runtime.resolve("codex-tooling"), "#!/bin/sh\nprintf '%s\\n' \"$@\"\n");
			Files.createFile(runtime.getParent().resolve(".complete"));
			fixture.assertRun(0, "command\nargument with spaces\n");
		}
	}

	/**
	 * Propagates a failed Maven bootstrap instead of invoking an absent runtime.
	 *
	 * @throws IOException if fixture access fails
	 * @throws InterruptedException if the launcher wait is interrupted
	 */
	@Test
	public void propagatesBootstrapFailure() throws IOException, InterruptedException
	{
		try (Fixture fixture = new Fixture())
		{
			fixture.executable(fixture.root.resolve("tooling/mvnw"), "#!/bin/sh\necho 'bootstrap failed' >&2\nexit 17\n");
			fixture.assertRun(17, "bootstrap failed");
		}
	}

	/**
	 * Checks that a successful bootstrap actually produced its runtime.
	 *
	 * @throws IOException if fixture access fails
	 * @throws InterruptedException if the launcher wait is interrupted
	 */
	@Test
	public void rejectsMissingBootstrapOutput() throws IOException, InterruptedException
	{
		try (Fixture fixture = new Fixture())
		{
			fixture.executable(fixture.root.resolve("tooling/mvnw"), "#!/bin/sh\nexit 0\n");
			fixture.assertRun(1, "Maven did not produce the tooling launcher");
		}
	}

	/**
	 * Retries a failed bootstrap without executing the incomplete runtime it left behind.
	 *
	 * @throws IOException if fixture access fails
	 * @throws InterruptedException if the launcher wait is interrupted
	 */
	@Test
	public void retriesIncompleteBootstrap() throws IOException, InterruptedException
	{
		try (Fixture fixture = new Fixture())
		{
			String producer = """
				#!/bin/sh
				native_directory="${0%/*}/../.cat/work/temp/build-caches/maven/target/tooling-distribution/runtime/bin"
				mkdir -p "$native_directory"
				printf '#!/bin/sh\\nexit 0\\n' > "$native_directory/codex-tooling"
				chmod +x "$native_directory/codex-tooling"
				echo 'bootstrap failed' >&2
				exit 17
				""";
			fixture.executable(fixture.root.resolve("tooling/mvnw"), producer);
			fixture.assertRun(17, "bootstrap failed");
			fixture.assertRun(17, "bootstrap failed");
		}
	}

	/**
	 * Keeps command input available when bootstrapping the runtime.
	 *
	 * @throws IOException if fixture access fails
	 * @throws InterruptedException if the launcher wait is interrupted
	 */
	@Test
	public void preservesInputDuringBootstrap() throws IOException, InterruptedException
	{
		try (Fixture fixture = new Fixture())
		{
			String producer = """
				#!/bin/sh
				IFS= read -r consumed || :
				native_directory="${0%/*}/../.cat/work/temp/build-caches/maven/target/tooling-distribution/runtime/bin"
				mkdir -p "$native_directory"
				printf '#!/bin/sh\\nIFS= read -r input\\nprintf "%%s\\\\n" "$input"\\n' > "$native_directory/codex-tooling"
				chmod +x "$native_directory/codex-tooling"
				touch "$native_directory/../.complete"
				""";
			fixture.executable(fixture.root.resolve("tooling/mvnw"), producer);
			fixture.assertRun(0, "stdin fixture\n", "stdin fixture\n".getBytes(StandardCharsets.UTF_8));
		}
	}

	/**
	 * Probes a restored setup runtime without invoking Maven again.
	 *
	 * @throws IOException if fixture access fails
	 * @throws InterruptedException if the setup wait is interrupted
	 */
	@Test
	public void probesVerifiedSetupCache() throws IOException, InterruptedException
	{
		try (Fixture fixture = new Fixture())
		{
			fixture.assertSetupRun("true", 0, "1", "launcher=");
		}
	}

	/**
	 * Requires Maven verification on a setup cache miss and propagates its failure.
	 *
	 * @throws IOException if fixture access fails
	 * @throws InterruptedException if the setup wait is interrupted
	 */
	@Test
	public void verifiesSetupCacheMiss() throws IOException, InterruptedException
	{
		try (Fixture fixture = new Fixture())
		{
			fixture.assertSetupRun("false", 17, "1", "");
		}
	}

	/**
	 * Rejects a restored runtime whose launcher probe returns an invalid result.
	 *
	 * @throws IOException if fixture access fails
	 * @throws InterruptedException if the setup wait is interrupted
	 */
	@Test
	public void rejectsInvalidSetupCache() throws IOException, InterruptedException
	{
		try (Fixture fixture = new Fixture())
		{
			fixture.assertSetupRun("true", 1, "2", "");
		}
	}

	/**
	 * Owns a project tree and controlled bootstrap/runtime executables.
	 */
	private static final class Fixture implements AutoCloseable
	{
		private final Path root;

		/**
		 * Allocates the fixture's project directory.
		 *
		 * @throws IOException if directory creation fails
		 */
		private Fixture() throws IOException
		{
			root = Files.createTempDirectory("project launcher-");
		}

		/**
		 * Creates an executable fixture script.
		 *
		 * @param path the script path
		 * @param content the shell script content
		 * @throws IOException if writing the script fails
		 */
		private void executable(Path path, String content) throws IOException
		{
			Files.createDirectories(path.getParent());
			Files.writeString(path, content);
			assertTrue(path.toFile().setExecutable(true), "Cannot make fixture script executable");
		}

		/**
		 * Exercises the setup shell's cache decision, failure status and output publication.
		 *
		 * @param hit the restored runtime cache result
		 * @param status the expected setup exit status
		 * @param probe the result returned by the controlled launcher
		 * @param expectedOutput the required published output
		 * @throws IOException if fixture or shell access fails
		 * @throws InterruptedException if the setup wait is interrupted
		 */
		private void assertSetupRun(String hit, int status, String probe, String expectedOutput)
			throws IOException, InterruptedException
		{
			Path project = Path.of(System.getProperty("tooling.launcher")).getParent().getParent().getParent();
			Path action = Files.createDirectories(root.resolve(".github/actions/setup-tooling"));
			Path script = action.resolve("setup.sh");
			Files.copy(project.resolve(".github/actions/setup-tooling/setup.sh"), script);
			executable(root.resolve("tooling/mvnw"), "#!/bin/sh\nexit 17\n");
			executable(root.resolve("tooling/bin/codex-tooling"), "#!/bin/sh\nprintf '%s\\n' '" + probe + "'\n");
			Path output = Files.createFile(root.resolve("github-output"));
			Path environment = Files.createFile(root.resolve("github-env"));
			Path log = root.resolve("setup.log");
			ProcessBuilder builder = NativeCommands.scriptBuilder(script);
			builder.directory(root.toFile()).redirectErrorStream(true).redirectOutput(log.toFile());
			builder.environment().put("GITHUB_ACTION_PATH", action.toString().replace('\\', '/'));
			builder.environment().put("GITHUB_OUTPUT", output.toString().replace('\\', '/'));
			builder.environment().put("GITHUB_ENV", environment.toString().replace('\\', '/'));
			builder.environment().put("TOOLING_RUNTIME_CACHE_HIT", hit);
			try (Process process = builder.start())
			{
				try
				{
					assertTrue(process.waitFor(30, TimeUnit.SECONDS), "Tooling setup timed out");
					assertEquals(process.exitValue(), status, Files.readString(log));
					String published = Files.readString(output);
					if (expectedOutput.isEmpty())
						assertEquals(published, "", Files.readString(log));
					else
						assertTrue(published.contains(expectedOutput), published);
				}
				finally
				{
					if (process.isAlive())
						process.destroyForcibly().waitFor();
				}
			}
		}

		/**
		 * Runs the maintained project launcher against this fixture.
		 *
		 * @param status the expected exit status
		 * @param expected the required output
		 * @throws IOException if starting the launcher or reading its log fails
		 * @throws InterruptedException if the launcher wait is interrupted
		 */
		private void assertRun(int status, String expected) throws IOException, InterruptedException
		{
			assertRun(status, expected, new byte[0]);
		}

		/**
		 * Runs the maintained launcher with command input.
		 *
		 * @param status the expected exit status
		 * @param expected the required output
		 * @param input the standard input bytes
		 * @throws IOException if starting the launcher or reading its log fails
		 * @throws InterruptedException if the launcher wait is interrupted
		 */
		private void assertRun(int status, String expected, byte[] input) throws IOException, InterruptedException
		{
			Path launcher = root.resolve("tooling/bin/codex-tooling");
			Files.createDirectories(launcher.getParent());
			Files.copy(Path.of(System.getProperty("tooling.launcher")), launcher, StandardCopyOption.REPLACE_EXISTING);
			Path log = root.resolve("launcher.log");
			ProcessBuilder builder = NativeCommands.scriptBuilder(launcher, "command", "argument with spaces");
			builder.directory(root.toFile()).redirectErrorStream(true).redirectOutput(log.toFile());
			try (Process process = builder.start())
			{
				try
				{
					process.getOutputStream().write(input);
					process.getOutputStream().close();
					assertTrue(process.waitFor(30, TimeUnit.SECONDS), "Project launcher timed out");
					assertEquals(process.exitValue(), status, Files.readString(log));
					assertTrue(Files.readString(log).contains(expected), Files.readString(log));
				}
				finally
				{
					if (process.isAlive())
						process.destroyForcibly().waitFor();
				}
			}
		}

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
