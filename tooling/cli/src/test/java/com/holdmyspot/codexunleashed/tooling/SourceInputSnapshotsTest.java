package com.holdmyspot.codexunleashed.tooling;

import java.io.IOException;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Map;
import java.util.stream.Stream;
import org.testng.annotations.Test;
import tools.jackson.databind.JsonNode;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertTrue;
import static org.testng.Assert.expectThrows;

/** Verifies snapshot compatibility, conditional successful recording, and owned write cleanup. */
public final class SourceInputSnapshotsTest
{
	/** Creates snapshot tests. */
	public SourceInputSnapshotsTest()
	{
	}

	/**
	 * Serializes schema-one data without losing timestamps beyond the signed-long nanosecond range.
	 *
	 * @throws IOException if snapshot access or cleanup fails
	 */
	@Test
	public void roundTripsExactSnapshotValues() throws IOException
	{
		try (Fixture fixture = new Fixture())
		{
			BigInteger time = new BigInteger("18446744073709551616123");
			SourceInputInventory.Snapshot snapshot = new SourceInputInventory.Snapshot(fixture.root,
				Map.of("nested/é.rs", "digest"), Map.of(".", time, "nested/é.rs", time.negate()));
			SourceInputSnapshots.write(fixture.snapshot, snapshot);
			JsonNode document = SourceInputSnapshots.read(fixture.snapshot);
			assertEquals(document.path("schema_version").intValue(), 1);
			assertEquals(document.path("files").path("nested/é.rs").stringValue(), "digest");
			assertEquals(document.path("mtimes").path(".").bigIntegerValue(), time);
			assertEquals(document.path("mtimes").path("nested/é.rs").bigIntegerValue(), time.negate());
			assertTrue(Files.readString(fixture.snapshot).contains("\\u00e9"));
			try (Stream<Path> files = Files.list(fixture.snapshot.getParent()))
			{
				assertEquals(files.count(), 1L);
			}
		}
	}

	/**
	 * Records unchanged prepared files while accepting lockfile creation or refresh at any depth.
	 *
	 * @throws IOException if writing, recording, or cleanup fails
	 */
	@Test
	public void recordsSuccessfulInputsIgnoringLocks() throws IOException
	{
		try (Fixture fixture = new Fixture())
		{
			SourceInputSnapshots.write(fixture.pending(), fixture.inputs(Map.of("src/lib.rs", "same")));
			Map<String, String> files = Map.of("src/lib.rs", "same", "Cargo.lock", "new", "nested/Cargo.lock", "newer");
			assertEquals(SourceInputSnapshots.record(fixture.snapshot, fixture.inputs(files)), 3);
			assertFalse(Files.exists(fixture.pending()));
			assertEquals(SourceInputSnapshots.read(fixture.snapshot).path("files").size(), 3);
		}
	}

	/**
	 * Refuses altered sources and missing preparation without replacing existing successful state.
	 *
	 * @throws IOException if snapshot access or cleanup fails
	 */
	@Test
	public void preservesStateAfterRefusedRecording() throws IOException
	{
		try (Fixture fixture = new Fixture())
		{
			Files.createDirectories(fixture.snapshot.getParent());
			Files.writeString(fixture.snapshot, "existing successful inputs");
			IOException missing = expectThrows(IOException.class,
				() -> SourceInputSnapshots.record(fixture.snapshot, fixture.inputs(Map.of("source.rs", "first"))));
			assertTrue(missing.getMessage().contains("were not prepared"));
			SourceInputSnapshots.write(fixture.pending(), fixture.inputs(Map.of("source.rs", "first")));
			IOException changed = expectThrows(IOException.class,
				() -> SourceInputSnapshots.record(fixture.snapshot, fixture.inputs(Map.of("source.rs", "second"))));
			assertTrue(changed.getMessage().contains("Source inputs changed during the build"));
			assertEquals(Files.readString(fixture.snapshot), "existing successful inputs");
			assertTrue(Files.isRegularFile(fixture.pending()));
		}
	}

	/**
	 * Removes its temporary sibling after replacement fails while preserving the original destination.
	 *
	 * @throws IOException if fixture access or cleanup fails
	 */
	@Test
	public void cleansFailedReplacement() throws IOException
	{
		try (Fixture fixture = new Fixture())
		{
			Files.createDirectories(fixture.snapshot);
			Files.writeString(fixture.snapshot.resolve("keep"), "existing output");
			expectThrows(IOException.class,
				() -> SourceInputSnapshots.write(fixture.snapshot, fixture.inputs(Map.of("source.rs", "hash"))));
			assertEquals(Files.readString(fixture.snapshot.resolve("keep")), "existing output");
			try (Stream<Path> files = Files.list(fixture.snapshot.getParent()))
			{
				assertEquals(files.count(), 1L);
			}
		}
	}

	/**
	 * Rejects malformed or trailing JSON rather than accepting a partial cache document.
	 *
	 * @throws IOException if fixture access or cleanup fails
	 */
	@Test
	public void rejectsMalformedDocuments() throws IOException
	{
		try (Fixture fixture = new Fixture())
		{
			Files.createDirectories(fixture.snapshot.getParent());
			for (String text : new String[]{"{", "{} {}", "", " "})
			{
				Files.writeString(fixture.snapshot, text);
				expectThrows(IOException.class, () -> SourceInputSnapshots.read(fixture.snapshot));
				assertEquals(Files.readString(fixture.snapshot), text);
			}
		}
	}

	/** Owns inputs and all snapshot artifacts under the maintained Maven temporary root. */
	private static final class Fixture implements AutoCloseable
	{
		private final Path root = Files.createTempDirectory("source-input-snapshots-");
		private final Path snapshot = root.resolve("cache/target.json");

		/**
		 * Allocates fixture ownership.
		 *
		 * @throws IOException if allocation fails
		 */
		private Fixture() throws IOException
		{
		}

		/**
		 * Returns the prepared snapshot path.
		 *
		 * @return the pending cache path
		 */
		private Path pending()
		{
			return snapshot.resolveSibling("target.pending");
		}

		/**
		 * Creates a digest inventory without timestamps irrelevant to recording acceptance.
		 *
		 * @param files exact prepared hashes
		 * @return the inventory
		 */
		private SourceInputInventory.Snapshot inputs(Map<String, String> files)
		{
			return new SourceInputInventory.Snapshot(root, files, Map.of());
		}

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
