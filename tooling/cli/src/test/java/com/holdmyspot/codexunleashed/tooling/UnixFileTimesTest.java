package com.holdmyspot.codexunleashed.tooling;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.testng.annotations.Test;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertTrue;
import static org.testng.Assert.expectThrows;

/** Checks actual native timestamps against an independent Node filesystem reader. */
public final class UnixFileTimesTest
{
	private static final String INSPECT = "const fs=require('node:fs');" +
		"const value=fs.statSync(process.argv[1],{bigint:true});" +
		"process.stdout.write(value.mtimeNs+'\\n'+value.atimeNs+'\\n');";

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
			String[] expected = {"-1", "1", "10413792000123456789"};
			for (int index = 0; index < values.length; index += 1)
			{
				SourceInputTimes.setTimes(fixture.file, FileTime.from(values[index]));
				SystemCommands.Result inspection = SystemCommands.capture(List.of("node", "-e", INSPECT,
					fixture.file.toString()), fixture.root, fixture.temporary, Map.of());
				assertEquals(inspection.status(), 0, inspection.stderr());
				assertEquals(inspection.stdout(), expected[index] + "\n" + expected[index] + "\n");
				assertEquals(SourceInputTimes.lastModified(fixture.file), values[index]);
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
			IOException write = expectThrows(IOException.class,
				() -> UnixFileTimes.set(missing, Instant.ofEpochSecond(-1, 999_999_999)));
			assertTrue(write.getMessage().contains(missing.toString()));
			assertTrue(write.getMessage().contains("errno="));
			IOException read = expectThrows(IOException.class, () -> UnixFileMetadata.lastModified(missing));
			assertTrue(read.getMessage().contains(missing.toString()));
			assertTrue(read.getMessage().contains("errno="));
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
