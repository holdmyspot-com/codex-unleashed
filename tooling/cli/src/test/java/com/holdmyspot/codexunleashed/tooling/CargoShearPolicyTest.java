package com.holdmyspot.codexunleashed.tooling;

import java.io.IOException;
import org.testng.annotations.Test;
import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

/**
 * Verifies that Cargo Shear accepts only reports without warnings or errors.
 */
public final class CargoShearPolicyTest
{
	/**
	 * Creates the policy tests.
	 */
	public CargoShearPolicyTest()
	{
	}

	/**
	 * Rejects warnings regardless of whether they are known upstream.
	 *
	 * @throws IOException if a fixture report cannot be parsed
	 */
	@Test
	public void rejectsKnownWarnings() throws IOException
	{
		String finding = "{\"code\":\"shear/unlinked_files\",\"severity\":\"warning\",\"message\":\"known orphan\"}";
		String report = "{\"summary\":{\"errors\":0,\"warnings\":1},\"findings\":[" + finding + "]}";
		requireThat(CargoShearPolicy.accepts(report), "acceptsKnownWarning").isFalse();
		String newWarningReport = report.replace("known orphan", "new orphan");
		requireThat(CargoShearPolicy.accepts(newWarningReport), "acceptsNewWarning").isFalse();
	}

	/**
	 * Rejects every error and new warning while accepting a clean report on any source commit.
	 *
	 * @throws IOException if a fixture report cannot be parsed
	 */
	@Test
	public void rejectsErrorsAndUnverifiedWarnings() throws IOException
	{
		requireThat(CargoShearPolicy.accepts(
			"{\"summary\":{\"errors\":1,\"warnings\":0},\"findings\":[]}"), "acceptsError").isFalse();
		requireThat(CargoShearPolicy.accepts(
			"{\"summary\":{\"errors\":0,\"warnings\":1},\"findings\":[{\"severity\":\"warning\"}]}"),
			"acceptsWarningFinding").isFalse();
		requireThat(CargoShearPolicy.accepts(
			"{\"summary\":{\"errors\":0,\"warnings\":0},\"findings\":[]}"), "acceptsCleanReport").isTrue();
		requireThat(CargoShearPolicy.accepts(
			"{\"summary\":{\"errors\":0,\"warnings\":1},\"findings\":[]}"), "acceptsWarningSummary").isFalse();
	}
}
