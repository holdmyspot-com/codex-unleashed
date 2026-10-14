package com.holdmyspot.codexunleashed.distribution;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertTrue;

public final class MavenWarningPolicyTest
{
	/**
	 * Creates the Maven wrapper policy tests.
	 */
	public MavenWarningPolicyTest()
	{
	}

	/**
	 * Supplies successful, diagnostic, and failing child commands.
	 *
	 * @return shell command bodies, expected statuses, and required output
	 */
	@DataProvider
	public Object[][] diagnosticCases()
	{
		return new Object[][]
		{
			{"echo '[INFO] clean output'", 0, "[INFO] clean output"},
			{"echo '[INFO] warning: quoted source text'", 0, "warning: quoted source text"},
			{"echo '[WARNING] fixture warning' >&2", 1, "[WARNING] fixture warning"},
			{"echo '[WARNING] stdout warning'", 1, "[WARNING] stdout warning"},
			{"echo '[ERROR] successful error'", 1, "[ERROR] successful error"},
			{"echo 'SLF4J(W): No SLF4J providers were found.' >&2", 1, "SLF4J(W):"},
			{"echo 'WARNING: fixture JVM warning' >&2", 1, "WARNING: fixture JVM warning"},
			{"echo 'original failure' >&2; exit 17", 17, "original failure"},
			{"echo '[WARNING] original failure' >&2; exit 17", 17, "[WARNING] original failure"}
		};
	}

	/**
	 * Applies the diagnostic policy at the maintained Maven wrapper boundary.
	 *
	 * @param command the fixture Maven shell body
	 * @param expectedStatus the required wrapper exit status
	 * @param expectedOutput the required forwarded output
	 * @throws IOException if fixture creation or invocation fails
	 * @throws InterruptedException if waiting for the wrapper is interrupted
	 */
	@Test(dataProvider = "diagnosticCases")
	public void enforcesDiagnosticPolicy(String command, int expectedStatus, String expectedOutput)
		throws IOException, InterruptedException
	{
		Path root = Files.createTempDirectory("maven warning policy-");
		try
		{
			Path tooling = root.resolve("tooling");
			Path wrapper = tooling.resolve("mvnw");
			Files.createDirectories(tooling.resolve(".mvn/wrapper"));
			Path maintainedTooling = Path.of(System.getProperty("tooling.launcher")).getParent().getParent();
			Files.copy(maintainedTooling.resolve("mvnw"), wrapper);
			Path guard = Path.of("bootstrap/src/main/java/com/holdmyspot/codexunleashed/bootstrap/MavenWarningGuard.java");
			Files.createDirectories(tooling.resolve(guard).getParent());
			Files.copy(maintainedTooling.resolve(guard), tooling.resolve(guard));
			assertTrue(wrapper.toFile().setExecutable(true), "Cannot make wrapper executable");
			String distribution = "https://example.invalid/apache-maven-fixture-bin.zip";
			Files.writeString(tooling.resolve(".mvn/wrapper/maven-wrapper.properties"),
				"distributionUrl=" + distribution + "\n");
			Path maven = root.resolve(".cat/work/temp/build-caches/maven/home/wrapper/dists/apache-maven-fixture/" +
				Integer.toHexString(distribution.hashCode()) + "/bin/mvn");
			Files.createDirectories(maven.getParent());
			Files.writeString(maven, "#!/bin/sh\n" + command + "\n");
			assertTrue(maven.toFile().setExecutable(true), "Cannot make Maven fixture executable");
			Path log = root.resolve("wrapper.log");
			ProcessBuilder builder = NativeCommands.scriptBuilder(wrapper, "--version");
			builder.redirectErrorStream(true).redirectOutput(log.toFile());
			try (Process process = builder.start())
			{
				try
				{
					assertTrue(process.waitFor(30, TimeUnit.SECONDS), "Maven wrapper timed out");
					String output = Files.readString(log);
					assertTrue(output.contains(expectedOutput), output);
					assertEquals(process.exitValue(), expectedStatus, output);
				}
				finally
				{
					if (process.isAlive())
						process.destroyForcibly().waitFor();
				}
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
}
