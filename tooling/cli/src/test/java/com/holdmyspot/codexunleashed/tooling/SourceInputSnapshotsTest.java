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

import static com.holdmyspot.codexunleashed.scheduler.TestRequirements.requireException;
import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

/**
 * Verifies snapshot compatibility, conditional successful recording, and owned write cleanup.
 */
public final class SourceInputSnapshotsTest
{
	/**
	 * Creates snapshot tests.
	 */
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
			SourceInputSnapshots.write(fixture.scope, fixture.snapshot, snapshot);
			JsonNode document = SourceInputSnapshots.read(fixture.scope, fixture.snapshot);
			requireThat(document.path("schema_version").intValue(), "document").isEqualTo(1);
			requireThat(document.path("files").path("nested/é.rs").stringValue(), "document").isEqualTo("digest");
			requireThat(document.path("mtimes").path(".").bigIntegerValue(), "document").isEqualTo(time);
			requireThat(document.path("mtimes").path("nested/é.rs").bigIntegerValue(), "document").isEqualTo(time.negate());
			requireThat(Files.readString(fixture.snapshot).contains("\\u00e9"), "Files").isTrue();
			try (Stream<Path> files = Files.list(fixture.snapshot.getParent()))
			{
				requireThat(files.count(), "files.count()").isEqualTo(1L);
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
			SourceInputSnapshots.write(fixture.scope, fixture.pending(), fixture.inputs(Map.of("src/lib.rs", "same")));
			Map<String, String> files = Map.of("src/lib.rs", "same", "Cargo.lock", "new", "nested/Cargo.lock", "newer");
			requireThat(SourceInputSnapshots.record(fixture.scope, fixture.snapshot, fixture.inputs(files)), "SourceInputSnapshots")
				.isEqualTo(3);
			requireThat(Files.exists(fixture.pending()), "Files.exists(fixture.pending())").isFalse();
			requireThat(SourceInputSnapshots.read(fixture.scope, fixture.snapshot).path("files").size(), "SourceInputSnapshots")
				.isEqualTo(3);
		}
	}

	/**
	 * Keeps completed preparation distinct from successful state and refuses content drift.
	 *
	 * @throws IOException if checkpoint, recording, or fixture operations fail
	 */
	@Test
	public void checkpointsWithoutRecordingSuccess() throws IOException
	{
		try (Fixture fixture = new Fixture())
		{
			SourceInputInventory.Snapshot inputs = fixture.inputs(Map.of("source.rs", "prepared"));
			Path resumable = SourceInputSnapshots.resumable(fixture.snapshot);
			requireException(IOException.class, () -> SourceInputSnapshots.checkpoint(fixture.scope, fixture.snapshot, inputs));
			requireThat(Files.exists(resumable), "Files.exists(resumable)").isFalse();
			SourceInputSnapshots.write(fixture.scope, fixture.pending(), inputs);
			SourceInputSnapshots.checkpoint(fixture.scope, fixture.snapshot, inputs);
			requireThat(Files.exists(fixture.snapshot), "Files.exists(fixture.snapshot)").isFalse();
			requireThat(Files.isRegularFile(fixture.pending()), "Files.isRegularFile(fixture.pending())").isTrue();
			String checkpoint = Files.readString(resumable);
			requireException(IOException.class, () -> SourceInputSnapshots.checkpoint(fixture.scope, fixture.snapshot,
				fixture.inputs(Map.of("source.rs", "drifted"))));
			requireThat(Files.readString(resumable), "Files.readString(resumable)").isEqualTo(checkpoint);
			SourceInputSnapshots.record(fixture.scope, fixture.snapshot, inputs);
			requireThat(Files.isRegularFile(fixture.snapshot), "Files.isRegularFile(fixture.snapshot)").isTrue();
			requireThat(Files.exists(resumable), "Files.exists(resumable)").isFalse();
			requireThat(Files.exists(fixture.pending()), "Files.exists(fixture.pending())").isFalse();
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
			IOException missing = requireException(IOException.class, () -> SourceInputSnapshots.record(fixture.scope, fixture.snapshot, fixture.inputs(Map.of("source.rs", "first"))));
			requireThat(missing.getMessage().contains("were not prepared"), "missing").isTrue();
			SourceInputSnapshots.write(fixture.scope, fixture.pending(),
				fixture.inputs(Map.of("source.rs", "first", "removed.rs", "old", "unchanged.rs", "same")));
			IOException changed = requireException(IOException.class, () -> SourceInputSnapshots.record(fixture.scope, fixture.snapshot,
					fixture.inputs(Map.of("source.rs", "second", "added.rs", "new", "unchanged.rs", "same"))));
			requireThat(changed.getMessage().contains("Source inputs changed during the build"), "changed").isTrue();
			requireThat(changed.getMessage().contains("Differing paths: [added.rs, removed.rs, source.rs]"), "changed")
				.withContext(changed.getMessage(), "diagnostic")
				.isTrue();
			requireThat(Files.readString(fixture.snapshot), "Files.readString(fixture.snapshot)")
				.isEqualTo("existing successful inputs");
			requireThat(Files.isRegularFile(fixture.pending()), "Files.isRegularFile(fixture.pending())").isTrue();
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
			requireException(IOException.class, () -> SourceInputSnapshots.write(fixture.scope, fixture.snapshot, fixture.inputs(Map.of("source.rs", "hash"))));
			requireThat(Files.readString(fixture.snapshot.resolve("keep")), "Files").isEqualTo("existing output");
			try (Stream<Path> files = Files.list(fixture.snapshot.getParent()))
			{
				requireThat(files.count(), "files.count()").isEqualTo(1L);
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
				requireException(IOException.class, () -> SourceInputSnapshots.read(fixture.scope, fixture.snapshot));
				requireThat(Files.readString(fixture.snapshot), "Files.readString(fixture.snapshot)").isEqualTo(text);
			}
		}
	}

	/**
	 * Owns inputs and all snapshot artifacts under the maintained Maven temporary root.
	 */
	private static final class Fixture implements AutoCloseable
	{
		private final ToolingScope scope = new DefaultToolingScope();
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

		/**
		 * Deletes the fixture root and all files and directories it owns.
		 *
		 * @throws IOException if a fixture entry cannot be removed
		 */
		@Override
		public void close() throws IOException
		{
			scope.close();
			try (Stream<Path> files = Files.walk(root))
			{
				for (Path file : files.sorted(Comparator.reverseOrder()).toList())
					Files.delete(file);
			}
		}
	}
}
