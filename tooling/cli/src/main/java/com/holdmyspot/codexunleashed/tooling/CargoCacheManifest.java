package com.holdmyspot.codexunleashed.tooling;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Stream;

/** Writes the complete target, registry archive and Git cache size inventory in retained root order. */
public final class CargoCacheManifest
{
	/** Prevents construction. */
	private CargoCacheManifest()
	{
	}

	/**
	 * Writes raw byte sizes and lexical filenames, replacing the existing manifest with native text newlines.
	 *
	 * @param output manifest destination whose parent exists
	 * @param environment explicit cache roots
	 * @throws NullPointerException if an argument, environment key or value is null
	 * @throws IOException if the target root is unspecified or traversal, metadata or output access fails
	 */
	public static void write(Path output, Map<String, String> environment) throws IOException
	{
		Objects.requireNonNull(output, "output");
		Map<String, String> variables = Map.copyOf(environment);
		String target = variables.get("CARGO_TARGET_DIR");
		if (target == null)
			throw new IOException("CARGO_TARGET_DIR is required to write the Cargo cache manifest");
		List<Path> roots = new ArrayList<>(List.of(parseRoot(target)));
		String home = variables.get("CARGO_HOME");
		if (home != null && !home.isEmpty())
		{
			Path cargo = parseRoot(home);
			roots.add(cargo.resolve("registry/cache"));
			roots.add(cargo.resolve("git"));
		}
		try (BufferedWriter writer = Files.newBufferedWriter(output))
		{
			for (Path root : roots)
				writeRoot(writer, root);
		}
	}

	/**
	 * Drops redundant dot components while preserving parent components and provider-specific root spelling.
	 *
	 * @param value supplied path text
	 * @return retained lexical path
	 */
	private static Path parseRoot(String value)
	{
		Path parsed = Path.of(value);
		Path result = parsed.getRoot();
		if (result == null)
			result = parsed.getFileSystem().getPath("");
		for (Path component : parsed)
		{
			if (!component.toString().equals("."))
				result = result.resolve(component);
		}
		return result;
	}

	/**
	 * Follows an explicit root link but does not recurse through directory links found below it.
	 *
	 * @param writer caller-owned manifest writer
	 * @param root lexical root used for reporting
	 * @throws IOException if traversal, metadata or manifest output fails
	 */
	private static void writeRoot(BufferedWriter writer, Path root) throws IOException
	{
		if (!Files.isDirectory(root))
			return;
		Path traversed = root.toRealPath();
		try (Stream<Path> paths = Files.walk(traversed))
		{
			List<Path> entries = paths.filter(path -> !path.equals(traversed)).
				map(path -> root.resolve(traversed.relativize(path))).sorted(ArtifactPaths.comparator(root)).toList();
			for (Path path : entries)
			{
				if (!Files.isRegularFile(path))
					continue;
				writer.write(Long.toString(Files.size(path)));
				writer.write('\t');
				writer.write(path.toString());
				writer.newLine();
			}
		}
		catch (UncheckedIOException failure)
		{
			throw failure.getCause();
		}
	}
}
