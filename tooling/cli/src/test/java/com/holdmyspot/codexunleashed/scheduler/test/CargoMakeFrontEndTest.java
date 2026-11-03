package com.holdmyspot.codexunleashed.scheduler.test;

import com.holdmyspot.codexunleashed.scheduler.CargoMakeFrontEnd;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.testng.annotations.Test;

import static com.holdmyspot.codexunleashed.scheduler.test.TestRequirements.requireException;
import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

/**
 * Verifies the make front end that lets GNU Make before 4.4 join a jobserver Cargo names by FIFO path.
 */
public final class CargoMakeFrontEndTest
{
	/** The FIFO path that the make variables name. */
	private static final Path FIFO = Path.of("/run/codex/jobserver/fifo");

	/** Creates front-end tests. */
	public CargoMakeFrontEndTest()
	{
	}

	/**
	 * The script runs the given make with its arguments, opening the FIFO onto the descriptors only when asked.
	 */
	@Test
	public void executesTheGivenMake()
	{
		Path make = Path.of("/usr/bin/make");
		String inherited = CargoMakeFrontEnd.script(make, FIFO, 200, 201, false);
		requireThat(inherited, "inheritedScript").startsWith("#!/bin/sh\n").endsWith("\nexec '/usr/bin/make' \"$@\"\n");
		String opening = CargoMakeFrontEnd.script(make, FIFO, 200, 201, true);
		requireThat(opening, "openingScript").startsWith("#!/bin/bash\n").endsWith(
			"\nexec 200<>'" + FIFO + "' 201<>'" + FIFO + "'\nexec '/usr/bin/make' \"$@\"\n");
	}

	/**
	 * Rejects paths the script cannot carry inside single quotes.
	 */
	@Test
	public void rejectsUnquotablePaths()
	{
		requireException(IllegalArgumentException.class, () ->
			CargoMakeFrontEnd.script(Path.of("/usr/bin/ma'ke"), FIFO, 200, 201, false));
		requireException(IllegalArgumentException.class, () ->
			CargoMakeFrontEnd.script(Path.of("/usr/bin/make"), Path.of("/run/a b"), 200, 201, false));
	}

	/**
	 * Replaces every FIFO auth value in each make variable that is set, leaves unset variables unset and keeps empty
	 * ones empty. The script runs under the real {@code /bin/sh} and hands off to {@code /usr/bin/env}, which prints
	 * the environment the script gave it.
	 * @throws Exception if the script cannot be written, run or removed
	 */
	@Test
	public void rewritesFifoAuthInSetMakeVariables() throws Exception
	{
		TestRequirements.requirePosixFileSystem();
		Path directory = Files.createTempDirectory("make-front-end-");
		try
		{
			Path script = directory.resolve("make");
			Files.writeString(script, CargoMakeFrontEnd.script(Path.of("/usr/bin/env"), FIFO, 200, 201, false));
			Files.setPosixFilePermissions(script, PosixFilePermissions.fromString("rwx------"));
			String auth = "fifo:" + FIFO;
			Map<String, String> environment = new LinkedHashMap<>();
			environment.put("CARGO_MAKEFLAGS", "-j --jobserver-fds=" + auth + " --jobserver-auth=" + auth);
			environment.put("MAKEFLAGS", "");
			ProcessBuilder builder = new ProcessBuilder(script.toString());
			builder.environment().clear();
			builder.environment().putAll(environment);
			builder.redirectErrorStream(true);
			Process process = builder.start();
			String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
			requireThat(process.waitFor(), "exit").withContext(output, "output").isEqualTo(0);
			List<String> lines = output.lines().filter(line -> line.contains("MAKEFLAGS") || line.startsWith("MFLAGS"))
				.sorted().toList();
			requireThat(lines, "makeVariables").isEqualTo(List.of(
				"CARGO_MAKEFLAGS=-j --jobserver-fds=200,201 --jobserver-auth=200,201", "MAKEFLAGS="));
		}
		finally
		{
			try (var paths = Files.walk(directory))
			{
				for (Path path : paths.sorted(Comparator.reverseOrder()).toList())
					Files.delete(path);
			}
		}
	}

	/**
	 * Finds the first executable {@code make} on absolute search-path entries, skipping relative entries.
	 * @throws IOException if fixture files cannot be written or removed
	 */
	@Test
	public void findsTheMakeABuildScriptWouldRun() throws IOException
	{
		TestRequirements.requirePosixFileSystem();
		Path directory = Files.createTempDirectory("make-search-");
		try
		{
			Path empty = Files.createDirectory(directory.resolve("empty"));
			Path first = Files.createDirectory(directory.resolve("first"));
			Path second = Files.createDirectory(directory.resolve("second"));
			Path plain = Files.createFile(first.resolve("make"));
			Path chosen = Files.createFile(second.resolve("make"));
			Files.setPosixFilePermissions(chosen, PosixFilePermissions.fromString("rwx------"));
			requireThat(Files.isExecutable(plain), "nonExecutableCandidate").isFalse();
			String search = "relative:" + empty + ":" + first + ":" + second;
			requireThat(CargoMakeFrontEnd.findMake(search), "found").isEqualTo(chosen.toRealPath());
			requireThat(CargoMakeFrontEnd.findMake(empty.toString()), "none").isNull();
		}
		finally
		{
			try (var paths = Files.walk(directory))
			{
				for (Path path : paths.sorted(Comparator.reverseOrder()).toList())
					Files.delete(path);
			}
		}
	}
}
