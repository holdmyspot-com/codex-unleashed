package com.holdmyspot.codexunleashed.tooling;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Stream;

/**
 * Records provenance only when a checkout equals its committed baseline plus the ordered patch queue.
 */
public final class SourceProvenance
{
	private static final List<String> CACHE_ROOTS = List.of(".cargo-home", ".git", "target", ".zig-cache");

	/**
	 * Prevents construction.
	 */
	private SourceProvenance()
	{
	}

	/**
	 * Holds the producer's original upstream and workflow metadata strings.
	 *
	 * @param upstreamRepository the upstream repository name
	 * @param upstreamRef the upstream ref
	 * @param workflowPath the workflow path
	 * @param workflowSha the workflow commit
	 * @param workflowRunId the workflow run identifier
	 */
	public record Metadata(String upstreamRepository, String upstreamRef, String workflowPath,
		String workflowSha, String workflowRunId)
	{
		/**
		 * Creates explicit metadata without normalizing its values.
		 *
		 * @param upstreamRepository the upstream repository name
		 * @param upstreamRef the upstream ref
		 * @param workflowPath the workflow path
		 * @param workflowSha the workflow commit
		 * @param workflowRunId the workflow run identifier
		 * @throws NullPointerException if any argument is null
		 */
		public Metadata
		{
			Objects.requireNonNull(upstreamRepository, "upstreamRepository");
			Objects.requireNonNull(upstreamRef, "upstreamRef");
			Objects.requireNonNull(workflowPath, "workflowPath");
			Objects.requireNonNull(workflowSha, "workflowSha");
			Objects.requireNonNull(workflowRunId, "workflowRunId");
		}
	}

	/**
	 * Describes the audit inputs and report destination.
	 *
	 * @param checkout the upstream checkout
	 * @param patchRepository the repository containing patches/
	 * @param output the report destination
	 * @param metadata the producer's metadata
	 */
	public record Request(Path checkout, Path patchRepository, Path output, Metadata metadata)
	{
		/**
		 * Creates a request without changing its source or destination.
		 *
		 * @param checkout the upstream checkout
		 * @param patchRepository the patch repository
		 * @param output the report destination
		 * @param metadata the producer's metadata
		 * @throws NullPointerException if any argument is null
		 */
		public Request
		{
			Objects.requireNonNull(checkout, "checkout");
			Objects.requireNonNull(patchRepository, "patchRepository");
			Objects.requireNonNull(output, "output");
			Objects.requireNonNull(metadata, "metadata");
		}
	}

	/**
	 * Verifies source trees with independent Git indexes before writing an ASCII provenance document.
	 *
	 * @param request the checkout, patch queue, output, and metadata
	 * @param temporaryDirectory the existing parent for owned audit and capture files
	 * @param gitEnvironment the explicit overrides of inherited Git environment variables
	 * @throws NullPointerException if any argument or environment entry is null
	 * @throws IOException if Git, source verification, report writing, or cleanup fails
	 */
	public static void generate(Request request, Path temporaryDirectory, Map<String, String> gitEnvironment)
		throws IOException
	{
		Objects.requireNonNull(request, "request");
		Objects.requireNonNull(temporaryDirectory, "temporaryDirectory");
		Map<String, String> environment = Map.copyOf(gitEnvironment);
		Path checkout = request.checkout().toRealPath();
		Path repository = request.patchRepository().toRealPath();
		List<Path> patchFiles = patchFiles(repository);
		List<Map<String, String>> patches = new ArrayList<>();
		for (Path patch : patchFiles)
			patches.add(Map.of("path", ArtifactPaths.relativeName(repository, patch), "sha256", Sha256.digest(patch)));
		String serialized;
		try (GitAudit audit = new GitAudit(checkout,
			Files.createTempDirectory(temporaryDirectory, "source-audit-"), environment))
		{
			String originalCommit = audit.run(null, "rev-parse", "HEAD").strip();
			String patchedTree = audit.verify(patchFiles);
			Metadata metadata = request.metadata();
			Map<String, Object> document = Map.of("schema_version", 2, "source_verified", true,
				"upstream", Map.of("repository", metadata.upstreamRepository(), "ref", metadata.upstreamRef(),
					"commit", originalCommit, "baseline_tree", audit.run(null, "rev-parse", "HEAD^{tree}").strip()),
				"patches", patches, "application_order", patches.stream().map(patch -> patch.get("path")).toList(),
				"patched_tree", patchedTree, "applied_diff_sha256", audit.diffDigest(),
				"changed_paths", paths(audit.run(audit.actual, "diff", "--cached", "--name-only", "-z", "HEAD")),
				"workflow", Map.of("path", metadata.workflowPath(), "sha", metadata.workflowSha(),
					"run_id", metadata.workflowRunId()));
			serialized = ArtifactJson.format(document);
		}
		Path output = request.output().toAbsolutePath().normalize();
		Files.createDirectories(output.getParent());
		Files.writeString(output, serialized, StandardCharsets.US_ASCII);
	}

	/**
	 * Selects regular in-tree patches in filesystem component order.
	 *
	 * @param repository the resolved patch repository
	 * @return the ordered patch files
	 * @throws IOException if a patch is unreadable, symbolic, or escapes the patch directory
	 */
	private static List<Path> patchFiles(Path repository) throws IOException
	{
		Path root = repository.resolve("patches");
		if (Files.isSymbolicLink(root))
			throw new IOException("Patch escapes patches/: " + root);
		if (!Files.exists(root))
			return List.of();
		List<Path> result;
		try (Stream<Path> files = Files.walk(root))
		{
			result = files.filter(path -> path.getFileName().toString().endsWith(".patch")).
				sorted(ArtifactPaths.comparator(repository)).toList();
		}
		for (Path patch : result)
		{
			if (Files.isSymbolicLink(patch) || !patch.toRealPath().startsWith(root))
				throw new IOException("Patch escapes patches/: " + patch);
			if (!Files.isRegularFile(patch, LinkOption.NOFOLLOW_LINKS))
				throw new IOException("Patch must be a regular file: " + patch);
		}
		return result;
	}

	/**
	 * Reads a Git NUL-delimited path list without trimming path components.
	 *
	 * @param output the Git path list
	 * @return the nonempty paths
	 */
	private static List<String> paths(String output)
	{
		return Arrays.stream(output.split("\0", -1)).filter(path -> !path.isEmpty()).toList();
	}

	/**
	 * Identifies an untracked source path that is outside the excluded build and metadata paths.
	 *
	 * @param path the repository-relative Git path
	 * @return true when the path belongs in the source audit
	 */
	private static boolean isSourcePath(String path)
	{
		return !path.equals("source-provenance.json") && !path.endsWith(".index") &&
			CACHE_ROOTS.stream().noneMatch(root -> path.equals(root) || path.startsWith(root + "/")) &&
			!("/" + path).contains("/target/") && !("/" + path).contains("/.zig-cache/");
	}

	/**
	 * Owns the replay and actual indexes and every command capture for one audit.
	 */
	private static final class GitAudit implements AutoCloseable
	{
		private final Path checkout;
		private final Path root;
		private final Path expected;
		private final Path actual;
		private final Map<String, String> environment;

		/**
		 * Assigns the newly allocated audit directory and explicit Git context.
		 *
		 * @param checkout the resolved upstream checkout
		 * @param root the owned temporary directory
		 * @param environment the explicit environment overrides
		 */
		private GitAudit(Path checkout, Path root, Map<String, String> environment)
		{
			this.checkout = checkout;
			this.root = root;
			this.environment = environment;
			expected = root.resolve("expected.index");
			actual = root.resolve("actual.index");
		}

		/**
		 * Compares ordered patch replay with the current source tree using independent indexes.
		 *
		 * @param patches the ordered patch files
		 * @return the verified patched tree
		 * @throws IOException if replay fails or source trees differ
		 */
		private String verify(List<Path> patches) throws IOException
		{
			run(expected, "read-tree", "HEAD");
			for (Path patch : patches)
				run(expected, "apply", "--cached", "--", patch.toString());
			String expectedTree = run(expected, "write-tree").strip();
			run(actual, "read-tree", "HEAD");
			List<String> sources = paths(run(actual, "ls-files", "--others", "--exclude-standard", "-z")).stream().
				filter(SourceProvenance::isSourcePath).toList();
			run(actual, "add", "--update");
			if (!sources.isEmpty())
			{
				List<String> arguments = new ArrayList<>(List.of("add", "--"));
				arguments.addAll(sources);
				run(actual, arguments.toArray(String[]::new));
			}
			for (String addition : paths(run(expected,
				"diff", "--cached", "--name-only", "--diff-filter=A", "-z", "HEAD")))
				run(actual, "add", "--force", "--", addition);
			String actualTree = run(actual, "write-tree").strip();
			if (!actualTree.equals(expectedTree))
				throw new IOException("Source audit failed: checkout is not upstream HEAD plus patches/. " +
					"Expected tree " + expectedTree + ", got " + actualTree + ". Differing paths:\n" +
					run(null, "diff-tree", "--no-commit-id", "--name-status", "-r", expectedTree, actualTree));
			return actualTree;
		}

		/**
		 * Hashes the actual index's raw binary diff.
		 *
		 * @return the lowercase diff digest
		 * @throws IOException if Git or digest capture fails
		 */
		private String diffDigest() throws IOException
		{
			SystemCommands.DigestResult result = SystemCommands.digest(
				command("diff", "--cached", "--binary", "--no-ext-diff"),
				checkout, root, context(actual));
			if (result.status() != 0)
				throw new IOException("Git diff failed with status " + result.status() + ": " + result.stderr());
			return result.stdoutSha256();
		}

		/**
		 * Runs Git with an optional owned index.
		 *
		 * @param index the temporary index, or null for ordinary repository reads
		 * @param arguments the Git arguments
		 * @return the unmodified UTF-8 stdout
		 * @throws IOException if Git or capture handling fails
		 */
		private String run(Path index, String... arguments) throws IOException
		{
			SystemCommands.Result result = SystemCommands.capture(command(arguments), checkout, root, context(index));
			if (result.status() != 0)
				throw new IOException("Git " + arguments[0] + " failed with status " + result.status() +
					": " + result.stderr());
			return result.stdout();
		}

		/**
		 * Creates the literal command arguments.
		 *
		 * @param arguments the Git arguments
		 * @return the executable and explicit checkout arguments
		 */
		private List<String> command(String... arguments)
		{
			List<String> command = new ArrayList<>(List.of("git", "-C", checkout.toString()));
			command.addAll(Arrays.asList(arguments));
			return command;
		}

		/**
		 * Overrides only the selected index while retaining explicit caller context.
		 *
		 * @param index the owned index, or null for ordinary reads
		 * @return the command environment overrides
		 */
		private Map<String, String> context(Path index)
		{
			Map<String, String> result = new HashMap<>(environment);
			if (index != null)
				result.put("GIT_INDEX_FILE", index.toString());
			return result;
		}

		/**
		 * Removes independent indexes, lock files, and any remaining owned captures without following links.
		 *
		 * @throws IOException if cleanup fails
		 */
		@Override
		public void close() throws IOException
		{
			try (Stream<Path> files = Files.walk(root))
			{
				for (Path file : files.sorted(Comparator.reverseOrder()).toList())
					Files.delete(file);
			}
		}
	}
}
