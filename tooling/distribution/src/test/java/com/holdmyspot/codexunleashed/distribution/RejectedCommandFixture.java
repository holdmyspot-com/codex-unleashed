package com.holdmyspot.codexunleashed.distribution;

/**
 * Rejects unexpected external commands from isolated workflow fixtures.
 */
public final class RejectedCommandFixture
{
	/**
	 * Prevents construction.
	 */
	private RejectedCommandFixture()
	{
	}

	/**
	 * Reports the prohibited invocation and exits unsuccessfully.
	 *
	 * @param args the attempted command arguments
	 */
	public static void main(String[] args)
	{
		System.err.println("Unexpected external command in workflow fixture: " + String.join(" ", args));
		System.exit(91);
	}
}
