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
			SourceInputInventory.Snapshot snapshot = SourceInputInventory.read(fixture.scope, source.getParent(), fixture.target,
				Optional.of(fixture.cargo), fixture.temporary, fixture.environment(), git);
			Path root = fixture.root.toRealPath();
			requireThat(git.commands, "gitCommands").isEqualTo(List.of(
				List.of("git", "-C", source.getParent().toRealPath().toString(), "rev-parse", "--show-toplevel"),
				List.of("git", "-C", root.toString(), "ls-files", "--cached", "-z"),
				List.of("git", "-C", root.toString(), "ls-files", "--others", "--exclude-standard", "-z", "--", ".",
					":(exclude,literal)target", ":(exclude,literal)cargo")));
			requireThat(git.environments, "gitEnvironments").isEqualTo(List.of(fixture.environment(),
				fixture.environment(), fixture.environment()));
			requireThat(snapshot.root(), "snapshot.root()").isEqualTo(root);
			requireThat(snapshot.files().keySet(), "snapshot.files().keySet()")
				.isEqualTo(Set.of(".gitignore", "workspace/src/lib.rs", "included.rs"));
			requireThat(snapshot.files().get("workspace/src/lib.rs"), "snapshot").isEqualTo(Sha256.digest(source));
			requireThat(snapshot.mtimes().get("workspace/src/lib.rs"), "snapshot")
				.isEqualTo(SourceInputTimes.nanos(NativeTimestampRange.expected(fixture.root, timestamp)));
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
			requireThat(snapshot.mtimes().get("source.rs"), "snapshot")
				.isEqualTo(SourceInputTimes.nanos(NativeTimestampRange.expected(fixture.root, required)));
		}
	}

	/**
	 * Answers Git commands with scripted results and records each one.
	 */
	private static final class Git implements SystemCommands.Capture
	{
		/** Reports that no Git repository contains the working directory. */
		private static final SystemCommands.Capture ABSENT = (command, directory, temporary, environment) ->
			new SystemCommands.Result(128, "", "fatal: not a git repository");
		/** The Git root that discovery reports. */
		private final Path root;
		/** NUL-separated tracked paths, or {@code null} if the listing fails. */
		private final String tracked;
		/** NUL-separated untracked, nonignored paths. */
		private final String untracked;
		/** The commands received, in order. */
		private final List<List<String>> commands = new ArrayList<>();
		/** The environment overrides received, in order. */
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
			this.root = root;
			this.tracked = tracked;
			this.untracked = untracked;
		}

		@Override
		public SystemCommands.Result capture(List<String> command, Path workingDirectory, Path temporaryDirectory,
			Map<String, String> environment) throws IOException
		{
			commands.add(List.copyOf(command));
			environments.add(Map.copyOf(environment));
			if (command.contains("rev-parse"))
				return new SystemCommands.Result(0, root.toRealPath() + "\n", "");
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
		private final Path base = Files.createTempDirectory("source-input-inventory-");
		private final Path root = Files.createDirectory(base.resolve("checkout"));
		private final Path target = Files.createDirectory(root.resolve("target"));
		private final Path cargo = Files.createDirectory(root.resolve("cargo"));
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
