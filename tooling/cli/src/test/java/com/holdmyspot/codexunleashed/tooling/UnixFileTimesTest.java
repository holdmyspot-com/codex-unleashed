package com.holdmyspot.codexunleashed.tooling;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;
import org.testng.annotations.Test;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertTrue;
import static org.testng.Assert.expectThrows;

/** Checks actual native timestamps against an independent filesystem reader. */
public final class UnixFileTimesTest
{
	/** Creates native timestamp tests. */
	public UnixFileTimesTest()
	{
	}

	/**
	 * Confirms access and modification nanoseconds independently of Java's metadata conversion.
	 *
	 * @throws IOException if subprocesses, native operations, or cleanup fail
	 */
	@Test
	public void preservesExactNativeTimestamps() throws IOException
	{
		try (Fixture fixture = new Fixture())
		{
			Instant[] values = {Instant.ofEpochSecond(-1, 999_999_999), Instant.ofEpochSecond(0, 1),
				Instant.parse("2300-01-01T00:00:00.123456789Z")};
			for (Instant value : values)
			{
				Instant expected = NativeTimestampRange.expected(fixture.root, value);
				String expectedNanos = SourceInputTimes.nanos(expected).toString();
				SourceInputTimes.setTimes(fixture.file, FileTime.from(value));
				assertEquals(NativeTimestampRange.inspect(fixture.file, fixture.temporary),
					List.of(expectedNanos, expectedNanos));
				assertEquals(SourceInputTimes.lastModified(fixture.file), expected);
			}
		}
	}

	/**
	 * Reports native filesystem failures instead of replacing them with successful rounded operations.
	 *
	 * @throws IOException if fixture allocation or cleanup fails
	 */
	@Test
	public void reportsNativeFilesystemFailures() throws IOException
	{
		try (Fixture fixture = new Fixture())
		{
			Path missing = fixture.root.resolve("absent");
			boolean windows = fixture.root.getFileSystem().getSeparator().equals("\\");
			IOException write = expectThrows(IOException.class,
				() -> SourceInputTimes.setTimes(missing, FileTime.from(Instant.ofEpochSecond(-1, 999_999_999))));
			assertTrue(write.getMessage().contains(missing.toString()));
			String errorKey = "errno=";
			if (windows)
				errorKey = "GetLastError=";
			assertTrue(write.getMessage().contains(errorKey));
			IOException read = expectThrows(IOException.class, () ->
			{
				if (windows)
					WindowsFileTimes.lastModified(missing);
				else
					UnixFileMetadata.lastModified(missing);
			});
			assertTrue(read.getMessage().contains(missing.toString()));
			assertTrue(read.getMessage().contains(errorKey));
		}
	}

	/** Owns native inputs and subprocess captures under Maven temporary storage. */
	private static final class Fixture implements AutoCloseable
	{
		private final Path root = Files.createTempDirectory("unix-file-times-");
		private final Path temporary = Files.createDirectory(root.resolve("captures"));
		private final Path file = Files.writeString(root.resolve("source"), "owned native timestamp input");

		/**
		 * Allocates fixture ownership.
		 *
		 * @throws IOException if allocation fails
		 */
		private Fixture() throws IOException
		{
		}

		@Override
		public void close() throws IOException
		{
			try (Stream<Path> paths = Files.walk(root))
			{
				for (Path path : paths.sorted(Comparator.reverseOrder()).toList())
					Files.delete(path);
			}
		}
	}
}
