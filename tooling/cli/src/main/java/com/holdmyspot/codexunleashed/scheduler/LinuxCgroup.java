package com.holdmyspot.codexunleashed.scheduler;

import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Properties;

/**
 * Owns a Linux cgroup-v2 subtree for a controller that remains outside that subtree.
 * The caller needs authority to create groups, kill their processes and remove groups.
 * Operations are single-thread confined. Population excludes zombies and does not establish that process statuses
 * have been collected; the process owner must still wait for its children.
 */
public final class LinuxCgroup implements AutoCloseable
{
	/** Prefix of exclusive controller groups created beneath the caller's delegated parent. */
	private static final String GROUP_PREFIX = "codex-controller-";

	/** Linux cgroup-v2 filesystem type reported by the JDK filesystem provider. */
	private static final String FILESYSTEM_TYPE = "cgroup2";

	/** Linux cgroup-v2 control file that kills a group's entire subtree. */
	private static final String KILL_FILE = "cgroup.kill";

	/** Linux cgroup-v2 event file containing the subtree population field. */
	private static final String EVENTS_FILE = "cgroup.events";

	/** Kernel event field distinguishing an empty subtree from one with live processes. */
	private static final String POPULATION_FIELD = "populated";

	/** Delay between population reads while close waits for terminated processes to leave the subtree. */
	private static final Duration POLL_INTERVAL = Duration.ofMillis(10);

	/** Exclusive subtree whose removal belongs to this instance. */
	private final Path directory;

	/** Maximum elapsed nanoseconds spent waiting for empty population during each close attempt. */
	private final long timeoutNanos;

	/** Indicates whether this instance successfully removed its entire subtree. */
	private boolean closed;

	/**
	 * Validates the parent and close timeout, then creates an exclusive empty cgroup-v2 subtree.
	 *
	 * @param parent the absolute existing cgroup-v2 parent in which the caller may create groups
	 * @param timeout the positive maximum close wait, representable as signed nanoseconds
	 * @throws NullPointerException if either argument is null
	 * @throws IllegalArgumentException if {@code parent} is relative or {@code timeout} is nonpositive or too large
	 * @throws UnsupportedOperationException if the parent filesystem is not cgroup v2
	 * @throws IOException if resolving or creating the group fails
	 */
	private LinuxCgroup(Path parent, Duration timeout) throws IOException
	{
		Objects.requireNonNull(parent, "parent may not be null");
		Objects.requireNonNull(timeout, "timeout may not be null");
		if (parent.isAbsolute() == false)
			throw new IllegalArgumentException("Cgroup parent must be absolute: " + parent);
		if (timeout.isNegative() || timeout.isZero())
			throw new IllegalArgumentException("Close timeout must be positive: " + timeout);
		try
		{
			timeoutNanos = timeout.toNanos();
		}
		catch (ArithmeticException failure)
		{
			throw new IllegalArgumentException("Close timeout exceeds signed nanoseconds: " + timeout, failure);
		}

		Path resolvedParent = parent.toRealPath();
		String filesystem = Files.getFileStore(resolvedParent).type();
		if (FILESYSTEM_TYPE.equals(filesystem) == false)
			throw new UnsupportedOperationException("Cgroup v2 is required; parent filesystem is " + filesystem +
				": " + resolvedParent);
		directory = Files.createTempDirectory(resolvedParent, GROUP_PREFIX);
	}

	/**
	 * Creates an exclusive empty subtree without moving any process into it.
	 * The controller retains ownership until close succeeds. A supervisor must enter this subtree before creating
	 * workload helpers so that recovery can also reach helpers awaiting their final placement.
	 *
	 * @param parent the absolute existing cgroup-v2 parent in which the caller may create groups
	 * @param timeout the positive maximum close wait, representable as signed nanoseconds
	 * @return the owned subtree
	 * @throws NullPointerException if either argument is null
	 * @throws IllegalArgumentException if {@code parent} is relative or {@code timeout} is nonpositive or too large
	 * @throws UnsupportedOperationException if the parent filesystem is not cgroup v2
	 * @throws IOException if resolving or creating the group fails
	 */
	public static LinuxCgroup create(Path parent, Duration timeout) throws IOException
	{
		return new LinuxCgroup(parent, timeout);
	}

	/**
	 * Returns the exclusive subtree path, retaining its last location after close.
	 *
	 * @return the absolute owned group path
	 */
	public Path directory()
	{
		return directory;
	}

	/**
	 * Indicates whether the kernel reports live processes in this group or any descendant group.
	 *
	 * @return true when the subtree has live processes
	 * @throws IllegalStateException if close already succeeded
	 * @throws IOException if the kernel events cannot be read or the population field is missing or unrecognized
	 */
	public boolean isPopulated() throws IOException
	{
		requireOpen();
		return isPopulated(directory);
	}

	/**
	 * Reads live population for a borrowed, retained action subtree without acquiring its ownership.
	 * @param directory the exclusive action cgroup retained by its existing owner
	 * @return true when the kernel reports live processes in that group or its descendants
	 * @throws IOException if events are missing, unreadable or have no valid population field
	 */
	static boolean isPopulated(Path directory) throws IOException
	{
		Properties events = new Properties();
		try (Reader input = Files.newBufferedReader(directory.resolve(EVENTS_FILE)))
		{
			events.load(input);
		}
		catch (IllegalArgumentException failure)
		{
			throw new IOException("Cgroup events cannot be decoded: " + directory, failure);
		}
		String population = events.getProperty(POPULATION_FIELD);
		return switch (population)
		{
			case "0" -> false;
			case "1" -> true;
			case null -> throw new IOException("Cgroup population field is missing: " + directory);
			default -> throw new IOException("Unrecognized cgroup population value '" + population + "': " + directory);
		};
	}

	/**
	 * Requests SIGKILL for every process in this subtree without waiting for population to become empty.
	 *
	 * @throws IllegalStateException if close already succeeded
	 * @throws IOException if the kernel rejects or cannot receive the termination request
	 */
	public void terminate() throws IOException
	{
		requireOpen();
		Files.writeString(directory.resolve(KILL_FILE), "1", StandardOpenOption.WRITE);
	}

	/**
	 * Terminates remaining processes, waits up to the configured timeout for empty population, and removes all owned
	 * groups. Repeated close after success has no effect. The caller's interrupt is preserved while cleanup proceeds.
	 * A failure retains ownership and may leave some empty descendant groups already removed; the caller may retry.
	 *
	 * @throws IOException if termination, sampling or removal fails, or population remains live at the timeout
	 */
	@Override
	public void close() throws IOException
	{
		if (closed)
			return;
		boolean interrupted = Thread.interrupted();
		try
		{
			terminate();
			long startedAt = System.nanoTime();
			while (isPopulated())
			{
				if ((System.nanoTime() - startedAt) >= timeoutNanos)
					throw new IOException("Cgroup remains populated after the configured close timeout: " + directory);
				try
				{
					Thread.sleep(POLL_INTERVAL);
				}
				catch (InterruptedException failure)
				{
					interrupted = true;
				}
			}

			List<Path> groups;
			try (var entries = Files.walk(directory))
			{
				groups = entries.filter(path -> Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS))
					.sorted(Comparator.reverseOrder()).toList();
			}
			for (Path group : groups)
				Files.delete(group);
			closed = true;
		}
		finally
		{
			if (interrupted)
				Thread.currentThread().interrupt();
		}
	}

	/**
	 * Rejects population or termination operations after successful cleanup.
	 *
	 * @throws IllegalStateException if close already succeeded
	 */
	private void requireOpen()
	{
		if (closed)
			throw new IllegalStateException("Cgroup owner is closed: " + directory);
	}
}
