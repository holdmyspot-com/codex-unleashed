package com.holdmyspot.codexunleashed.distribution;

import java.util.Arrays;

/** Supplies the native spelling of one controlled MSYS temporary path. */
public final class CygpathFixture
{
	/** The literal shell input, independent of MSYS environment conversion in the native child. */
	static final String MSYS_TEMPORARY_DIRECTORY = "/hosted-msys-temporary";

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
		if (args.length != 2 || !args[0].equals("-m") || !args[1].equals(MSYS_TEMPORARY_DIRECTORY))
			throw new IllegalArgumentException("Expected conversion of " + MSYS_TEMPORARY_DIRECTORY +
				"; received " + Arrays.toString(args));
		System.out.println(System.getenv("FIXTURE_NATIVE_TEMP"));
	}
}
