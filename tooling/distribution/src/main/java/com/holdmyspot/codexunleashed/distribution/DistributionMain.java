package com.holdmyspot.codexunleashed.distribution;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Assembles the tooling module and its dependencies into a bundled Java runtime.
 */
public final class DistributionMain
{
	private static final String TOOLING_MODULE = "com.holdmyspot.codexunleashed.tooling";
	private static final String TOOLING_MAIN = TOOLING_MODULE + ".Main";

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
		Path jlink = Path.of(System.getProperty("java.home"), "bin", "jlink");
		if (!Files.isRegularFile(jlink))
			jlink = jlink.resolveSibling("jlink.exe");
		try (var diagnostics = new JlinkDiagnostics(Files.createTempFile("jlink-", ".log")))
		{
			ProcessBuilder builder = new ProcessBuilder(jlink.toString(),
				"-J-Djava.io.tmpdir=" + System.getProperty("java.io.tmpdir"),
				"--module-path", modules.toString(), "--add-modules", TOOLING_MODULE,
				"--launcher", "codex-tooling=" + TOOLING_MODULE + "/" + TOOLING_MAIN, "--output", output.toString(),
				"--strip-debug", "--no-header-files", "--no-man-pages");
			builder.redirectErrorStream(true).redirectOutput(diagnostics.path().toFile());
			try (Process process = builder.start())
			{
				int result = process.waitFor();
				if (result != 0)
					throw new IOException("jlink failed with status " + result + ": " +
						Files.readString(diagnostics.path()).strip());
			}
		}
		catch (InterruptedException failure)
		{
			Thread.currentThread().interrupt();
			throw new IOException("Interrupted while waiting for jlink", failure);
		}

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

	/**
	 * Owns the linker's captured output until the process result is consumed.
	 *
	 * @param path the owned temporary diagnostics file
	 */
	private record JlinkDiagnostics(Path path) implements AutoCloseable
	{
		/**
		 * Deletes captured output while preserving any primary linking failure.
		 *
		 * @throws IOException if the diagnostics file cannot be removed
		 */
		@Override
		public void close() throws IOException
		{
			Files.delete(path);
		}
	}
}
