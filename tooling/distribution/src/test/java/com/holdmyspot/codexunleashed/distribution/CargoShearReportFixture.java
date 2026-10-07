package com.holdmyspot.codexunleashed.distribution;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

/**
 * Provides controlled Cargo Shear report output through a real external process.
 */
public final class CargoShearReportFixture
{
	/**
	 * Prevents construction.
	 */
	private CargoShearReportFixture()
	{
	}

	/**
	 * Verifies maintained command arguments and emits the selected report with warning status.
	 *
	 * @param args the Cargo Shear command arguments
	 * @throws IOException if the selected fixture report cannot be read
	 */
	public static void main(String[] args) throws IOException
	{
		if (!Arrays.equals(args, new String[]{"--deny-warnings", "--format=json"}))
			throw new IllegalArgumentException("Cargo Shear received unexpected arguments");
		if (!Files.isRegularFile(Path.of("Cargo.toml")))
			throw new IllegalArgumentException("Cargo Shear received the wrong working directory");
		System.err.print("shear diagnostics\n");
		System.out.print(Files.readString(Path.of(System.getenv("CARGO_SHEAR_TEST_REPORT"))));
		System.exit(1);
	}
}
