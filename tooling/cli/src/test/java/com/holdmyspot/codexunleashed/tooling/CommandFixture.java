package com.holdmyspot.codexunleashed.tooling;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

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
	 * @throws IOException if writing the execution fixture result fails
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
			case "execution" -> Files.writeString(Path.of(args[1]), Path.of(".").toRealPath() + "\n" + args[2] +
				"\n" + System.getenv("COMMAND_EXECUTION_INPUT") + "\n" + System.getenv("PATH"));
			case "shear-warning" ->
			{
				if (args.length != 4 || !"--deny-warnings".equals(args[2]) || !"--format=json".equals(args[3]))
					throw new IllegalArgumentException("Cargo Shear arguments differ from the maintained command");
				if (!Files.isRegularFile(Path.of("Cargo.toml")))
					throw new IllegalArgumentException("Cargo Shear working directory lacks the fixture manifest");
				System.out.print(args[1]);
				System.err.print("shear diagnostics\n");
				System.exit(1);
			}
			case "capture" ->
			{
				System.out.print(System.getProperty("user.dir") + "\n" + args[1]);
				System.err.print("diagnostic".repeat(20_000));
				System.exit(7);
			}
			default -> throw new IllegalArgumentException("Unknown fixture mode: " + args[0]);
		}
	}
}
