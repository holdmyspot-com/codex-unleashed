package com.holdmyspot.codexunleashed.tooling;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalLong;

/**
 * Runs a build whose rustc invocations are scheduled by the free memory of the system and the build's control group.
 */
public final class ScheduleCompilersCommand
{
	/**
	 * The command name.
	 */
	public static final String NAME = "schedule-compilers";
	/**
	 * The share of total memory that the scheduler keeps free.
	 */
	private static final int RESERVE_PERCENT = 10;
	/**
	 * The least free memory, in bytes, that the scheduler keeps free.
	 */
	private static final long MINIMUM_RESERVE = 1L << 30;
	/**
	 * The delay between free-memory readings.
	 */
	private static final Duration INTERVAL = Duration.ofMillis(250);

	/**
	 * Prevents construction.
	 */
	private ScheduleCompilersCommand()
	{
	}

	/**
	 * Runs a build under the compiler scheduler.
	 *
	 * @param args the separator and the build command
	 * @param err the diagnostic destination
	 * @return the build's status, or two for invalid arguments
	 * @throws NullPointerException if an argument is null
	 * @throws IOException if the scheduler or the build cannot start
	 */
	public static int run(String[] args, PrintStream err) throws IOException
	{
		Objects.requireNonNull(err, "err");
		for (String argument : args)
			Objects.requireNonNull(argument, "argument");
		if (args.length < 2 || !args[0].equals("--"))
		{
			err.println("Usage: " + NAME + " -- <command> [argument ...]");
			return 2;
		}
		long reserve = Math.max(MINIMUM_RESERVE, readMeminfo("MemTotal") / 100 * RESERVE_PERCENT);
		try (CompilerSchedulerService service = new CompilerSchedulerService(reserve,
			ScheduleCompilersCommand::freeMemory, INTERVAL, err))
		{
			Map<String, String> environment = new HashMap<>(System.getenv());
			environment.putAll(service.environment());
			return SystemCommands.execute(Arrays.asList(Arrays.copyOfRange(args, 1, args.length)),
				Path.of("").toAbsolutePath(), environment);
		}
	}

	/**
	 * Returns the memory that the build can still use: the system's available memory, limited by the room left under
	 * the memory limit of this process's control group.
	 *
	 * @return the free memory, in bytes
	 * @throws IOException if the memory cannot be read
	 */
	private static long freeMemory() throws IOException
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
