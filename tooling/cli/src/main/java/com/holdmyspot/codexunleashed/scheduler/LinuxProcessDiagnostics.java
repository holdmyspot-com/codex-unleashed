package com.holdmyspot.codexunleashed.scheduler;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/**
 * Describes Linux processes for failure diagnostics. Descriptions are best effort: a process that exits while it is
 * read is described by the error that its read returned.
 */
public final class LinuxProcessDiagnostics
{
	private LinuxProcessDiagnostics()
	{
	}

	/**
	 * Describes the children of every thread of a process, including zombies. A traced zombie stays unreapable by its
	 * parent until its tracer collects it, so the tracer identifies who holds it.
	 * @param pid the parent process ID
	 * @return one {@code pid=… name=… state=… ppid=… tracer=…} line per child, a line naming a read failure, or
	 *   {@code none} when the process has no children
	 */
	public static String children(long pid)
	{
		StringBuilder description = new StringBuilder();
		try (DirectoryStream<Path> threads = Files.newDirectoryStream(Path.of("/proc", Long.toString(pid), "task")))
		{
			for (Path thread : threads)
			{
				for (String child : Files.readString(thread.resolve("children")).split(" "))
				{
					if (child.isBlank())
						continue;
					description.append(describe(child.strip())).append('\n');
				}
			}
		}
		catch (IOException failure)
		{
			description.append("children of ").append(pid).append(" unreadable: ").append(failure).append('\n');
		}
		if (description.isEmpty())
			return "none";
		return description.toString();
	}

	/**
	 * Describes one process from its status file.
	 * @param pid the process ID
	 * @return the description, or the read failure
	 */
	private static String describe(String pid)
	{
		Map<String, String> fields = new HashMap<>();
		try
		{
			for (String line : Files.readAllLines(Path.of("/proc", pid, "status")))
			{
				int colon = line.indexOf(':');
				if (colon > 0)
					fields.put(line.substring(0, colon), line.substring(colon + 1).strip());
			}
		}
		catch (IOException failure)
		{
			return "pid=" + pid + " unreadable: " + failure;
		}
		String state = fields.getOrDefault("State", "?");
		int space = state.indexOf(' ');
		if (space > 0)
			state = state.substring(0, space);
		return "pid=" + pid + " name=" + fields.getOrDefault("Name", "?") + " state=" + state + " ppid=" +
			fields.getOrDefault("PPid", "?") + " tracer=" + fields.getOrDefault("TracerPid", "?");
	}
}
