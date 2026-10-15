package com.holdmyspot.codexunleashed.distribution;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

/** Models the build, output discovery, and native callback execution boundaries. */
public final class WindowsStackAssemblyFixture
{
	/** Prevents construction. */
	private WindowsStackAssemblyFixture()
	{
	}

	/**
	 * Records the requested boundary and returns its selected output or status.
	 *
	 * @param arguments the literal child arguments
	 * @throws IOException if execution evidence cannot be recorded
	 */
	public static void main(String[] arguments) throws IOException
	{
		Path evidence = Path.of(System.getenv("STACK_FIXTURE_EVIDENCE"));
		String command = Arrays.stream(arguments).filter(argument -> !argument.startsWith("-")).
			findFirst().orElse("callback");
		Files.writeString(evidence.resolve(command), String.join("\0", arguments));
		if (command.equals("cquery"))
			System.out.print(System.getenv("STACK_FIXTURE_BINARY") + "\r\n");
		if (command.equals("callback"))
			System.exit(Integer.parseInt(System.getenv("STACK_FIXTURE_STATUS")));
	}
}
