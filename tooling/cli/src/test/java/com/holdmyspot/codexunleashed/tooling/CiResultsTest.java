package com.holdmyspot.codexunleashed.tooling;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

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
				requireThat(CiResults.check(inventory, stream), "status").isEqualTo(0);
			}
			requireThat(output.toString(StandardCharsets.UTF_8), "output").isEqualTo(
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
			requireThat(CiResults.check("{\"z\":{\"result\":\"failure\"},\"b\":{\"result\":\"cancelled\"}," +
				"\"a\":{\"result\":\"skipped\"},\"ok\":{\"result\":\"success\"}}", stream), "status").isEqualTo(1);
		}
		requireThat(output.toString(StandardCharsets.UTF_8), "output").isEqualTo(String.join(System.lineSeparator(),
			"CI dependencies did not succeed:", "a: skipped", "b: cancelled", "z: failure", ""));
	}

	/**
	 * Rejects missing, null, and non-string statuses without printing success.
	 *
	 * @param inventory malformed dependency inventory
	 * @throws IOException if the inventory is rejected as expected
	 */
	@Test(dataProvider = "malformedDependencyInventories", expectedExceptions = IOException.class)
	public void rejectsMalformedDependencyInventory(String inventory) throws IOException
	{
		var output = new ByteArrayOutputStream();
		try (var stream = new PrintStream(output, true, StandardCharsets.UTF_8))
		{
			CiResults.check(inventory, stream);
		}
		finally
		{
			requireThat(output.size(), "outputSize").isEqualTo(0);
		}
	}

	/**
	 * Supplies independent malformed inventories for the rejection test.
	 *
	 * @return one inventory per invocation
	 */
	@DataProvider
	public Object[][] malformedDependencyInventories()
	{
		return new Object[][]{{"{invalid"}, {"{} {}"}, {"[]"}, {"null"}, {"{\"job\":{}}"},
			{"{\"job\":{\"result\":null}}"}, {"{\"job\":{\"result\":false}}"}};
	}
}
