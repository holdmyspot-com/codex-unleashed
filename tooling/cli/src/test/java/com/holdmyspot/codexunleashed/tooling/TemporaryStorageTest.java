package com.holdmyspot.codexunleashed.tooling;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import org.testng.annotations.Test;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertTrue;

/**
 * Verifies actual default temporary allocation beneath the maintained wrapper's project cache.
 */
public final class TemporaryStorageTest
{
	/**
	 * Creates the storage tests.
	 */
	public TemporaryStorageTest()
	{
	}

	/**
	 * Checks JVM startup arguments when available and verifies the default allocation's real parent directory.
	 *
	 * @throws IOException if the allocation cannot be inspected or removed
	 */
	@Test
	public void allocatesUnderProjectCache() throws IOException
	{
		Path configured = Path.of(System.getProperty("java.io.tmpdir")).toAbsolutePath().normalize();
		Path cache = Path.of(System.getProperty("tooling.build.root")).toAbsolutePath().normalize().getParent();
		assertTrue(configured.startsWith(cache), "The wrapper must supply a project cache temporary directory");
		String option = "-Djava.io.tmpdir=" + configured;
		ProcessHandle.current().info().arguments().ifPresent(arguments ->
			assertTrue(Arrays.asList(arguments).contains(option), "The temporary directory must be set at JVM startup"));
		Path directory = Files.createTempDirectory("allocation-check-");
		try
		{
			assertEquals(directory.toRealPath().getParent(), configured.toRealPath());
		}
		finally
		{
			Files.delete(directory);
		}
	}
}
