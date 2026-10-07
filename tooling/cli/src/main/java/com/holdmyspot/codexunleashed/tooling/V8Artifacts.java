package com.holdmyspot.codexunleashed.tooling;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream;
import org.apache.commons.compress.compressors.gzip.GzipParameters;

/** Discovers Cargo V8 output and stages reproducible library/binding release pairs. */
public final class V8Artifacts
{
	/** The ordinary release artifact profile. */
	public static final String RELEASE_PROFILE = "release";
	/** The sandbox release artifact profile. */
	public static final String SANDBOX_PROFILE = "ptrcomp_sandbox_release";

	/** Prevents construction. */
	private V8Artifacts()
	{
	}

	/**
	 * Supplies the library and generated Rust binding input paths.
	 *
	 * @param library the native static library
	 * @param binding the raw Rust binding
	 */
	public record Pair(Path library, Path binding)
	{
		/**
		 * Requires both paths without consuming their contents.
		 *
		 * @param library the library path
		 * @param binding the binding path
		 * @throws NullPointerException if either path is null
		 */
		public Pair
		{
			Objects.requireNonNull(library, "library");
			Objects.requireNonNull(binding, "binding");
		}
	}

	/**
	 * Selects direct gn_out files or the first sorted matching paths below the target-specific release tree.
	 * Missing outputs retain their expected direct paths so staging can report both absent inputs.
	 *
	 * @param target the Cargo target
	 * @param targetDirectory Cargo output storage
	 * @return selected or expected inputs
	 * @throws NullPointerException if either argument is null
	 * @throws IOException if output traversal fails
	 */
	public static Pair upstreamPaths(String target, Path targetDirectory) throws IOException
	{
		Objects.requireNonNull(target, "target");
		Objects.requireNonNull(targetDirectory, "targetDirectory");
		String libraryName = "librusty_v8.a";
		if (target.endsWith("-pc-windows-msvc"))
			libraryName = "rusty_v8.lib";
		Path release = targetDirectory.resolve(target).resolve(RELEASE_PROFILE);
		Pair expected = new Pair(release.resolve("gn_out/obj").resolve(libraryName),
			release.resolve("gn_out/src_binding.rs"));
		if (Files.exists(expected.library()) && Files.exists(expected.binding()) || !Files.isDirectory(release))
			return expected;
		Path selectedLibrary = targetDirectory.getFileSystem().getPath(libraryName);
		Path selectedBinding = targetDirectory.getFileSystem().getPath("src_binding.rs");
		try (Stream<Path> paths = Files.walk(release))
		{
			List<Path> entries = paths.filter(path -> !path.equals(release)).sorted(ArtifactPaths.comparator(release)).
				toList();
			List<Path> libraries = entries.stream().filter(path -> path.getFileName().equals(selectedLibrary)).
				toList();
			List<Path> bindings = entries.stream().filter(path -> path.getFileName().equals(selectedBinding)).
				toList();
			if (!libraries.isEmpty() && !bindings.isEmpty())
				return new Pair(libraries.getFirst(), bindings.getFirst());
			return expected;
		}
		catch (UncheckedIOException failure)
		{
			throw failure.getCause();
		}
	}

	/**
	 * Writes a zero-clock gzip library, unchanged binding bytes, and their ordered SHA-256 checksum manifest.
	 * Existing payload files are overwritten while destination links and permissions remain effective.
	 *
	 * @param target the release target
	 * @param library the library input
	 * @param binding the generated binding input
	 * @param outputDirectory release output storage
	 * @param sandbox whether the sandbox artifact profile is selected
	 * @return library, binding and checksum paths in reporting order
	 * @throws NullPointerException if a reference argument is null
	 * @throws IOException if inputs are absent, copying, compression or output writing fails
	 */
	public static List<Path> stage(String target, Path library, Path binding, Path outputDirectory, boolean sandbox)
		throws IOException
	{
		Objects.requireNonNull(target, "target");
		Objects.requireNonNull(library, "library");
		Objects.requireNonNull(binding, "binding");
		Objects.requireNonNull(outputDirectory, "outputDirectory");
		List<Path> missing = Stream.of(library, binding).filter(path -> !Files.exists(path)).toList();
		if (!missing.isEmpty())
			throw new IOException("missing release outputs for " + target + ": " + missing);
		Files.createDirectories(outputDirectory);
		String profile = RELEASE_PROFILE;
		if (sandbox)
			profile = SANDBOX_PROFILE;
		String libraryName = "librusty_v8_" + profile + "_" + target + ".a.gz";
		if (target.endsWith("-pc-windows-msvc"))
			libraryName = "rusty_v8_" + profile + "_" + target + ".lib.gz";
		Path archive = outputDirectory.resolve(libraryName);
		Path stagedBinding = outputDirectory.resolve("src_binding_" + profile + "_" + target + ".rs");
		Path checksums = outputDirectory.resolve("rusty_v8_" + profile + "_" + target + ".sha256");
		GzipParameters parameters = new GzipParameters();
		parameters.setCompressionLevel(6);
		parameters.setModificationInstant(Instant.EPOCH);
		try (InputStream input = Files.newInputStream(library); OutputStream file = Files.newOutputStream(archive);
			GzipCompressorOutputStream gzip = new GzipCompressorOutputStream(file, parameters))
		{
			input.transferTo(gzip);
		}
		PayloadFiles.copy(binding, stagedBinding);
		Files.writeString(checksums, Sha256.digest(archive) + "  " + archive.getFileName() + System.lineSeparator() +
			Sha256.digest(stagedBinding) + "  " + stagedBinding.getFileName() + System.lineSeparator());
		return List.of(archive, stagedBinding, checksums);
	}
}
