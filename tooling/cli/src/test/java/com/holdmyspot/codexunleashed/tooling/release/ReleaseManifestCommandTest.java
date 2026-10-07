package com.holdmyspot.codexunleashed.tooling.release;

import com.holdmyspot.codexunleashed.tooling.Main;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import org.testng.annotations.Test;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertTrue;

/**
 * Verifies manifest command help and required inputs before artifact access.
 */
public final class ReleaseManifestCommandTest
{
	/**
	 * Creates the manifest command tests.
	 */
	public ReleaseManifestCommandTest()
	{
	}

	/**
	 * Prints help without requiring release artifacts or source metadata.
	 */
	@Test
	public void printsHelp()
	{
		ByteArrayOutputStream output = new ByteArrayOutputStream();
		ByteArrayOutputStream errors = new ByteArrayOutputStream();
		try (PrintStream out = new PrintStream(output, true, StandardCharsets.UTF_8);
			PrintStream err = new PrintStream(errors, true, StandardCharsets.UTF_8))
		{
			assertEquals(Main.run(new String[]{"generate-release-manifest", "--help"}, InputStream.nullInputStream(),
				out, err), 0);
		}
		assertTrue(output.toString(StandardCharsets.UTF_8).contains("--upstream-commit"));
		assertEquals(errors.size(), 0);
	}

	/**
	 * Rejects omitted source commit metadata without writing a manifest.
	 */
	@Test
	public void rejectsMissingCommit()
	{
		ByteArrayOutputStream output = new ByteArrayOutputStream();
		ByteArrayOutputStream errors = new ByteArrayOutputStream();
		try (PrintStream out = new PrintStream(output, true, StandardCharsets.UTF_8);
			PrintStream err = new PrintStream(errors, true, StandardCharsets.UTF_8))
		{
			assertEquals(Main.run(new String[]{"generate-release-manifest", "--release-dir=missing", "--patch-repo=missing",
				"--output=unwritten.json"}, InputStream.nullInputStream(), out, err), 2);
		}
		assertEquals(output.size(), 0);
		assertTrue(errors.toString(StandardCharsets.UTF_8).contains("--upstream-commit"));
	}
}
