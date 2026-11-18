package com.holdmyspot.codexunleashed.tooling;

import java.io.FilterWriter;
import java.io.IOException;
import java.io.Writer;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.Map;
import java.util.TreeMap;
import tools.jackson.databind.json.JsonMapper;

/** Writes resource samples in the watchdog's stable JSON-lines format. */
public final class QuietBuildResourceJson
{
	/** Creates the stateless serializer. */
	private QuietBuildResourceJson()
	{
	}

	/**
	 * Writes one ASCII JSON record and a line terminator without taking ownership of the writer or scope.
	 *
	 * @param scope invocation scope providing the shared artifact mapper
	 * @param output destination writer
	 * @param sample resource observation
	 * @throws IOException if writing the record fails
	 */
	public static void write(ToolingScope scope, Writer output, QuietBuildResources.Sample sample) throws IOException
	{
		Map<String, Object> record = new TreeMap<>();
		record.put("time", epochSeconds(sample));
		if (sample.outcome() instanceof QuietBuildResources.Failure failure)
		{
			record.put("error", failure.description());
		}
		else if (sample.outcome() instanceof QuietBuildResources.LinuxCounters linux)
		{
			record.put("cpu_ticks", enumMap(linux.cpuTicks()));
			record.put("processors", linux.processors());
			record.put("memory_total_bytes", linux.memoryTotalBytes());
			record.put("memory_available_bytes", linux.memoryAvailableBytes());
			record.put("swap_used_bytes", linux.swapUsedBytes());
			record.put("swap_in_pages", linux.swapInPages());
			record.put("swap_out_pages", linux.swapOutPages());
			record.put("major_faults", linux.majorFaults());
			record.put("memory_stall_us", new TreeMap<>(linux.memoryStallMicroseconds()));
			record.put("cpu_stall_us", new TreeMap<>(linux.cpuStallMicroseconds()));
		}
		else if (sample.outcome() instanceof QuietBuildResources.DarwinCounters darwin)
		{
			Map<String, Object> ticks = new TreeMap<>();
			ticks.put("user", darwin.cpuTicks().user());
			ticks.put("system", darwin.cpuTicks().system());
			ticks.put("idle", darwin.cpuTicks().idle());
			ticks.put("nice", darwin.cpuTicks().nice());
			record.put("cpu_ticks", ticks);
			record.put("processors", darwin.processors());
			record.put("memory_total_bytes", darwin.memoryTotalBytes());
			record.put("memory_available_bytes", darwin.memoryAvailableBytes());
			record.put("swap_used_bytes", darwin.swapUsedBytes());
			record.put("swap_in_pages", darwin.swapInPages());
			record.put("swap_out_pages", darwin.swapOutPages());
			record.put("page_ins", darwin.pageIns());
			record.put("compressed_pages", darwin.compressedPages());
			record.put("memory_pressure_level", darwin.memoryPressureLevel());
		}
		else if (sample.outcome() instanceof QuietBuildResources.WindowsCounters windows)
		{
			Map<String, Object> ticks = new TreeMap<>();
			ticks.put("idle", windows.cpuTicks().idle());
			ticks.put("system", windows.cpuTicks().system());
			ticks.put("user", windows.cpuTicks().user());
			record.put("cpu_ticks", ticks);
			Object processors = null;
			if (windows.processors().isPresent())
			{
				processors = windows.processors().getAsInt();
			}
			record.put("processors", processors);
			record.put("memory_total_bytes", windows.memoryTotalBytes());
			record.put("memory_available_bytes", windows.memoryAvailableBytes());
			record.put("commit_used_bytes", windows.commitUsedBytes());
			record.put("commit_limit_bytes", windows.commitLimitBytes());
			Map<String, Double> paging = new TreeMap<>();
			addPaging(paging, windows.paging(), QuietBuildResources.PagingCounter.PAGES_INPUT_PER_SECOND,
				"\\Memory\\Pages Input/sec");
			addPaging(paging, windows.paging(), QuietBuildResources.PagingCounter.PAGES_OUTPUT_PER_SECOND,
				"\\Memory\\Pages Output/sec");
			addPaging(paging, windows.paging(), QuietBuildResources.PagingCounter.PAGING_FILE_USAGE_PERCENT,
				"\\Paging File(_Total)\\% Usage");
			record.put("paging", paging);
		}
		JsonMapper mapper = scope.getArtifactJsonMapper();
		mapper.writeValue(new NonClosingWriter(output), record);
		output.write('\n');
	}

	/**
	 * Converts the typed CPU states to their lowercase wire names.
	 *
	 * @param values typed CPU counters
	 * @return sorted wire values
	 */
	private static Map<String, Object> enumMap(Map<QuietBuildResources.LinuxCpuState, java.math.BigInteger> values)
	{
		Map<String, Object> result = new TreeMap<>();
		values.forEach((key, value) -> result.put(key.name().toLowerCase(java.util.Locale.ROOT), value));
		return result;
	}

	/**
	 * Adds a paging value only when the platform supplied it.
	 *
	 * @param destination serialized paging map
	 * @param source typed paging map
	 * @param counter typed counter
	 * @param wireName upstream counter name
	 */
	private static void addPaging(Map<String, Double> destination,
		Map<QuietBuildResources.PagingCounter, Double> source, QuietBuildResources.PagingCounter counter,
		String wireName)
	{
		Double value = source.get(counter);
		if (value != null)
			destination.put(wireName, value);
	}

	/**
	 * Rounds the timestamp to milliseconds using Python's ties-to-even behavior.
	 *
	 * @param sample sample containing the timestamp
	 * @return rounded epoch seconds
	 */
	private static Object epochSeconds(QuietBuildResources.Sample sample)
	{
		BigDecimal rounded = BigDecimal.valueOf(sample.time().getEpochSecond()).
			add(BigDecimal.valueOf(sample.time().getNano(), 9)).setScale(3, RoundingMode.HALF_EVEN);
		if (rounded.signum() == 0 && sample.time().isBefore(Instant.EPOCH))
			return -0.0d;
		return rounded;
	}

	/** Shields the caller's writer from a mapper closing its generator target. */
	private static final class NonClosingWriter extends FilterWriter
	{
		/**
		 * Creates a writer that borrows its delegate.
		 *
		 * @param output caller-owned writer
		 */
		private NonClosingWriter(Writer output)
		{
			super(output);
		}

		@Override
		public void close() throws IOException
		{
			flush();
		}
	}
}
