package com.holdmyspot.codexunleashed.tooling;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import org.testng.annotations.Test;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.expectThrows;

/**
 * Verifies that terminal CI jobs accept only explicit dependency success.
 */
public final class CiResultsTest
{
	/**
	 * Creates the CI result policy tests.
	 */
	public CiResultsTest()
	{
	}

	/**
	 * Accepts successful dependencies and an empty dependency object.
	 *
	 * @throws IOException if valid fixture JSON cannot be read
	 */
	@Test
	public void acceptsSuccess() throws IOException
	{
		for (String inventory : new String[]{"{}", "{\"build\":{\"result\":\"success\"}}"})
		{
			var output = new ByteArrayOutputStream();
			try (var stream = new PrintStream(output, true, StandardCharsets.UTF_8))
			{
				assertEquals(CiResults.check(inventory, stream), 0);
			}
			assertEquals(output.toString(StandardCharsets.UTF_8),
				"All CI dependencies succeeded." + System.lineSeparator());
		}
	}

	/**
	 * Reports failed, skipped, and cancelled dependencies in job-name order.
	 *
	 * @throws IOException if valid fixture JSON cannot be read
	 */
	@Test
	public void reportsUnsuccessfulDependencies() throws IOException
	{
		var output = new ByteArrayOutputStream();
		try (var stream = new PrintStream(output, true, StandardCharsets.UTF_8))
		{
			assertEquals(CiResults.check("{\"z\":{\"result\":\"failure\"},\"b\":{\"result\":\"cancelled\"}," +
				"\"a\":{\"result\":\"skipped\"},\"ok\":{\"result\":\"success\"}}", stream), 1);
		}
		assertEquals(output.toString(StandardCharsets.UTF_8), String.join(System.lineSeparator(),
			"CI dependencies did not succeed:", "a: skipped", "b: cancelled", "z: failure", ""));
	}

	/**
	 * Rejects missing, null, and non-string statuses without printing success.
	 */
	@Test
	public void rejectsMalformedDependencyInventory()
	{
		for (String inventory : new String[]{"{invalid", "{} {}", "[]", "null", "{\"job\":{}}",
			"{\"job\":{\"result\":null}}", "{\"job\":{\"result\":false}}"})
		{
			var output = new ByteArrayOutputStream();
			try (var stream = new PrintStream(output, true, StandardCharsets.UTF_8))
			{
				expectThrows(IOException.class, () -> CiResults.check(inventory, stream));
			}
			assertEquals(output.size(), 0);
		}
	}
}
