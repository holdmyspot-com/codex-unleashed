package com.holdmyspot.codexunleashed.tooling.test;

import com.holdmyspot.codexunleashed.tooling.BlobSizePolicy;
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import org.testng.annotations.Test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

/**
 * Checks the size threshold independently of Git interaction.
 */
public final class BlobSizePolicyTest
{
	/**
	 * Creates policy checks.
	 */
	public BlobSizePolicyTest()
	{
	}

	/**
	 * Accepts a blob at the threshold and ignores larger non-blob objects.
	 *
	 * @throws IOException if the test metadata cannot be read
	 */
	@Test
	public void acceptsLimitAndIgnoresNonBlobs() throws IOException
	{
		ByteArrayOutputStream diagnostics = new ByteArrayOutputStream();
		try (PrintStream err = new PrintStream(diagnostics, true, StandardCharsets.UTF_8))
		{
			int status = BlobSizePolicy.check(records("a blob 512000", "b tree 900000"), err);
			requireThat(status, "status").isEqualTo(0);
			requireThat(diagnostics.toString(StandardCharsets.UTF_8), "diagnostics").isEmpty();
		}
	}

	/**
	 * Rejects every blob above the threshold and includes actionable object identifiers.
	 *
	 * @throws IOException if the test metadata cannot be read
	 */
	@Test
	public void rejectsOversizedBlobWithRecoveryCommand() throws IOException
	{
		ByteArrayOutputStream diagnostics = new ByteArrayOutputStream();
		try (PrintStream err = new PrintStream(diagnostics, true, StandardCharsets.UTF_8))
		{
			int status = BlobSizePolicy.check(records("c blob 512001"), err);
			requireThat(status, "status").isEqualTo(1);
			requireThat(diagnostics.toString(StandardCharsets.UTF_8), "diagnostics").
				contains("c").contains("512001").contains("git log --find-object=c");
		}
	}

	/**
	 * Rejects malformed batch-check output as an I/O failure.
	 *
	 * @throws IOException when the metadata reader fails
	 */
	@Test
	public void rejectsMalformedRecord() throws IOException
	{
		ByteArrayOutputStream diagnostics = new ByteArrayOutputStream();
		try (PrintStream err = new PrintStream(diagnostics, true, StandardCharsets.UTF_8))
		{
			try
			{
				BlobSizePolicy.check(records("not a batch record"), err);
				requireThat(false, "malformedMetadata").isTrue();
			}
			catch (IOException expected)
			{
				requireThat(expected.getMessage(), "message").contains("Malformed Git batch-check record");
			}
		}
	}

	/**
	 * Creates a reader over supplied Git metadata lines.
	 *
	 * @param lines metadata records
	 * @return a reader containing the supplied records
	 */
	private static BufferedReader records(String... lines)
	{
		return new BufferedReader(new StringReader(String.join("\n", lines)));
	}
}
