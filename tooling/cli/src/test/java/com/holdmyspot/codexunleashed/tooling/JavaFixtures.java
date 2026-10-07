package com.holdmyspot.codexunleashed.tooling;

import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Launches portable Java process fixtures through the current test JDK.
 */
final class JavaFixtures
{
	/**
	 * Prevents construction.
	 */
	private JavaFixtures()
	{
	}

	/**
	 * Constructs a child command with literal argument boundaries and explicit temporary storage.
	 *
	 * @param fixture the class with the fixture entry point
	 * @param arguments the fixture's arguments
	 * @return the executable and its direct arguments
	 * @throws URISyntaxException if the fixture class location is invalid
	 */
	static List<String> command(Class<?> fixture, String... arguments) throws URISyntaxException
	{
		Path java = Path.of(System.getProperty("java.home"), "bin", "java");
		if (!Files.isRegularFile(java))
			java = java.resolveSibling("java.exe");
		Path classes = Path.of(fixture.getProtectionDomain().getCodeSource().getLocation().toURI());
		List<String> command = new ArrayList<>(List.of(java.toString(),
			"-Djava.io.tmpdir=" + System.getProperty("java.io.tmpdir"), "-cp", classes.toString(), fixture.getName()));
		command.addAll(Arrays.asList(arguments));
		return List.copyOf(command);
	}
}
