package com.holdmyspot.codexunleashed.scheduler;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.apache.commons.io.FileUtils;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertTrue;
import static org.testng.Assert.expectThrows;

/**
 * Exercises Linux host-counter interpretation using isolated files representing the proc filesystem.
 */
public final class LinuxMemoryTest
{
	/**
	 * Creates host-counter behavior tests.
	 */
	public LinuxMemoryTest()
	{
	}

	/**
	 * Uses available physical memory rather than free memory and retains exact units and cumulative activity.
	 *
	 * @throws IOException if fixture setup, sampling or cleanup fails
	 */
	@Test
	public void readsHostCounters() throws IOException
	{
		Path procRoot = Files.createTempDirectory("linux-memory-");
		try
		{
			writeCounters(procRoot, "MemTotal: 8192 kB\nMemFree: 1 kB\nMemAvailable: 4096 kB\n" +
				"SwapTotal: 2048 kB\nSwapFree: 512 kB\n", "pswpout 123\n",
				"some avg10=0.00 avg60=0.00 avg300=0.00 total=456\n");
			assertEquals(LinuxMemory.read(procRoot),
				new LinuxMemory.Sample(8388608, 4194304, 2097152, 1572864, 123, 456));
		}
		finally
		{
			FileUtils.deleteDirectory(procRoot.toFile());
		}
	}

	/**
	 * Preserves successful zero availability, disabled swap and zero activity without treating them as missing.
	 *
	 * @throws IOException if fixture setup, sampling or cleanup fails
	 */
	@Test
	public void preservesValidZeroCounters() throws IOException
	{
		Path procRoot = Files.createTempDirectory("linux-memory-zero-");
		try
		{
			writeCounters(procRoot, "MemTotal: 1 kB\nMemAvailable: 0 kB\nSwapTotal: 0 kB\nSwapFree: 0 kB\n",
				"pswpout 0\n", "some avg10=0.00 avg60=0.00 avg300=0.00 total=0\n");
			assertEquals(LinuxMemory.read(procRoot), new LinuxMemory.Sample(1024, 0, 0, 0, 0, 0));
		}
		finally
		{
			FileUtils.deleteDirectory(procRoot.toFile());
		}
	}

	/**
	 * Supplies missing, malformed, overflowing and inconsistent kernel observations and their diagnostic context.
	 *
	 * @return counter filename, replacement contents or null for an absent file, and the required diagnostic context
	 */
	@DataProvider
	public Object[][] invalidObservations()
	{
		String memory = "MemTotal: 4096 kB\nMemAvailable: 2048 kB\nSwapTotal: 1024 kB\nSwapFree: 1024 kB\n";
		return new Object[][] {
			{Path.of("meminfo"), memory.replace("MemAvailable: 2048 kB\n", ""), "MemAvailable"},
			{Path.of("vmstat"), "pgpgout 0\n", "pswpout"},
			{Path.of("pressure/memory"), "some avg10=0.00\n", "some.total"},
			{Path.of("meminfo"), memory.replace("2048 kB", "2048 MB"), "MemAvailable"},
			{Path.of("vmstat"), "pswpout -1\n", "pswpout"},
			{Path.of("meminfo"), memory.replace("4096 kB", "9223372036854775807 kB"), "MemTotal"},
			{Path.of("meminfo"), memory.replace("2048 kB", "8192 kB"), "availableBytes"},
			{Path.of("meminfo"), memory.replace("SwapFree: 1024", "SwapFree: 2048"), "SwapFree"},
			{Path.of("pressure/memory"), "some avg10=0.00 total=unknown\n", "some.total"},
			{Path.of("meminfo"), memory.replace("4096 kB", "0 kB"), "totalBytes"},
			{Path.of("pressure/memory"), null, "pressure"}
		};
	}

	/**
	 * Rejects unavailable or invalid observations rather than returning a plausible zero sample.
	 *
	 * @param counterFile the relative kernel counter filename
	 * @param contents replacement contents, or null to remove the file
	 * @param diagnostic the counter or source that the exception must identify
	 * @throws IOException if fixture setup or cleanup fails
	 */
	@Test(dataProvider = "invalidObservations")
	public void rejectsInvalidObservations(Path counterFile, String contents, String diagnostic) throws IOException
	{
		Path procRoot = Files.createTempDirectory("linux-memory-invalid-");
		try
		{
			writeCounters(procRoot,
				"MemTotal: 4096 kB\nMemAvailable: 2048 kB\nSwapTotal: 1024 kB\nSwapFree: 1024 kB\n",
				"pswpout 0\n", "some avg10=0.00 total=0\n");
			Path source = procRoot.resolve(counterFile);
			if (contents == null)
				Files.delete(source);
			else
				Files.writeString(source, contents);

			IOException failure = expectThrows(IOException.class, () -> LinuxMemory.read(procRoot));
			assertTrue(failure.getMessage().contains(diagnostic), failure.getMessage());
		}
		finally
		{
			FileUtils.deleteDirectory(procRoot.toFile());
		}
	}

	/**
	 * Requires a proc filesystem root supplied by the runtime boundary.
	 */
	@Test
	public void rejectsNullRoot()
	{
		expectThrows(NullPointerException.class, () -> LinuxMemory.read(null));
	}

	/**
	 * Creates the kernel records consumed by the public sampling operation.
	 *
	 * @param procRoot the test-owned directory representing the proc filesystem
	 * @param memory meminfo records
	 * @param activity vmstat records
	 * @param pressure memory PSI records
	 * @throws IOException if a fixture cannot be written
	 */
	private static void writeCounters(Path procRoot, String memory, String activity, String pressure) throws IOException
	{
		Files.createDirectories(procRoot.resolve("pressure"));
		Files.writeString(procRoot.resolve("meminfo"), memory);
		Files.writeString(procRoot.resolve("vmstat"), activity);
		Files.writeString(procRoot.resolve("pressure/memory"), pressure);
	}
}
