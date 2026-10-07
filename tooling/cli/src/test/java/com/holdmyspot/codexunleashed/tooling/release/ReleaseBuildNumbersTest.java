package com.holdmyspot.codexunleashed.tooling.release;

import java.math.BigInteger;
import java.util.List;

import org.testng.annotations.Test;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertThrows;

public final class ReleaseBuildNumbersTest
{
	/**
	 * Creates the build-number selection tests.
	 */
	public ReleaseBuildNumbersTest()
	{
	}

	/**
	 * Counts builds independently for each upstream release.
	 */
	@Test
	public void selectsPerUpstreamRelease()
	{
		List<String> tags = List.of("\u00a0rust-v0.160.0+29\u0085", "rust-v0.159.1+23", " rust-v0.160.0+7\n");
		assertEquals(ReleaseBuildNumbers.next("rust-v0.161.0", tags), BigInteger.ONE);
		assertEquals(ReleaseBuildNumbers.next("rust-v0.160.0", tags), BigInteger.valueOf(30));
	}

	/**
	 * Ignores zero, leading-zero builds, prereleases, and unrelated tags.
	 */
	@Test
	public void ignoresExcludedTags()
	{
		List<String> tags = List.of("rust-v0.160.0", "rust-v0.160.0+0", "rust-v0.160.0+099",
			"rust-v0.160.0+8-alpha", "rust-v0.160.0+-9", "rust-v0.160.0+1+10");
		assertEquals(ReleaseBuildNumbers.next("rust-v0.160.0", tags), BigInteger.ONE);
	}

	/**
	 * Preserves unbounded build numbers.
	 */
	@Test
	public void acceptsLargeBuildNumbers()
	{
		assertEquals(ReleaseBuildNumbers.next("rust-v0.160.0", List.of("rust-v0.160.0+99999999999999999999999")),
			new BigInteger("100000000000000000000000"));
	}

	/**
	 * Retains the upstream tag rule's acceptance of Unicode digits and leading zeroes.
	 */
	@Test
	public void acceptsExistingUpstreamTagForms()
	{
		assertEquals(ReleaseBuildNumbers.next("rust-v٠.١.٢", List.of("rust-v٠.١.٢+3")), BigInteger.valueOf(4));
		assertEquals(ReleaseBuildNumbers.next("rust-v00.01.02", List.of("rust-v00.01.02+3")), BigInteger.valueOf(4));
	}

	/**
	 * Rejects tags outside the upstream release form.
	 */
	@Test
	public void rejectsInvalidUpstreamTag()
	{
		assertThrows(IllegalArgumentException.class, () -> ReleaseBuildNumbers.next("v0.160.0", List.of()));
		assertThrows(IllegalArgumentException.class, () -> ReleaseBuildNumbers.next("rust-v0.160.0+1", List.of()));
	}
}
