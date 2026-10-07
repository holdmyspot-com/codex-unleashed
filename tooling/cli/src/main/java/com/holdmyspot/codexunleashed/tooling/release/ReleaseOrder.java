package com.holdmyspot.codexunleashed.tooling.release;

import java.math.BigInteger;

/**
 * Compares stable release versions including the downstream numeric vendor build.
 */
public final class ReleaseOrder
{
	/**
	 * Prevents construction.
	 */
	private ReleaseOrder()
	{
	}

	/**
	 * Compares upstream components first and the vendor build second; an absent build has value zero.
	 *
	 * @param left the stable version with an optional plus-separated numeric vendor build
	 * @param right the stable version with an optional plus-separated numeric vendor build
	 * @return negative, zero, or positive when the left version is older, equal, or newer
	 * @throws IllegalArgumentException if either version is unsupported
	 * @throws NullPointerException if either version is null
	 */
	public static int compareVersions(String left, String right)
	{
		String[] leftParts = NpmVersions.toNpmVersion(left).replace('-', '.').split("\\.");
		String[] rightParts = NpmVersions.toNpmVersion(right).replace('-', '.').split("\\.");
		for (int index = 0; index < 4; ++index)
		{
			BigInteger leftNumber = BigInteger.ZERO;
			BigInteger rightNumber = BigInteger.ZERO;
			if (index < leftParts.length)
				leftNumber = new BigInteger(leftParts[index]);
			if (index < rightParts.length)
				rightNumber = new BigInteger(rightParts[index]);
			int comparison = leftNumber.compareTo(rightNumber);
			if (comparison != 0)
				return comparison;
		}
		return 0;
	}
}
