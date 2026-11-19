package com.holdmyspot.codexunleashed.tooling;

import org.apache.commons.io.file.PathUtils;
import org.apache.commons.io.file.StandardDeleteOption;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;

import static com.holdmyspot.codexunleashed.tooling.ReleaseManifestSchema.require;
import static com.holdmyspot.codexunleashed.tooling.ReleaseManifestSchema.text;
import static java.nio.charset.StandardCharsets.UTF_8;

/**
 * Verifies {@link ReleaseManifest} source identities against pinned Git blobs and independent raw patch replay.
 */
public final class ReleaseManifestSource
{
	/**
	 * Borrowed operation scope supplying the configured JSON mapper.
	 */
	private final ToolingScope scope;

	/**
	 * Injected Git process boundary.
	 */
	private final SystemCommands.Capture capture;

	/**
	 * Explicit operation directories and process environment.
	 */
	private final ReleaseManifest.Environment environment;

	/**
	 * Creates source verification with one operation's dependencies.
	 *
	 * @param scope       borrowed operation scope
	 * @param capture     Git process boundary
	 * @param environment explicit directories and process environment
	 * @throws NullPointerException if a required dependency is null
	 */
	public ReleaseManifestSource(ToolingScope scope, SystemCommands.Capture capture,
		ReleaseManifest.Environment environment)
	{
		this.scope = Objects.requireNonNull(scope, "scope");
		this.capture = Objects.requireNonNull(capture, "capture");
		this.environment = Objects.requireNonNull(environment, "environment");
	}

	/**
	 * Reads the trusted release queue and binds every raw patch to the pinned fork's Git blob.
	 *
	 * @param patchRepository supplied fork checkout
	 * @param forkCommit      required fork HEAD
	 * @return ordered patch paths and SHA256 identities
	 * @throws IOException if a file or Git operation cannot be accessed
	 * @throws IllegalArgumentException if checkout content differs from its pinned identity
	 */
	public ArrayNode patches(Path patchRepository, String forkCommit) throws IOException
	{
		require(git(patchRepository, "rev-parse", "HEAD").equals(forkCommit),
			"Patch repository HEAD differs from fork_commit");
		Path readme = patchRepository.resolve("patches/README.md");
		String readmeContent = readReadme(readme);
		List<String> names = patchQueue(readme);
		require(names.size() <= 512 && new HashSet<>(names).size() == names.size(),
			"Invalid or duplicate patch queue entries");
		ArrayNode result = scope.getJsonMapper().createArrayNode();
		for (String name : names)
		{
			ReleaseManifestSchema.patchPath(name);
			Path path = patchRepository.resolve(name);
			String digest = digest(path);
			require(git(patchRepository, "hash-object", path.toRealPath().toString()).
				equals(git(patchRepository, "rev-parse", forkCommit + ":" + name)),
				"Patch differs from fork commit: " + name);
			result.addObject().put("path", name).put("sha256", digest);
		}
		String committedReadme = git(patchRepository, "show", forkCommit + ":patches/README.md");
		require(strip(readmeContent).equals(committedReadme), "Patch README differs from fork commit");
		return result;
	}

	/**
	 * Reads clean source HEAD, tree, and Cargo.lock identities without modifying the checkout. The release commits its
	 * lockfile alignment (see {@link WorkspaceLockAlignment}), so a modified lockfile is as unclean as any other change.
	 *
	 * @param source supplied upstream source checkout
	 * @return source commit, tree, and lockfile digest
	 * @throws IOException if a file or Git operation cannot be accessed
	 * @throws IllegalArgumentException if tracked or untracked source inputs are modified
	 */
	public ObjectNode identity(Path source) throws IOException
	{
		String uncommitted = git(source, "status", "--porcelain", "--untracked-files=normal");
		require(uncommitted.isEmpty(), "Source checkout must be clean before recording or verification");
		ObjectNode result = scope.getJsonMapper().createObjectNode();
		result.put("commit", git(source, "rev-parse", "HEAD"));
		result.put("tree", git(source, "rev-parse", "HEAD^{tree}"));
		result.put("cargo_lock_sha256", digest(source.resolve("codex-rs/Cargo.lock")));
		return result;
	}

	/**
	 * Verifies exact checkout identities and independently reconstructs the declared tree from raw patches, followed
	 * by the lockfile alignment when the source's HEAD is that commit (see {@link WorkspaceLockAlignment}).
	 *
	 * @param value           schema-validated receipt or manifest
	 * @param patchRepository supplied pinned fork checkout
	 * @param source          supplied clean patched upstream checkout
	 * @throws IOException if source reconstruction or cleanup fails
	 * @throws IllegalArgumentException if any source, ancestry, or replay identity differs
	 */
	public void validate(JsonNode value, Path patchRepository, Path source) throws IOException
	{
		require(patches(patchRepository, text(value.get("fork_commit"))).equals(value.get("patches")),
			"Patch inventory differs from pinned fork");
		require(identity(source).equals(value.get("source")), "Source HEAD, tree or Cargo.lock differs from manifest");
		String upstream = text(value.get("upstream").get("commit"));
		String tag = text(value.get("upstream").get("tag"));
		require(git(source, "rev-parse", tag + "^{commit}").equals(upstream),
			"Upstream tag differs from pinned commit");
		git(source, "merge-base", "--is-ancestor", upstream, "HEAD");
		BigInteger count = new BigInteger(git(source, "rev-list", "--count", upstream + "..HEAD"));
		BigInteger extraCommits = count.subtract(BigInteger.valueOf(value.get("patches").size()));
		// The release commits the lockfile alignment after the queue, but only when the lockfile needed it, so the
		// ancestry holds the patches alone or the patches followed by exactly that commit.
		boolean aligned = extraCommits.equals(BigInteger.ONE);
		require(extraCommits.signum() == 0 || aligned && isAlignmentCommit(source),
			"Source ancestry differs from ordered patch count");
		Path temporary = Files.createTempDirectory(environment.temporaryDirectory(), "release-source-");
		try
		{
			Path reconstructed = temporary.resolve("source");
			run(List.of("git", "clone", "--shared", "--no-checkout", source.toRealPath().toString(),
				reconstructed.toString()));
			git(reconstructed, "checkout", "--detach", upstream);
			for (JsonNode patch : value.get("patches"))
			{
				Path path = patchRepository.resolve(text(patch.get("path"))).toRealPath();
				git(reconstructed, "-c", "core.longpaths=true", "apply", "--index", path.toString());
			}
			if (aligned)
			{
				// The alignment commit changes only the lockfile, so its tree effect is that same edit on the patched tree.
				WorkspaceLockAlignment.align(scope, reconstructed);
				git(reconstructed, "add", "--", WorkspaceLockAlignment.LOCK_PATH);
			}
			require(git(reconstructed, "write-tree").equals(text(value.get("source").get("tree"))),
				"Source tree is not pinned upstream plus the ordered raw patches");
		}
		finally
		{
			PathUtils.deleteDirectory(temporary, new LinkOption[]{LinkOption.NOFOLLOW_LINKS},
				StandardDeleteOption.OVERRIDE_READ_ONLY);
		}
	}

	/**
	 * Recognizes the commit that {@link WorkspaceLockAlignment} makes: the checkout's HEAD must carry that command's
	 * fixed author, committer and message and change no path other than the lockfile. An unrelated extra commit does
	 * not qualify, and the content of the lockfile edit is checked by the caller's tree replay.
	 *
	 * @param source supplied upstream source checkout
	 * @return {@code true} if HEAD is an alignment commit
	 * @throws IOException if a Git operation cannot be run
	 * @throws IllegalArgumentException if Git reports failure
	 */
	private boolean isAlignmentCommit(Path source) throws IOException
	{
		String identity = WorkspaceLockAlignment.COMMIT_NAME + " <" + WorkspaceLockAlignment.COMMIT_EMAIL + ">";
		return git(source, "log", "-1", "--format=%an <%ae>", "HEAD").equals(identity) &&
			git(source, "log", "-1", "--format=%cn <%ce>", "HEAD").equals(identity) &&
			git(source, "log", "-1", "--format=%B", "HEAD").equals(WorkspaceLockAlignment.COMMIT_MESSAGE) &&
			git(source, "diff-tree", "--no-commit-id", "--name-only", "-r", "HEAD").
				equals(WorkspaceLockAlignment.LOCK_PATH);
	}

	/**
	 * Reads the globally bounded queue document before invoking the existing queue parser.
	 *
	 * @param readme queue README path
	 * @return UTF-8 README content
	 * @throws IOException if the document cannot be read
	 * @throws IllegalArgumentException if its size exceeds the configuration bound
	 */
	private static String readReadme(Path readme) throws IOException
	{
		require(Files.size(readme) <= ReleaseManifestSchema.MAX_DOCUMENT_BYTES, "Patch README is too large");
		try (InputStream input = Files.newInputStream(readme))
		{
			byte[] content = input.readNBytes(ReleaseManifestSchema.MAX_DOCUMENT_BYTES + 1);
			require(content.length <= ReleaseManifestSchema.MAX_DOCUMENT_BYTES, "Patch README is too large");
			return UTF_8.newDecoder().decode(ByteBuffer.wrap(content)).toString();
		}
	}

	/**
	 * Invokes the trusted in-process queue parser, retaining only validated queue names.
	 *
	 * @param readme supplied fork's README path
	 * @return ordered parser output
	 * @throws IOException if the queue parser cannot read its input
	 * @throws IllegalArgumentException if queue parsing fails
	 */
	private List<String> patchQueue(Path readme) throws IOException
	{
		try (ByteArrayOutputStream output = new ByteArrayOutputStream();
			ByteArrayOutputStream errors = new ByteArrayOutputStream();
			PrintStream out = new PrintStream(output, true, UTF_8);
			PrintStream err = new PrintStream(errors, true, UTF_8))
		{
			int status = UpstreamAutoRelease.run(scope, new String[]{"patches", "--readme", readme.toString()}, out, err);
			require(status == 0, "Patch queue parser failed (exit " + status + "); inspect patches/README.md");
			String names = strip(output.toString(UTF_8));
			if (names.isEmpty())
				return List.of();
			return List.of(names.split("\\s+"));
		}
	}

	/**
	 * Hashes a regular file with bounded streaming reads, rejecting symbolic links.
	 *
	 * @param path supplied source file
	 * @return lowercase SHA256 digest
	 * @throws IOException if hashing fails
	 * @throws IllegalArgumentException if the path is not a regular file
	 */
	private static String digest(Path path) throws IOException
	{
		require(Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS), "Expected a regular file: " + path.getFileName());
		return Sha256.digest(path);
	}

	/**
	 * Runs Git against one explicit repository without a shell.
	 *
	 * @param repository Git checkout path
	 * @param arguments  Git operation and arguments
	 * @return stripped successful stdout
	 * @throws IOException if the process cannot be run
	 * @throws IllegalArgumentException if Git reports failure
	 */
	private String git(Path repository, String... arguments) throws IOException
	{
		List<String> command = new ArrayList<>(List.of("git", "-C", repository.toString()));
		command.addAll(Arrays.asList(arguments));
		return run(command);
	}

	/**
	 * Captures Git output without publishing captured stderr or credential-bearing command arguments.
	 *
	 * @param command direct Git argument vector
	 * @return stripped successful stdout
	 * @throws IOException if process capture fails
	 * @throws IllegalArgumentException if Git reports failure
	 */
	private String run(List<String> command) throws IOException
	{
		SystemCommands.Result result = capture.capture(command, environment.workingDirectory(),
			environment.temporaryDirectory(), environment.variables());
		require(result.status() == 0, command.get(0) + " " + command.get(1) + " failed (exit " + result.status() +
			"); inspect the supplied checkout or verifier");
		return strip(result.stdout());
	}

	/**
	 * Strips Unicode whitespace with the Python helper's text comparison semantics.
	 *
	 * @param value decoded process or file text
	 * @return text without leading or trailing Unicode whitespace
	 */
	private static String strip(String value)
	{
		int start = 0;
		int end = value.length();
		while (start < end && whitespace(value.codePointAt(start)))
			start += Character.charCount(value.codePointAt(start));
		while (end > start && whitespace(value.codePointBefore(end)))
			end -= Character.charCount(value.codePointBefore(end));
		return value.substring(start, end);
	}

	/**
	 * Recognizes Unicode whitespace including the next-line character used by Python's text stripping.
	 *
	 * @param codePoint Unicode scalar value
	 * @return whether the character is whitespace
	 */
	private static boolean whitespace(int codePoint)
	{
		return Character.isWhitespace(codePoint) || Character.isSpaceChar(codePoint) || codePoint == 0x85;
	}
}
