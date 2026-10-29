package com.holdmyspot.codexunleashed.scheduler;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SecureDirectoryStream;
import org.apache.commons.io.FileUtils;
import org.testng.SkipException;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertTrue;
import static org.testng.Assert.expectThrows;

/**
 * Exercises the owned process-memory sampler using isolated cgroup and proc records.
 */
public final class LinuxResidencyTest
{
	/** Creates owned process-memory sampling tests. */
	public LinuxResidencyTest()
	{
	}

	/**
	 * Runs these Linux filesystem contract cases only on a provider supporting retained directory operations.
	 *
	 * @throws IOException if the capability fixture or cleanup fails
	 * @throws SkipException if the provider cannot exercise the supported sampling boundary
	 */
	@BeforeClass
	public void requireSecureDirectories() throws IOException
	{
		Path fixture = Files.createTempDirectory("linux-residency-provider-");
		try
		{
			try (var directory = Files.newDirectoryStream(fixture))
			{
				if ((directory instanceof SecureDirectoryStream<?>) == false)
					throw new SkipException("Linux residency fixtures require secure directory operations");
			}
		}
		finally
		{
			Files.delete(fixture);
		}
	}

	/**
	 * Includes descendants and counts a repeated membership entry only once.
	 *
	 * @throws IOException if fixture setup, sampling or cleanup fails
	 */
	@Test
	public void readsOwnedSubtree() throws IOException
	{
		Path fixture = Files.createTempDirectory("linux-residency-");
		try
		{
			Path root = fixture.resolve("cgroups");
			Path proc = fixture.resolve("proc");
			Path owned = root.resolve("owned");
			writeGroup(owned, "41\n");
			writeGroup(owned.resolve("child"), "42\n42\n");
			writeProcess(proc, 41, "0::/owned\n", "VmRSS: 1 kB\n");
			writeProcess(proc, 42, "0::/owned/child\n", "VmRSS: 12 kB\n");
			assertEquals(LinuxResidency.read(root, proc, owned), new LinuxResidency.Sample(13312, 2));
		}
		finally
		{
			FileUtils.deleteDirectory(fixture.toFile());
		}
	}

	/**
	 * Supplies valid zero, unavailable and invalid memory observations.
	 *
	 * @return status records, expected bytes or null for failure, and required failure context
	 */
	@DataProvider
	public Object[][] memoryObservations()
	{
		return new Object[][] {
			{"VmRSS: 0 kB\n", 0L, null},
			{"Name: worker\n", null, "VmRSS"},
			{"VmRSS: 1 MB\n", null, "kB"},
			{"VmRSS: -1 kB\n", null, "Negative"},
			{"VmRSS: 9223372036854775807 kB\n", null, "overflows"}
		};
	}

	/**
	 * Keeps a genuine zero memory observation distinct from absent or invalid data.
	 *
	 * @param status the process memory records
	 * @param expectedBytes the expected bytes, or null for a rejected observation
	 * @param diagnostic the required context for a rejected observation
	 * @throws IOException if fixture setup, valid sampling or cleanup fails
	 */
	@Test(dataProvider = "memoryObservations")
	public void preservesMemoryObservation(String status, Long expectedBytes, String diagnostic) throws IOException
	{
		Path fixture = Files.createTempDirectory("linux-residency-memory-");
		try
		{
			Path root = fixture.resolve("cgroups");
			Path proc = fixture.resolve("proc");
			Path owned = root.resolve("owned");
			writeGroup(owned, "41\n");
			writeProcess(proc, 41, "0::/owned\n", status);
			if (expectedBytes == null)
			{
				IOException failure = expectThrows(IOException.class, () -> LinuxResidency.read(root, proc, owned));
				assertTrue(failure.getMessage().contains(diagnostic), failure.getMessage());
			}
			else
				assertEquals(LinuxResidency.read(root, proc, owned), new LinuxResidency.Sample(expectedBytes, 1));
		}
		finally
		{
			FileUtils.deleteDirectory(fixture.toFile());
		}
	}

	/**
	 * Supplies raw records that cannot establish exact owned unified membership.
	 *
	 * @return rejected records, including null for an unavailable membership file
	 */
	@DataProvider
	public Object[][] unavailableMembership()
	{
		return new Object[][] {
			{"0::/foreign\n"},
			{"5:cpu:/foreign\n0::/owned\n"},
			{"0::/owned \n"},
			{null}
		};
	}

	/**
	 * Rejects foreign, unsupported or absent membership instead of returning a memory sample.
	 *
	 * @param membership the raw record, or null for an unavailable file
	 * @throws IOException if fixture setup or cleanup fails
	 */
	@Test(dataProvider = "unavailableMembership")
	public void rejectsUnavailableMembership(String membership) throws IOException
	{
		Path fixture = Files.createTempDirectory("linux-residency-membership-");
		try
		{
			Path root = fixture.resolve("cgroups");
			Path proc = fixture.resolve("proc");
			Path owned = root.resolve("owned");
			writeGroup(owned, "41\n");
			writeProcess(proc, 41, "0::/owned\n", "VmRSS: 1 kB\n");
			Path source = proc.resolve("41/cgroup");
			if (membership == null)
				Files.delete(source);
			else
				Files.writeString(source, membership);
			expectThrows(IOException.class, () -> LinuxResidency.read(root, proc, owned));
		}
		finally
		{
			FileUtils.deleteDirectory(fixture.toFile());
		}
	}

	/**
	 * Distinguishes an empty owned tree from missing process data.
	 *
	 * @throws IOException if fixture setup, sampling or cleanup fails
	 */
	@Test
	public void readsEmptySubtree() throws IOException
	{
		Path fixture = Files.createTempDirectory("linux-residency-empty-");
		try
		{
			Path root = fixture.resolve("cgroups");
			Path proc = Files.createDirectory(fixture.resolve("proc"));
			Path owned = root.resolve("owned");
			writeGroup(owned, "");
			assertEquals(LinuxResidency.read(root, proc, owned), new LinuxResidency.Sample(0, 0));
			Files.writeString(owned.resolve("cgroup.procs"), "41\n");
			expectThrows(IOException.class, () -> LinuxResidency.read(root, proc, owned));
		}
		finally
		{
			FileUtils.deleteDirectory(fixture.toFile());
		}
	}

	/**
	 * Writes an owned cgroup membership fixture.
	 *
	 * @param group the group directory
	 * @param members its process-ID records
	 * @throws IOException if setup fails
	 */
	private static void writeGroup(Path group, String members) throws IOException
	{
		Files.createDirectories(group);
		Files.writeString(group.resolve("cgroup.procs"), members);
	}

	/**
	 * Writes the process records consumed through the retained directory.
	 *
	 * @param proc the proc filesystem root
	 * @param pid the positive process ID
	 * @param membership the raw unified membership record
	 * @param status the process status counters
	 * @throws IOException if setup fails
	 */
	private static void writeProcess(Path proc, long pid, String membership, String status) throws IOException
	{
		Path process = proc.resolve(Long.toString(pid));
		Files.createDirectories(process);
		Files.writeString(process.resolve("cgroup"), membership);
		Files.writeString(process.resolve("status"), status);
	}
}
