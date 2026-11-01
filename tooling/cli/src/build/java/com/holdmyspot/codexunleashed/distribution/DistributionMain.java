package com.holdmyspot.codexunleashed.distribution;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

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
	 * @param args the module directory, new output directory, and optional native source directory;
	 *             the native source directory is required on Linux and macOS. Linux ARM64 may instead specify
	 *             {@code --bazel-backend <prebuilt-directory>} as the third and fourth arguments
	 * @throws IllegalArgumentException if the argument count is invalid or the output already exists
	 * @throws IOException if linking or writing the image fails
	 */
	public static void main(String[] args) throws IOException
	{
		boolean bazelBackend = args.length == 4 && args[2].equals("--bazel-backend");
		if (args.length < 2 || (args.length > 3 && !bazelBackend))
			throw new IllegalArgumentException("Expected modules, runtime output, optional native source directory or --bazel-backend directory");
		if (bazelBackend && (!System.getProperty("os.name").equals("Linux") ||
			!System.getProperty("os.arch").equals("aarch64")))
			throw new UnsupportedOperationException("The optional Bazel backend distribution requires native Linux ARM64");
		boolean mac = System.getProperty("os.name").equals("Mac OS X");
		boolean linux = System.getProperty("os.name").equals("Linux");
		if (linux && !bazelBackend && args.length != 3)
			throw new IllegalArgumentException("Linux packaging requires the native source directory");
		if (mac && args.length != 3)
			throw new IllegalArgumentException("macOS packaging requires the native source directory");
		Path modules = Path.of(args[0]).toAbsolutePath();
		Path output = Path.of(args[1]).toAbsolutePath();
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
				"--module-path", modules.toString(), "--add-modules", TOOLING_MODULE + "," + LOGGING_MODULE +
					(bazelBackend ? ",jdk.crypto.ec,java.logging" : ""),
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
			""".formatted(normalizeLauncher, TOOLING_MODULE + ",com.github.oshi.ffm", TOOLING_MODULE, TOOLING_MAIN);
		Files.writeString(output.resolve("bin/codex-tooling"), launcher);
		if (File.separatorChar == '\\')
			packageWindowsWrapper(modules, output);
		if (linux)
		{
			if (bazelBackend)
				stageLinuxLaunchers(Path.of(args[3]).toAbsolutePath().normalize(), output);
			else
				packageLinuxLaunchers(Path.of(args[2]).toAbsolutePath(), output);
		}
		if (mac)
			packageMacSupport(Path.of(args[2]).toAbsolutePath(), output);
		if (bazelBackend)
			stageBazelCompanion(modules, output, Path.of(args[3]).toAbsolutePath().normalize());
		Files.createFile(output.resolve(".complete"));
	}

	/**
	 * Compiles the Linux exec bindings without passing the workload environment through a shell.
	 * Compilation is a packaging operation; invocation requires only the assembled runtime.
	 * @param sources directory containing linux-java-launcher.c
	 * @param runtime newly linked runtime
	 * @throws IOException if the source is missing or compilation fails
	 */
	private static void packageLinuxLaunchers(Path sources, Path runtime) throws IOException
	{
		Path source = sources.resolve("linux-java-launcher.c");
		if (!Files.isRegularFile(source))
			throw new IOException("Linux launcher source is required: " + source);
		for (String name : List.of("codex-tooling", "codex-cargo-wrapper-linux"))
		{
			Path output = runtime.resolve("bin").resolve(name);
			List<String> command = List.of("/usr/bin/cc", "-std=c11", "-O2", "-Wall", "-Wextra", "-Werror",
				"-DCARGO_WRAPPER=" + (name.equals("codex-tooling") ? "0" : "1"),
				source.toString(), "-o", output.toString());
			try (JlinkDiagnostics diagnostic = new JlinkDiagnostics(Files.createTempFile("linux-launcher-", ".log")))
			{
				ProcessBuilder builder = new ProcessBuilder(command).redirectErrorStream(true)
					.redirectOutput(diagnostic.path().toFile());
				try (Process process = builder.start())
				{
					int status = process.waitFor();
					if (status != 0)
						throw new IOException("Linux launcher compilation failed with status " + status + ": " +
							Files.readString(diagnostic.path()));
				}
				catch (InterruptedException failure)
				{
					Thread.currentThread().interrupt();
					throw new IOException("Interrupted while compiling Linux launcher", failure);
				}
			}
			Files.setPosixFilePermissions(output,
				java.nio.file.attribute.PosixFilePermissions.fromString("rwxr-xr-x"));
		}
	}

	/**
	 * Copies prebuilt exec bindings for the optional compiler-free Bazel distribution.
	 * @param backend explicit prebuilt artifact directory
	 * @param runtime newly linked runtime
	 * @throws IOException if either launcher is absent or is not a regular executable
	 */
	private static void stageLinuxLaunchers(Path backend, Path runtime) throws IOException
	{
		for (String name : List.of("codex-tooling", "codex-cargo-wrapper-linux"))
		{
			Path source = backend.resolve(name);
			if (!Files.isRegularFile(source, java.nio.file.LinkOption.NOFOLLOW_LINKS) || !Files.isExecutable(source))
				throw new IOException("Prebuilt Linux exec launcher required: " + source);
			Files.copy(source, runtime.resolve("bin").resolve(name), StandardCopyOption.REPLACE_EXISTING,
				StandardCopyOption.COPY_ATTRIBUTES);
		}
	}


    /**
     * Adds JDK-generated module descriptors only to the two pinned staged TOML implementation artifacts.
     * Original classes and resources remain unchanged; dependency source and repository artifacts are untouched.
     * Checker annotations remain compile-time metadata and are not a required linked runtime module.
     * @param modules exclusively owned runtime staging directory
     * @throws IOException if descriptor generation, compilation or publication fails
     */
    private static void modularizeToml(Path modules) throws IOException
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
	 * Stages a validated native backend and one actual application classpath JAR for Linux process helpers.
	 * The ordinary modular launcher remains unchanged. The companion uses the unnamed module because existing
	 * authority/supervisor children consume a single java.class.path artifact; its native-access grant therefore
	 * names ALL-UNNAMED, the only JDK-supported target for those classpath FFM callers.
	 * @param modules packaged application and dependency JARs
	 * @param output newly linked runtime image
	 * @param backend prebuilt backend whose manifest is checked by the production consumer
	 * @throws IOException if assembly, native validation or a child process fails
	 */
	private static void stageBazelCompanion(Path modules, Path output, Path backend) throws IOException
	{
		if (!System.getProperty("os.name").equals("Linux") || !System.getProperty("os.arch").equals("aarch64"))
			throw new UnsupportedOperationException("The optional Bazel backend distribution requires native Linux ARM64");
		Path lib = output.resolve("lib/bazel-backend");
		Files.createDirectory(lib);
		Path application = lib.resolve("codex-tooling.jar");
		assembleClasspath(modules, application);
		Path javaExecutable = output.resolve("bin/java");
		Path log = lib.resolve("native-validation.log");
		ProcessBuilder validation = new ProcessBuilder(javaExecutable.toString(), "-Djava.io.tmpdir=" + System.getProperty("java.io.tmpdir"),
			"-cp", application.toString(), "com.holdmyspot.codexunleashed.scheduler.LinuxBazelBackend",
			backend.toString(), lib.resolve("native").toString());
		validation.redirectErrorStream(true).redirectOutput(log.toFile());
		try (Process process = validation.start())
		{
			int result = process.waitFor();
			if (result != 0) throw new IOException("Native backend package validation failed with status " + result + ": " + Files.readString(log));
		}
		catch (InterruptedException failure)
		{
			Thread.currentThread().interrupt();
			throw new IOException("Interrupted while validating native backend distribution", failure);
		}
		Path keytool = Path.of(System.getProperty("java.home"), "bin/keytool");
		Path linkedKeytool = output.resolve("bin/keytool");
		if (!Files.exists(linkedKeytool, java.nio.file.LinkOption.NOFOLLOW_LINKS))
			Files.copy(keytool, linkedKeytool, StandardCopyOption.COPY_ATTRIBUTES);
		else if (!Files.isRegularFile(linkedKeytool, java.nio.file.LinkOption.NOFOLLOW_LINKS) ||
			!Files.isExecutable(linkedKeytool))
			throw new IOException("Fresh linked runtime keytool is not a regular executable: " + linkedKeytool);
		Files.writeString(lib.resolve("application.sha256"), sha256(application) + "  codex-tooling.jar\n");
		String companion = """
			#!/bin/sh
			set -eu
			DIR=$(CDPATH= cd -- "${0%/*}" && pwd)
			JAVA_TEMP=${TMPDIR:-/tmp}
			exec "$DIR/java" "-Djava.io.tmpdir=$JAVA_TEMP" -Xlog:all=off:stdout -Xlog:all=warning:stderr \
				--enable-native-access=ALL-UNNAMED -cp "$DIR/../lib/bazel-backend/codex-tooling.jar" \
				com.holdmyspot.codexunleashed.tooling.Main "$@"
			""";
		Files.writeString(output.resolve("bin/codex-tooling-bazel"), companion);
		Files.setPosixFilePermissions(output.resolve("bin/codex-tooling-bazel"),
			java.nio.file.attribute.PosixFilePermissions.fromString("rwxr-xr-x"));
	}

	/**
	 * Assembles exact packaged classes/resources into one deterministic helper-compatible classpath JAR.
	 * Service registrations are merged; differing duplicate classes and resources are rejected. Per-JAR
	 * licenses are retained separately so a common LICENSE filename cannot silently erase provenance.
	 * @param modules directory containing the final application and runtime dependency JARs
	 * @param output new assembled JAR
	 * @throws IOException if a JAR is unreadable or conflicting entries are present
	 */
	public static void assembleClasspath(Path modules, Path output) throws IOException
	{
		Map<String, byte[]> entries = new TreeMap<>();
		Map<String, String> services = new TreeMap<>();
		try (Stream<Path> files = Files.list(modules))
		{
			for (Path jar : files.filter(path -> path.getFileName().toString().endsWith(".jar")).sorted().toList())
			{
				try (JarFile input = new JarFile(jar.toFile()))
				{
					for (JarEntry entry : input.stream().filter(value -> !value.isDirectory()).toList())
					{
						String name = entry.getName();
						boolean moduleMetadata = name.equals("module-info.class") || name.endsWith("/module-info.class");
						boolean signature = name.startsWith("META-INF/") &&
							(name.endsWith(".SF") || name.endsWith(".RSA") || name.endsWith(".DSA"));
						if (moduleMetadata || signature || name.equalsIgnoreCase("META-INF/MANIFEST.MF") || name.equals("META-INF/INDEX.LIST")) continue;
						byte[] bytes;
						try (java.io.InputStream data = input.getInputStream(entry)) { bytes = data.readAllBytes(); }
						if (name.startsWith("META-INF/services/"))
						{
							services.merge(name, new String(bytes, java.nio.charset.StandardCharsets.UTF_8) + "\n", String::concat);
							continue;
						}
						boolean license = name.toUpperCase(java.util.Locale.ROOT).contains("LICENSE") ||
							name.toUpperCase(java.util.Locale.ROOT).contains("NOTICE");
						if (license) name = "META-INF/dependency-licenses/" + jar.getFileName() + "/" + name;
						byte[] previous = entries.putIfAbsent(name, bytes);
						if (previous != null && !Arrays.equals(previous, bytes))
							throw new IOException("Classpath companion contains differing duplicate resource: " + name + " from " + jar);
					}
				}
			}
		}
		for (Map.Entry<String, String> service : services.entrySet())
			entries.put(service.getKey(), service.getValue().getBytes(java.nio.charset.StandardCharsets.UTF_8));
		Manifest manifest = new Manifest();
		manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
		manifest.getMainAttributes().put(Attributes.Name.MULTI_RELEASE, "true");
		try (JarOutputStream archive = new JarOutputStream(Files.newOutputStream(output)))
		{
			JarEntry manifestEntry = new JarEntry("META-INF/MANIFEST.MF");
			manifestEntry.setTime(0);
			archive.putNextEntry(manifestEntry);
			manifest.write(archive);
			archive.closeEntry();
			for (Map.Entry<String, byte[]> entry : entries.entrySet())
			{
				JarEntry item = new JarEntry(entry.getKey());
				item.setTime(0);
				archive.putNextEntry(item);
				archive.write(entry.getValue());
				archive.closeEntry();
			}
		}
	}

	/**
	 * Computes a staged artifact's exact SHA-256 bytes.
	 * @param path assembled artifact
	 * @return lowercase digest
	 * @throws IOException if reading fails
	 */
	private static String sha256(Path path) throws IOException
	{
		try
		{
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)));
		}
		catch (NoSuchAlgorithmException failure) { throw new AssertionError("JDK lacks SHA-256", failure); }
	}

	/**
	 * Builds the macOS Cargo descriptor bridge, compiler wrapper and workload owners into one runtime.
	 * The selected Xcode toolchain supplies the macOS SDK through xcrun; this operation runs only on macOS.
	 * JavaScript instrumentation remains in the tooling module's ordinary resources.
	 *
	 * @param sources directory containing the pinned mac-cargo.c and mac-workload.c sources
	 * @param runtime linked runtime receiving native libraries and executable helpers
	 * @throws IOException if source files are missing or native compilation fails
	 */
	private static void packageMacSupport(Path sources, Path runtime) throws IOException
	{
		Path cargo = sources.resolve("mac-cargo.c");
		Path workload = sources.resolve("mac-workload.c");
		if (!Files.isRegularFile(cargo) || !Files.isRegularFile(workload))
			throw new IOException("macOS native sources must contain mac-cargo.c and mac-workload.c: " + sources);
		Path libraries = Files.createDirectory(runtime.resolve("lib/codex"));
		compileMac(cargo, libraries.resolve("libmac-cargo.dylib"), List.of("-dynamiclib"));
		compileMac(cargo, runtime.resolve("bin/codex-cargo-wrapper"), List.of("-DMC_WRAPPER"));
		compileMac(workload, libraries.resolve("libmac-workload.dylib"),
			List.of("-dynamiclib", "-framework", "CoreFoundation"));
		compileMac(workload, runtime.resolve("bin/codex-workload-anchor"),
			List.of("-DMW_ANCHOR", "-framework", "CoreFoundation"));
	}

	/**
	 * Compiles one native macOS support artifact with the selected Xcode SDK.
	 * Diagnostic capture is deleted after its result has been consumed, including failed compilation.
	 *
	 * @param source pinned native source file
	 * @param output new native library or executable path
	 * @param options artifact-specific compiler definitions and linkage
	 * @throws IOException if compiler launch, compilation or diagnostic cleanup fails
	 */
	private static void compileMac(Path source, Path output, List<String> options) throws IOException
	{
		List<String> command = new ArrayList<>(List.of("/usr/bin/xcrun", "--sdk", "macosx", "clang",
			"-std=c11", "-Wall", "-Wextra"));
		command.addAll(options);
		command.addAll(List.of(source.toString(), "-o", output.toString()));
		try (JlinkDiagnostics diagnostics = new JlinkDiagnostics(Files.createTempFile("mac-native-", ".log")))
		{
			ProcessBuilder builder = new ProcessBuilder(command);
			builder.redirectErrorStream(true).redirectOutput(diagnostics.path().toFile());
			try (Process process = builder.start())
			{
				int result = process.waitFor();
				if (result != 0)
					throw new IOException("macOS native compilation failed for " + source + " with status " + result +
						": " + Files.readString(diagnostics.path()).strip());
			}
		}
		catch (InterruptedException failure)
		{
			Thread.currentThread().interrupt();
			throw new IOException("Interrupted while compiling macOS native support: " + source, failure);
		}
	}


	/**
	 * Packages the Cargo wrapper as a native Windows console application.
	 * Builds outside the runtime tree so jpackage cannot recursively copy its own destination.
	 *
	 * @param modules the packaged application modules
	 * @param runtime the linked runtime to embed
	 * @throws IOException if packaging, publication or cleanup fails
	 */
	private static void packageWindowsWrapper(Path modules, Path runtime) throws IOException
	{
		Path staging = Files.createTempDirectory(runtime.getParent(), "cargo-wrapper-");
		try (PackagingDirectory cleanup = new PackagingDirectory(staging);
			var diagnostics = new JlinkDiagnostics(Files.createTempFile("jpackage-", ".log")))
		{
			Path jpackage = Path.of(System.getProperty("java.home"), "bin", "jpackage.exe");
			ProcessBuilder builder = new ProcessBuilder(jpackage.toString(),
				"-J-Djava.io.tmpdir=" + System.getProperty("java.io.tmpdir"),
				"--type", "app-image", "--name", "codex-cargo-wrapper", "--dest", cleanup.path().toString(),
				"--runtime-image", runtime.toString(), "--module-path", modules.toString(),
				"--module", TOOLING_MODULE + "/com.holdmyspot.codexunleashed.scheduler.WindowsCargoWrapperMain",
				"--win-console", "--java-options=--enable-native-access=" + TOOLING_MODULE);
			builder.redirectErrorStream(true).redirectOutput(diagnostics.path().toFile());
			try (Process process = builder.start())
			{
				int result = process.waitFor();
				if (result != 0)
					throw new IOException("jpackage failed with status " + result + ": " +
						Files.readString(diagnostics.path()).strip());
			}
			Path wrapperImage = Files.createDirectory(runtime.resolve("wrapper-image"));
			Files.move(cleanup.path().resolve("codex-cargo-wrapper"), wrapperImage.resolve("codex-cargo-wrapper"));
		}
		catch (InterruptedException failure)
		{
			Thread.currentThread().interrupt();
			throw new IOException("Interrupted while waiting for jpackage", failure);
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
