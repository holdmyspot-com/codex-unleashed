package com.holdmyspot.codexunleashed.tooling;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import org.testng.annotations.Test;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertTrue;

/**
 * Verifies argument handling without requiring a checkout for help or invalid options.
 */
public final class SourceProvenanceCommandTest
{
	/**
	 * Creates the command tests.
	 */
	public SourceProvenanceCommandTest()
	{
	}

	/**
	 * Provides help before validating required checkout paths.
	 */
	@Test
	public void printsHelp()
	{
		ByteArrayOutputStream output = new ByteArrayOutputStream();
		ByteArrayOutputStream errors = new ByteArrayOutputStream();
		try (PrintStream out = new PrintStream(output, true, StandardCharsets.UTF_8);
			PrintStream err = new PrintStream(errors, true, StandardCharsets.UTF_8))
		{
			assertEquals(Main.run(new String[]{"generate-source-provenance", "--help"}, InputStream.nullInputStream(),
				out, err), 0);
		}
		assertTrue(output.toString(StandardCharsets.UTF_8).contains("--upstream-checkout"));
		assertEquals(errors.size(), 0);
	}

	/**
	 * Rejects missing required paths before attempting source inspection.
	 */
	@Test
	public void rejectsMissingPaths()
	{
		ByteArrayOutputStream output = new ByteArrayOutputStream();
		ByteArrayOutputStream errors = new ByteArrayOutputStream();
		try (PrintStream out = new PrintStream(output, true, StandardCharsets.UTF_8);
			PrintStream err = new PrintStream(errors, true, StandardCharsets.UTF_8))
		{
			assertEquals(Main.run(new String[]{"generate-source-provenance", "--upstream-ref=rust-v0.160.0"},
				InputStream.nullInputStream(), out, err), 2);
		}
		assertEquals(output.size(), 0);
		assertTrue(errors.toString(StandardCharsets.UTF_8).contains("--output"));
	}
}
