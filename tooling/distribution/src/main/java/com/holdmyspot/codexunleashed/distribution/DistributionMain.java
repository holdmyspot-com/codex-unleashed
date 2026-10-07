package com.holdmyspot.codexunleashed.distribution;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.spi.ToolProvider;

/**
 * Assembles the tooling module and its dependencies into a bundled Java runtime.
 */
public final class DistributionMain
{
	private static final String TOOLING_MODULE = "com.holdmyspot.codexunleashed.tooling";
	private static final String TOOLING_MAIN = TOOLING_MODULE + ".Main";
	private static final Object JLINK_LOCK = new Object();

	/**
	 * Prevents construction.
	 */
	private DistributionMain()
	{
	}

	/**
	 * Builds a runtime image from the packaged tooling modules.
	 *
	 * @param args the module directory followed by the new output directory
	 * @throws IllegalArgumentException if the argument count is invalid or the output already exists
	 * @throws IOException if linking or writing the image fails
	 */
	public static void main(String[] args) throws IOException
	{
		if (args.length != 2)
			throw new IllegalArgumentException("Expected the module directory and new output directory");
		Path modules = Path.of(args[0]).toAbsolutePath();
		Path output = Path.of(args[1]).toAbsolutePath();
		if (Files.exists(output))
			throw new IllegalArgumentException("Runtime output already exists: " + output);
		ToolProvider jlink = ToolProvider.findFirst("jlink").
			orElseThrow(() -> new IllegalStateException("The JDK does not provide jlink"));
		var diagnostics = new StringWriter();
		int result;
		try (var writer = new PrintWriter(diagnostics))
		{
			// WORKAROUND: https://bugs.openjdk.org/browse/JDK-8390505
			// Serializes jlink's shared option/plugin state until the JDK includes the fix.
			synchronized (JLINK_LOCK)
			{
				result = jlink.run(writer, writer, "--module-path", modules.toString(), "--add-modules", TOOLING_MODULE,
					"--launcher", "codex-tooling=" + TOOLING_MODULE + "/" + TOOLING_MAIN, "--output", output.toString(),
					"--strip-debug", "--no-header-files", "--no-man-pages");
			}
		}
		if (result != 0)
			throw new IOException("jlink failed with status " + result + ": " + diagnostics.toString().strip());

		String launcher = """
			#!/bin/sh
			set -eu
			DIR=$(CDPATH= cd -- "${0%%/*}" && pwd)
			exec "$DIR/java" "-Djava.io.tmpdir=${TMPDIR:-/tmp}" -Xlog:all=off:stdout -Xlog:all=warning:stderr \
				--enable-native-access=%s -m %s/%s "$@"
			""".formatted(TOOLING_MODULE, TOOLING_MODULE, TOOLING_MAIN);
		Files.writeString(output.resolve("bin/codex-tooling"), launcher);
		Files.createFile(output.resolve(".complete"));
	}
}
