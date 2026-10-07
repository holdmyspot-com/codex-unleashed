package com.holdmyspot.codexunleashed.distribution;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
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
	 * Writes a shell adapter that invokes the fixture through the current JDK with literal arguments.
	 * All modeled command behavior resides in the Java fixture.
	 *
	 * @param executable the new adapter path
	 * @param fixture the Java fixture entry point
	 * @throws IOException if the adapter cannot be created or made executable
	 * @throws URISyntaxException if the fixture class location is invalid
	 */
	static void writeLauncher(Path executable, Class<?> fixture) throws IOException, URISyntaxException
	{
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
	 * Writes a process adapter using the platform's executable script format.
	 *
	 * @param executable the new adapter path without a Windows suffix
	 * @param fixture the Java fixture entry point
	 * @return the path passed directly to ProcessBuilder
	 * @throws IOException if the adapter cannot be created or made executable
	 * @throws URISyntaxException if the fixture class location is invalid
	 */
	static Path writeNativeLauncher(Path executable, Class<?> fixture) throws IOException, URISyntaxException
	{
		if (Files.getFileAttributeView(executable, PosixFileAttributeView.class) != null)
		{
			writeLauncher(executable, fixture);
			return executable;
		}
		Path java = Path.of(System.getProperty("java.home"), "bin", "java.exe");
		Path classes = Path.of(fixture.getProtectionDomain().getCodeSource().getLocation().toURI());
		Path script = executable.resolveSibling(executable.getFileName() + ".cmd");
		Files.writeString(script, "@echo off\r\nsetlocal DisableDelayedExpansion\r\n" +
			quoteBatch(java.toString()) + " " + quoteBatch("-Djava.io.tmpdir=" + System.getProperty("java.io.tmpdir")) +
			" -cp " + quoteBatch(classes.toString()) + " " + quoteBatch(fixture.getName()) + " %*\r\n");
		return script;
	}

	/**
	 * Quotes a fixture-controlled executable or class path for a Windows batch adapter.
	 *
	 * @param value the literal path or class name
	 * @return the double-quoted batch representation with literal percent signs
	 */
	private static String quoteBatch(String value)
	{
		return "\"" + value.replace("%", "%%") + "\"";
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
