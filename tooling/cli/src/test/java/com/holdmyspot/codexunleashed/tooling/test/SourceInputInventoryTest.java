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
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.apache.commons.io.file.PathUtils;
import org.apache.commons.io.file.StandardDeleteOption;
import org.testng.annotations.Test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

/**
 * Verifies raw source inventories with real Git and the defined filesystem fallback.
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
	 * Includes tracked modifications and nonignored untracked files while excluding build and download state.
	 *
	 * @throws IOException if fixture writing, Git, inventory, or cleanup fails
	 */
	@Test
	public void inventoriesActualGitSources() throws IOException
	{
		try (Fixture fixture = new Fixture())
		{
			SystemCommands.run(java.util.List.of("git", "init", "-q", fixture.root.toString()));
			Files.writeString(fixture.root.resolve(".gitignore"), "ignored\n");
			Path source = Files.createDirectories(fixture.root.resolve("workspace/src")).resolve("lib.rs");
			Files.writeString(source, "original");
			SystemCommands.run(java.util.List.of("git", "-c", "core.autocrlf=false", "-C", fixture.root.toString(),
				"add", ".gitignore", "workspace"));
			byte[] payload = {0, (byte) 255, 13, 10};
			Files.write(source, payload);
			Instant timestamp = Instant.ofEpochSecond(1_000_000_000L, 123_456_789);
			Files.setLastModifiedTime(source, FileTime.from(timestamp));
			Files.writeString(fixture.root.resolve("included.rs"), "untracked input");
			Files.writeString(fixture.root.resolve("ignored"), "ignored input");
			SourceInputInventory.Snapshot snapshot = SourceInputInventory.read(fixture.scope, source.getParent(), fixture.target,
				Optional.of(fixture.cargo), fixture.temporary, fixture.environment(), SystemCommands::capture);
			requireThat(snapshot.root(), "snapshot.root()").isEqualTo(fixture.root.toRealPath());
			requireThat(snapshot.files().keySet(), "snapshot.files().keySet()")
				.isEqualTo(Set.of(".gitignore", "workspace/src/lib.rs", "included.rs"));
			requireThat(snapshot.files().get("workspace/src/lib.rs"), "snapshot").isEqualTo(Sha256.digest(source));
			requireThat(snapshot.mtimes().get("workspace/src/lib.rs"), "snapshot")
				.isEqualTo(SourceInputTimes.nanos(NativeTimestampRange.expected(fixture.root, timestamp)));
			requireThat(snapshot.mtimes().containsKey("."), "snapshot").isTrue();
			requireThat(snapshot.mtimes().containsKey("workspace/src"), "snapshot").isTrue();
			requireThat(snapshot.files().containsKey("target/output"), "snapshot").isFalse();
			requireThat(snapshot.files().containsKey("cargo/download"), "snapshot").isFalse();
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
				Optional.of(fixture.cargo), fixture.temporary, fixture.environment(), SystemCommands::capture);
			requireThat(snapshot.root(), "snapshot.root()").isEqualTo(fixture.root.toRealPath());
			requireThat(snapshot.files(), "snapshot.files()").isEqualTo(Map.of("src/lib.rs", Sha256.digest(source)));
			requireThat(snapshot.mtimes().containsKey("src"), "snapshot").isTrue();
		}
	}

	/**
	 * Retains explicitly tracked download paths while always excluding compiled output files.
	 *
	 * @throws IOException if Git, inventory, or cleanup fails
	 */
	@Test
	public void retainsTrackedDownloadInputs() throws IOException
	{
		try (Fixture fixture = new Fixture())
		{
			SystemCommands.run(java.util.List.of("git", "init", "-q", fixture.root.toString()));
			SystemCommands.run(java.util.List.of("git", "-C", fixture.root.toString(), "add", "cargo", "target"));
			SourceInputInventory.Snapshot snapshot = SourceInputInventory.read(fixture.scope, fixture.root, fixture.target,
				Optional.of(fixture.cargo), fixture.temporary, fixture.environment(), SystemCommands::capture);
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
				Optional.of(fixture.cargo), fixture.temporary, fixture.environment(), SystemCommands::capture);
			requireThat(snapshot.mtimes().get("source.rs"), "snapshot")
				.isEqualTo(SourceInputTimes.nanos(NativeTimestampRange.expected(fixture.root, required)));
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
		 * Prevents Git from finding the surrounding project when exercising fallback.
		 *
		 * @return explicit Git ceiling environment
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
