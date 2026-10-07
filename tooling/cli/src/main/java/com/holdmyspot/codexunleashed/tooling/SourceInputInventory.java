package com.holdmyspot.codexunleashed.tooling;

import java.io.IOException;
import java.math.BigInteger;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/** Collects raw source digests and nanosecond timestamps independently of cache preparation decisions. */
public final class SourceInputInventory
{
	private static final Set<String> OMITTED_DIRECTORIES = Set.of(".git", "target", "node_modules");

	/** Prevents construction. */
	private SourceInputInventory()
	{
	}

	/**
	 * Contains the canonical source root, relative raw-file hashes, and file/ancestor timestamps.
	 *
	 * @param root the canonical repository root or fallback workspace
	 * @param files repository-relative slash paths to lowercase SHA-256
	 * @param mtimes repository-relative slash paths to exact epoch nanoseconds
	 */
	public record Snapshot(Path root, Map<String, String> files, Map<String, BigInteger> mtimes)
	{
		/**
		 * Copies both maps so callers cannot alter the collected source state.
		 *
		 * @param root the canonical root
		 * @param files source digests
		 * @param mtimes source and ancestor timestamps
		 * @throws NullPointerException if any input, key, or map value is null
		 */
		public Snapshot
		{
			Objects.requireNonNull(root, "root");
			files = Map.copyOf(files);
			mtimes = Map.copyOf(mtimes);
		}
	}

	/**
	 * Uses Git's tracked and nonignored untracked names, or the retained filesystem fallback when not a repository.
	 *
	 * @param workspace the source workspace
	 * @param targetDirectory compiled outputs to exclude
	 * @param cargoHome optional downloads to exclude from untracked or fallback inputs
	 * @param temporaryDirectory the owned subprocess capture parent
	 * @param environment explicit child environment overrides
	 * @return immutable digests and exact timestamps
	 * @throws NullPointerException if any argument is null
	 * @throws IOException if Git startup, inventory, hashing, timestamps, or capture cleanup fails
	 */
	public static Snapshot read(Path workspace, Path targetDirectory, Optional<Path> cargoHome,
		Path temporaryDirectory, Map<String, String> environment) throws IOException
	{
		Objects.requireNonNull(workspace, "workspace");
		Objects.requireNonNull(targetDirectory, "targetDirectory");
		Objects.requireNonNull(cargoHome, "cargoHome");
		Objects.requireNonNull(temporaryDirectory, "temporaryDirectory");
		Map<String, String> overrides = Map.copyOf(environment);
		Path working = workspace.toRealPath();
		SystemCommands.Result discovery = SystemCommands.capture(List.of("git", "-C", working.toString(),
			"rev-parse", "--show-toplevel"), working, temporaryDirectory, overrides);
		Path root = working;
		if (discovery.status() == 0)
			root = Path.of(discovery.stdout().strip()).toRealPath();
		Set<Path> excluded = new LinkedHashSet<>();
		excluded.add(SourceInputPaths.resolve(targetDirectory));
		if (cargoHome.isPresent())
			excluded.add(SourceInputPaths.resolve(cargoHome.orElseThrow()));
		List<Path> paths;
		if (discovery.status() == 0)
			paths = gitPaths(root, excluded, temporaryDirectory, overrides);
		else
			paths = fallbackPaths(root, excluded);
		return snapshot(root, paths, SourceInputPaths.resolve(targetDirectory));
	}

	/**
	 * Keeps explicit tracked files and asks Git to exclude cache trees only from untracked discovery.
	 *
	 * @param root the Git root
	 * @param excluded canonical cache directories
	 * @param temporary capture storage
	 * @param environment child overrides
	 * @return source candidates in Git order
	 * @throws IOException if Git or decoding fails
	 */
	private static List<Path> gitPaths(Path root, Set<Path> excluded, Path temporary, Map<String, String> environment)
		throws IOException
	{
		List<String> tracked = List.of("git", "-C", root.toString(), "ls-files", "--cached", "-z");
		List<String> untracked = new ArrayList<>(List.of("git", "-C", root.toString(), "ls-files", "--others",
			"--exclude-standard", "-z", "--", "."));
		for (Path directory : excluded)
		{
			if (directory.startsWith(root) && !directory.equals(root))
				untracked.add(":(exclude,literal)" + relative(root, directory));
		}
		List<Path> paths = new ArrayList<>();
		for (List<String> command : List.of(tracked, untracked))
		{
			SystemCommands.Result result = SystemCommands.capture(command, root, temporary, environment);
			if (result.status() != 0)
				throw new IOException("Git source inventory failed with status " + result.status() + ": " + result.stderr());
			for (String name : result.stdout().split("\\x00"))
			{
				if (!name.isEmpty())
					paths.add(root.resolve(name));
			}
		}
		return paths;
	}

	/**
	 * Walks without following directory links and prunes only defined names or explicitly excluded caches.
	 *
	 * @param root the workspace root
	 * @param excluded canonical cache paths
	 * @return candidate source files
	 * @throws IOException if walking or canonicalization fails
	 */
	private static List<Path> fallbackPaths(Path root, Set<Path> excluded) throws IOException
	{
		List<Path> paths = new ArrayList<>();
		Files.walkFileTree(root, new SimpleFileVisitor<>()
		{
			@Override
			public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes) throws IOException
			{
				if (!directory.equals(root) && (OMITTED_DIRECTORIES.contains(directory.getFileName().toString()) ||
					excluded.contains(directory.toRealPath())))
					return FileVisitResult.SKIP_SUBTREE;
				return FileVisitResult.CONTINUE;
			}

			@Override
			public FileVisitResult visitFile(Path file, BasicFileAttributes attributes)
			{
				paths.add(file);
				return FileVisitResult.CONTINUE;
			}
		});
		return paths;
	}

	/**
	 * Hashes regular files outside the compiled target and includes their lexical in-repository ancestors.
	 *
	 * @param root the source root
	 * @param paths source candidates
	 * @param target the canonical compiled target
	 * @return the immutable inventory
	 * @throws IOException if hashing or timestamp access fails
	 */
	private static Snapshot snapshot(Path root, List<Path> paths, Path target) throws IOException
	{
		Map<String, String> digests = new LinkedHashMap<>();
		Set<Path> timestamps = new LinkedHashSet<>();
		for (Path file : paths)
		{
			if (!Files.isRegularFile(file) || file.toRealPath().startsWith(target))
				continue;
			digests.put(relative(root, file), Sha256.digest(file));
			timestamps.add(file);
			Path parent = file.getParent();
			while (parent != null && parent.startsWith(root))
			{
				timestamps.add(parent);
				parent = parent.getParent();
			}
		}
		Map<String, BigInteger> mtimes = new LinkedHashMap<>();
		for (Path path : timestamps)
		{
			Instant value = SourceInputTimes.lastModified(path);
			mtimes.put(relative(root, path), SourceInputTimes.nanos(value));
		}
		return new Snapshot(root, digests, mtimes);
	}

	/**
	 * Uses slash paths and the retained dot spelling for the root itself.
	 *
	 * @param root the source root
	 * @param path a source or ancestor
	 * @return the repository-relative snapshot key
	 */
	private static String relative(Path root, Path path)
	{
		String result = root.relativize(path).toString().replace(java.io.File.separatorChar, '/');
		if (result.isEmpty())
			return ".";
		return result;
	}
}
