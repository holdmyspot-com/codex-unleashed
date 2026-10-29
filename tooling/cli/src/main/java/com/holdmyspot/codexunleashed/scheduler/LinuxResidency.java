package com.holdmyspot.codexunleashed.scheduler;

import java.io.IOException;
import java.nio.channels.Channels;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.FileVisitResult;
import java.nio.file.Path;
import java.nio.file.SecureDirectoryStream;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
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
			long bytes = readProcess(process, expected);
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
			throw new IOException("Owned process membership changed while sampling " + owned);
		return new Sample(residentBytes, before.size());
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
	 *
	 * @param process the proc directory for the sampled PID
	 * @param expected the exact unified-v2 membership record for the enumerated group
	 * @return the process's nonnegative sampled RSS bytes
	 * @throws UnsupportedOperationException if the provider lacks secure directory operations
	 * @throws IOException if either observation fails or membership differs from the expected record
	 */
	private static long readProcess(Path process, byte[] expected) throws IOException
	{
		try (var directory = Files.newDirectoryStream(process))
		{
			if (directory instanceof SecureDirectoryStream<Path> secure)
			{
				requireMembership(secure, process, expected);
				long bytes;
				try (var channel = secure.newByteChannel(Path.of("status"), Set.of(StandardOpenOption.READ));
					var input = Channels.newReader(channel, StandardCharsets.UTF_8))
				{
					bytes = LinuxMemory.readResidentBytes(input, process.resolve("status"));
				}
				requireMembership(secure, process, expected);
				return bytes;
			}
			throw new UnsupportedOperationException("Provider lacks secure process-directory operations: " + process);
		}
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
