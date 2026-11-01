package com.holdmyspot.codexunleashed.scheduler;

import java.io.IOException;
import java.io.StringReader;
import java.io.StringWriter;
import java.nio.channels.Channels;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.FileVisitResult;
import java.nio.file.Path;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.SecureDirectoryStream;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Samples summed resident memory within an owned Linux cgroup-v2 subtree.
 * <p>
 * Counts are sequential estimates: shared pages can be counted per process and short-lived peaks can be missed.
 * Matching membership observations do not establish an atomic snapshot. Calls share no mutable state.
 */
public final class LinuxResidency
{
	/**
	 * Prevents construction of this stateless sampler.
	 */
	private LinuxResidency()
	{
	}

	/**
	 * Represents the summed RSS of the observed processes.
	 *
	 * @param residentBytes the nonnegative sum of sampled resident bytes
	 * @param processes the nonnegative number of distinct sampled processes
	 */
	public record Sample(long residentBytes, int processes)
	{
		/**
		 * Creates a validated memory observation.
		 *
		 * @param residentBytes the nonnegative sum of sampled resident bytes
		 * @param processes the nonnegative number of distinct sampled processes
		 * @throws IllegalArgumentException if either count is negative
		 */
		public Sample
		{
			if (residentBytes < 0)
				throw new IllegalArgumentException("residentBytes must be nonnegative: " + residentBytes);
			if (processes < 0)
				throw new IllegalArgumentException("processes must be nonnegative: " + processes);
		}
	}

	/**
	 * Reads checked process RSS and accepts it only with matching owned membership observations.
	 * <p>
	 * The caller must supply a cgroup mount root that maps the same hierarchy root as its proc membership view.
	 * This operation supports unified-v2-only membership records. It reads each process through one open directory,
	 * comparing its exact membership record before and after the RSS read. No failed observation becomes zero.
	 * An empty subtree returns zero bytes and zero processes. Membership changes fail this call without retrying;
	 * a controller may request a new sample later. The sampler changes no processes or cgroup configuration.
	 *
	 * @param cgroupRoot the nonnull mounted hierarchy root matching the reader's cgroup namespace
	 * @param procRoot the nonnull proc filesystem root
	 * @param directory the nonnull owned strict descendant of cgroupRoot, including its child groups
	 * @return the checked sampled bytes and process count
	 * @throws NullPointerException if an input is null
	 * @throws IllegalArgumentException if directory is not a strict descendant of cgroupRoot
	 * @throws UnsupportedOperationException if secure directory operations are unavailable
	 * @throws IOException if membership or counters are unavailable, inconsistent, changed, malformed or overflow,
	 * or an observed group path cannot be represented without losing bytes
	 */
	public static Sample read(Path cgroupRoot, Path procRoot, Path directory) throws IOException
	{
		Path root = cgroupRoot.toRealPath();
		Path proc = procRoot.toRealPath();
		Path owned = directory.toRealPath();
		if (owned.equals(root))
			throw new IllegalArgumentException("directory must be a strict descendant of cgroupRoot: " + owned);
		if (owned.startsWith(root) == false)
			throw new IllegalArgumentException("directory is outside cgroupRoot: " + owned);

		Map<Long, Path> before = members(owned);
		long residentBytes = 0;
		for (Map.Entry<Long, Path> member : before.entrySet())
		{
			Path group = member.getValue();
			Path relative = root.relativize(group);
			if (Path.of(relative.toString()).equals(relative) == false)
				throw new IOException("Group path cannot round-trip through its text representation: " + group);
			byte[] expected = ("0::/" + relative + "\n").getBytes(StandardCharsets.UTF_8);
			Path process = proc.resolve(Long.toString(member.getKey()));
			long bytes;
			try { bytes = readProcess(process, expected, group); }
			catch (IOException failure)
			{
				Map<Long, Path> after = members(owned);
				if (group.equals(after.get(member.getKey()))) throw failure;
				throw new MembershipChanged("Sampled process left its owned group: " + process, failure);
			}
			try
			{
				residentBytes = Math.addExact(residentBytes, bytes);
			}
			catch (ArithmeticException failure)
			{
				throw new IOException("Summed process RSS overflows bytes below " + owned, failure);
			}
		}

		Map<Long, Path> after = members(owned);
		if (before.equals(after) == false)
			throw new MembershipChanged("Owned process membership changed while sampling " + owned, null);
		return new Sample(residentBytes, before.size());
	}

	/** Indicates independently observed membership churn, without supplying any substitute memory value. */
	static final class MembershipChanged extends IOException
	{
		/**
		 * Retains the observation that invalidated the complete sample.
		 * @param message exact observed ownership change
		 * @param cause missing process data, or null when complete before/after maps differ
		 */
		private MembershipChanged(String message, IOException cause) { super(message, cause); }
	}

	/**
	 * Lists each distinct process ID and its group within the supplied subtree, accepting repeated IDs within one group.
	 *
	 * @param directory the owned subtree root
	 * @return each distinct PID and its group
	 * @throws IOException if a group cannot be read, a PID is invalid, or the same PID occurs in multiple groups
	 */
	private static Map<Long, Path> members(Path directory) throws IOException
	{
		Map<Long, Path> result = new HashMap<>();
		Files.walkFileTree(directory, new SimpleFileVisitor<Path>()
		{
			@Override
			public FileVisitResult preVisitDirectory(Path group, BasicFileAttributes attributes) throws IOException
			{
				Path source = group.resolve("cgroup.procs");
				for (String line : Files.readAllLines(source))
				{
					long pid;
					try
					{
						pid = Long.parseLong(line);
					}
					catch (NumberFormatException failure)
					{
						throw new IOException("Invalid process ID in " + source + ": " + line, failure);
					}
					if (pid <= 0)
						throw new IOException("Process ID must be positive in " + source + ": " + pid);
					Path previous = result.putIfAbsent(pid, group);
					if (previous != null)
					{
						if (previous.equals(group) == false)
							throw new IOException("Process ID occurs in multiple sampled groups: " + pid);
					}
				}
				return FileVisitResult.CONTINUE;
			}
		});
		return result;
	}

	/**
	 * Reads one process through a retained directory and checks its exact membership around the RSS observation.
	 * An exited leader without RSS may use one verified surviving thread's shared-process counter.
	 *
	 * @param process the proc directory for the sampled PID
	 * @param expected the exact unified-v2 membership record for the enumerated group
	 * @param group the enumerated cgroup whose thread membership can select a surviving task
	 * @return the process's nonnegative sampled RSS bytes
	 * @throws UnsupportedOperationException if the provider lacks secure directory operations
	 * @throws IOException if either observation fails or membership differs from the expected record
	 */
	private static long readProcess(Path process, byte[] expected, Path group) throws IOException
	{
		try (var directory = Files.newDirectoryStream(process))
		{
			if (directory instanceof SecureDirectoryStream<Path> secure)
			{
				requireMembership(secure, process, expected);
				long bytes;
				String status = readStatus(secure);
				try
				{
					bytes = LinuxMemory.readResidentBytes(new StringReader(status), process.resolve("status"));
				}
				catch (IOException failure)
				{
					if (statusField(status, "VmRSS") != null ||
						isExitedTask(status, process.getFileName().toString()) == false) throw failure;
					bytes = readSurvivingThread(secure, process, expected, group, failure);
				}
				requireMembership(secure, process, expected);
				return bytes;
			}
			throw new UnsupportedOperationException("Provider lacks secure process-directory operations: " + process);
		}
	}

	/**
	 * Retains the status from one bound process or task directory without reopening its numeric identity.
	 * @param directory borrowed retained proc directory
	 * @return the exact decoded status text consumed by the counter parser
	 * @throws IOException if the bound status cannot be read
	 */
	private static String readStatus(SecureDirectoryStream<Path> directory) throws IOException
	{
		try (var channel = directory.newByteChannel(Path.of("status"), Set.of(StandardOpenOption.READ));
			var input = Channels.newReader(channel, StandardCharsets.UTF_8))
		{
			StringWriter output = new StringWriter();
			input.transferTo(output);
			return output.toString();
		}
	}

	/**
	 * Selects one actual shared-address-space counter below the same retained process directory.
	 * Other processes in the cgroup cannot supply the counter; task paths must be real directories and their
	 * status must identify the original thread group. Missing or exited tasks supply no replacement value.
	 * @param directory borrowed retained process directory
	 * @param process process path used for diagnostics and the expected thread-group identity
	 * @param expected exact owned unified membership record
	 * @param group owned cgroup supplying native thread identities
	 * @param original original missing leader counter, retained if no live task can supply RSS
	 * @return one verified task's actual shared-process RSS bytes, counted once for the process
	 * @throws IOException if ownership, identity, task counters or fresh thread membership cannot be established
	 */
	private static long readSurvivingThread(SecureDirectoryStream<Path> directory, Path process, byte[] expected,
		Path group, IOException original) throws IOException
	{
		Set<Long> candidates = threadMembers(group);
		try (var tasks = directory.newDirectoryStream(Path.of("task"), LinkOption.NOFOLLOW_LINKS))
		{
			for (long tid : candidates)
			{
				if (Long.toString(tid).equals(process.getFileName().toString())) continue;
				Path taskPath = process.resolve("task").resolve(Long.toString(tid));
				try (var task = tasks.newDirectoryStream(Path.of(Long.toString(tid)), LinkOption.NOFOLLOW_LINKS))
				{
					requireMembership(task, taskPath, expected);
					String status = readStatus(task);
					if (process.getFileName().toString().equals(statusField(status, "Tgid")) == false)
						throw new IOException("Bound task has a different thread-group identity: " + taskPath);
					if (isExitedTask(status, process.getFileName().toString())) continue;
					long bytes = LinuxMemory.readResidentBytes(new StringReader(status), taskPath.resolve("status"));
					requireMembership(task, taskPath, expected);
					if (threadMembers(group).contains(tid) == false)
						throw new MembershipChanged("Sampled memory task left its owned group: " + taskPath, original);
					return bytes;
				}
				catch (NoSuchFileException disappeared)
				{
					// A cgroup may also list tasks from other processes, or a selected task may have exited.
					// Neither case supplies RSS; another bound task must provide the actual process counter.
				}
			}
		}
		throw new IOException("Exited process leader has no verified live memory task: " + process, original);
	}

	/**
	 * Reads each distinct positive native task identity in one owned cgroup.
	 * @param group owned group selected by the process-membership observation
	 * @return native task identities in observed order
	 * @throws IOException if the complete thread record is unavailable or contains an invalid identity
	 */
	private static Set<Long> threadMembers(Path group) throws IOException
	{
		Set<Long> result = new LinkedHashSet<>();
		for (String line : Files.readAllLines(group.resolve("cgroup.threads")))
		{
			try
			{
				long tid = Long.parseLong(line);
				if (tid <= 0) throw new NumberFormatException("Nonpositive task identity");
				result.add(tid);
			}
			catch (NumberFormatException failure)
			{
				throw new IOException("Invalid task identity in " + group + ": " + line, failure);
			}
		}
		return result;
	}

	/**
	 * Checks a complete native exited-state spelling and its containing process identity.
	 * @param status bound status record
	 * @param pid expected canonical thread-group identity
	 * @return whether this record independently identifies an exited task of the expected process
	 * @throws IOException if a selected status field is duplicated
	 */
	private static boolean isExitedTask(String status, String pid) throws IOException
	{
		String state = statusField(status, "State");
		return ("Z (zombie)".equals(state) || "X (dead)".equals(state)) && pid.equals(statusField(status, "Tgid"));
	}

	/**
	 * Reads one uniquely named native status field without accepting duplicate identity observations.
	 * @param status complete bound status record
	 * @param name exact field name before its colon
	 * @return stripped field value, or null when the field is absent
	 * @throws IOException if the field occurs more than once
	 */
	private static String statusField(String status, String name) throws IOException
	{
		String result = null;
		for (String line : status.lines().toList())
		{
			if (line.startsWith(name + ":"))
			{
				if (result != null) throw new IOException("Duplicate process status field: " + name);
				result = line.substring(name.length() + 1).strip();
			}
		}
		return result;
	}

	/**
	 * Requires the bound process to report the expected exact unified membership record.
	 *
	 * @param directory the borrowed bound proc directory
	 * @param process the process path used only for diagnostics
	 * @param expected the expected raw membership record
	 * @throws IOException if reading fails or the raw record differs, including unsupported hybrid records
	 */
	private static void requireMembership(SecureDirectoryStream<Path> directory, Path process, byte[] expected)
		throws IOException
	{
		try (var channel = directory.newByteChannel(Path.of("cgroup"), Set.of(StandardOpenOption.READ));
			var input = Channels.newInputStream(channel))
		{
			if (Arrays.equals(expected, input.readAllBytes()) == false)
				throw new IOException("Process does not report the expected exact unified membership record: " + process);
		}
	}
}
