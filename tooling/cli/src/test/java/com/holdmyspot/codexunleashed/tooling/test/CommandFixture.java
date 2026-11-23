package com.holdmyspot.codexunleashed.tooling.test;

import java.io.IOException;

/**
 * Provides a portable external process fixture for command execution tests.
 */
public final class CommandFixture
{
	/**
	 * Prevents construction.
	 */
	private CommandFixture()
	{
	}

	/**
	 * Emits controlled process output or a controlled exit status.
	 *
	 * @param args the fixture mode followed by the literal payload
	 * @throws IOException if writing the output fails
	 */
	public static void main(String[] args) throws IOException
	{
		switch (args[0])
		{
			case "echo" -> System.out.print(args[1]);
			case "invalid-utf8" ->
			{
				System.out.write(255);
				System.out.flush();
			}
			case "fail" -> System.exit(7);
			case "capture" ->
			{
				System.out.print(System.getProperty("user.dir") + "\n" + args[1] + " λ");
				System.err.print("diagnostic".repeat(20_000));
				System.exit(7);
			}
			default -> throw new IllegalArgumentException("Unknown fixture mode: " + args[0]);
		}
	}
}
