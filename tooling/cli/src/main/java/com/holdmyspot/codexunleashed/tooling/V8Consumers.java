package com.holdmyspot.codexunleashed.tooling;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Checks the retained literal V8 consumer selectors against the resolved crate version. */
public final class V8Consumers
{
	private static final List<String> TARGETS = List.of("aarch64_apple_darwin", "aarch64_pc_windows_gnullvm",
		"aarch64_pc_windows_msvc", "aarch64_unknown_linux_gnu", "aarch64_unknown_linux_musl",
		"x86_64_apple_darwin", "x86_64_pc_windows_gnullvm", "x86_64_pc_windows_msvc",
		"x86_64_unknown_linux_gnu", "x86_64_unknown_linux_musl");

	/** Prevents construction. */
	private V8Consumers()
	{
	}

	/**
	 * Reads the checkout's authoritative crate version and checks its consumer source without changing files.
	 *
	 * @param checkout explicit source checkout
	 * @return the resolved dotted V8 version
	 * @throws NullPointerException if the checkout is null
	 * @throws IOException if source/version reading or selector validation fails
	 */
	public static String checkCheckout(Path checkout) throws IOException
	{
		Objects.requireNonNull(checkout, "checkout");
		String version = V8Versions.resolve(checkout);
		check(Files.readString(checkout.resolve("third_party/v8/BUILD.bazel")), version);
		return version;
	}

	/**
	 * Requires every retained library and binding selector substring, including selectors in raw source comments.
	 *
	 * @param source raw BUILD.bazel text
	 * @param version authoritative dotted crate version
	 * @throws NullPointerException if either argument is null
	 * @throws IOException if one or more selectors are absent
	 */
	public static void check(String source, String version) throws IOException
	{
		Objects.requireNonNull(source, "source");
		Objects.requireNonNull(version, "version");
		String suffix = version.replace('.', '_');
		List<String> missing = new ArrayList<>();
		for (String target : TARGETS)
		{
			String selector = ":v8_" + suffix + "_" + librarySuffix(target);
			if (!source.contains(selector))
				missing.add(selector);
		}
		for (String target : TARGETS)
		{
			String selector = ":src_binding_release_" + target + "_" + suffix + "_release";
			if (!source.contains(selector))
				missing.add(selector);
		}
		if (!missing.isEmpty())
			throw new IOException("BUILD.bazel is missing resolved V8 consumer selectors:\n- " +
				String.join("\n- ", missing));
	}

	/**
	 * Selects the exact retained library label suffix for a known target.
	 *
	 * @param target consumer target identifier
	 * @return library label suffix
	 * @throws IllegalStateException if the private target table contains an unsupported identifier
	 */
	private static String librarySuffix(String target)
	{
		return switch (target)
		{
			case "aarch64_apple_darwin", "aarch64_unknown_linux_gnu", "x86_64_apple_darwin",
				"x86_64_unknown_linux_gnu" -> target + "_bazel";
			case "aarch64_unknown_linux_musl" -> target + "_release_base";
			case "x86_64_unknown_linux_musl" -> target + "_release";
			case "aarch64_pc_windows_gnullvm", "aarch64_pc_windows_msvc", "x86_64_pc_windows_gnullvm",
				"x86_64_pc_windows_msvc" -> target;
			default -> throw new IllegalStateException("Unsupported V8 consumer target: " + target);
		};
	}
}
