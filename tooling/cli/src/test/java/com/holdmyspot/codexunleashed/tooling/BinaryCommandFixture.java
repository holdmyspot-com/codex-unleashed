package com.holdmyspot.codexunleashed.tooling;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Arrays;

/**
 * Emits invalid UTF-8 stdout after checking the explicit process context.
 */
public final class BinaryCommandFixture
{
	/**
	 * Prevents construction.
	 */
	private BinaryCommandFixture()
	{
	}

	/**
	 * Produces fixed raw bytes and a diagnostic stream larger than ordinary pipe capacity.
	 *
	 * @param args the expected working directory and exit status
	 * @throws IOException if stdout cannot be written
	 */
	public static void main(String[] args) throws IOException
	{
		if (!Path.of("").toAbsolutePath().equals(Path.of(args[0])) ||
			!"explicit context".equals(System.getenv("CODEX_UNLEASHED_CAPTURE_MARKER")))
			throw new IllegalArgumentException("The fixture did not receive its explicit process context");
		byte[] bytes = new byte[200_000];
		Arrays.fill(bytes, (byte) 0xff);
		System.err.print("e".repeat(200_000));
		System.out.write(bytes);
		System.exit(Integer.parseInt(args[1]));
	}
}
