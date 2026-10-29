package com.holdmyspot.codexunleashed.tooling;

import java.nio.file.Path;

/**
 * Selects the Cargo output profile and confines freshness snapshots to that profile.
 */
public enum CargoCacheProfile
{
	/**
	 * Default optimized artifacts and release freshness snapshots.
	 */
	RELEASE,
	/**
	 * Native runtime-test artifacts and independent ci-test freshness snapshots.
	 */
	CI_TEST;

	/**
	 * Parses the profiles supported by the cache command.
	 *
	 * @param name the exact Cargo profile name, either release or ci-test
	 * @return the selected profile
	 * @throws NullPointerException if {@code name} is null
	 * @throws IllegalArgumentException if {@code name} is unsupported
	 */
	public static CargoCacheProfile parse(String name)
	{
		return switch (name)
		{
			case "release" -> RELEASE;
			case "ci-test" -> CI_TEST;
			default -> throw new IllegalArgumentException("Unsupported Cargo cache profile: " + name +
				"; expected release or ci-test");
		};
	}

	/**
	 * Supplies the profile name used by Cargo and its output directory.
	 *
	 * @return release or ci-test
	 */
	public String cargoName()
	{
		return switch (this)
		{
			case RELEASE -> "release";
			case CI_TEST -> "ci-test";
		};
	}

	/**
	 * Locates this profile's freshness records.
	 *
	 * @param targetDirectory Cargo's compiled-output root
	 * @return {@code targetDirectory/.codex-source-inputs} for release, or its {@code ci-test} child for ci-test
	 * @throws NullPointerException if {@code targetDirectory} is null
	 */
	public Path snapshotDirectory(Path targetDirectory)
	{
		Path snapshots = targetDirectory.resolve(".codex-source-inputs");
		return switch (this)
		{
			case RELEASE -> snapshots;
			case CI_TEST -> snapshots.resolve(cargoName());
		};
	}
}
