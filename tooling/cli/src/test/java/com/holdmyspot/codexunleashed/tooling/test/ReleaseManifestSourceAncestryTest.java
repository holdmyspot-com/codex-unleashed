package com.holdmyspot.codexunleashed.tooling.test;

import com.holdmyspot.codexunleashed.tooling.DefaultToolingScope;
import com.holdmyspot.codexunleashed.tooling.Main;
import com.holdmyspot.codexunleashed.tooling.ReleaseManifest;
import com.holdmyspot.codexunleashed.tooling.ReleaseManifestSource;
import com.holdmyspot.codexunleashed.tooling.SystemCommands;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.testng.annotations.Test;
import tools.jackson.databind.node.ObjectNode;
import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

/**
 * Checks that the release source validation accepts a patched checkout that ends with the lockfile alignment commit,
 * and nothing else beyond the ordered patches, using real Git checkouts.
 */
public final class ReleaseManifestSourceAncestryTest
{
	/**
	 * The upstream tag of the fixture.
	 */
	private static final String TAG = "rust-v0.167.0";
	/**
	 * Applies the queue's single patch, which adds one file.
	 */
	private static final String PATCH = """
		diff --git a/codex-rs/patched.txt b/codex-rs/patched.txt
		new file mode 100644
		--- /dev/null
		+++ b/codex-rs/patched.txt
		@@ -0,0 +1 @@
		+patched
		""";
	/**
	 * Makes the next commit with a fixed identity and no signing.
	 */
	private static final List<String> COMMIT = List.of("-c", "user.name=Test", "-c", "user.email=test@example.com", "-c",
		"commit.gpgsign=false", "commit", "-q");

	/**
	 * Constructs independent checkouts for each ancestry check.
	 */
	public ReleaseManifestSourceAncestryTest()
	{
	}

	/**
	 * Accepts a checkout that holds exactly the ordered patches.
	 *
	 * @throws IOException if a fixture file or Git process fails
	 */
	@Test
	public void acceptsPatchesAlone() throws IOException
	{
		try (Fixture fixture = new Fixture())
		{
			fixture.validate();
		}
	}

	/**
	 * Accepts the lockfile alignment commit that follows the patches, which the release makes when the lockfile still
	 * records the development version.
	 *
	 * @throws IOException if a fixture file or Git process fails
	 */
	@Test
	public void acceptsPatchesFollowedByTheAlignmentCommit() throws IOException
	{
		try (Fixture fixture = new Fixture())
		{
			fixture.align();
			requireThat(fixture.git(fixture.source, "rev-list", "--count", fixture.upstream + "..HEAD"), "count").
				isEqualTo("2");
			fixture.validate();
		}
	}

	/**
	 * Rejects an extra commit that is not the alignment commit.
	 *
	 * @throws IOException if a fixture file or Git process fails
	 */
	@Test(expectedExceptions = IllegalArgumentException.class,
		expectedExceptionsMessageRegExp = ".*Source ancestry differs from ordered patch count.*")
	public void rejectsAnUnrelatedExtraCommit() throws IOException
	{
		try (Fixture fixture = new Fixture())
		{
			fixture.commitFile("codex-rs/extra.txt", "extra\n", "Add an extra file");
			fixture.validate();
		}
	}

	/**
	 * Rejects an extra commit that carries the alignment message but also changes another path.
	 *
	 * @throws IOException if a fixture file or Git process fails
	 */
	@Test(expectedExceptions = IllegalArgumentException.class,
		expectedExceptionsMessageRegExp = ".*Source ancestry differs from ordered patch count.*")
	public void rejectsAnAlignmentMessageThatChangesAnotherPath() throws IOException
	{
		try (Fixture fixture = new Fixture())
		{
			Files.writeString(fixture.source.resolve("codex-rs/extra.txt"), "extra\n");
			fixture.alignmentLockEdit("0.167.0");
			fixture.git(fixture.source, "add", "-A");
			fixture.commitAsAlignment();
			fixture.validate();
		}
	}

	/**
	 * Rejects two extra commits, even when both are alignment commits.
	 *
	 * @throws IOException if a fixture file or Git process fails
	 */
	@Test(expectedExceptions = IllegalArgumentException.class,
		expectedExceptionsMessageRegExp = ".*Source ancestry differs from ordered patch count.*")
	public void rejectsASecondExtraCommit() throws IOException
	{
		try (Fixture fixture = new Fixture())
		{
			fixture.align();
			fixture.alignmentLockEdit("0.167.1");
			fixture.git(fixture.source, "add", "-A");
			fixture.commitAsAlignment();
			fixture.validate();
		}
	}

	/**
	 * Rejects a commit that has the alignment's identity, message and path but not its lockfile edit, which the tree
	 * replay detects.
	 *
	 * @throws IOException if a fixture file or Git process fails
	 */
	@Test(expectedExceptions = IllegalArgumentException.class,
		expectedExceptionsMessageRegExp = ".*Source tree is not pinned upstream plus the ordered raw patches.*")
	public void rejectsAnAlignmentCommitWithTheWrongLockfileEdit() throws IOException
	{
		try (Fixture fixture = new Fixture())
		{
			fixture.alignmentLockEdit("9.9.9");
			fixture.git(fixture.source, "add", "-A");
			fixture.commitAsAlignment();
			fixture.validate();
		}
	}

	/**
	 * An upstream checkout with its queue of one patch applied, and the fork that holds the queue.
	 */
	private static final class Fixture implements AutoCloseable
	{
		/**
		 * Owns every fixture directory.
		 */
		private final TestDirectory root;
		/**
		 * Borrowed JSON services.
		 */
		private final DefaultToolingScope scope = new DefaultToolingScope();
		/**
		 * The fork checkout that holds the patch queue.
		 */
		private final Path fork;
		/**
		 * The patched upstream checkout under validation.
		 */
		private final Path source;
		/**
		 * The pinned upstream commit.
		 */
		private final String upstream;
		/**
		 * The pinned fork commit.
		 */
		private final String forkCommit;

		/**
		 * Creates the upstream, the fork and the checkout with the patch applied and committed.
		 *
		 * @throws IOException if a fixture file or Git process fails
		 */
		private Fixture() throws IOException
		{
			root = new TestDirectory("source-ancestry-");
			try
			{
				Path upstreamRepository = root.createDirectories("upstream");
				root.write("upstream/codex-rs/Cargo.toml",
					"[workspace]\nmembers = [\"alpha\"]\n\n[workspace.package]\nversion = \"0.167.0\"\n");
				root.write("upstream/codex-rs/alpha/Cargo.toml", "[package]\nname = \"alpha\"\nversion.workspace = true\n");
				root.write("upstream/codex-rs/Cargo.lock", "[[package]]\nname = \"alpha\"\nversion = \"0.0.0\"\n");
				git(upstreamRepository, "init", "-q");
				git(upstreamRepository, "add", "-A");
				commit(upstreamRepository, "Upstream release");
				git(upstreamRepository, "tag", TAG);
				upstream = git(upstreamRepository, "rev-parse", "HEAD");

				fork = root.createDirectories("fork");
				root.write("fork/patches/README.md", "1. [One](one.patch)\n");
				root.write("fork/patches/one.patch", PATCH);
				git(fork, "init", "-q");
				git(fork, "add", "-A");
				commit(fork, "Fork queue");
				forkCommit = git(fork, "rev-parse", "HEAD");

				source = root.getPath().resolve("source");
				// A Windows runner's core.autocrlf=true would check the clone out with CRLF line endings.
				git(root.getPath(), "-c", "core.autocrlf=false", "clone", "-q", upstreamRepository.toString(),
					source.toString());
				git(source, "apply", "--index", fork.resolve("patches/one.patch").toString());
				commit(source, "Apply patch one");
			}
			catch (IOException | RuntimeException | Error failure)
			{
				close();
				throw failure;
			}
		}

		/**
		 * Aligns and commits the lockfile through the packaged command entry point.
		 */
		private void align()
		{
			ByteArrayOutputStream errors = new ByteArrayOutputStream();
			try (PrintStream out = new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8);
				PrintStream err = new PrintStream(errors, true, StandardCharsets.UTF_8))
			{
				int status = Main.run(new String[]{"align-workspace-lock", "--source-root", source.toString()},
					new ByteArrayInputStream(new byte[0]), out, err, Map.of());
				requireThat(status, "status").withContext(errors.toString(StandardCharsets.UTF_8), "diagnostics").
					isEqualTo(0);
			}
		}

		/**
		 * Sets the version of the lockfile's only package without staging it.
		 *
		 * @param version the version to record
		 * @throws IOException if the lockfile cannot be written
		 */
		private void alignmentLockEdit(String version) throws IOException
		{
			Files.writeString(source.resolve("codex-rs/Cargo.lock"),
				"[[package]]\nname = \"alpha\"\nversion = \"" + version + "\"\n");
		}

		/**
		 * Commits the staged changes with the identity and message of the alignment command.
		 *
		 * @throws IOException if Git fails
		 */
		private void commitAsAlignment() throws IOException
		{
			git(source, "-c", "user.name=Gili Tzabari", "-c", "user.email=cowwoc2020@gmail.com", "-c",
				"commit.gpgsign=false", "commit", "-q", "-m",
				"Align workspace package versions in Cargo.lock with the release version");
		}

		/**
		 * Commits one new file to the patched checkout.
		 *
		 * @param relative the file's path in the checkout
		 * @param content  the file's content
		 * @param message  the commit message
		 * @throws IOException if the file or Git fails
		 */
		private void commitFile(String relative, String content, String message) throws IOException
		{
			Files.writeString(source.resolve(relative), content);
			git(source, "add", "-A");
			commit(source, message);
		}

		/**
		 * Validates the patched checkout against the identity that the production code records for it.
		 *
		 * @throws IOException if Git or a fixture file fails
		 * @throws IllegalArgumentException if the validation rejects the checkout
		 */
		private void validate() throws IOException
		{
			ReleaseManifest.Environment environment = new ReleaseManifest.Environment(root.getPath(), root.getPath(),
				"test host", Map.of());
			ReleaseManifestSource verifier = new ReleaseManifestSource(scope, SystemCommands::capture, environment);
			ObjectNode value = scope.getJsonMapper().createObjectNode();
			value.put("fork_commit", forkCommit);
			value.set("patches", verifier.patches(fork, forkCommit));
			value.set("source", verifier.identity(source));
			value.putObject("upstream").put("commit", upstream).put("tag", TAG);
			verifier.validate(value, fork, source);
		}

		/**
		 * Commits the staged changes of a repository.
		 *
		 * @param repository the repository
		 * @param message    the commit message
		 * @throws IOException if Git fails
		 */
		private void commit(Path repository, String message) throws IOException
		{
			List<String> arguments = new ArrayList<>(COMMIT);
			arguments.addAll(List.of("-m", message));
			git(repository, arguments.toArray(String[]::new));
		}

		/**
		 * Runs Git in a directory and requires success.
		 *
		 * @param directory working directory
		 * @param arguments Git arguments
		 * @return standard output without its final line terminator
		 * @throws IOException if Git cannot run or fails
		 */
		private String git(Path directory, String... arguments) throws IOException
		{
			List<String> command = new ArrayList<>(List.of("git"));
			command.addAll(List.of(arguments));
			SystemCommands.Result result = SystemCommands.capture(command, directory, root.getPath());
			if (result.status() != 0)
				throw new IOException("git " + String.join(" ", arguments) + " failed: " + result.stderr());
			return result.stdout().strip();
		}

		@Override
		public void close() throws IOException
		{
			scope.close();
			root.close();
		}
	}
}
