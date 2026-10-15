package com.holdmyspot.codexunleashed.distribution;

/** Supplies the native spelling of one controlled MSYS temporary path. */
public final class CygpathFixture
{
	/** Prevents construction. */
	private CygpathFixture()
	{
	}

	/**
	 * Converts the fixture's configured temporary directory.
	 *
	 * @param args the output-format option and MSYS path
	 * @throws IllegalArgumentException if the conversion request differs from the fixture
	 */
	public static void main(String[] args)
	{
		if (args.length != 2 || !args[0].equals("-m") || !args[1].equals(System.getenv("TMPDIR")))
			throw new IllegalArgumentException("Expected conversion of the configured MSYS temporary directory");
		System.out.println(System.getenv("FIXTURE_NATIVE_TEMP"));
	}
}
