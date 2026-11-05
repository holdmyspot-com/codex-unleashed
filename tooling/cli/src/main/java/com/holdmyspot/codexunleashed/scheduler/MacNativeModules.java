package com.holdmyspot.codexunleashed.scheduler;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import tools.jackson.core.io.JsonStringEncoder;

/**
 * Locates the precompiled native code that the macOS hook scripts load into each held image.
 * <p>
 * The distribution build compiles each hook script's C source into one dylib in the runtime's {@code lib/codex}
 * directory. A script loads its dylib with {@code new CModule(File.readAllBytes(path), symbols)}, so the held image
 * reads the installed bytes itself and no C source reaches the image.
 *
 * @param descriptorProtection dylib that registers and protects the image's kernel queue descriptors
 * @param spawnSearch          dylib that restores spawn attribute flags across PATH-searched spawns
 * @param cargoTokens          dylib that mediates reads and writes of the Cargo jobserver FIFO
 * @param allocationHook       dylib that replaces malloc to admit allocations against the memory budget
 */
public record MacNativeModules(Path descriptorProtection, Path spawnSearch, Path cargoTokens, Path allocationHook)
{
	/**
	 * Placeholder that each hook script writes where its dylib's path belongs, as a JavaScript string literal.
	 */
	private static final String PLACEHOLDER = "@NATIVE_MODULE@";

	/**
	 * Locates the four installed dylibs.
	 *
	 * @param descriptorProtection dylib that registers and protects the image's kernel queue descriptors
	 * @param spawnSearch          dylib that restores spawn attribute flags across PATH-searched spawns
	 * @param cargoTokens          dylib that mediates reads and writes of the Cargo jobserver FIFO
	 * @param allocationHook       dylib that replaces malloc to admit allocations against the memory budget
	 * @throws NullPointerException     if any path is null
	 * @throws IllegalArgumentException if any path is relative
	 */
	public MacNativeModules
	{
		for (Path path : List.of(descriptorProtection, spawnSearch, cargoTokens, allocationHook))
			if (!path.isAbsolute())
				throw new IllegalArgumentException("Native module path must be absolute: " + path);
	}

	/**
	 * Locates the dylibs that the distribution build installs in one runtime library directory.
	 *
	 * @param libraries absolute runtime {@code lib/codex} directory
	 * @return the four dylib paths inside {@code libraries}
	 * @throws IllegalArgumentException if {@code libraries} is relative
	 */
	public static MacNativeModules in(Path libraries)
	{
		return new MacNativeModules(libraries.resolve("libmac-descriptor-protection.dylib"),
			libraries.resolve("libmac-spawn-search.dylib"), libraries.resolve("libmac-cargo-tokens.dylib"),
			libraries.resolve("libmac-allocation-hook.dylib"));
	}

	/**
	 * Names each dylib for the launch configuration's pinned byte identities.
	 *
	 * @return identity name of each dylib
	 */
	Map<String, Path> files()
	{
		return Map.of("descriptorProtectionModule", descriptorProtection, "spawnSearchModule", spawnSearch,
			"cargoTokensModule", cargoTokens, "allocationHookModule", allocationHook);
	}

	/**
	 * Writes a dylib's path into the hook script that loads it.
	 *
	 * @param resource name of the hook script, for diagnostics
	 * @param script   hook script text containing the placeholder exactly once
	 * @param module   dylib that the script loads
	 * @return the script with the placeholder replaced by the dylib's path as a JavaScript string literal
	 * @throws IllegalStateException if {@code script} does not contain the placeholder exactly once
	 */
	static String bind(String resource, String script, Path module)
	{
		int first = script.indexOf(PLACEHOLDER);
		if (first < 0 || script.indexOf(PLACEHOLDER, first + 1) >= 0)
			throw new IllegalStateException("Hook script must name its native module exactly once: " + resource);
		StringBuilder result = new StringBuilder(script.substring(0, first)).append('"');
		JsonStringEncoder.getInstance().quoteAsString(module.toString(), result);
		return result.append('"').append(script, first + PLACEHOLDER.length(), script.length()).toString();
	}
}
