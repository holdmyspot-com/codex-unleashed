package com.holdmyspot.codexunleashed.tooling.test;

import com.holdmyspot.codexunleashed.tooling.DefaultToolingScope;
import com.holdmyspot.codexunleashed.tooling.QuietBuildResourceJson;
import com.holdmyspot.codexunleashed.tooling.QuietBuildResources;
import java.io.IOException;
import java.io.StringWriter;
import java.math.BigInteger;
import java.time.Instant;
import java.util.Map;
import java.util.OptionalInt;
import org.testng.annotations.Test;
import tools.jackson.databind.JsonNode;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

/** Tests the resource-watch JSON-lines wire representation. */
public final class QuietBuildResourceJsonTest
{
	/** Creates the serializer tests. */
	public QuietBuildResourceJsonTest()
	{
	}

	/**
	 * Writes sorted ASCII Linux counters and rounds timestamps to milliseconds.
	 *
	 * @throws IOException if serialization fails
	 */
	@Test
	public void writesLinuxSample() throws IOException
	{
		QuietBuildResources.LinuxCounters counters = new QuietBuildResources.LinuxCounters(
			Map.of(QuietBuildResources.LinuxCpuState.USER, BigInteger.TEN), BigInteger.TWO, BigInteger.valueOf(100),
			BigInteger.valueOf(60), BigInteger.valueOf(4), BigInteger.ONE, BigInteger.TWO, BigInteger.valueOf(3),
			Map.of("some\u00e9", BigInteger.valueOf(8)), Map.of("full", BigInteger.valueOf(9)));
		String json = write(new QuietBuildResources.Sample(Instant.parse("1970-01-01T00:02:03.456500Z"), counters));
		requireThat(json, "json").isEqualTo("{\"cpu_stall_us\":{\"full\":9},\"cpu_ticks\":{\"user\":10}," +
			"\"major_faults\":3,\"memory_available_bytes\":60,\"memory_stall_us\":{\"some\\u00e9\":8}," +
			"\"memory_total_bytes\":100,\"processors\":2,\"swap_in_pages\":1,\"swap_out_pages\":2," +
			"\"swap_used_bytes\":4,\"time\":123.456}\n");
	}

	/**
	 * Emits the Darwin-only memory counters and nested CPU tick names.
	 *
	 * @throws IOException if serialization fails
	 */
	@Test
	public void writesDarwinSample() throws IOException
	{
		QuietBuildResources.DarwinCounters counters = new QuietBuildResources.DarwinCounters(
			new QuietBuildResources.DarwinCpuTicks(BigInteger.ONE, BigInteger.TWO, BigInteger.TEN, BigInteger.ZERO),
			BigInteger.valueOf(8), BigInteger.valueOf(100), BigInteger.valueOf(70), BigInteger.valueOf(5),
			BigInteger.valueOf(6), BigInteger.valueOf(7), BigInteger.valueOf(8), BigInteger.valueOf(9),
			BigInteger.TEN);
		String json = write(new QuietBuildResources.Sample(Instant.EPOCH, counters));
		requireThat(json, "json").isEqualTo("{\"compressed_pages\":9,\"cpu_ticks\":{\"idle\":10,\"nice\":0," +
			"\"system\":2,\"user\":1},\"memory_available_bytes\":70,\"memory_pressure_level\":10," +
			"\"memory_total_bytes\":100,\"page_ins\":8,\"processors\":8,\"swap_in_pages\":6," +
			"\"swap_out_pages\":7,\"swap_used_bytes\":5,\"time\":0.000}\n");
	}

	/**
	 * Preserves Windows null processors and exact external paging counter names.
	 *
	 * @throws IOException if serialization fails
	 */
	@Test
	public void writesWindowsSampleAndOmitsAbsentPaging() throws IOException
	{
		QuietBuildResources.WindowsCounters counters = new QuietBuildResources.WindowsCounters(
			new QuietBuildResources.WindowsCpuTicks(BigInteger.ONE, BigInteger.TWO, BigInteger.TEN), OptionalInt.empty(),
			BigInteger.valueOf(100), BigInteger.valueOf(70), BigInteger.valueOf(40), BigInteger.valueOf(200),
			Map.of(QuietBuildResources.PagingCounter.PAGES_INPUT_PER_SECOND, 1.5,
				QuietBuildResources.PagingCounter.PAGING_FILE_USAGE_PERCENT, 20.0));
		String json = write(new QuietBuildResources.Sample(Instant.EPOCH, counters));
		requireThat(json, "json").isEqualTo("{\"commit_limit_bytes\":200,\"commit_used_bytes\":40," +
			"\"cpu_ticks\":{\"idle\":1,\"system\":2,\"user\":10},\"memory_available_bytes\":70," +
			"\"memory_total_bytes\":100,\"paging\":{\"\\\\Memory\\\\Pages Input/sec\":1.5," +
			"\"\\\\Paging File(_Total)\\\\% Usage\":20.0},\"processors\":null,\"time\":0.000}\n");
	}

	/**
	 * Emits only time and error for failed observations and leaves the writer open.
	 *
	 * @throws IOException if serialization fails
	 */
	@Test
	@SuppressWarnings("PMD.CloseResource")
	public void writesFailureAndBorrowsWriter() throws IOException
	{
		TrackingWriter output = new TrackingWriter();
		try (DefaultToolingScope scope = new DefaultToolingScope())
		{
			QuietBuildResourceJson.write(scope, output,
				new QuietBuildResources.Sample(Instant.ofEpochSecond(-1, 999_500_000),
					new QuietBuildResources.Failure("IOException: bad \"counter\"")));
		}
		requireThat(output.closed, "output.closed").isFalse();
		requireThat(output.toString().contains("\"time\":-0.0"), "negativeZeroTimestamp").isTrue();
		try (DefaultToolingScope scope = new DefaultToolingScope())
		{
			JsonNode record = scope.getJsonMapper().readTree(output.toString());
			requireThat(record.size(), "record.size()").isEqualTo(2);
			requireThat(record.path("error").asString(), "error").isEqualTo("IOException: bad \"counter\"");
		}
	}

	/**
	 * Serializes a record using a fresh invocation scope.
	 *
	 * @param sample sample to serialize
	 * @return encoded JSON line
	 * @throws IOException if serialization fails
	 */
	private static String write(QuietBuildResources.Sample sample) throws IOException
	{
		try (DefaultToolingScope scope = new DefaultToolingScope())
		{
			StringWriter output = new StringWriter();
			QuietBuildResourceJson.write(scope, output, sample);
			return output.toString();
		}
	}

	/** Records whether the serializer closes a borrowed writer. */
	private static final class TrackingWriter extends StringWriter
	{
		/** Whether close was called. */
		private boolean closed;

		/** Creates a borrowed-writer probe. */
		private TrackingWriter()
		{
		}

		@Override
		public void close() throws IOException
		{
			closed = true;
			super.close();
		}
	}
}
