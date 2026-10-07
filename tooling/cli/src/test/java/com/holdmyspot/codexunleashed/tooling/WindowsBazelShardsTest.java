package com.holdmyspot.codexunleashed.tooling;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.testng.annotations.Test;
import static org.testng.Assert.assertEquals;

/**
 * Verifies duration-balanced target selection through the tooling command.
 */
public final class WindowsBazelShardsTest
{
	/**
	 * Creates shard command tests.
	 */
	public WindowsBazelShardsTest()
	{
	}

	/**
	 * Assigns long targets first, retains unknown targets, and emits deterministic LF-delimited selections.
	 *
	 * @throws IOException if fixture creation or cleanup fails
	 */
	@Test
	public void balancesKnownAndUnknownTargets() throws IOException
	{
		Path durations = Files.createTempFile("windows-bazel-durations-", ".tsv");
		try
		{
			Files.writeString(durations, "//a\t10\n//b\t8\n//c\t2\n");
			for (int shard = 1; shard <= 2; ++shard)
			{
				ByteArrayOutputStream output = new ByteArrayOutputStream();
				ByteArrayOutputStream diagnostics = new ByteArrayOutputStream();
				try (PrintStream out = new PrintStream(output, true, StandardCharsets.UTF_8);
					PrintStream err = new PrintStream(diagnostics, true, StandardCharsets.UTF_8))
				{
					int status = Main.run(new String[] {"select-windows-bazel-targets", "--shard", Integer.toString(shard),
						"--shard-count", "2", "--durations", durations.toString()},
						new ByteArrayInputStream("//unknown\n//c\n//b\n//a\n".getBytes(StandardCharsets.UTF_8)), out, err);
					assertEquals(status, 0, diagnostics.toString(StandardCharsets.UTF_8));
				}
				String expected = "//a\n//unknown\n";
				if (shard == 2)
					expected = "//b\n//c\n";
				assertEquals(output.toString(StandardCharsets.UTF_8), expected);
			}
		}
		finally
		{
			Files.delete(durations);
		}
	}
}
