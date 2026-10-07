package com.holdmyspot.codexunleashed.distribution;

import java.io.File;
import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.Set;

/**
 * Makes Java process fixtures discoverable by maintained Bash workflow commands.
 */
final class JavaCommandFixtures
{
	private static final Set<PosixFilePermission> EXECUTABLE_PERMISSIONS =
		Set.copyOf(PosixFilePermissions.fromString("rwx------"));

	/**
	 * Prevents construction.
	 */
	private JavaCommandFixtures()
	{
	}

	/**
	 * Writes an adapter that invokes the fixture through the current JDK with literal arguments.
	 * All modeled command behavior resides in the Java fixture.
	 *
	 * @param executable the new adapter path
	 * @param fixture the Java fixture entry point
	 * @throws IOException if the adapter cannot be created or made executable
	 * @throws URISyntaxException if the fixture class location is invalid
	 */
	static void writeLauncher(Path executable, Class<?> fixture) throws IOException, URISyntaxException
	{
		if (File.separatorChar == '\\')
		{
			writeNativeLauncher(executable, fixture);
			return;
		}
		Path java = Path.of(System.getProperty("java.home"), "bin", "java");
		if (!Files.isRegularFile(java))
			java = java.resolveSibling("java.exe");
		Path classes = Path.of(fixture.getProtectionDomain().getCodeSource().getLocation().toURI());
		String script = "#!/bin/sh\nexec " + quote(java.toString().replace('\\', '/')) + " " +
			quote("-Djava.io.tmpdir=" + System.getProperty("java.io.tmpdir")) + " -cp " +
			quote(classes.toString().replace('\\', '/')) + " " + quote(fixture.getName()) + " \"$@\"\n";
		Files.writeString(executable, script);
		if (Files.getFileAttributeView(executable, PosixFileAttributeView.class) != null)
			Files.setPosixFilePermissions(executable, EXECUTABLE_PERMISSIONS);
	}

	/**
	 * Writes a process adapter using the platform's executable format.
	 *
	 * @param executable the new adapter path without a Windows suffix
	 * @param fixture the Java fixture entry point
	 * @return the path passed directly to ProcessBuilder
	 * @throws IOException if the adapter cannot be created or made executable
	 * @throws URISyntaxException if the fixture class location is invalid
	 */
	static Path writeNativeLauncher(Path executable, Class<?> fixture) throws IOException, URISyntaxException
	{
		if (File.separatorChar != '\\')
		{
			writeLauncher(executable, fixture);
			return executable;
		}
		Path java = Path.of(System.getProperty("java.home"), "bin", "java.exe");
		Path classes = Path.of(fixture.getProtectionDomain().getCodeSource().getLocation().toURI());
		Path binary = executable.resolveSibling(executable.getFileName() + ".exe");
		Path configuration = executable.resolveSibling(executable.getFileName() + ".fixture");
		Files.writeString(configuration, String.join("\0", List.of(java.toString(),
			"-Djava.io.tmpdir=" + System.getProperty("java.io.tmpdir"), "-Dstdout.encoding=UTF-8",
			"-Dstderr.encoding=UTF-8", "-cp", classes.toString(), fixture.getName())));
		compileWindowsAdapter(binary);
		return binary;
	}

	/**
	 * Compiles a native adapter that forwards literal arguments and status to the configured Java fixture.
	 *
	 * @param binary the caller-owned native executable
	 * @throws IOException if compilation or cleanup fails
	 */
	private static void compileWindowsAdapter(Path binary) throws IOException
	{
		try (FixtureFile source = new FixtureFile(Files.createTempFile(binary.getParent(), "native-fixture-", ".rs"));
			FixtureFile log = new FixtureFile(Files.createTempFile(binary.getParent(), "native-fixture-", ".log")))
		{
			Files.writeString(source.path(), """
				use std::{env, fs, process::{Command, exit}};
				fn main() {
				    let executable = env::current_exe().expect("fixture executable path");
				    let configuration = fs::read_to_string(executable.with_extension("fixture"))
				        .expect("fixture command configuration");
				    let mut arguments = configuration.split('\\0');
				    let program = arguments.next().expect("fixture Java executable");
				    let result = Command::new(program).args(arguments).args(env::args_os().skip(1))
				        .status().unwrap_or_else(|failure| {
				            eprintln!("Cannot start Java fixture: {failure}");
				            exit(126);
				        });
				    exit(result.code().unwrap_or(1));
				}
				""");
			ProcessBuilder builder = NativeCommands.createBuilder("rustc", "--crate-name", "codex_fixture",
				source.path().toString(), "-o", binary.toString());
			builder.redirectErrorStream(true).redirectOutput(log.path().toFile());
			try (Process process = builder.start())
			{
				if (process.waitFor() != 0)
					throw new IOException("Native fixture compilation failed: " + Files.readString(log.path()));
			}
		}
		catch (InterruptedException failure)
		{
			Thread.currentThread().interrupt();
			throw new IOException("Interrupted while compiling the native fixture", failure);
		}
	}

	/**
	 * Owns a compiler input or capture while preserving the primary compilation failure.
	 *
	 * @param path the temporary file
	 */
	private record FixtureFile(Path path) implements AutoCloseable
	{
		/**
		 * Removes the temporary file after the compiler finishes.
		 *
		 * @throws IOException if cleanup fails
		 */
		@Override
		public void close() throws IOException
		{
			Files.delete(path);
		}
	}

	/**
	 * Quotes a literal value for the shell adapter's argument boundary.
	 *
	 * @param value the literal argument
	 * @return the single-quoted shell representation
	 */
	private static String quote(String value)
	{
		return "'" + value.replace("'", "'\"'\"'") + "'";
	}
}
