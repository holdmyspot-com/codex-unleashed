package com.holdmyspot.codexunleashed.tooling.test;

import com.holdmyspot.codexunleashed.tooling.DefaultToolingScope;
import com.holdmyspot.codexunleashed.tooling.Sha256;
import com.holdmyspot.codexunleashed.tooling.SourceInputInventory;
import com.holdmyspot.codexunleashed.tooling.SourceInputTimes;
import com.holdmyspot.codexunleashed.tooling.SystemCommands;
import com.holdmyspot.codexunleashed.tooling.ToolingScope;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.apache.commons.io.file.PathUtils;
import org.apache.commons.io.file.StandardDeleteOption;
import org.testng.annotations.Test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

/**
 * Verifies raw source inventories from Git's reported paths and the defined filesystem fallback.
 * <p>
 * Git is replaced by a scripted command runner. Which paths Git reports is Git's documented behavior; these tests
 * check the commands the inventory issues and what it does with Git's answers.
 */
public final class SourceInputInventoryTest
{
	/**
	 * Creates inventory tests.
	 */
	public SourceInputInventoryTest()
	{
	}

	/**
	 * Inventories the files Git reports from the Git root, asking Git to leave cache trees out of untracked discovery.
	 *
	 * @throws IOException if fixture writing, inventory, or cleanup fails
	 */
	@Test
	public void inventoriesGitReportedSources() throws IOException
	{
		try (Fixture fixture = new Fixture())
		{
			Path source = Files.createDirectories(fixture.root.resolve("workspace/src")).resolve("lib.rs");
			byte[] payload = {0, (byte) 255, 13, 10};
			Files.write(source, payload);
			Instant timestamp = Instant.ofEpochSecond(1_000_000_000L, 123_456_789);
			Files.setLastModifiedTime(source, FileTime.from(timestamp));
			Files.writeString(fixture.root.resolve("included.rs"), "untracked input");
			Files.writeString(fixture.root.resolve("ignored"), "ignored input");
			Git git = new Git(fixture.root, ".gitignore\0workspace/src/lib.rs\0", "included.rs\0");
			Files.writeString(fixture.root.resolve(".gitignore"), "ignored\n");
			Map<String, String> originalEnvironment = Map.of("GIT_CEILING_DIRECTORIES", fixture.base.toString(), "LC_ALL",
				"caller-locale");
			SourceInputInventory.Snapshot snapshot = SourceInputInventory.read(fixture.scope, source.getParent(),
				fixture.target, Optional.of(fixture.cargo), fixture.temporary, originalEnvironment, git);
			Path root = fixture.root.toRealPath();
			requireThat(git.commands, "gitCommands").isEqualTo(List.of(
				List.of("git", "-C", source.getParent().toRealPath().toString(), "rev-parse", "--show-toplevel"),
				List.of("git", "-C", root.toString(), "ls-files", "--cached", "-z"),
				List.of("git", "-C", root.toString(), "ls-files", "--others", "--exclude-standard", "-z", "--", ".",
					":(exclude,literal)target", ":(exclude,literal)cargo")));
			requireThat(git.environments, "gitEnvironments").isEqualTo(List.of(
				Map.of("GIT_CEILING_DIRECTORIES", fixture.base.toString(), "LC_ALL", "C"), originalEnvironment,
				originalEnvironment));
			requireThat(originalEnvironment, "originalEnvironment").isEqualTo(Map.of(
				"GIT_CEILING_DIRECTORIES", fixture.base.toString(), "LC_ALL", "caller-locale"));
			requireThat(snapshot.root(), "snapshot.root()").isEqualTo(root);
			requireThat(snapshot.files().keySet(), "snapshot.files().keySet()").
				isEqualTo(Set.of(".gitignore", "workspace/src/lib.rs", "included.rs"));
			requireThat(snapshot.files().get("workspace/src/lib.rs"), "snapshot").isEqualTo(Sha256.digest(source));
			requireThat(snapshot.mtimes().get("workspace/src/lib.rs"), "snapshot").
				isEqualTo(SourceInputTimes.nanos(NativeTimestampRange.expected(fixture.root, timestamp)));
			requireThat(snapshot.mtimes().containsKey("."), "snapshot").isTrue();
			requireThat(snapshot.mtimes().containsKey("workspace/src"), "snapshot").isTrue();
		}
	}

	/**
	 * Reports a failed Git listing instead of inventorying a partial source set.
	 *
	 * @throws IOException if fixture writing or cleanup fails
	 */
	@Test
	public void rejectsFailedGitListing() throws IOException
	{
		try (Fixture fixture = new Fixture())
		{
			Git git = new Git(fixture.root, null, "");
			try
			{
				SourceInputInventory.read(fixture.scope, fixture.root, fixture.target, Optional.of(fixture.cargo),
					fixture.temporary, fixture.environment(), git);
				throw new AssertionError("A failed Git listing was accepted");
			}
			catch (IOException e)
			{
				requireThat(e.getMessage(), "message").contains("status 128").contains("listing failed");
			}
		}
	}

	/**
	 * Inventories raw file hashes outside a Git repository while pruning cache and dependency directories.
	 *
	 * @throws IOException if fixture writing, inventory, or cleanup fails
	 */
	@Test
	public void inventoriesWithoutGitRepository() throws IOException
	{
		try (Fixture fixture = new Fixture())
		{
			Path source = Files.createDirectories(fixture.root.resolve("src")).resolve("lib.rs");
			Files.writeString(source, "source");
			for (String directory : new String[]{".git", "node_modules", "nested/target"})
				Files.writeString(Files.createDirectories(fixture.root.resolve(directory)).resolve("excluded"), "ignored");
			SourceInputInventory.Snapshot snapshot = SourceInputInventory.read(fixture.scope, fixture.root, fixture.target,
				Optional.of(fixture.cargo), fixture.temporary, fixture.environment(), Git.ABSENT);
			requireThat(snapshot.root(), "snapshot.root()").isEqualTo(fixture.root.toRealPath());
			requireThat(snapshot.files(), "snapshot.files()").isEqualTo(Map.of("src/lib.rs", Sha256.digest(source)));
			requireThat(snapshot.mtimes().containsKey("src"), "snapshot").isTrue();
		}
	}

	/**
	 * Inventories filesystem-boundary workspaces only for Git's specific no-repository diagnostic.
	 *
	 * @throws IOException if fixture writing, inventory, or cleanup fails
	 */
	@Test
	public void inventoriesAtGitFilesystemBoundary() throws IOException
	{
		try (Fixture fixture = new Fixture())
		{
			Path source = Files.createDirectories(fixture.root.resolve("src")).resolve("lib.rs");
			Files.writeString(source, "source");
			String mountPoint = fixture.root.getRoot().toString();
			String diagnostic = "fatal: not a git repository (or any parent up to mount point " + mountPoint + ")\n" +
				"Stopping at filesystem boundary (GIT_DISCOVERY_ACROSS_FILESYSTEM not set).\n";
			Git git = new Git(fixture.root, null, null, new SystemCommands.Result(128, "", diagnostic));
			SourceInputInventory.Snapshot snapshot = SourceInputInventory.read(fixture.scope, fixture.root,
				fixture.target, Optional.of(fixture.cargo), fixture.temporary, fixture.environment(), git);
			requireThat(snapshot.files().keySet(), "snapshot.files().keySet()").isEqualTo(Set.of("src/lib.rs"));
		}
	}

	/**
	 * Rejects ownership, corrupt-repository, arbitrary status-128 and other discovery failures with their evidence.
	 *
	 * @throws IOException if fixture writing or cleanup fails
	 */
	@Test
	public void rejectsUnrecognizedGitDiscoveryFailures() throws IOException
	{
		try (Fixture fixture = new Fixture())
		{
			List<SystemCommands.Result> failures = List.of(
				new SystemCommands.Result(128, "", "fatal: detected dubious ownership in repository"),
				new SystemCommands.Result(128, "", "fatal: not a git repository: corrupt .git metadata"),
				new SystemCommands.Result(128, "", "fatal: arbitrary repository text"),
				new SystemCommands.Result(128, "", "fatal: not a git repository (or any parent up to mount point " +
					"relative)\nStopping at filesystem boundary (GIT_DISCOVERY_ACROSS_FILESYSTEM not set)."),
				new SystemCommands.Result(1, "", "git discovery failed"));
			for (SystemCommands.Result failure : failures)
			{
				Git git = new Git(fixture.root, null, null, failure);
				requireGitDiscoveryFailure(fixture, git, failure);
			}
		}
	}

	/**
	 * Propagates the capture error when Git discovery cannot start or complete.
	 *
	 * @throws IOException if fixture writing or cleanup fails
	 */
	@Test
	public void propagatesGitDiscoveryCaptureFailure() throws IOException
	{
		try (Fixture fixture = new Fixture())
		{
			IOException expected = new IOException("Git startup failed");
			SystemCommands.Capture capture = (command, directory, temporary, environment) ->
			{
				throw expected;
			};
			try
			{
				SourceInputInventory.read(fixture.scope, fixture.root, fixture.target, Optional.of(fixture.cargo),
					fixture.temporary, fixture.environment(), capture);
				throw new AssertionError("A Git discovery capture failure was accepted");
			}
			catch (IOException failure)
			{
				requireThat(failure, "failure").isEqualTo(expected);
			}
		}
	}

	/**
	 * Retains explicitly tracked download paths while always excluding compiled output files.
	 *
	 * @throws IOException if inventory or cleanup fails
	 */
	@Test
	public void retainsTrackedDownloadInputs() throws IOException
	{
		try (Fixture fixture = new Fixture())
		{
			Git git = new Git(fixture.root, "cargo/download\0target/output\0", "");
			SourceInputInventory.Snapshot snapshot = SourceInputInventory.read(fixture.scope, fixture.root, fixture.target,
				Optional.of(fixture.cargo), fixture.temporary, fixture.environment(), git);
			requireThat(snapshot.files().keySet(), "snapshot.files().keySet()").isEqualTo(Set.of("cargo/download"));
		}
	}

	/**
	 * Preserves native fractional timestamps beyond signed-long epoch nanoseconds.
	 *
	 * @throws IOException if native timestamp writes, inventory, or cleanup fails
	 */
	@Test
	public void inventoriesFarFutureNanoseconds() throws IOException
	{
		try (Fixture fixture = new Fixture())
		{
			Path source = fixture.root.resolve("source.rs");
			Files.writeString(source, "source");
			Instant required = Instant.parse("2300-01-01T00:00:00.123456789Z");
			SourceInputTimes.setTimes(fixture.scope, source, FileTime.from(required));
			SourceInputInventory.Snapshot snapshot = SourceInputInventory.read(fixture.scope, fixture.root, fixture.target,
				Optional.of(fixture.cargo), fixture.temporary, fixture.environment(), Git.ABSENT);
			requireThat(snapshot.mtimes().get("source.rs"), "snapshot").
				isEqualTo(SourceInputTimes.nanos(NativeTimestampRange.expected(fixture.root, required)));
		}
	}

	/**
	 * Requires discovery failures to identify their directory, status, and original diagnostic.
	 *
	 * @param fixture the owned inventory fixture
	 * @param git the scripted discovery result
	 * @param expected the status and diagnostic that must be preserved
	 * @throws IOException if fixture inventory fails before reporting the expected diagnostic
	 */
	private static void requireGitDiscoveryFailure(Fixture fixture, Git git, SystemCommands.Result expected)
		throws IOException
	{
		try
		{
			SourceInputInventory.read(fixture.scope, fixture.root, fixture.target, Optional.of(fixture.cargo),
				fixture.temporary, fixture.environment(), git);
			throw new AssertionError("An unrecognized Git discovery failure was accepted");
		}
		catch (IOException failure)
		{
				requireThat(failure.getMessage(), "message").isEqualTo("Git root discovery failed in " + fixture.root +
					" with status " + expected.status() + ": " + expected.stderr());
		}
	}

	/**
	 * Answers Git commands with scripted results and records each one.
	 */
	private static final class Git implements SystemCommands.Capture
	{
		/**
		 * Reports Git's ordinary no-repository diagnostic.
		 */
		private static final SystemCommands.Capture ABSENT = (command, directory, temporary, environment) ->
			new SystemCommands.Result(128, "", "fatal: not a git repository (or any of the parent directories): .git");
		/**
		 * The repository root returned for successful scripted discovery.
		 */
		private final Path root;
		/**
		 * NUL-separated tracked paths, or {@code null} if the listing fails.
		 */
		private final String tracked;
		/**
		 * NUL-separated untracked, nonignored paths.
		 */
		private final String untracked;
		/**
		 * The scripted result of Git root discovery.
		 */
		private final SystemCommands.Result discovery;
		/**
		 * Git commands received, in order.
		 */
		private final List<List<String>> commands = new ArrayList<>();
		/**
		 * Git environment overrides received, in order.
		 */
		private final List<Map<String, String>> environments = new ArrayList<>();

		/**
		 * Creates a scripted repository.
		 *
		 * @param root the repository root
		 * @param tracked NUL-separated tracked paths, or {@code null} to fail the listing
		 * @param untracked NUL-separated untracked paths
		 */
		private Git(Path root, String tracked, String untracked)
		{
			this(root, tracked, untracked, new SystemCommands.Result(0, "", ""));
		}

		/**
		 * Creates a scripted Git result for root discovery and source listings.
		 *
		 * @param root the repository root
		 * @param tracked NUL-separated tracked paths, or {@code null} to fail the listing
		 * @param untracked NUL-separated untracked paths
		 * @param discovery the scripted root-discovery result
		 */
		private Git(Path root, String tracked, String untracked, SystemCommands.Result discovery)
		{
			this.root = root;
			this.tracked = tracked;
			this.untracked = untracked;
			this.discovery = discovery;
		}

		@Override
		public SystemCommands.Result capture(List<String> command, Path workingDirectory, Path temporaryDirectory,
			Map<String, String> environment) throws IOException
		{
			commands.add(List.copyOf(command));
			environments.add(Map.copyOf(environment));
			if (command.contains("rev-parse"))
			{
				if (discovery.status() == 0)
					return new SystemCommands.Result(0, root.toRealPath() + "\n", "");
				return discovery;
			}
			if (command.contains("--cached"))
			{
				if (tracked == null)
					return new SystemCommands.Result(128, "", "listing failed");
				return new SystemCommands.Result(0, tracked, "");
			}
			return new SystemCommands.Result(0, untracked, "");
		}
	}

	/**
	 * Owns files and subprocess captures under the maintained Maven temporary root.
	 */
	private static final class Fixture implements AutoCloseable
	{
		/** Shared native bindings owned until inventory and timestamp operations finish. */
		private final ToolingScope scope = new DefaultToolingScope();
		/** Temporary root removed when the fixture closes. */
		private final Path base = Files.createTempDirectory("source-input-inventory-");
		/** Workspace whose regular files become fallback inventory candidates. */
		private final Path root = Files.createDirectory(base.resolve("checkout"));
		/** Compiled outputs excluded from every inventory. */
		private final Path target = Files.createDirectory(root.resolve("target"));
		/** Download cache excluded from untracked and filesystem fallback inputs. */
		private final Path cargo = Files.createDirectory(root.resolve("cargo"));
		/** Existing parent for any subprocess capture files. */
		private final Path temporary = Files.createDirectory(base.resolve("temporary"));

		/**
		 * Creates excluded cache markers.
		 *
		 * @throws IOException if fixture allocation or writing fails
		 */
		private Fixture() throws IOException
		{
			Files.writeString(target.resolve("output"), "compiled output");
			Files.writeString(cargo.resolve("download"), "download state");
		}

		/**
		 * Returns the environment overrides the inventory passes to Git.
		 *
		 * @return the Git ceiling environment
		 */
		private Map<String, String> environment()
		{
			return Map.of("GIT_CEILING_DIRECTORIES", base.toString());
		}

		/**
		 * Deletes the fixture root and all files and directories it owns.
		 *
		 * @throws IOException if a fixture entry cannot be removed
		 */
		@Override
		public void close() throws IOException
		{
			scope.close();
			PathUtils.deleteDirectory(base, new LinkOption[]{LinkOption.NOFOLLOW_LINKS},
				StandardDeleteOption.OVERRIDE_READ_ONLY);
		}
	}
}
