package com.holdmyspot.codexunleashed.tooling;

import java.io.IOException;
import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Hosts compiler scheduling in a process whose working directory owns the local socket.
 */
public final class CompilerSchedulerHost
{
	/** Prevents construction. */
	private CompilerSchedulerHost()
	{
	}

	/**
	 * Resolves the current runtime and module locations before changing the host's working directory.
	 *
	 * @return literal JDK launcher arguments
	 */
	static List<String> command()
	{
		String executable = "java";
		if (File.separatorChar == '\\')
			executable = "java.exe";
		List<String> command = new ArrayList<>();
		command.add(Path.of(System.getProperty("java.home"), "bin", executable).toString());
		command.add("-Djava.io.tmpdir=" + Path.of(System.getProperty("java.io.tmpdir")).toAbsolutePath());
		String modulePath = System.getProperty("jdk.module.path", "");
		if (!modulePath.isEmpty())
		{
			List<String> entries = new ArrayList<>();
			for (String entry : modulePath.split(Pattern.quote(File.pathSeparator)))
				entries.add(Path.of(entry).toAbsolutePath().toString());
			command.add("--module-path");
			command.add(String.join(File.pathSeparator, entries));
		}
		command.add("--module");
		command.add(CompilerSchedulerHost.class.getModule().getName() + "/" + CompilerSchedulerHost.class.getName());
		return List.copyOf(command);
	}

	/**
	 * Keeps the scheduler alive until its parent closes standard input.
	 *
	 * @param args no arguments
	 * @throws IOException if memory, socket initialization, parent input, or scheduler shutdown fails
	 */
	public static void main(String[] args) throws IOException
	{
		if (args.length != 0)
			throw new IllegalArgumentException("The compiler scheduler host accepts no arguments");
		CompilerMemory memory = CompilerMemory.system();
		try (CompilerSchedulerServer scheduler = new CompilerSchedulerServer(Path.of("s"), memory.reserve(),
			memory.available(), CompilerScheduler.OBSERVATION_INTERVAL, System.err))
		{
			scheduler.checkHealthy();
			System.out.println("ready");
			while (System.in.read() != -1)
			{
				scheduler.checkHealthy();
			}
			scheduler.checkHealthy();
		}
	}
}
