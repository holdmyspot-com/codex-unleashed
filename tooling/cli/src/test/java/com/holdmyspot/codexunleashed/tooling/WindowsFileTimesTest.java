package com.holdmyspot.codexunleashed.tooling;

import java.io.IOException;
import java.time.Instant;
import java.util.Map;
import org.testng.annotations.Test;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.expectThrows;

/** Verifies documented FILETIME epoch and native tick precision independently of the host operating system. */
public final class WindowsFileTimesTest
{
	/** Creates the native timestamp conversion tests. */
	public WindowsFileTimesTest()
	{
	}

	/**
	 * Preserves known FILETIME values before the Unix epoch and beyond signed-long epoch nanoseconds.
	 *
	 * @throws IOException if a representable vector is rejected
	 */
	@Test
	public void preservesNativeTimestampVectors() throws IOException
	{
		Map<Instant, Long> vectors = Map.of(
			Instant.EPOCH, 116_444_736_000_000_000L,
			Instant.ofEpochSecond(-1, 999_999_900), 116_444_735_999_999_999L,
			Instant.parse("2020-01-02T03:04:05.123456700Z"), 132_224_078_451_234_567L,
			Instant.parse("2300-01-01T00:00:00.123456700Z"), 220_582_656_001_234_567L);
		for (Map.Entry<Instant, Long> vector : vectors.entrySet())
		{
			assertEquals(WindowsFileTimes.encode(vector.getKey()), vector.getValue().longValue());
			assertEquals(WindowsFileTimes.decode(vector.getValue()), vector.getKey());
		}
		assertEquals(WindowsFileTimes.encode(Instant.ofEpochSecond(-1, 999_999_999)), 116_444_735_999_999_999L);
		assertEquals(WindowsFileTimes.encode(Instant.ofEpochSecond(0, 1)), 116_444_736_000_000_000L);
		assertEquals(WindowsFileTimes.encode(Instant.parse("2300-01-01T00:00:00.123456789Z")),
			220_582_656_001_234_567L);
		assertEquals(WindowsFileTimes.encode(WindowsFileTimes.decode(Long.MIN_VALUE)), Long.MIN_VALUE);
	}

	/** Rejects unrepresentable values and SetFileTime's unchanged-time sentinels. */
	@Test
	public void rejectsUnwritableTimestampValues()
	{
		for (Instant invalid : new Instant[]{Instant.MIN, Instant.MAX, Instant.parse("1601-01-01T00:00:00Z"),
			WindowsFileTimes.decode(-1L)})
			expectThrows(IOException.class, () -> WindowsFileTimes.encode(invalid));
	}
}
