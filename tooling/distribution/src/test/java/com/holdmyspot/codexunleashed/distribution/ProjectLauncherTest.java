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
