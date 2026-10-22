package com.holdmyspot.codexunleashed.tooling;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.OptionalLong;

/**
 * Supplies RAM budgets shared by the Cargo and Bazel compiler schedulers on Linux.
 */
final class LinuxCompilerMemory
{
	/**
	 * Prevents construction.
	 */
	private LinuxCompilerMemory()
	{
	}

	/**
	 * Returns the free RAM reserve used by both compiler adapters.
	 *
	 * @return the reserve in bytes
	 * @throws IOException if total memory cannot be read
	 */
	static long reserve() throws IOException
	{
		return PhysicalCompilerMemory.reserve(readMeminfo("MemTotal"));
	}

	/**
	 * Returns the memory that the build can still use: the system's available memory, limited by the room left under
	 * the memory limit of this process's control group.
	 *
	 * @return the free memory, in bytes
	 * @throws IOException if the memory cannot be read
	 */
	static long available() throws IOException
	{
		long free = readMeminfo("MemAvailable");
		OptionalLong room = controlGroupRoom();
		if (room.isPresent())
			free = Math.min(free, room.getAsLong());
		return free;
	}

	/**
	 * Reads a field of {@code /proc/meminfo}.
	 *
	 * @param field the field name
	 * @return the value, in bytes
	 * @throws IOException if the field cannot be read
	 */
	private static long readMeminfo(String field) throws IOException
	{
		for (String line : Files.readAllLines(Path.of("/proc/meminfo")))
		{
			if (line.startsWith(field + ":"))
			{
				String[] words = line.substring(field.length() + 1).trim().split("\\s+");
				return Long.parseLong(words[0]) * 1024;
			}
		}
		throw new IOException("/proc/meminfo does not report " + field);
	}

	/**
	 * Returns how much memory this process's cgroup v2 control group can still charge before reaching its limit.
	 *
	 * @return the remaining memory, in bytes, or empty if the control group has no memory limit
	 * @throws IOException if the control group cannot be read
	 */
	private static OptionalLong controlGroupRoom() throws IOException
	{
		String group = null;
		// The unified cgroup v2 hierarchy is the entry with hierarchy ID 0 and no controller list.
		for (String line : Files.readAllLines(Path.of("/proc/self/cgroup")))
		{
			String[] fields = line.split(":", 3);
			if (fields.length == 3 && fields[0].equals("0") && fields[1].isEmpty())
				group = fields[2];
		}
		if (group == null)
			return OptionalLong.empty();
		Path directory = Path.of("/sys/fs/cgroup", group);
		try
		{
			String limit = Files.readString(directory.resolve("memory.max")).strip();
			if (limit.equals("max"))
				return OptionalLong.empty();
			long current = Long.parseLong(Files.readString(directory.resolve("memory.current")).strip());
			return OptionalLong.of(Math.max(0, Long.parseLong(limit) - current));
		}
		catch (NoSuchFileException _)
		{
			return OptionalLong.empty();
		}
	}
}
