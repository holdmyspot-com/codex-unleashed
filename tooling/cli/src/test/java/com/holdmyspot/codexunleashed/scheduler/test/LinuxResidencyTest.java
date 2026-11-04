package com.holdmyspot.codexunleashed.scheduler.test;

import com.holdmyspot.codexunleashed.scheduler.LinuxResidency;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SecureDirectoryStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.apache.commons.io.FileUtils;
import org.testng.SkipException;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

import static com.holdmyspot.codexunleashed.scheduler.test.TestRequirements.requireException;
import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

/**
 * Exercises the owned process-memory sampler using isolated cgroup and proc records.
 */
public final class LinuxResidencyTest
{
	/**
	 * Creates owned process-memory sampling tests.
	 */
	public LinuxResidencyTest()
	{
	}

	/**
	 * Checks retained directory operations using the current test's owned fixture.
	 * @param fixture existing directory whose provider must support retained directory operations
	 * @throws IOException if opening or closing the fixture directory fails
	 * @throws SkipException if the provider cannot exercise the supported sampling boundary
	 */
	private static void requireSecureDirectory(Path fixture) throws IOException
	{
		try (var directory = Files.newDirectoryStream(fixture))
		{
			if ((directory instanceof SecureDirectoryStream<?>) == false)
				throw new SkipException("Linux residency fixtures require secure directory operations");
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
			requireSecureDirectory(fixture);
			Path root = fixture.resolve("cgroups");
			Path proc = fixture.resolve("proc");
			Path owned = root.resolve("owned");
			writeGroup(owned, "41\n");
			writeGroup(owned.resolve("child"), "42\n42\n");
			writeProcess(proc, 41, "0::/owned\n", "VmRSS: 1 kB\n");
			writeProcess(proc, 42, "0::/owned/child\n", "VmRSS: 12 kB\n");
			requireThat(LinuxResidency.read(root, proc, owned), "LinuxResidency")
				.isEqualTo(new LinuxResidency.Sample(13312, 2));
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
			requireSecureDirectory(fixture);
			Path root = fixture.resolve("cgroups");
			Path proc = fixture.resolve("proc");
			Path owned = root.resolve("owned");
			writeGroup(owned, "41\n");
			writeProcess(proc, 41, "0::/owned\n", status);
			if (expectedBytes == null)
			{
				IOException failure = requireException(IOException.class, () -> LinuxResidency.read(root, proc, owned));
				requireThat(failure.getMessage().contains(diagnostic), "failure.getMessage().contains(diagnostic)")
					.withContext(failure.getMessage(), "diagnostic")
					.isTrue();
			}
			else
				requireThat(LinuxResidency.read(root, proc, owned), "LinuxResidency")
					.isEqualTo(new LinuxResidency.Sample(expectedBytes, 1));
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
			requireSecureDirectory(fixture);
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
			var failure = requireException(IOException.class, () -> LinuxResidency.read(root, proc, owned));
			requireThat(failure instanceof LinuxResidency.MembershipChanged, "unavailableDataIsNotVerifiedChurn").isFalse();
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
			requireSecureDirectory(fixture);
			Path root = fixture.resolve("cgroups");
			Path proc = Files.createDirectory(fixture.resolve("proc"));
			Path owned = root.resolve("owned");
			writeGroup(owned, "");
			requireThat(LinuxResidency.read(root, proc, owned), "LinuxResidency").isEqualTo(new LinuxResidency.Sample(0, 0));
			Files.writeString(owned.resolve("cgroup.procs"), "41\n");
			requireException(IOException.class, () -> LinuxResidency.read(root, proc, owned));
		}
		finally
		{
			FileUtils.deleteDirectory(fixture.toFile());
		}
	}

	/**
	 * Supplies process disappearance, unavailable RSS during exit and churn after a successful process read.
	 * @return whether to remove the enumerated process and whether to remove its RSS counter before releasing the read
	 */
	@DataProvider
	public Object[][] membershipChanges()
	{
		return new Object[][] {{true, false}, {false, true}, {false, false}};
	}

	/**
	 * Rejects a complete sample after independently observing membership change, with no replacement zero reading.
	 * A FIFO holds the first membership read until the publisher replaces its path with an empty regular record.
	 * This orders the two complete observations without timing sleeps or replacing the sampler's filesystem boundary.
	 * @param removeProcess whether the enumerated process also disappears before its RSS read
	 * @param removeCounter whether the retained process status loses its RSS counter before sampling
	 * @throws Exception if fixture creation, sampling, publisher completion or cleanup fails
	 */
	@Test(dataProvider = "membershipChanges")
	public void rejectsVerifiedMembershipChange(boolean removeProcess, boolean removeCounter) throws Exception
	{
		Path fixture = Files.createTempDirectory("linux-residency-change-");
		try
		{
			requireSecureDirectory(fixture);
			Path root = fixture.resolve("cgroups");
			Path proc = fixture.resolve("proc");
			Path owned = root.resolve("owned");
			Files.createDirectories(owned);
			writeProcess(proc, 41, "0::/owned\n", "VmRSS: 1 kB\n");
			Path members = owned.resolve("cgroup.procs");
			Process creator = new ProcessBuilder("mkfifo", members.toString()).inheritIO().start();
			requireThat(creator.waitFor(), "fifoCreationExit").isEqualTo(0);
			try (var executor = Executors.newVirtualThreadPerTaskExecutor())
			{
				var publisher = executor.submit(() ->
				{
					try (var output = Files.newOutputStream(members))
					{
						output.write("41\n".getBytes(StandardCharsets.UTF_8));
						Files.move(members, owned.resolve("retained-first-membership"));
						Files.writeString(members, "");
						if (removeProcess) FileUtils.deleteDirectory(proc.resolve("41").toFile());
						if (removeCounter) Files.writeString(proc.resolve("41/status"), "Name: exiting\n");
					}
					return null;
				});
				var failure = requireException(IOException.class, () -> LinuxResidency.read(root, proc, owned));
				publisher.get(30, TimeUnit.SECONDS);
				requireThat(failure instanceof LinuxResidency.MembershipChanged, "verifiedChurnRejectsWholeSample").isTrue();
				requireThat(LinuxResidency.read(root, proc, owned), "freshEmptySample")
					.isEqualTo(new LinuxResidency.Sample(0, 0));
			}
		}
		finally
		{
			Files.deleteIfExists(fixture.resolve("cgroups/owned/retained-first-membership"));
			FileUtils.deleteDirectory(fixture.toFile());
		}
	}

	/**
	 * Supplies valid surviving threads and rejected alternatives for an exited process leader.
	 * @return fixture variation and whether an actual owned thread supplies process RSS
	 */
	@DataProvider
	public Object[][] exitedLeaders()
	{
		return new Object[][] {{"live", true}, {"zero", true}, {"runningLeader", false}, {"wrongLeader", false},
			{"wrongThread", false}, {"foreignMembership", false}, {"missingThreads", false},
			{"taskSymlink", false}, {"threadSymlink", false}, {"invalidCounter", false}, {"onlyExited", false},
			{"leaderInvalidCounter", false}, {"duplicateThreadGroup", false}, {"missingCounter", false}};
	}

	/**
	 * Reads a surviving thread's real shared-process counter once, without treating an exited leader as zero.
	 * @param variation independently invalidated identity, ownership, directory or memory observation
	 * @param succeeds whether the complete bound thread observation remains valid
	 * @throws IOException if fixture setup, successful sampling or cleanup fails
	 */
	@Test(dataProvider = "exitedLeaders")
	public void readsProcessWithExitedLeader(String variation, boolean succeeds) throws IOException
	{
		Path fixture = Files.createTempDirectory("linux-residency-exited-leader-");
		try
		{
			requireSecureDirectory(fixture);
			Path root = fixture.resolve("cgroups");
			Path proc = fixture.resolve("proc");
			Path owned = root.resolve("owned");
			writeGroup(owned, "41\n");
			writeProcess(proc, 41, "0::/owned\n", "State:\tZ (zombie)\nTgid:\t41\n");
			Files.writeString(owned.resolve("cgroup.threads"), "42\n79\n77\n78\n");
			Path tasks = proc.resolve("41/task");
			writeProcess(tasks, 79, "0::/owned\n", "State:\tZ (zombie)\nTgid:\t41\n");
			for (long tid : new long[] {77, 78})
				writeProcess(tasks, tid, "0::/owned\n", "State:\tS (sleeping)\nTgid:\t41\nVmRSS: 12 kB\n");
			switch (variation)
			{
				case "live" -> { }
				case "runningLeader" -> Files.writeString(proc.resolve("41/status"), "State:\tR (running)\nTgid:\t41\n");
				case "wrongLeader" -> Files.writeString(proc.resolve("41/status"), "State:\tZ (zombie)\nTgid:\t99\n");
				case "leaderInvalidCounter" -> Files.writeString(proc.resolve("41/status"),
					"State:\tZ (zombie)\nTgid:\t41\nVmRSS: -1 kB\n");
				case "missingThreads" -> Files.delete(owned.resolve("cgroup.threads"));
				case "taskSymlink" ->
				{
					Files.move(tasks, proc.resolve("foreign"));
					Files.createSymbolicLink(tasks, proc.resolve("foreign"));
				}
				case "threadSymlink" ->
				{
					Files.move(tasks.resolve("77"), proc.resolve("foreign"));
					Files.createSymbolicLink(tasks.resolve("77"), proc.resolve("foreign"));
				}
				case "zero", "wrongThread", "foreignMembership", "invalidCounter", "onlyExited", "duplicateThreadGroup", "missingCounter" ->
				{
					for (long tid : new long[] {77, 78})
					{
						Path thread = tasks.resolve(Long.toString(tid));
						if (variation.equals("foreignMembership")) Files.writeString(thread.resolve("cgroup"), "0::/foreign\n");
						else Files.writeString(thread.resolve("status"), switch (variation)
						{
							case "zero" -> "State:\tS (sleeping)\nTgid:\t41\nVmRSS: 0 kB\n";
							case "wrongThread" -> "State:\tS (sleeping)\nTgid:\t99\nVmRSS: 12 kB\n";
							case "duplicateThreadGroup" -> "State:\tS (sleeping)\nTgid:\t41\nTgid:\t41\nVmRSS: 12 kB\n";
							case "invalidCounter" -> "State:\tS (sleeping)\nTgid:\t41\nVmRSS: -1 kB\n";
							case "missingCounter" -> "State:\tS (sleeping)\nTgid:\t41\n";
							default -> "State:\tZ (zombie)\nTgid:\t41\n";
						});
					}
				}
				default -> throw new IllegalArgumentException(variation);
			}
			if (succeeds)
				requireThat(LinuxResidency.read(root, proc, owned), "survivingThreadSample")
					.isEqualTo(new LinuxResidency.Sample(variation.equals("zero") ? 0 : 12288, 1));
			else
			{
				IOException failure = requireException(IOException.class, () -> LinuxResidency.read(root, proc, owned));
				// A live leader or thread without VmRSS belongs to a process that released its address space while exiting.
				requireThat(failure instanceof LinuxResidency.MembershipChanged, "membershipRejection")
					.isEqualTo(variation.equals("missingCounter") || variation.equals("runningLeader"));
			}
		}
		finally
		{
			FileUtils.deleteDirectory(fixture.toFile());
		}
	}

	/**
	 * A thread that is reaped while its status is being read fails that read through its retained directory, as the
	 * kernel's ESRCH does, rather than with a missing file. The thread is gone, so a later thread supplies the
	 * process's counter.
	 * @throws Exception if fixture setup, sampling or cleanup fails
	 */
	@Test(timeOut = 10_000)
	public void skipsThreadReapedWhileRead() throws Exception
	{
		Path fixture = Files.createTempDirectory("linux-residency-reaped-thread-");
		var reaper = Executors.newSingleThreadExecutor();
		try
		{
			requireSecureDirectory(fixture);
			Path root = fixture.resolve("cgroups");
			Path proc = fixture.resolve("proc");
			Path owned = root.resolve("owned");
			writeGroup(owned, "41\n");
			writeProcess(proc, 41, "0::/owned\n", "State:\tZ (zombie)\nTgid:\t41\n");
			Files.writeString(owned.resolve("cgroup.threads"), "80\n77\n");
			Path tasks = proc.resolve("41/task");
			writeProcess(tasks, 77, "0::/owned\n", "State:\tS (sleeping)\nTgid:\t41\nVmRSS: 12 kB\n");
			Path reaped = tasks.resolve("80");
			Files.createDirectories(reaped);
			Files.writeString(reaped.resolve("cgroup"), "0::/owned\n");
			Path status = reaped.resolve("status");
			Process mkfifo = new ProcessBuilder("mkfifo", status.toString()).inheritIO().start();
			requireThat(mkfifo.waitFor(), "mkfifo").isEqualTo(0);
			// Opening the FIFO for writing waits for the sampler to open it; the thread's directory is then removed
			// before the sampler reads end of file, so the failed read belongs to a thread that no longer exists.
			var reaping = reaper.submit(() ->
			{
				try (var writer = Files.newOutputStream(status))
				{
					Files.delete(status);
					Files.delete(reaped.resolve("cgroup"));
					Files.delete(reaped);
				}
				return null;
			});
			requireThat(LinuxResidency.read(root, proc, owned), "sample").isEqualTo(new LinuxResidency.Sample(12288, 1));
			reaping.get(5, TimeUnit.SECONDS);
		}
		finally
		{
			reaper.shutdownNow();
			FileUtils.deleteDirectory(fixture.toFile());
		}
	}

	/**
	 * Supplies exiting process records that have released their address space but are not yet zombies.
	 * @return exit-path state, and whether the sample is a retryable membership rejection
	 */
	@DataProvider
	public Object[][] releasingLeaders()
	{
		return new Object[][] {{"State:\tR (running)\nTgid:\t41\n", true}, {"State:\tD (disk sleep)\nTgid:\t41\n", true},
			{"State:\tR (running)\nTgid:\t99\n", false}, {"State:\tR (running)\n", false}};
	}

	/**
	 * A process between releasing its address space and becoming a zombie has no VmRSS line. Its sample is a
	 * retryable membership rejection, never a zero or a fatal authority failure.
	 * @param status exiting leader's status record
	 * @param rejected whether the sample is rejected as membership churn rather than failing
	 * @throws IOException if fixture setup or cleanup fails
	 */
	@Test(dataProvider = "releasingLeaders")
	public void rejectsProcessReleasingItsAddressSpace(String status, boolean rejected) throws IOException
	{
		Path fixture = Files.createTempDirectory("linux-residency-releasing-");
		try
		{
			requireSecureDirectory(fixture);
			Path root = fixture.resolve("cgroups");
			Path proc = fixture.resolve("proc");
			Path owned = root.resolve("owned");
			writeGroup(owned, "41\n");
			Files.writeString(owned.resolve("cgroup.threads"), "41\n");
			writeProcess(proc, 41, "0::/owned\n", status);
			Files.createDirectories(proc.resolve("41/task"));
			IOException failure = requireException(IOException.class, () -> LinuxResidency.read(root, proc, owned));
			requireThat(failure instanceof LinuxResidency.MembershipChanged, "membershipRejection").isEqualTo(rejected);
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
