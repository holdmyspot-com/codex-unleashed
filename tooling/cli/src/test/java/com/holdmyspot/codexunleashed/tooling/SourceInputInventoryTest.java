package com.holdmyspot.codexunleashed.tooling;

import java.io.IOException;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.Comparator;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;
import org.testng.annotations.Test;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertTrue;

/** Verifies raw source inventories with real Git and the defined filesystem fallback. */
public final class SourceInputInventoryTest
{
	/** Creates inventory tests. */
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
			SystemCommands.run(java.util.List.of("git", "-C", fixture.root.toString(), "add", ".gitignore", "workspace"));
			byte[] payload = {0, (byte) 255, 13, 10};
			Files.write(source, payload);
			Files.setLastModifiedTime(source, FileTime.from(Instant.ofEpochSecond(1_000_000_000L, 123_456_789)));
			Files.writeString(fixture.root.resolve("included.rs"), "untracked input");
			Files.writeString(fixture.root.resolve("ignored"), "ignored input");
			SourceInputInventory.Snapshot snapshot = SourceInputInventory.read(source.getParent(), fixture.target,
				Optional.of(fixture.cargo), fixture.temporary, fixture.environment());
			assertEquals(snapshot.root(), fixture.root.toRealPath());
			assertEquals(snapshot.files().keySet(), Set.of(".gitignore", "workspace/src/lib.rs", "included.rs"));
			assertEquals(snapshot.files().get("workspace/src/lib.rs"), Sha256.digest(source));
			assertEquals(snapshot.mtimes().get("workspace/src/lib.rs"), new BigInteger("1000000000123456789"));
			assertTrue(snapshot.mtimes().containsKey("."));
			assertTrue(snapshot.mtimes().containsKey("workspace/src"));
			assertFalse(snapshot.files().containsKey("target/output"));
			assertFalse(snapshot.files().containsKey("cargo/download"));
		}
	}

	/**
	 * Walks a checkout without Git using the retained directory exclusions and raw content hashes.
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
			SourceInputInventory.Snapshot snapshot = SourceInputInventory.read(fixture.root, fixture.target,
				Optional.of(fixture.cargo), fixture.temporary, fixture.environment());
			assertEquals(snapshot.root(), fixture.root.toRealPath());
			assertEquals(snapshot.files(), Map.of("src/lib.rs", Sha256.digest(source)));
			assertTrue(snapshot.mtimes().containsKey("src"));
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
			SourceInputInventory.Snapshot snapshot = SourceInputInventory.read(fixture.root, fixture.target,
				Optional.of(fixture.cargo), fixture.temporary, fixture.environment());
			assertEquals(snapshot.files().keySet(), Set.of("cargo/download"));
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
			SourceInputTimes.setTimes(source, FileTime.from(required));
			SourceInputInventory.Snapshot snapshot = SourceInputInventory.read(fixture.root, fixture.target,
				Optional.of(fixture.cargo), fixture.temporary, fixture.environment());
			assertEquals(snapshot.mtimes().get("source.rs"), SourceInputTimes.nanos(required));
		}
	}

	/** Owns files and subprocess captures under the maintained Maven temporary root. */
	private static final class Fixture implements AutoCloseable
	{
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

		@Override
		public void close() throws IOException
		{
			try (Stream<Path> files = Files.walk(base))
			{
				for (Path file : files.sorted(Comparator.reverseOrder()).toList())
					Files.delete(file);
			}
		}
	}
}
