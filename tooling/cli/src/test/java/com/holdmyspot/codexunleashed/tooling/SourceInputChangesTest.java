package com.holdmyspot.codexunleashed.tooling;

import java.io.IOException;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Comparator;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;
import org.testng.annotations.Test;
import tools.jackson.databind.JsonNode;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertTrue;
import static org.testng.Assert.expectThrows;

/** Verifies content-based change marking, interrupted-build protection, and timestamp confinement. */
public final class SourceInputChangesTest
{
	private static final Instant BEFORE = Instant.ofEpochSecond(1_000_000_000L, 123_456_789);
	private static final Instant NOW = Instant.ofEpochSecond(1_600_000_000L, 987_654_321);
	private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

	/** Creates source-change tests. */
	public SourceInputChangesTest()
	{
	}

	/**
	 * Restores unchanged files and marks changed and removed file parents using actual file attributes.
	 *
	 * @throws IOException if writing, change application, or cleanup fails
	 */
	@Test
	public void restoresAndMarksActualFiles() throws IOException
	{
		try (Fixture fixture = new Fixture())
		{
			Path unchanged = fixture.file("src/unchanged.rs");
			Path changed = fixture.file("src/changed.rs");
			JsonNode previous = fixture.previous(Map.of("src/unchanged.rs", "same", "src/changed.rs", "old",
				"src/deleted.rs", "deleted"), Map.of(".", fixture.nanos(BEFORE), "src", fixture.nanos(BEFORE),
				"src/unchanged.rs", fixture.nanos(BEFORE), "src/changed.rs", fixture.nanos(BEFORE)));
			Set<String> result = SourceInputChanges.apply(fixture.current(Map.of("src/unchanged.rs", "same",
				"src/changed.rs", "new")), previous, Optional.empty(), CLOCK);
			assertEquals(result, Set.of("src/changed.rs", "src/deleted.rs"));
			assertEquals(Files.getLastModifiedTime(unchanged).toInstant(), BEFORE);
			assertEquals(Files.readAttributes(unchanged, BasicFileAttributes.class).lastAccessTime().toInstant(), BEFORE);
			assertEquals(Files.getLastModifiedTime(changed).toInstant(), NOW);
			assertEquals(Files.getLastModifiedTime(changed.getParent()).toInstant(), NOW);
			assertEquals(Files.getLastModifiedTime(fixture.root).toInstant(), NOW);
		}
	}

	/**
	 * Invalidates a reverted source if an interrupted build used a different version of that source.
	 *
	 * @throws IOException if fixture access or cleanup fails
	 */
	@Test
	public void detectsInterruptedRevertedSources() throws IOException
	{
		try (Fixture fixture = new Fixture())
		{
			Path source = fixture.file("source.rs");
			JsonNode previous = fixture.previous(Map.of("source.rs", "successful"), Map.of());
			JsonNode interrupted = fixture.previous(Map.of("source.rs", "partially-built"), Map.of());
			assertEquals(SourceInputChanges.apply(fixture.current(Map.of("source.rs", "successful")), previous,
				Optional.of(interrupted), CLOCK), Set.of("source.rs"));
			assertEquals(Files.getLastModifiedTime(source).toInstant(), NOW);
		}
	}

	/**
	 * Retains schema equality and optional timestamp behavior from existing snapshots.
	 *
	 * @throws IOException if fixture access or cleanup fails
	 */
	@Test
	public void acceptsRetainedSchemaValues() throws IOException
	{
		try (Fixture fixture = new Fixture())
		{
			for (String schema : new String[]{"1", "1.0", "true"})
			{
				JsonNode previous = fixture.document("{\"schema_version\":" + schema + ",\"files\":{}}");
				assertEquals(SourceInputChanges.apply(fixture.current(Map.of()), previous, Optional.empty(), CLOCK), Set.of());
			}
			for (String schema : new String[]{"0", "false", "\"1\"", "null"})
			{
				JsonNode previous = fixture.document("{\"schema_version\":" + schema + ",\"files\":{}}");
				IOException failure = expectThrows(IOException.class,
					() -> SourceInputChanges.apply(fixture.current(Map.of()), previous, Optional.empty(), CLOCK));
				assertTrue(failure.getMessage().contains("Unsupported source-input cache snapshot"));
			}
		}
	}

	/**
	 * Rejects cached timestamp paths outside the checkout before touching the unrelated file.
	 *
	 * @throws IOException if fixture access or cleanup fails
	 */
	@Test
	public void rejectsEscapingTimestampPaths() throws IOException
	{
		try (Fixture fixture = new Fixture())
		{
			Path outside = fixture.base.resolve("outside");
			Files.writeString(outside, "unrelated input");
			FileTime timestamp = Files.getLastModifiedTime(outside);
			JsonNode previous = fixture.previous(Map.of(), Map.of("../outside", fixture.nanos(BEFORE)));
			IOException failure = expectThrows(IOException.class,
				() -> SourceInputChanges.apply(fixture.current(Map.of()), previous, Optional.empty(), CLOCK));
			assertTrue(failure.getMessage().contains("Source-input cache path escapes checkout"));
			assertEquals(Files.getLastModifiedTime(outside), timestamp);
		}
	}

	/**
	 * Checks symbolic-link target identity before restoring or marking a source timestamp.
	 *
	 * @throws IOException if fixture access or cleanup fails
	 */
	@Test
	public void rejectsEscapingSourceLinks() throws IOException
	{
		try (Fixture fixture = new Fixture())
		{
			Path outside = fixture.base.resolve("outside");
			Files.writeString(outside, "unrelated input");
			FileTime timestamp = Files.getLastModifiedTime(outside);
			Files.createSymbolicLink(fixture.root.resolve("source.rs"), outside);
			JsonNode previous = fixture.previous(Map.of("source.rs", "before"), Map.of());
			expectThrows(IOException.class, () -> SourceInputChanges.apply(fixture.current(Map.of("source.rs", "after")),
				previous, Optional.empty(), CLOCK));
			assertEquals(Files.getLastModifiedTime(outside), timestamp);
		}
	}

	/**
	 * Restores pre-epoch and boolean integer timestamps while leaving unused missing-file values unconsumed.
	 *
	 * @throws IOException if timestamp writes or cleanup fail
	 */
	@Test
	public void preservesTimestampValueBoundaries() throws IOException
	{
		try (Fixture fixture = new Fixture())
		{
			Path source = fixture.file("source.rs");
			for (String value : new String[]{"-1", "true", "false"})
			{
				JsonNode previous = fixture.document("{\"schema_version\":1,\"files\":{\"source.rs\":\"same\"}," +
					"\"mtimes\":{\"source.rs\":" + value + ",\"missing\":\"unused timestamp\"}}");
				assertEquals(SourceInputChanges.apply(fixture.current(Map.of("source.rs", "same")), previous,
					Optional.empty(), CLOCK), Set.of());
				Instant expected = Instant.EPOCH;
				if (value.equals("-1"))
					expected = Instant.ofEpochSecond(-1, 999_999_999);
				else if (value.equals("true"))
					expected = Instant.ofEpochSecond(0, 1);
				assertEquals(Files.getLastModifiedTime(source).toInstant(), expected);
			}
		}
	}

	/**
	 * Resolves a dangling source link before deciding whether an absent timestamp path is confined.
	 *
	 * @throws IOException if fixture access or cleanup fails
	 */
	@Test
	public void rejectsDanglingEscapingLinks() throws IOException
	{
		try (Fixture fixture = new Fixture())
		{
			Files.createSymbolicLink(fixture.root.resolve("source.rs"), fixture.base.resolve("missing-outside"));
			JsonNode previous = fixture.previous(Map.of("source.rs", "same"),
				Map.of("source.rs", fixture.nanos(BEFORE)));
			expectThrows(IOException.class, () -> SourceInputChanges.apply(fixture.current(Map.of("source.rs", "same")),
				previous, Optional.empty(), CLOCK));
		}
	}

	/** Owns source files, snapshot documents, and unrelated-file controls beneath Maven temporary storage. */
	private static final class Fixture implements AutoCloseable
	{
		private final Path base = Files.createTempDirectory("source-input-changes-");
		private final Path root = Files.createDirectory(base.resolve("checkout"));

		/**
		 * Allocates fixture ownership.
		 *
		 * @throws IOException if allocation fails
		 */
		private Fixture() throws IOException
		{
		}

		/**
		 * Creates a source file.
		 *
		 * @param name repository-relative name
		 * @return the written file
		 * @throws IOException if writing fails
		 */
		private Path file(String name) throws IOException
		{
			Path file = root.resolve(name);
			Files.createDirectories(file.getParent());
			Files.writeString(file, "source");
			return file;
		}

		/**
		 * Creates collected inputs used by the change policy.
		 *
		 * @param files content digests
		 * @return the current inventory
		 */
		private SourceInputInventory.Snapshot current(Map<String, String> files)
		{
			return new SourceInputInventory.Snapshot(root, files, Map.of());
		}

		/**
		 * Writes and reads a compatible successful or interrupted snapshot.
		 *
		 * @param files stored digests
		 * @param times stored nanosecond timestamps
		 * @return the parsed snapshot
		 * @throws IOException if snapshot I/O fails
		 */
		private JsonNode previous(Map<String, String> files, Map<String, BigInteger> times) throws IOException
		{
			Path stored = base.resolve("previous.json");
			SourceInputSnapshots.write(stored, new SourceInputInventory.Snapshot(root, files, times));
			return SourceInputSnapshots.read(stored);
		}

		/**
		 * Parses controlled schema spellings.
		 *
		 * @param text the exact JSON document
		 * @return the parsed snapshot
		 * @throws IOException if writing or parsing fails
		 */
		private JsonNode document(String text) throws IOException
		{
			Path stored = base.resolve("document.json");
			Files.writeString(stored, text);
			return SourceInputSnapshots.read(stored);
		}

		/**
		 * Produces exact epoch nanoseconds for retained timestamp fixtures.
		 *
		 * @param instant the source timestamp
		 * @return its exact nanosecond value
		 */
		private BigInteger nanos(Instant instant)
		{
			return BigInteger.valueOf(instant.getEpochSecond()).multiply(BigInteger.valueOf(1_000_000_000L)).
				add(BigInteger.valueOf(instant.getNano()));
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
