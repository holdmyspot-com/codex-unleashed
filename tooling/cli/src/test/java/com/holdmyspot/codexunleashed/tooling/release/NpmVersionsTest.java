package com.holdmyspot.codexunleashed.tooling.release;

import org.testng.annotations.Test;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.expectThrows;

/**
 * Verifies exact release prefixes and vendor version spelling at the npm publication boundary.
 */
public final class NpmVersionsTest
{
	/**
	 * Creates version tests.
	 */
	public NpmVersionsTest()
	{
	}

	/**
	 * Retains stable versions and replaces only the vendor build separator.
	 */
	@Test
	public void convertsVendorBuildSeparator()
	{
		assertEquals(NpmVersions.toNpmVersion("0.160.0"), "0.160.0");
		assertEquals(NpmVersions.toNpmVersion("0.160.0+34"), "0.160.0-34");
		assertEquals(NpmVersions.toNpmVersion("00.0160.000+000"), "00.0160.000-000");
		assertEquals(NpmVersions.toNpmVersion("0.160.0+999999999999999999999999999999999999"),
			"0.160.0-999999999999999999999999999999999999");
	}

	/**
	 * Preserves Unicode decimal base digits and requires ASCII vendor build digits.
	 */
	@Test
	public void preservesRequiredDigitForms()
	{
		assertEquals(NpmVersions.toNpmVersion("٠.١٦٠.０+34"), "٠.١٦٠.０-34");
		for (String version : new String[]{"0.160.0+٣٤", "0.160.0+３４", "0.160.0+", "0.160.0-dev", "0.160.0-34",
			"0.160.0+1.2", "0.160.0\n", " 0.160.0", "0.160.0 ", "0.160", "0.160.0.12345", "0.².0", ""})
			expectThrows(IllegalArgumentException.class, () -> NpmVersions.toNpmVersion(version));
	}

	/**
	 * Removes only an exact supported prefix without normalizing the remaining release text.
	 */
	@Test
	public void removesExactTagPrefix()
	{
		assertEquals(NpmVersions.removeTagPrefix("rust-v0.160.0+34"), "0.160.0+34");
		assertEquals(NpmVersions.removeTagPrefix("v0.160.0"), "0.160.0");
		assertEquals(NpmVersions.removeTagPrefix("v"), "");
		assertEquals(NpmVersions.removeTagPrefix("rust-v 0.160.0"), " 0.160.0");
		for (String tag : new String[]{"0.160.0", "Rust-v0.160.0", "V0.160.0", " rust-v0.160.0", ""})
			expectThrows(IllegalArgumentException.class, () -> NpmVersions.removeTagPrefix(tag));
	}
}
