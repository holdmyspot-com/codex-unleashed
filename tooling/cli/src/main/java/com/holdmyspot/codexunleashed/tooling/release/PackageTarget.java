package com.holdmyspot.codexunleashed.tooling.release;

import java.util.Arrays;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Defines supported package targets and their resource-platform identities.
 */
public enum PackageTarget
{
	/** Linux x86-64 with GNU libraries. */
	LINUX_X86_GNU("x86_64-unknown-linux-gnu", "linux-x86_64", true, false),
	/** Linux x86-64 with musl libraries. */
	LINUX_X86_MUSL("x86_64-unknown-linux-musl", "linux-x86_64", true, false),
	/** Linux ARM64 with GNU libraries. */
	LINUX_ARM_GNU("aarch64-unknown-linux-gnu", "linux-aarch64", true, false),
	/** Linux ARM64 with musl libraries. */
	LINUX_ARM_MUSL("aarch64-unknown-linux-musl", "linux-aarch64", true, false),
	/** macOS x86-64. */
	MACOS_X86("x86_64-apple-darwin", "macos-x86_64", false, false),
	/** macOS ARM64. */
	MACOS_ARM("aarch64-apple-darwin", "macos-aarch64", false, false),
	/** Windows x86-64 with MSVC. */
	WINDOWS_X86("x86_64-pc-windows-msvc", "windows-x86_64", false, true),
	/** Windows ARM64 with MSVC. */
	WINDOWS_ARM("aarch64-pc-windows-msvc", "windows-aarch64", false, true);

	private static final Map<String, PackageTarget> HOST_TARGETS = Map.of(
		"darwin/aarch64", MACOS_ARM, "darwin/x86_64", MACOS_X86,
		"linux/aarch64", LINUX_ARM_MUSL, "linux/x86_64", LINUX_X86_MUSL,
		"windows/aarch64", WINDOWS_ARM, "windows/x86_64", WINDOWS_X86);
	private final String triple;
	private final String dotslashPlatform;
	private final boolean linux;
	private final boolean windows;

	/**
	 * Assigns one supported target's identities and resource requirements.
	 *
	 * @param triple the Rust target triple
	 * @param dotslashPlatform the DotSlash resource platform
	 * @param linux indicates Linux resource requirements
	 * @param windows indicates Windows resource requirements
	 */
	PackageTarget(String triple, String dotslashPlatform, boolean linux, boolean windows)
	{
		this.triple = triple;
		this.dotslashPlatform = dotslashPlatform;
		this.linux = linux;
		this.windows = windows;
	}

	/**
	 * Resolves an exact supported target triple.
	 *
	 * @param triple the case-sensitive target triple
	 * @return the supported target
	 * @throws NullPointerException if {@code triple} is null
	 * @throws IllegalArgumentException if the target is unsupported
	 */
	public static PackageTarget fromTriple(String triple)
	{
		Objects.requireNonNull(triple, "triple");
		return Arrays.stream(values()).filter(target -> target.triple.equals(triple)).findFirst().
			orElseThrow(() -> new IllegalArgumentException("Unsupported package target: " + triple));
	}

	/**
	 * Selects the release default for a supplied host system and architecture.
	 *
	 * @param system the host system name, such as Linux, Darwin, or Windows
	 * @param machine the host architecture, including amd64 and arm64 aliases
	 * @return the host release target
	 * @throws NullPointerException if either argument is null
	 * @throws IllegalArgumentException if the host is unsupported
	 */
	public static PackageTarget forHost(String system, String machine)
	{
		Objects.requireNonNull(system, "system");
		Objects.requireNonNull(machine, "machine");
		String architecture = switch (machine.toLowerCase(Locale.ROOT))
		{
			case "amd64", "x86_64" -> "x86_64";
			case "aarch64", "arm64" -> "aarch64";
			default -> machine.toLowerCase(Locale.ROOT);
		};
		PackageTarget result = HOST_TARGETS.get(system.toLowerCase(Locale.ROOT) + "/" + architecture);
		if (result == null)
			throw new IllegalArgumentException("Unsupported host platform " + system + "/" + machine +
				". Pass --target explicitly. Supported targets: " + String.join(", ",
				Arrays.stream(values()).map(PackageTarget::triple).sorted().toList()));
		return result;
	}

	/**
	 * Returns the Rust target triple.
	 *
	 * @return the target triple
	 */
	public String triple()
	{
		return triple;
	}

	/**
	 * Returns the resource platform identity.
	 *
	 * @return the DotSlash platform
	 */
	public String dotslashPlatform()
	{
		return dotslashPlatform;
	}

	/**
	 * Identifies a Linux target.
	 *
	 * @return true for Linux targets
	 */
	public boolean isLinux()
	{
		return linux;
	}

	/**
	 * Identifies a Windows target.
	 *
	 * @return true for Windows targets
	 */
	public boolean isWindows()
	{
		return windows;
	}

	/**
	 * Returns the target's executable suffix.
	 *
	 * @return .exe on Windows or an empty suffix on Unix targets
	 */
	public String executableSuffix()
	{
		if (windows)
			return ".exe";
		return "";
	}

	/**
	 * Returns the packaged ripgrep executable name.
	 *
	 * @return the target-specific executable name
	 */
	public String ripgrepName()
	{
		return "rg" + executableSuffix();
	}
}
