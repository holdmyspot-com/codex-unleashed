package com.holdmyspot.codexunleashed.tooling;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.testng.annotations.Test;
import static org.testng.Assert.assertEquals;

/** Executes the package-version query through the named-module CLI with explicit build environments. */
public final class PackageVersionCommandTest
{
	/** Creates query tests. */
	public PackageVersionCommandTest()
	{
	}

	/**
	 * Retains numeric, absent and explicitly empty build suffixes and refuses invalid arity before reading inputs.
	 *
	 * @throws IOException if fixture, process or cleanup operations fail
	 */
	@Test
	public void queriesExactPackageVersion() throws IOException
	{
		Path root = Files.createTempDirectory("package-version-query-");
		try
		{
			Path checkout = Files.createDirectories(root.resolve("checkout with spaces/codex-rs")).getParent();
			Files.writeString(checkout.resolve("codex-rs/Cargo.toml"),
				"[workspace.package]\nversion = \"0.160.0\"\n");
			for (String build : List.of("41", "absent", ""))
			{
				Map<String, String> environment = new HashMap<>(System.getenv());
				environment.remove("CODEX_UNLEASHED_BUILD_NUMBER");
				String suffix = "dev";
				if (!build.equals("absent"))
				{
					environment.put("CODEX_UNLEASHED_BUILD_NUMBER", build);
					suffix = build;
				}
				SystemCommands.Result result = cli(root, environment, List.of(checkout.toString()));
				assertEquals(result.status(), 0, result.stdout() + result.stderr());
				assertEquals(result.stdout(), "0.160.0+" + suffix + System.lineSeparator());
				assertEquals(result.stderr(), "");
			}
			assertEquals(cli(root, System.getenv(), List.of()).status(), 2);
			assertEquals(cli(root, System.getenv(), List.of(root.resolve("missing").toString(), "extra")).status(), 2);
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
	 * Runs the query with a checkout independent from the child's working directory.
	 *
	 * @param root process and temporary directory
	 * @param environment complete environment overrides
	 * @param arguments query arguments
	 * @return completed child outcome
	 * @throws IOException if child handling fails
	 */
	private static SystemCommands.Result cli(Path root, Map<String, String> environment, List<String> arguments)
		throws IOException
	{
		var command = new java.util.ArrayList<>(List.of(Path.of(System.getProperty("java.home"), "bin/java").toString(),
			"-Djava.io.tmpdir=" + root, "--module-path", System.getProperty("jdk.module.path"), "--module",
			"com.holdmyspot.codexunleashed.tooling/com.holdmyspot.codexunleashed.tooling.Main", "get-codex-package-version"));
		command.addAll(arguments);
		return SystemCommands.capture(command, root, root, environment);
	}
}
