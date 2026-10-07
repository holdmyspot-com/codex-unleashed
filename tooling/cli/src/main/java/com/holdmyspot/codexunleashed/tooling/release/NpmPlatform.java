package com.holdmyspot.codexunleashed.tooling.release;

/**
 * Defines the ordered public release targets and their npm platform package suffixes.
 */
public enum NpmPlatform
{
	/** Linux x64 musl binaries. */
	LINUX_X64("x86_64-unknown-linux-musl", "linux-x64"),
	/** Linux arm64 musl binaries. */
	LINUX_ARM64("aarch64-unknown-linux-musl", "linux-arm64"),
	/** Darwin x64 binaries. */
	DARWIN_X64("x86_64-apple-darwin", "darwin-x64"),
	/** Darwin arm64 binaries. */
	DARWIN_ARM64("aarch64-apple-darwin", "darwin-arm64"),
	/** Windows x64 binaries. */
	WINDOWS_X64("x86_64-pc-windows-msvc", "win32-x64"),
	/** Windows arm64 binaries. */
	WINDOWS_ARM64("aarch64-pc-windows-msvc", "win32-arm64");

	private final String target;
	private final String packageSuffix;

	/**
	 * Associates a release target with its npm platform name.
	 *
	 * @param target the release target triple
	 * @param packageSuffix the npm package suffix
	 */
	NpmPlatform(String target, String packageSuffix)
	{
		this.target = target;
		this.packageSuffix = packageSuffix;
	}

	/**
	 * Returns the release target triple.
	 *
	 * @return the target triple
	 */
	public String target()
	{
		return target;
	}

	/**
	 * Returns the platform package suffix.
	 *
	 * @return the npm platform suffix
	 */
	public String packageSuffix()
	{
		return packageSuffix;
	}

	/**
	 * Returns the npm operating-system value.
	 *
	 * @return linux, darwin, or win32
	 */
	public String operatingSystem()
	{
		return packageSuffix.substring(0, packageSuffix.indexOf('-'));
	}

	/**
	 * Returns the npm CPU value.
	 *
	 * @return x64 or arm64
	 */
	public String architecture()
	{
		return packageSuffix.substring(packageSuffix.indexOf('-') + 1);
	}
}
