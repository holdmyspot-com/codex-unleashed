package com.holdmyspot.codexunleashed.tooling.test;

import com.holdmyspot.codexunleashed.tooling.DefaultToolingScope;
import com.holdmyspot.codexunleashed.tooling.QuietBuildCapture;
import com.holdmyspot.codexunleashed.tooling.QuietBuildDump;
import com.holdmyspot.codexunleashed.tooling.QuietBuildProcessAccess;
import java.io.IOException;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import org.testng.annotations.Test;
import tools.jackson.databind.JsonNode;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

/**
 * Exercises production native access and process reporting on the executing supported host.
 */
public final class QuietBuildNativeFactsHostTest
{
	/**
	 * Creates native host tests.
	 */
	public QuietBuildNativeFactsHostTest()
	{
	}

	/**
	 * Writes the current host process into a direct process report.
	 *
	 * @throws IOException if fixture setup, native reporting or cleanup fails
	 */
	@Test
	public void writesActualHostDump() throws IOException
	{
		String contextFile = System.getProperty("quiet.build.test.context");
		Path source = Path.of(contextFile);
		Path temporaryDirectory;
		Path workingDirectory;
		Map<String, String> environment = new HashMap<>();
		try (DefaultToolingScope scope = new DefaultToolingScope())
		{
			JsonNode json = scope.getJsonMapper().readTree(source.toFile());
			workingDirectory = Path.of(json.get("working_directory").textValue());
			temporaryDirectory = Files.createTempDirectory(Path.of(json.get("temporary_directory").textValue()),
				"native-host-test-");
			for (Map.Entry<String, JsonNode> entry : json.get("environment").properties())
				environment.put(entry.getKey(), entry.getValue().textValue());
		}
		environment.put("TMPDIR", temporaryDirectory.toString());
		environment.put("TMP", temporaryDirectory.toString());
		environment.put("TEMP", temporaryDirectory.toString());
		try
		{
			QuietBuildCapture.Context context = new QuietBuildCapture.Context(workingDirectory, temporaryDirectory,
				environment);
			QuietBuildDump.Platform platform = actualPlatform();
			Path procfs = temporaryDirectory.resolve("no-procfs");
			if (platform == QuietBuildDump.Platform.LINUX)
				procfs = Path.of("/proc");
			StringWriter output = new StringWriter();
			try (DefaultToolingScope scope = new DefaultToolingScope())
			{
				QuietBuildDump.create(scope, new QuietBuildProcessAccess(), context, platform, procfs).
					dump(null, output);
			}
			requireThat(output.toString(), "output").contains("pid=" + ProcessHandle.current().pid());
		}
		finally
		{
			try (java.util.stream.Stream<Path> paths = Files.walk(temporaryDirectory))
			{
				for (Path path : paths.sorted((first, second) -> second.compareTo(first)).toList())
					Files.deleteIfExists(path);
			}
		}
	}

	/**
	 * Selects the host platform from its operating-system property.
	 *
	 * @return the matching supported process-report platform
	 */
	private static QuietBuildDump.Platform actualPlatform()
	{
		String os = System.getProperty("os.name").toLowerCase(java.util.Locale.ROOT);
		if (os.equals("linux"))
			return QuietBuildDump.Platform.LINUX;
		if (os.equals("darwin") || os.startsWith("mac os"))
			return QuietBuildDump.Platform.DARWIN;
		if (os.startsWith("windows"))
			return QuietBuildDump.Platform.WINDOWS;
		throw new UnsupportedOperationException("Unsupported host operating system: " + os);
	}
}
