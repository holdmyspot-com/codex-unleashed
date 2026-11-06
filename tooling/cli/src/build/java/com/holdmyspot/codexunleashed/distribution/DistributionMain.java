package com.holdmyspot.codexunleashed.distribution;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Assembles the tooling module and its dependencies into a bundled Java runtime.
 */
public final class DistributionMain
{
	/** Runtime service provider selected explicitly for the bundled logging implementation. */
	private static final String LOGGING_MODULE = "ch.qos.logback.classic";
	/**
	 * Application module that jlink retains and both launchers execute.
	 */
	private static final String TOOLING_MODULE = "com.holdmyspot.codexunleashed.tooling";
	/**
	 * Command entry point executed by the bundled tooling launcher.
	 */
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
	 * @param args the module directory followed by the new runtime directory
	 * @throws IllegalArgumentException if the argument count is invalid or the output already exists
	 * @throws IOException if linking or writing the image fails
	 */
	public static void main(String[] args) throws IOException
	{
		if (args.length != 2)
			throw new IllegalArgumentException("Expected the module directory and the new runtime directory");
		Path modules = Path.of(args[0]).toAbsolutePath().normalize();
		Path output = Path.of(args[1]).toAbsolutePath().normalize();
		if (Files.exists(output))
			throw new IllegalArgumentException("Runtime output already exists: " + output);
		modularizeToml(modules);
		Path jlink = Path.of(System.getProperty("java.home"), "bin", "jlink");
		if (!Files.isRegularFile(jlink))
			jlink = jlink.resolveSibling("jlink.exe");
		try (var diagnostics = new JlinkDiagnostics(Files.createTempFile("jlink-", ".log")))
		{
			ProcessBuilder builder = new ProcessBuilder(jlink.toString(),
				"-J-Djava.io.tmpdir=" + System.getProperty("java.io.tmpdir"),
				"--module-path", modules.toString(), "--add-modules", TOOLING_MODULE + "," + LOGGING_MODULE,
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

		String normalizeLauncher = "";
		if (File.separatorChar == '\\')
			normalizeLauncher = "LAUNCHER=${LAUNCHER//\\\\//}\n";
		String launcher = """
			#!/bin/sh
			set -eu
			LAUNCHER=$0
			%sDIR=$(CDPATH= cd -- "${LAUNCHER%%/*}" && pwd)
			JAVA_TEMP=${TMPDIR:-/tmp}
			if command -v cygpath >/dev/null 2>&1; then
				JAVA_TEMP=$(cygpath -m "$JAVA_TEMP")
			fi
			exec "$DIR/java" "-Djava.io.tmpdir=$JAVA_TEMP" -Xlog:all=off:stdout -Xlog:all=warning:stderr \
				-Djdk.lang.Process.allowAmbiguousCommands=true --enable-native-access=%s -m %s/%s "$@"
			""".formatted(normalizeLauncher, TOOLING_MODULE, TOOLING_MODULE, TOOLING_MAIN);
		Files.writeString(output.resolve("bin/codex-tooling"), launcher);
		Files.createFile(output.resolve(".complete"));
	}

    /**
     * Adds JDK-generated module descriptors only to the two pinned staged TOML implementation artifacts.
     * Original classes and resources remain unchanged; dependency source and repository artifacts are untouched.
     * Checker annotations remain compile-time metadata and are not a required linked runtime module.
     * @param modules exclusively owned runtime staging directory
     * @throws IOException if descriptor generation, compilation or publication fails
     */
    public static void modularizeToml(Path modules) throws IOException
    {
        Path toml = modules.resolve("tomlj-1.1.1.jar");
        Path antlr = modules.resolve("antlr4-runtime-4.11.1.jar");
        if (!Files.isRegularFile(toml) || !Files.isRegularFile(antlr))
            throw new IOException("The staged pinned TOML parser and ANTLR runtime are required");
        boolean tomlAutomatic = java.lang.module.ModuleFinder.of(toml).findAll().iterator().next().descriptor().isAutomatic();
        boolean antlrAutomatic = java.lang.module.ModuleFinder.of(antlr).findAll().iterator().next().descriptor().isAutomatic();
        if (!tomlAutomatic && !antlrAutomatic) return;
        if (tomlAutomatic != antlrAutomatic) throw new IOException("Incomplete staged TOML module publication");
        try (PackagingDirectory scratch = new PackagingDirectory(Files.createTempDirectory(modules.getParent(), "toml-modules-")))
        {
            Path generated = scratch.path().resolve("source");
            runPackagingTool(scratch.path(), "jdeps", "--generate-module-info", generated.toString(),
                "--module-path", modules.resolve("checker-qual-3.21.2.jar").toString(), toml.toString(), antlr.toString());
            Path compiled = Files.createDirectory(scratch.path().resolve("classes"));
            runPackagingTool(scratch.path(), "javac", "--release", "27", "-Xlint:all",
                "--module-source-path", generated.toString(),
                "--patch-module", "org.tomlj=" + toml, "--patch-module", "org.antlr.antlr4.runtime=" + antlr,
                "-d", compiled.toString(), generated.resolve("org.tomlj/module-info.java").toString(),
                generated.resolve("org.antlr.antlr4.runtime/module-info.java").toString());
            for (var entry : Map.of("org.tomlj", toml, "org.antlr.antlr4.runtime", antlr).entrySet())
            {
                Path classes = compiled.resolve(entry.getKey());
                Path pending = scratch.path().resolve(entry.getValue().getFileName());
                Files.copy(entry.getValue(), pending);
                runPackagingTool(scratch.path(), "jar", "--update", "--file", pending.toString(),
                    "-C", classes.toString(), "module-info.class");
            }
            // Publish after both complete JAR copies exist; uncertain partial publication fails closed on a retry.
            for (Path original : List.of(toml, antlr))
                Files.move(scratch.path().resolve(original.getFileName()), original,
                    StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        }
    }

    /**
     * Runs an installed JDK packaging operation and retains its actual diagnostic on failure.
     * @param directory exclusively owned diagnostic directory
     * @param tool JDK executable name
     * @param arguments exact tool arguments
     * @throws IOException if execution or result collection fails
     */
    private static void runPackagingTool(Path directory, String tool, String... arguments) throws IOException
    {
        Path executable = Path.of(System.getProperty("java.home"), "bin",
            tool + (File.separatorChar == 92 ? ".exe" : ""));
        List<String> command = new ArrayList<>(); command.add(executable.toString()); command.addAll(List.of(arguments));
        try (JlinkDiagnostics diagnostic = new JlinkDiagnostics(Files.createTempFile(directory, tool + "-", ".log")))
        {
            ProcessBuilder builder = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(diagnostic.path().toFile());
            try (Process child = builder.start())
            {
                int status = child.waitFor();
                if (status != 0) throw new IOException(tool + " failed with status " + status + ": " + Files.readString(diagnostic.path()));
                String output = Files.readString(diagnostic.path());
                if (!output.isEmpty()) System.err.print(output);
            }
        }
        catch (InterruptedException failure)
        {
            Thread.currentThread().interrupt(); throw new IOException("Interrupted " + tool + " packaging", failure);
        }
    }

	/**
	 * Owns a packaging staging tree, including incomplete output after a failed tool invocation.
	 *
	 * @param path the exclusively owned staging directory
	 */
	private record PackagingDirectory(Path path) implements AutoCloseable
	{
		/**
		 * Removes the staging tree while preserving a primary packaging failure through suppression.
		 *
		 * @throws IOException if staging cleanup fails
		 */
		@Override
		public void close() throws IOException
		{
			try (Stream<Path> paths = Files.walk(path))
			{
				for (Path entry : paths.sorted(Comparator.reverseOrder()).toList())
					Files.delete(entry);
			}
		}
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
