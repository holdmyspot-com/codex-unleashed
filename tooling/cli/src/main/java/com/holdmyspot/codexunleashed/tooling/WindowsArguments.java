package com.holdmyspot.codexunleashed.tooling;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Encodes literal argument vectors for the Microsoft C runtime without invoking a command shell. */
final class WindowsArguments
{
	/** Prevents construction. */
	private WindowsArguments()
	{
	}

	/**
	 * Applies explicit Windows quoting while retaining the executable's separate path boundary.
	 *
	 * @param command the validated nonempty argument vector
	 * @return the process builder argument vector
	 * @throws IOException if Windows JDK quoting conflicts with the explicit encoder or the executable requires a shell
	 */
	static List<String> prepare(List<String> command) throws IOException
	{
		if (File.separatorChar != '\\')
			return command;
		if (System.getProperty("jdk.lang.Process.allowAmbiguousCommands", "true").equalsIgnoreCase("false"))
			throw new IOException("Explicit Windows argument encoding requires " +
				"-Djdk.lang.Process.allowAmbiguousCommands=true");
		String executable = command.getFirst();
		if (executable.indexOf('"') >= 0)
			throw new IOException("Windows executable paths cannot contain literal quotes: " + executable);
		String suffix = executable.toLowerCase(Locale.ROOT);
		if (suffix.endsWith(".cmd") || suffix.endsWith(".bat"))
			throw new IOException("Windows batch files require an explicit command interpreter: " + executable);

		List<String> encoded = new ArrayList<>(command.size());
		encoded.add(executable);
		for (int index = 1; index < command.size(); ++index)
			encoded.add(quote(command.get(index)));
		return List.copyOf(encoded);
	}

	/**
	 * Encodes an argument using Windows C runtime quote and backslash rules.
	 *
	 * @param argument the literal argument
	 * @return one quoted Windows argument
	 */
	static String quote(String argument)
	{
		StringBuilder encoded = new StringBuilder(argument.length() + 2).append('"');
		int backslashes = 0;
		for (int index = 0; index < argument.length(); ++index)
		{
			char character = argument.charAt(index);
			if (character == '\\')
			{
				++backslashes;
				continue;
			}
			if (character == '"')
				encoded.append("\\".repeat(backslashes * 2 + 1));
			else
				encoded.append("\\".repeat(backslashes));
			encoded.append(character);
			backslashes = 0;
		}
		return encoded.append("\\".repeat(backslashes * 2)).append('"').toString();
	}
}
