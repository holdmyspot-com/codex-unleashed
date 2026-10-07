package com.holdmyspot.codexunleashed.tooling;

import java.io.IOException;
import org.testng.annotations.Test;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertTrue;

/**
 * Verifies that only unchanged, verified release warnings pass Cargo Shear policy.
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
	 * Rejects changed release identities, changed sources, and new warning content.
	 *
	 * @throws IOException if a fixture report cannot be parsed
	 */
	@Test
	public void acceptsOnlyUnchangedVerifiedWarnings() throws IOException
	{
		String finding = "{\"code\":\"shear/unlinked_files\",\"severity\":\"warning\",\"message\":\"known orphan\"}";
		String baseline = "{\"source_sha\":\"" + "a".repeat(40) + "\",\"findings\":[" + finding + "]}";
		String report = "{\"summary\":{\"errors\":0,\"warnings\":1},\"findings\":[" + finding + "]}";
		assertTrue(CargoShearPolicy.accepts(report, baseline, "a".repeat(40), false));
		assertFalse(CargoShearPolicy.accepts(report, baseline, "b".repeat(40), false));
		assertFalse(CargoShearPolicy.accepts(report, baseline, "a".repeat(40), true));
		assertFalse(CargoShearPolicy.accepts(report.replace("known orphan", "new orphan"), baseline,
			"a".repeat(40), false));
	}

	/**
	 * Rejects every error and new warning while accepting a clean report on any source commit.
	 *
	 * @throws IOException if a fixture report cannot be parsed
	 */
	@Test
	public void rejectsErrorsAndUnverifiedWarnings() throws IOException
	{
		String baseline = "{\"source_sha\":\"" + "a".repeat(40) + "\",\"findings\":[]}";
		assertFalse(CargoShearPolicy.accepts(
			"{\"summary\":{\"errors\":1,\"warnings\":0},\"findings\":[]}", baseline, "a".repeat(40), false));
		assertFalse(CargoShearPolicy.accepts(
			"{\"summary\":{\"errors\":0,\"warnings\":1},\"findings\":[{\"severity\":\"warning\"}]}",
			baseline, "a".repeat(40), false));
		assertTrue(CargoShearPolicy.accepts(
			"{\"summary\":{\"errors\":0,\"warnings\":0},\"findings\":[]}", baseline, "b".repeat(40), false));
		assertFalse(CargoShearPolicy.accepts(
			"{\"summary\":{\"errors\":0,\"warnings\":1},\"findings\":[]}", baseline, "a".repeat(40), false));
	}
}
